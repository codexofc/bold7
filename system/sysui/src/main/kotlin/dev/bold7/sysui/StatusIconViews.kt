package dev.bold7.sysui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewParent
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

internal const val STATUS_ICON_CONTAINER = "com.android.systemui.statusbar.phone.StatusIconContainer"
private const val STATUS_BAR_ICON = "com.android.internal.statusbar.StatusBarIcon"
internal const val STATUS_BAR_POLICY = "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy"
internal const val BLUETOOTH_CONTROLLER = "com.android.systemui.statusbar.policy.BluetoothControllerImpl"
internal const val LOCATION_CONTROLLER = "com.android.systemui.statusbar.policy.LocationControllerImpl"
internal const val ICON_CONTROLLER = "com.android.systemui.statusbar.phone.StatusBarIconControllerImpl"

/**
 * The 9900's indicators in place of Android's status icons (StatusIcons), in the two bars Bold7
 * dresses, the status bar and the keyguard bar ([dressed]: their StatusIconContainer); the shade
 * header keeps Android's. Android keeps every decision, which icon shows, when, in which state:
 * only the drawing changes. Two hooks, after Android's method:
 * - StatusBarIconView.maybeUpdateIconScaleDimens()V: scale 1 for a theme image, which Android
 *   would shrink to its 15 dp icon height (mIconScale, applied in onDraw);
 * - then, only with it, StatusBarIconView.getIcon(StatusBarIcon)Drawable: the drawable that every
 *   path of Android's draws (set, a change of layout direction or density), the 9900's image for
 *   a slot that has one, centred in its line at its own size (the view's scale type is CENTER;
 *   the spacing between icons is [iconSpacing]'s). Android's first set() comes before the view is
 *   in the bar: Android's icon then, the 9900's at the next getIcon, which onRtlPropertiesChanged
 *   makes at the view's first measure in the bar (updateDrawable(true), Android's own path).
 * Then, only with both, the two slots the 9900 shows in two states (StatusIconStates), two hooks.
 * A failure is counted and leaves Android's icon.
 */
internal fun installStatusIcons(
    loader: ClassLoader,
    images: (Context) -> Images?,
    fail: (String, Throwable?) -> Unit,
    after: (String, () -> Executable, (Chain, Any?) -> Any?) -> Boolean,
    dressed: (ViewParent?) -> Boolean,
) {
    val icons = try {
        StatusIconHooks(loader, images, dressed)
    } catch (e: ReflectiveOperationException) {
        fail("hook target missing: status icons, Android's kept", e)
        return
    }
    val scaled = after("$ICON_VIEW.maybeUpdateIconScaleDimens()V + mIconScale", { icons.maybeUpdateIconScaleDimens }) { chain, result ->
        result.also { icons.keepScale(chain.thisObject as ImageView) }
    }
    if (!scaled) return
    val drawn = after("$ICON_VIEW.getIcon(StatusBarIcon)Drawable + getSlot", { icons.getIcon }) { chain, android ->
        icons.icon(chain.thisObject as ImageView, android as Drawable?)
    }
    if (!drawn) return
    val states = try {
        StatusIconStates(loader, icons, fail)
    } catch (e: ReflectiveOperationException) {
        fail("hook target missing: two-state status icons, Android's states kept", e)
        return
    }
    after("$STATUS_BAR_POLICY.updateBluetooth()V + mBluetooth", { states.updateBluetooth }) { chain, result ->
        result.also { states.bluetooth(chain.thisObject!!) }
    }
    after("$STATUS_BAR_POLICY.onLocationActiveChanged()V + mLocationController", { states.onLocationActiveChanged }) { chain, result ->
        result.also { states.location(chain.thisObject!!) }
    }
}

private class StatusIconHooks(loader: ClassLoader, private val images: (Context) -> Images?, private val dressed: (ViewParent?) -> Boolean) {
    private val view = loader.loadClass(ICON_VIEW)
    val getIcon: Method = view.getDeclaredMethod("getIcon", loader.loadClass(STATUS_BAR_ICON))
    val maybeUpdateIconScaleDimens: Method = view.getDeclaredMethod("maybeUpdateIconScaleDimens")
    private val slot = view.getDeclaredMethod("getSlot")
    private val updateDrawable = view.getMethod("updateDrawable", Boolean::class.javaPrimitiveType)
    private val scale = view.getField("mIconScale")
    private val logged = HashSet<String>()
    /** The last image of each two-state slot (StatusIconStates), main thread only. */
    private val states = HashMap<String, String?>()
    /** The dressed bars' views of the two-state slots, to redraw when their state changes. */
    private val twoState = WeakHashMap<ImageView, String>()

