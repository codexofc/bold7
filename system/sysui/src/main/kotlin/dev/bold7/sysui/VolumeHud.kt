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
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityManager
import java.lang.reflect.Method

/**
 * The 9900's volume overlay in place of Android's volume dialog. VolumeDialogImpl keeps doing
 * everything else (state, safety and CSD warnings, dismiss requests); only its show is replaced,
 * by [show], which returns false, letting Android's dialog show, whenever the overlay cannot.
 * What Android's show does for the system is done here too: notifyVisible (AudioService only
 * changes the ring volume while the volume UI is visible) and userActivity.
 * Every call comes on SystemUI's main thread (VolumeDialogImpl's handler, Looper.getMainLooper()),
 * the output devices too (an AudioDeviceCallback on that thread, registered at the first show,
 * for the process: no binder call per key press).
 */
internal class VolumeHud(
    loader: ClassLoader,
    private val images: (Context) -> Images?,
    private val fail: (String, Exception) -> Unit,
) {
    private val impl = loader.loadClass(VOLUME_DIALOG)
    val showH: Method = impl.getDeclaredMethod(VOLUME_SHOW, impl, Int::class.java, Boolean::class.java, Int::class.java)
    val onStateChangedH: Method = impl.getDeclaredMethod("onStateChangedH", loader.loadClass(VOLUME_STATE))
    val dismissH: Method = impl.getDeclaredMethod("dismissH", Int::class.java)
    private val mContext = impl.getField("mContext")
    private val mController = impl.getField("mController")
    private val mState = impl.getField("mState")
    private val notifyVisible = loader.loadClass(VOLUME_CONTROLLER).getMethod("notifyVisible", Boolean::class.java)
    private val userActivity = loader.loadClass(VOLUME_CONTROLLER).getMethod("userActivity")
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

    private var view: HudView? = null
    private var controller: Any? = null
    private var visible = false
    private val timeout = Runnable { guarded { hide() } }

    /** The connected outputs' types, kept by [devices], registered once at the first show. */
    private val outputs = HashSet<Int>()
    private var devices: AudioDeviceCallback? = null

    /** Shows or refreshes the overlay for [dialog], a VolumeDialogImpl; false leaves it to Android. */
    fun show(dialog: Any): Boolean {
        val context = mContext.get(dialog) as Context
        if (devices == null) devices = watchOutputs(context)
        val look = look(dialog) ?: return false
        val v = view ?: HudView(context, images(context) ?: return false).also { attach(context, it) }
        v.look = look
        // Visible only once the system knows: a failure before leaves the overlay hidden, Android's
        // dialog shows, and the next press tries again.
        if (!visible) {
            controller = mController.get(dialog)
            notifyVisible.invoke(controller, true)
            v.visibility = View.VISIBLE
            visible = true
        }
        // The timeout before anything else that may fail: a visible overlay always goes away.
        v.removeCallbacks(timeout)
        // Android's own delay (VolumeDialogImpl.rescheduleTimeoutH), stretched if accessibility
        // asks: the 9900's was not timed (D).
        val ms = context.getSystemService(AccessibilityManager::class.java).getRecommendedTimeoutMillis(3000, AccessibilityManager.FLAG_CONTENT_ICONS)
        v.postDelayed(timeout, ms.toLong())
        userActivity.invoke(controller)
        return true
    }

    /** After Android's onStateChangedH: the level or the stream changed under the visible overlay. */
    fun update(dialog: Any) {
        if (visible) look(dialog)?.let { view?.look = it }
    }

    /** After Android's dismissH (screen off, dismiss requests), and at the timeout: gone at once (D, no fade seen). */
    fun hide() {
        val v = view ?: return
        if (!visible) return
        visible = false
        v.removeCallbacks(timeout)
        v.visibility = View.GONE
        notifyVisible.invoke(controller, false)
    }

    private fun look(dialog: Any): VolumeLook? {
        val s = mState.get(dialog) ?: return null
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
        audio.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper()))
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

    // Runs from the view's own callbacks, outside the hook: nothing may escape into SystemUI.
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
