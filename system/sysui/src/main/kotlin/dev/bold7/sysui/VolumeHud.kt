package dev.bold7.sysui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * LineageOS 24's volume dialog: its view model collects the visibility Android decides and, on each
 * Visible, builds and shows its dialog in this lambda (VolumeDialogPluginViewModel.launchVolumeDialog,
 * which ART may inline into its caller: the lambda, called through Function2, is the hook).
 */
internal const val VOLUME_LAUNCH = "com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogPluginViewModel\$launchVolumeDialog\$1"
internal const val VOLUME_PLUGIN_VIEW_MODEL = "com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogPluginViewModel"
internal const val VOLUME_VISIBILITY = "com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor"
internal const val VOLUME_VISIBLE = "com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel\$Visible"
internal const val VOLUME_CONTROLLER_IMPL = "com.android.systemui.volume.VolumeDialogControllerImpl"
/** Where the panel of every stream's slider opens: the dialog's settings button and the Settings.Panel.ACTION_VOLUME broadcast. */
internal const val VOLUME_PANEL_STATE = "com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractor"
internal const val VOLUME_CONTROLLER = "com.android.systemui.plugins.VolumeDialogController"
internal const val VOLUME_STATE = "$VOLUME_CONTROLLER\$State"
internal const val VOLUME_STREAM_STATE = "$VOLUME_CONTROLLER\$StreamState"

/**
 * The 9900's volume overlay in place of LineageOS's volume dialog. Android keeps deciding
 * everything (when the volume UI shows and for how long, its timeout and dismissals, notifyVisible
 * and userActivity, the hearing warnings): only the dialog it would show on each Visible is
 * replaced by the overlay ([onVisibility], instead of the view model's lambda), which draws the
 * state the controller sends its callbacks, ours a [Proxy] of the plugin interface, registered at
 * the lambda's first call (the Invisible it gets at SystemUI's start).
 * Whenever the overlay cannot show, Android's dialog shows for that press: a volume never changes
 * without a visual feedback.
 * Every call runs on SystemUI's main thread (the lambda's dispatcher, the callbacks posted on its
 * looper), the output devices too (an AudioDeviceCallback on that thread, registered at the first
 * show, for the process: no binder call per key press).
 */