    /** After getIcon: the 9900's image for a status icon of a dressed bar, Android's [android] otherwise. */
    fun icon(view: ImageView, android: Drawable?): Drawable? {
        val slot = slot.invoke(view) as String?
        val name = statusIconImage(slot, states) ?: return android
        if (!dressed(view.parent)) return android
        val image = Indicator((images(view.context) ?: return android)[name])
        if (slot in SLOT_STATES) twoState[view] = slot
        scale.setFloat(view, 1f)
        // Android gives its icons a fixed height of its own (IconManager.mIconSize): the line's
        // height instead, the image centred in it. Here, during the view's measure, its container has already measured it at Android's
        // height and a request now would be absorbed: posted, for the next pass.
        view.layoutParams?.let {
            if (it.height != MATCH_PARENT) {
                it.height = MATCH_PARENT
                view.post(view::requestLayout)
            }
        }
        if (logged.add("$slot $name")) Log.i(TAG, "status icon: $name for Android's $slot")
        return image
    }

    /** After maybeUpdateIconScaleDimens: the theme's images at their own size. */
    fun keepScale(view: ImageView) {
        if (view.drawable is Indicator) scale.setFloat(view, 1f)
    }

    /**
     * A two-state [slot]'s new image: its dressed views draw it again by Android's own
     * updateDrawable(true), which asks getIcon. On the thread Android changes the icons on.
     */
    fun state(slot: String, image: String?) {
        if (slot in states && states[slot] == image) return
        states[slot] = image
        Log.i(TAG, "status icon state: $slot ${image ?: "off"}")
        if (image == null) return
        for (view in twoState.filterValues { it == slot }.keys.toList()) updateDrawable.invoke(view, true)
    }
}

/**
 * What the 9900 shows and Android does not tell apart (SLOT_STATES): Bluetooth on without a
 * device, GPS on and unused. Two hooks after the methods where PhoneStatusBarPolicy decides those
 * icons, each making the same calls Android makes there, on its thread: the icon shown
 * (StatusBarIconControllerImpl.setIconVisibility) whenever the 9900 shows one, never hidden where
 * Android shows it, and the 9900's image for the state.
 * - updateBluetooth()V, which Android runs at start (BluetoothControllerImpl.addCallback) and at
 *   each change: on is BluetoothControllerImpl.mEnabled, in use Android's own "connected";
 * - onLocationActiveChanged()V: in use is Android's mAreActiveLocationRequests, on is
 *   LocationManager's, read at start and at each android.location.MODE_CHANGED, which Android
 *   gives this policy no callback for (no polling). Nothing where Android puts the location in its
 *   privacy chip instead (privacyConfig.locationAvailable, false on the Q25).
 */
private class StatusIconStates(loader: ClassLoader, private val icons: StatusIconHooks, private val fail: (String, Throwable?) -> Unit) {
    private val policy = loader.loadClass(STATUS_BAR_POLICY)
    val updateBluetooth: Method = policy.getDeclaredMethod("updateBluetooth")
    val onLocationActiveChanged: Method = policy.getDeclaredMethod("onLocationActiveChanged")
    private val iconController = policy.getField("mIconController")
    private val setIconVisibility = loader.loadClass(ICON_CONTROLLER).getMethod("setIconVisibility", String::class.java, Boolean::class.javaPrimitiveType)
    private val slotBluetooth = policy.getField("mSlotBluetooth")
    private val slotLocation = policy.getField("mSlotLocation")
    private val bluetoothController = policy.getField("mBluetooth")
    private val bluetooth = loader.loadClass(BLUETOOTH_CONTROLLER)
    private val btEnabled = bluetooth.getField("mEnabled")
    private val btConnection = bluetooth.getField("mConnectionState")
    private val btActive = bluetooth.getField("mIsActive")
    private val btAudioOnly = bluetooth.getField("mAudioProfileOnly")
    private val locationController = policy.getField("mLocationController")
    private val location = loader.loadClass(LOCATION_CONTROLLER)
    private val locationActive = location.getField("mAreActiveLocationRequests")
    private val locationContext = location.getField("mContext")
    private val privacyController = policy.getField("mPrivacyItemController")
    private val privacyConfig: Field = privacyController.type.getField("privacyConfig")
    private val locationInPrivacyChip: Field = privacyConfig.type.getField("locationAvailable")

    private var seen: Any? = null
    /** LocationManager.isLocationEnabled, null until read. */
    private var locationOn: Boolean? = null
    private val modeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = guarded("location mode") {
            locationOn = context.getSystemService(LocationManager::class.java).isLocationEnabled
            seen?.let(::location)
        }
    }

    fun bluetooth(policy: Any) {
        attach(policy)
        val bt = bluetoothController.get(policy)
        if (bt == null || bt.javaClass !== bluetooth) return
        val on = btEnabled.getBoolean(bt)
        // Android's own condition for its "connected" icon (updateBluetooth).
        val inUse = on && btConnection.getInt(bt) == 2 && (btActive.getBoolean(bt) || !btAudioOnly.getBoolean(bt))
        show(policy, slotBluetooth, on, inUse)
    }

    fun location(policy: Any) {
        attach(policy)
        if (locationInPrivacyChip.getBoolean(privacyConfig.get(privacyController.get(policy)))) return
        val controller = locationController.get(policy)
        if (controller == null || controller.javaClass !== location) return
        show(policy, slotLocation, locationOn ?: return, locationActive.getBoolean(controller))
    }

    private fun show(policy: Any, slotField: Field, on: Boolean, inUse: Boolean) {
        val slot = slotField.get(policy) as String? ?: return
        if (slot !in SLOT_STATES) return fail("two-state status icon: unknown slot $slot, Android's kept", null)
        val image = slotState(slot, on, inUse)
        icons.state(slot, image)
        // Shown whenever the 9900 shows it, which includes every case Android shows it.
        setIconVisibility.invoke(iconController.get(policy), slot, image != null)
    }

    /**
     * At the first hook: the location mode, now and at each change, on the main thread. The first
     * hook is updateBluetooth, inside the policy's start (CentralSurfacesImpl), which then sets the
     * location icon and hides it: the location state waits for the end of that start, posted.
     * The receiver stays registered for good: there is one policy, for SystemUI's whole life.
     */
    private fun attach(policy: Any) {
        if (seen === policy) return
        seen = policy
        val context = locationController.get(policy)?.takeIf { it.javaClass === location }?.let { locationContext.get(it) as Context } ?: return
        val main = Handler(Looper.getMainLooper())
        context.registerReceiver(modeChanged, IntentFilter(LocationManager.MODE_CHANGED_ACTION), null, main, Context.RECEIVER_NOT_EXPORTED)
        locationOn = context.getSystemService(LocationManager::class.java).isLocationEnabled
        main.post { guarded("location start") { location(policy) } }
    }

    // The receiver runs outside any hook's guard: nothing may escape into SystemUI.
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            fail("two-state status icon: $what", e)
        } catch (e: ReflectiveOperationException) {
            fail("two-state status icon: $what", e)
        }
    }
}

/**
 * [spacing] px between two status icons, the theme's or Android's, instead of Android's 0 dp
 * (status_bar_system_icon_spacing, which the keyguard bar and the shade header share): the
 * field StatusIconContainer reads at each layout, set once on a dressed bar's container. The
 * banner's 10 (Banner.SPACING), the framework's 6 on the keyguard bar. Missing,
 * Android's spacing stays.
 */
internal fun iconSpacing(statusIcons: View, fail: (String, Exception?) -> Unit, spacing: Int) {
    if (statusIcons.javaClass.name != STATUS_ICON_CONTAINER) {
        return fail("view target missing: @id/statusIcons is ${statusIcons.javaClass.name}, not $STATUS_ICON_CONTAINER", null)
    }
    try {
        statusIcons.javaClass.getField("mIconSpacing").setInt(statusIcons, spacing)
    } catch (e: ReflectiveOperationException) {
        fail("view target missing: $STATUS_ICON_CONTAINER.mIconSpacing", e)
    }
}

/**
 * A theme image as SystemUI's icon views draw it: its own colors, whatever Android tints it with:
 * setImageTintList (DarkIconDispatcher) reaches Drawable.setTintList, which does nothing;
 * setColorFilter (setStaticDrawableColor) is ignored here. [image] is shared (Images).
 */
internal class Indicator(val image: Drawable) : Drawable() {
    override fun getIntrinsicWidth() = image.intrinsicWidth
    override fun getIntrinsicHeight() = image.intrinsicHeight

    // The image is shared (Images): its bounds are set at each draw, on the main thread.
    override fun draw(canvas: Canvas) {
        image.bounds = bounds
        image.draw(canvas)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