internal class VolumeHud(
    loader: ClassLoader,
    private val images: (Context) -> Images?,
    private val fail: (String, Exception) -> Unit,
) {
    private val lambda = loader.loadClass(VOLUME_LAUNCH)
    val invoke: Method = lambda.getDeclaredMethod("invoke", Any::class.java, Any::class.java)
    private val lambdaModel = lambda.getField("this\$0")
    private val visibilityInteractor = loader.loadClass(VOLUME_PLUGIN_VIEW_MODEL).getField("dialogVisibilityInteractor")
    private val interactorController = loader.loadClass(VOLUME_VISIBILITY).getField("controller")
    private val visibleModel = loader.loadClass(VOLUME_VISIBLE)
    private val callbacks = loader.loadClass("$VOLUME_CONTROLLER\$Callbacks")
    private val addCallback = loader.loadClass(VOLUME_CONTROLLER).getMethod("addCallback", callbacks, Handler::class.java)
    private val getState = loader.loadClass(VOLUME_CONTROLLER).getMethod("getState")
    private val mContext = loader.loadClass(VOLUME_CONTROLLER_IMPL).getField("mContext")
    private val state = loader.loadClass(VOLUME_STATE)
    private val activeStream = state.getField("activeStream")
    private val ringerMode = state.getField("ringerModeInternal")
    private val states = state.getField("states")
    private val stream = loader.loadClass(VOLUME_STREAM_STATE)
    private val level = stream.getField("level")
    private val levelMax = stream.getField("levelMax")
    private val muted = stream.getField("muted")
    private val zenMode = state.getField("zenMode")
    private val disallowAlarms = state.getField("disallowAlarms")
    private val disallowMedia = state.getField("disallowMedia")
    private val disallowRinger = state.getField("disallowRinger")
    private val disallowSystem = state.getField("disallowSystem")

    private val main = Handler(Looper.getMainLooper())
    /** The controller (a VolumeDialogControllerImpl), once our callback is registered with it. */
    private var controller: Any? = null
    /** The controller's last state, a copy it sent on the main thread. */
    private var last: Any? = null
    private var view: HudView? = null

    /** The connected outputs' types, kept by [devices], registered once at the first show. */
    private val outputs = HashSet<Int>()
    private var devices: AudioDeviceCallback? = null

    /**
     * Instead of the view model's lambda, with the visibility [model] Android decided: the overlay
     * shows on Visible and goes on anything else. True when the overlay shows, and Android's
     * dialog does not; false lets the lambda run (Android's dialog, or its logging of a dismissal).
     */
    fun onVisibility(lambda: Any, model: Any): Boolean {
        if (Looper.myLooper() != main.looper) return false
        val c = controller ?: bind(lambda)
        if (!visibleModel.isInstance(model)) {
            // At once (D, no fade seen).
            view?.visibility = View.GONE
            return false
        }
        val context = mContext.get(c) as Context
        if (devices == null) devices = watchOutputs(context)
        val look = look()
        val v = view ?: images(context)?.let { HudView(context, it).also { hud -> attach(context, hud) } }
        if (look == null || v == null) {
            Log.w(TAG, "volume: ${if (look == null) "no state" else "no theme"}, Android's dialog shown")
            return false
        }
        v.look = look
        v.visibility = View.VISIBLE
        return true
    }

    /** Our callback with the controller, and its state asked for now, not at the first change. */
    private fun bind(lambda: Any): Any {
        val c = interactorController.get(visibilityInteractor.get(lambdaModel.get(lambda)))!!
        val callback = Proxy.newProxyInstance(callbacks.classLoader, arrayOf(callbacks)) { proxy, m, args ->
            when (m.name) {
                "onStateChanged" -> guarded { onState(args!![0]) }
                // Kept as a map key by the controller.
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.get(0)
                "toString" -> return@newProxyInstance "Bold7VolumeHud"
            }
            null
        }
        addCallback.invoke(c, callback, main)
        controller = c
        getState.invoke(c)
        return c
    }

    /** The controller's state changed (a copy, on the main thread): the level under the visible overlay. */
    private fun onState(state: Any) {
        last = state
        val v = view ?: return
        if (v.visibility == View.VISIBLE) look()?.let { v.look = it }
    }

    private fun look(): VolumeLook? {
        val s = last ?: return null
        val all = states.get(s) as SparseArray<*>
        val active = hudStream(activeStream.getInt(s)) { all[it] != null } ?: return null
        val ss = all[active] ?: return null
        // Do Not Disturb silences streams that Android's own rows then show muted.
        val policy = ZenPolicy(disallowAlarms.getBoolean(s), disallowMedia.getBoolean(s), disallowRinger.getBoolean(s), disallowSystem.getBoolean(s))
        val silenced = muted.getBoolean(ss) || zenMuted(active, zenMode.getInt(s), policy)
        return volumeLook(active, level.getInt(ss), levelMax.getInt(ss), silenced, ringerMode.getInt(s), volumeOutput(active, outputs))
    }

    private fun watchOutputs(context: Context): AudioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            for (d in added) if (d.isSink) outputs += d.type
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            for (d in removed) if (d.isSink) outputs -= d.type
        }
    }.also {
        // The devices already there, read once for this first show (the callback repeats them later,
        // posted on the main thread).
        val audio = context.getSystemService(AudioManager::class.java)
        for (d in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) outputs += d.type
        audio.registerAudioDeviceCallback(it, main)
    }

    private fun attach(context: Context, v: HudView) {
        v.visibility = View.GONE
        context.getSystemService(WindowManager::class.java).addView(v, LayoutParams(
            HUD_WIDTH, HUD_HEIGHT, TYPE_VOLUME_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // In the middle of the screen (sim-108, C), whatever the bars.
            gravity = Gravity.CENTER
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            windowAnimations = 0
            title = "Bold7Volume"
        })
        view = v
    }

    // Runs outside the hooks (the controller's callbacks): nothing may escape into SystemUI.
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            fail("volume HUD failed", e)
        } catch (e: ReflectiveOperationException) {
            fail("volume HUD failed", e)
        }
    }

    private companion object {
        // WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY, hidden from the SDK: the type of Android's
        // own volume dialog, above the keyguard and the status bar.
        const val TYPE_VOLUME_OVERLAY = 2020
    }
}

/** The overlay itself: vol-bg.png, the output's icon, the bar. Draws only; nothing to touch. */
@SuppressLint("ViewConstructor")
private class HudView(context: Context, private val images: Images) : View(context) {
    private val background = images["vol_bg"].apply { setBounds(0, 0, HUD_WIDTH, HUD_HEIGHT) }

    var look = VolumeLook("vol_loudspeaker", "vol_0")
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        background.draw(canvas)
        images[look.icon].run {
            val top = hudIconTop(intrinsicHeight)
            setBounds(ICON_X, top, ICON_X + intrinsicWidth, top + intrinsicHeight)
            draw(canvas)
        }
        images[look.bar].run {
            setBounds(BAR_X, BAR_Y, BAR_X + intrinsicWidth, BAR_Y + intrinsicHeight)
            draw(canvas)
        }
    }
}
