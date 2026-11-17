package dev.bold7.sysui

import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.app.Instrumentation
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Region
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.error.HookFailedError
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Field
import dev.bold7.design.OS7_LOCALE
import dev.bold7.design.ShellColors
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val SYSTEMUI = "com.android.systemui"
internal const val TAG = "Bold7/SystemUI"

// Simplification: the guard's test switch rides on log.tag.* properties, which shell may set and any app
// may read through the public Log.isLoggable. Fails safe: a global level of V also
// blocks hooks.
private const val FAULT_TAG = "Bold7GuardFault"

/**
 * The 9900's banner over apps where a resource cannot do it (system/overlays/android gives the
 * bar its 67 px and the indicators' order, system/overlays/systemui its black): the banner's two
 * lines, the theme's indicator images, which SystemUI could only tint in one color. Nine hooks,
 * run after the original method but the last, so that with hooks off SystemUI is its own again
 * (radio icons included):
 * - PhoneStatusBarView.onFinishInflate()V: battery and time to the left, the screen's title or the
 *   network's name in the middle, coverage, bars and Wi-Fi to the right, the Hub's counters in
 *   the middle of the second line, Android's status icons before the Wi-Fi; the one-line bars of
 *   the Phone and the Calendar, none over the apps that have their own (StatusBarLayout,
 *   BannerView, HubCounts);
 * - KeyguardStatusBarView.onFinishInflate()V: the lock screen's bar, clipped away while the
 *   9900's face draws its banner in its place (LockFace);
 * - StatusBarWifiView, StatusBarMobileView and StatusBarIconView .isIconVisible()Z: false for
 *   Android's radio icons in those two bars, which now have ours ([androidIconShown]);
 * - BatteryMeterView.onBatteryLevelChanged(IZ)V: the 9900's battery, in the status bar only, and
 *   while it charges the bolt over it (BatteryBolt);
 * - Clock.getSmallTime() and CommandQueue.onSystemBarAttributesChanged: the banner's time, no
 *   black under the keyguard, no bar of Android over the home's banner ([installStatusBar]);
 * - CentralSurfacesCommandQueueCallbacks.showTransient(IIZ)V: on the home, the bar a swipe from
 *   the top shows is hidden again at once (shade hooks);
 * - HeadsUpAppearanceController.hide(View;I;Lambda)V, instead of Android's for the time only: a
 *   pinned notification no longer wipes it (its text, which would replace it, stays hidden).
 * Then four for the status icons ([installStatusIcons]).
 *
 * Then the volume overlay and the power key's dialog, six hooks ([installVolume], [installPowerMenu]):
 * - VolumeDialogImpl.-$$Nest$mshowH(VolumeDialogImpl;IZI)V, instead of Android's: the 9900's
 *   volume overlay, Android's dialog whenever it cannot show;
 * - VolumeDialogImpl.onStateChangedH(State)V and dismissH(I)V, after: refresh and hide it;
 * - VolumePanelFactory.create()V, instead of Android's, and VolumeDialogImpl.initSettingsH(I)V,
 *   after: no panel of every stream's slider, no settings button in the fallback.
 * - GlobalActionsDialogLite$ActionsDialogLite.onCreate(Bundle)V, after: OS 7's dialog over
 *   Android's menu, running Android's own actions.
 *
 * Then Android's warnings of a loud headset, two hooks ([installHearingWarnings]):
 * - VolumeDialogImpl$5.start()V (SafetyWarningDialog, folded there by R8) and
 *   CsdWarningDialog.start()V, after: OS 7's dialog over Android's, its buttons clicking
 *   Android's (HearingWarnings).
 *
 * Then the screen while the phone powers off or restarts, one hook ([installShutdown]):
 * - GlobalActionsImpl.showShutdownUi(Z;String)V, instead of Android's: black, OS 7's busy clock
 *   (ShutdownScreen); Android's screen for a restart into recovery and whenever ours fails.
 *
 * Then the toasts, one hook ([installToasts]):
 * - ToastFactory.createToast(Context;CharSequence;String;II)SystemUIToast, after: OS 7's status
 *   message on the view and animators Android just built (Toasts); the place and the framework's
 *   own toasts are the framework overlay's.
 *
 * Then the shade's scrim, one hook ([installScrim]):
 * - ScrimController.updateThemeColors()V, after: black as the scrims' colour (mColors) and every
 *   ScrimState's surface, in place of the light ones Android puts behind its shade.
 *
 * Then the apps' starting window, one hook ([installSplash]):
 * - PhoneStartingWindowTypeAlgorithm.getSuggestedWindowType(StartingWindowInfo)I, after: no
 *   splash (startingWindowType), as on the 9900.
 *
 * Then the clipboard preview, one hook ([installClipboard]):
 * - ClipboardListener.onPrimaryClipChanged()V, instead of Android's: no « copied » preview at the
 *   bottom left, as on the 9900. The clipboard itself is untouched.
 *
 * Then the lock screen, fifteen hooks ([installLock], [installLockFace], [installPlaceCall]): the
 * 9900's banner, counters, padlock and owner's information, its password entry over the bouncer
 * and over the SIM's PIN and PUK, never the bouncer's input nor its checks, the wallpaper whole
 * under the device's (ScrimController.applyState), and the Call key opening the Phone's Place Call
 * (NotificationShadeWindowView.dispatchKeyEvent).
 *
 * Then the shade without Android's notifications, six hooks ([installShade], ShadeHooks.kt):
 * its quick settings alone, the notifications in the
 * home's list of BBOS 7.
 *
 * Then the shade shut, four hooks ([installShut], ShadeHooks.kt): nothing
 * opens it from the status bar of an app, from the lock screen (with ShadeHooks' quick settings
 * hook) nor at an app's request, as on the 9900; bold7_android_shade=1 gives it back.
 *
 * Then no heads-up card but for calls, alarms and full screen intents, one hook ([installHeadsUp],
 * HeadsUp.kt): after NotificationInterruptStateProviderImpl.checkHeadsUp.
 *
 * No custom quick settings panel, tiles or instant previews: Android's quick settings are used.
 *
 * Its second scope, Google Messages' main process: none of the above, only the
 * bridge that opens ExternalMessagingApiService to Messages of Bold7 and hands Google Messages'
 * consent page to the launcher's Setup screen ([installMessagesBridge]), logged under Bold7/MessagesBridge. One Vector module for both: one APK to install, scope and
 * remove, and the same switches (bold7 mounted, fault injection) turn both off.
 */
class SysuiModule : XposedModule() {
    private var installed = 0
    // Counted from SystemUI's main thread and from the shell's splash threads (wmshell.splashworker,
    // wmshell.splashscreen).
    private val failed = AtomicInteger()
    private var images: Images? = null
    private var statusBarBattery = WeakReference<View>(null)
    private val bouncer = Bouncer(::images, ::fail)
    private val face = LockFace(::images, ::fail, bouncer)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var batteryIcon: Field? = null
    /** The dressed bar's battery with its charging bolt; replaced when SystemUI inflates the bar again. */
    private var batteryBolt: BatteryBolt? = null
    /** The StatusIconContainer of each dressed bar, by the bar's name; weak, bars get re-inflated. */
    private val dressedIcons = WeakHashMap<ViewParent, String>()
    private val loggedHidden = HashSet<String>()
    private val layout = StatusBarLayout(::fail)
    private val password = PasswordScreen(::images, ::fail)

    private var process = ModuleProcess.NONE
    private var tag = TAG

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        process = moduleProcess(param.processName)
        if (process == ModuleProcess.MESSAGES) tag = BRIDGE_TAG
        if (process == ModuleProcess.NONE) detach()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (process == ModuleProcess.MESSAGES) {
            if (param.packageName == MESSAGES && installed == 0) installMessagesBridge(param.classLoader)
            return
        }
        when (packageReady(param.packageName, alreadyHooked = installed > 0)) {
            PackageReady.OTHER_PACKAGE -> {
                Log.i(TAG, "package ${param.packageName}: not SystemUI, no hooks")
                return
            }
            PackageReady.ALREADY_HOOKED -> {
                Log.i(TAG, "package ${param.packageName} again (first=${param.isFirstPackage}): hooks already in, ignored")
                return
            }
            PackageReady.INSTALL -> Unit
        }
        val apk = param.applicationInfo.sourceDir
        val start = SystemClock.elapsedRealtime()
        val actual = try {
            apkFingerprint(readApkEntries(apk))
        } catch (e: IOException) {
            diag(Log.ERROR, "guard: cannot read $apk, no hooks", e)
            return
        }
        val took = SystemClock.elapsedRealtime() - start
        val fault = Log.isLoggable(FAULT_TAG, Log.VERBOSE)
        val mounted = File(BOLD7_MOUNT_WITNESS).exists()
        if (!hooksAllowed(actual, fault, mounted)) {
            diag(Log.WARN, "guard: no hooks, fingerprint=$actual expected=$EXPECTED_SYSTEMUI_FINGERPRINT fault=$fault bold7Mounted=$mounted apk=$apk (${took} ms)")
            return
        }
        diag(Log.INFO, "guard: fingerprint matches ($actual, ${took} ms), installing hooks (first=${param.isFirstPackage})")
        val loader = param.classLoader
        after("$STATUS_BAR_VIEW.onFinishInflate()V", { loader.loadClass(STATUS_BAR_VIEW).getDeclaredMethod("onFinishInflate") }) { chain, result ->
            result.also { dress(chain.thisObject as ViewGroup) }
        }
        after("$KEYGUARD_BAR_VIEW.onFinishInflate()V", { loader.loadClass(KEYGUARD_BAR_VIEW).getDeclaredMethod("onFinishInflate") }) { chain, result ->
            result.also { face.onKeyguardBar(chain.thisObject as View) }
        }
        after("$WIFI_VIEW.isIconVisible()Z", { loader.loadClass(WIFI_VIEW).getDeclaredMethod("isIconVisible") }) { chain, shown ->
            radioIconShown(chain.thisObject as View, "wifi", shown as Boolean)
        }
        after("$MOBILE_VIEW.isIconVisible()Z", { loader.loadClass(MOBILE_VIEW).getDeclaredMethod("isIconVisible") }) { chain, shown ->
            radioIconShown(chain.thisObject as View, "mobile", shown as Boolean)
        }
        var iconSlot: Method? = null
        after("$ICON_VIEW.isIconVisible()Z + getSlot()", {
            val cls = loader.loadClass(ICON_VIEW)
            iconSlot = cls.getDeclaredMethod("getSlot")
            cls.getDeclaredMethod("isIconVisible")
        }) { chain, shown ->
            val view = chain.thisObject as View
            // Notification icons go through here too: the slot is read only in a dressed bar.
            if (shown as Boolean && view.parent in dressedIcons) radioIconShown(view, iconSlot!!.invoke(view) as String?, true) else shown
        }
        after("$BATTERY_VIEW.onBatteryLevelChanged(IZ)V + mBatteryIconView", {
            val cls = loader.loadClass(BATTERY_VIEW)
            batteryIcon = cls.getField("mBatteryIconView")
            cls.getDeclaredMethod("onBatteryLevelChanged", Int::class.java, Boolean::class.java)
        }) { chain, result ->
            result.also {
                val view = chain.thisObject
                if (view === statusBarBattery.get()) showBattery(view as View, chain.args[0] as Int, chain.args[1] as Boolean)
            }
        }
        installStatusBar(loader)
        installStatusIcons(loader, ::images, ::fail, ::after) { it in dressedIcons }
        installVolume(loader)
        installHearingWarnings(loader)
        installPowerMenu(loader)
        installShutdown(loader)
        installScrim(loader)
        installToasts(loader)
        installSystemPalette(loader, ::fail, ::after, ::instead)
        installSplash(loader)
        installClipboard(loader)
        installLock(loader)
        installShade(loader, ::fail, ::after, ::instead, ::withArgs)
        installShut(loader, ::after, ::instead, ::replace)
        installHeadsUp(loader, ::after)
        installPassword()
        diag(Log.INFO, "hooks installed=$installed failed=$failed")
    }

    /** What StatusBarLayout needs from Android once the bar is arranged. */
    private fun installStatusBar(loader: ClassLoader) {
        after("$CLOCK_VIEW.getSmallTime()CharSequence", { layout.clockTarget(loader) }) { chain, text ->
            layout.timeText(chain.thisObject as TextView, text as CharSequence?)
        }
        after("$COMMAND_QUEUE.onSystemBarAttributesChanged(...)V + $BAR_BACKGROUND.mOverrideAlpha", { layout.barAttributesTarget(loader) }) { chain, result ->
            result.also { layout.onBarAttributes(chain.args[0] as Int, chain.args[6] as String?) }
        }
        instead("$HEADS_UP_APPEARANCE.hide(View;I;Lambda)V", { layout.headsUpHideTarget(loader) }) { chain ->
            layout.keptShown(chain.getArg(0) as View?)
        }
        after("$SURFACES_CALLBACKS.showTransient(IIZ)V + IWindowManager.hideTransientBars(I)V", { layout.transientTarget(loader) }) { chain, result ->
            result.also { layout.onTransient(chain.getArg(0) as Int, chain.getArg(1) as Int) }
        }
    }

    /**
     * The 9900's volume overlay in place of Android's dialog: the show is replaced last, and only
     * once the hooks that refresh and hide the overlay are in, so that it never shows without them.
     * Any missing piece leaves Android's dialog as it is.
     */
    private fun installVolume(loader: ClassLoader) {
        val hud = try {
            VolumeHud(loader, ::images, ::fail)
        } catch (e: ReflectiveOperationException) {
            fail("hook target missing: $VOLUME_DIALOG (volume HUD), Android's dialog kept", e)
            return
        }
        val refresh = after("$VOLUME_DIALOG.onStateChangedH(State)V", { hud.onStateChangedH }) { chain, result ->
            result.also { hud.update(chain.thisObject!!) }
        }
        val hide = after("$VOLUME_DIALOG.dismissH(I)V", { hud.dismissH }) { _, result -> result.also { hud.hide() } }
        if (!refresh || !hide) {
            Log.w(TAG, "volume: Android's dialog kept")
            return
        }
        // Before, not after: showing Android's dialog then hiding it would draw it for nothing.
        if (instead("$VOLUME_DIALOG.$VOLUME_SHOW(VolumeDialogImpl;IZI)V", { hud.showH }) { chain -> hud.show(chain.getArg(0)) }) installVolumePanel(loader)
    }

    /**
     * OS 7 has no panel of every stream's slider (volumes are set in Options, Sounds and Ringtones):
     * the panel opens nowhere, neither from the settings button of Android's dialog nor from the
     * broadcast of Settings.Panel.ACTION_VOLUME (VolumePanelFactory.create is the only way to it).
     * Only once the overlay is in, so that a volume never loses its visual feedback: Android's
     * dialog, when the overlay falls back to it, loses its settings button as well.
     */
    private fun installVolumePanel(loader: ClassLoader) {
        instead("$VOLUME_PANEL_FACTORY.create()V", { loader.loadClass(VOLUME_PANEL_FACTORY).getDeclaredMethod("create") }) {
            Log.i(TAG, "volume: Android's panel not opened")
            true
        }
        val settings = try {
            loader.loadClass(VOLUME_DIALOG).getField("mSettingsView")
        } catch (e: ReflectiveOperationException) {
            fail("hook target missing: $VOLUME_DIALOG.mSettingsView (settings button of the fallback dialog)", e)
            return
        }
        after("$VOLUME_DIALOG.initSettingsH(I)V", { loader.loadClass(VOLUME_DIALOG).getDeclaredMethod("initSettingsH", Int::class.java) }) { chain, result ->
            result.also { (settings.get(chain.thisObject) as View?)?.visibility = View.GONE }
        }
    }

    /** OS 7's dialog over Android's power menu, after Android built it (PowerMenuScreen). */
    private fun installPowerMenu(loader: ClassLoader) {
        val screen = try {
            PowerMenuScreen(loader, ::images, ::fail) { layout.powerMenuShown = it }
        } catch (e: ReflectiveOperationException) {
            fail("hook target missing: $ACTIONS_DIALOG (power menu), Android's menu kept", e)
            return
        }
        after("$ACTIONS_DIALOG.onCreate(Bundle)V", { screen.onCreate }) { chain, result ->
            result.also { screen.dress(chain.thisObject as Dialog) }
        }
    }

    /** OS 7's dialog over Android's warnings of a loud headset, once each is built (HearingWarnings). */
    private fun installHearingWarnings(loader: ClassLoader) {
        val warnings = try {
            HearingWarnings(loader, ::images, ::fail)
        } catch (e: ReflectiveOperationException) {
            fail("hook target missing: $SAFE_VOLUME_DIALOG or $CSD_DIALOG (hearing warnings), Android's kept", e)
            return
        }
        after("$SAFE_VOLUME_DIALOG.start()V", { warnings.safeVolumeStart }) { chain, result ->
            result.also { warnings.dress(chain.thisObject as AlertDialog, HearingWarning.SAFE_VOLUME) }
        }
        after("$CSD_DIALOG.start()V", { warnings.csdStart }) { chain, result ->
            result.also { warnings.dress(chain.thisObject as AlertDialog, HearingWarning.CSD) }
        }
    }

    /** OS 7's screen while the phone powers off or restarts, in place of Android's (ShutdownScreen). */
    private fun installShutdown(loader: ClassLoader) {
        val screen = try {
            ShutdownScreen(loader, ::images)
        } catch (e: ReflectiveOperationException) {
            fail("hook target missing: $GLOBAL_ACTIONS_IMPL (shutdown screen), Android's kept", e)
            return
        }
        instead("$GLOBAL_ACTIONS_IMPL.showShutdownUi(Z;String)V", { screen.showShutdownUi }) { chain ->
            screen.show(chain.thisObject!!, chain.getArg(0) as Boolean, chain.getArg(1) as String?)
        }
    }

    /**
     * The black under the quick settings. Android paints its scrims (behind the shade, behind
     * its notifications) in the theme's colorBackgroundFloating (mColors, #FAFAFA in the light
     * theme) and tints some states with its surface (materialColorSurface, system_surface_light):
     * white through the middle of a pull of the shade and over the lock screen. OS 7 has no
     * notification list in the shade (ShadeHooks hides Android's in the unlocked shade, not the
     * locked one); the scrims are set to black again after each theme change.
     */
    private fun installScrim(loader: ClassLoader) {
        var setSurface: Method? = null
        var states: Array<*>? = null
        var colors: Field? = null
        var setMain: Method? = null
        var setSecondary: Method? = null
        var setDarkText: Method? = null
        after("$SCRIM_CONTROLLER.updateThemeColors()V + mColors, $SCRIM_STATE.setSurfaceColor(I)V", {
            val state = loader.loadClass(SCRIM_STATE)
            setSurface = state.getMethod("setSurfaceColor", Int::class.javaPrimitiveType)
            states = state.enumConstants
            val controller = loader.loadClass(SCRIM_CONTROLLER)
            colors = controller.getField("mColors")
            val gradient = colors!!.type
            setMain = gradient.getMethod("setMainColor", Int::class.javaPrimitiveType)
            setSecondary = gradient.getMethod("setSecondaryColor", Int::class.javaPrimitiveType)
            setDarkText = gradient.getMethod("setSupportsDarkText", Boolean::class.javaPrimitiveType)
            controller.getMethod("updateThemeColors")
        }) { chain, result ->
            result.also {
                val black = ShellColors.StatusBar
                val gradient = colors!!.get(chain.thisObject)
                setMain!!.invoke(gradient, black)
                setSecondary!!.invoke(gradient, black)
                setDarkText!!.invoke(gradient, false)
                states!!.forEach { setSurface!!.invoke(it, black) }
            }
        }
    }

    /** The dialog service's toasts on SystemUI's, one hook after ToastFactory.createToast (Toasts). */
    private fun installToasts(loader: ClassLoader) {
        val toasts = try {
            Toasts(loader, ::images) { diag(Log.INFO, it) }
        } catch (e: ReflectiveOperationException) {
            fail("hook target missing: $SYSTEMUI_TOAST (toasts), Android's kept", e)
            return
        }
        after("$TOAST_FACTORY.createToast(Context;CharSequence;String;II)SystemUIToast", { toasts.createToast }) { _, toast ->
            toast.also { toasts.dress(it!!) }
        }
    }

    /**
     * No splash while an app opens, as on the 9900 (startingWindowType), one hook after
     * PhoneStartingWindowTypeAlgorithm.getSuggestedWindowType(StartingWindowInfo)I, on the shell's
     * splash thread: Android's own choice whenever it fails.
     */
    private fun installSplash(loader: ClassLoader) {
        after("$STARTING_WINDOW_ALGORITHM.getSuggestedWindowType(StartingWindowInfo)I", {
            // StartingWindowInfo is hidden from the SDK: by name, from the framework.
            loader.loadClass(STARTING_WINDOW_ALGORITHM).getMethod("getSuggestedWindowType", loader.loadClass("android.window.StartingWindowInfo"))
        }) { _, type -> startingWindowType(type as Int) }
    }

    /**
     * No clipboard preview (clipboard): that listener only shows the overlay and the toast
     * of Android's own copy, it holds no flag to read (Android 14) and nothing else depends on it.
     * The clipboard service, copy and paste are not touched.
     */
    private fun installClipboard(loader: ClassLoader) {
        instead("$CLIPBOARD_LISTENER.onPrimaryClipChanged()V", { loader.loadClass(CLIPBOARD_LISTENER).getDeclaredMethod("onPrimaryClipChanged") }) { true }
    }

    /**
     * The 9900's password entry over Android's bouncer (Bouncer), four hooks, never its checks.
     * After KeyguardPINView.onFinishInflate()V, KeyguardPasswordView.onFinishInflate()V and
     * KeyguardSimPinPukMeView.onFinishInflate()V: the cover of OS 7 over the device's
     * PIN or password and over MediaTek's view of the SIM's PIN and PUK (never the pattern); around
     * KeyguardPinBasedInputView.onKeyDown(I;KeyEvent)Z: the key code of the number a Q25 letter key
     * carries, Android handling it from there (the SIM's view only while covered). Then the face
     * ([installLockFace]).
     */
    private fun installLock(loader: ClassLoader) {
        val pin = "$KEYGUARD_PIN_VIEW.onFinishInflate()V"
        after(pin, { bouncer.pinTarget(loader) }) { chain, result ->
            val view = chain.thisObject as ViewGroup
            result.also { onMain(pin) { bouncer.dress(view, ::find) } }
        }
        val password = "$KEYGUARD_PASSWORD_VIEW.onFinishInflate()V"
        after(password, { bouncer.passwordTarget(loader) }) { chain, result ->
            val view = chain.thisObject as ViewGroup
            result.also { onMain(password) { bouncer.dress(view, ::find) } }
        }
        val sim = "$KEYGUARD_SIM_VIEW.onFinishInflate()V"
        after(sim, { bouncer.simTarget(loader) }) { chain, result ->
            val view = chain.thisObject as ViewGroup
            result.also { onMain(sim) { bouncer.dress(view, ::find) } }
        }
        withArgs("$KEYGUARD_PIN_BASED_VIEW.onKeyDown(I;KeyEvent)Z", { bouncer.pinKeyTarget(loader) }) { chain ->
            bouncer.pinKeyArgs(chain.thisObject!!, chain.getArg(0) as Int, chain.getArg(1) as KeyEvent?)
        }
        installLockFace(loader)
        installPlaceCall(loader, ::after)
    }

    /**
     * The 9900's face of the lock screen (LockFace), ten hooks. After LockIconView.onFinishInflate()V
     * and KeyguardBottomAreaView.onFinishInflate()V (the face, at the top of the bottom area); then,
     * only with the face, instead of NotificationStackScrollLayout.dispatchDraw(Canvas)V and
     * onDraw(Canvas)V (not drawn on the lock screen itself, Android's drawing everywhere else and
     * whenever the face is not there), of its onInterceptTouchEvent(MotionEvent)Z and
     * onTouchEvent(MotionEvent)Z (untouched where undrawn) and getChildAtRawPosition(FF) (no unseen
     * row under a pull of the shade), of KeyguardStatusView.dispatchDraw(Canvas)V (Android's clock
     * undrawn where the stack is), and after KeyguardIndicationTextView.setNextIndication()V (its
     * text in a clear colour) and ScrimController.applyState()V (no darkening under the face nor
     * under OS 7's entry). The keyguard bar is clipped from its own hook (onFinishInflate).
     */
    private fun installLockFace(loader: ClassLoader) {
        val icon = "$LOCK_ICON_VIEW.onFinishInflate()V"
        after(icon, { face.lockIconTarget(loader) }) { chain, result ->
            val view = chain.thisObject as View
            result.also { onMain(icon) { face.onLockIcon(view) } }
        }
        val area = "$KEYGUARD_BOTTOM_AREA.onFinishInflate()V"
        val added = after(area, { face.bottomAreaTarget(loader) }) { chain, result ->
            val view = chain.thisObject as ViewGroup
            result.also { onMain(area) { face.addFace(view, ::find) } }
        }
        if (!added) return
        for (name in listOf("dispatchDraw", "onDraw")) {
            instead("$NOTIFICATION_STACK.$name(Canvas)V", { face.stackTarget(loader, name, Canvas::class.java) }) { chain ->
                face.skipStack(chain.thisObject as ViewGroup, dispatch = name == "dispatchDraw")
            }
        }
        // Undrawn, untouched: the stack takes every touch away from its rows (intercepts) and keeps
        // none (onTouchEvent false), so that the touch goes on to the panel, the unlock gesture.
        replace("$NOTIFICATION_STACK.onInterceptTouchEvent(MotionEvent)Z", { face.stackTarget(loader, "onInterceptTouchEvent", MotionEvent::class.java) }) { chain ->
            face.stackTouch(chain.getArg(0) as MotionEvent, intercept = true)
        }
        replace("$NOTIFICATION_STACK.onTouchEvent(MotionEvent)Z", { face.stackTarget(loader, "onTouchEvent", MotionEvent::class.java) }) { chain ->
            face.stackTouch(chain.getArg(0) as MotionEvent, intercept = false)
        }
        // No unseen row under the finger: a pull of the shade from a hidden card opens the shade
        // as from an empty place (DragDownHelper), not that card expanded.
        instead("$NOTIFICATION_STACK.getChildAtRawPosition(FF)ExpandableView", { face.stackTarget(loader, "getChildAtRawPosition", Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!) }) {
            face.noRowUnderFinger()
        }
        instead("$KEYGUARD_STATUS_VIEW.dispatchDraw(Canvas)V", { face.statusViewDrawTarget(loader) }) { face.skipStatusView() }
        after("$KEYGUARD_INDICATION_TEXT.setNextIndication()V", { face.indicationTarget(loader) }) { chain, result ->
            result.also { face.hideIndication(chain.thisObject as TextView) }
        }
        after("$SCRIM_CONTROLLER.applyState()V + mState, mBehindAlpha, mNotificationsAlpha, mQsExpansion, mTransitionToFullShadeProgress", { face.scrimTarget(loader) }) { chain, result ->
            result.also { face.clearScrims(chain.thisObject!!) }
        }
    }

    /**
     * Runs [block] on SystemUI's main thread: now if already there, else posted, guarded there too.
     * KeyguardSecurityViewFlipperController.asynchronouslyInflateView inflates the bouncer on
     * "AsyncLayoutInflator", and Images and Bouncer are main thread only.
     */
    /**
     * In Google Messages' main process (the SMS bridge): two hooks. After
     * SharedPreferencesImpl.getAll(), the map Phenotype caches its flags from gets the API's two
     * switches and Bold7's certificate ([bridgeFlags]); every other file, and every failure
     * (counted), gives Messages its own map back. After Instrumentation.callApplicationOnCreate,
     * the consent page offered to the launcher ([offerConsentPage]). No code of Messages is
     * replaced, nothing is written to its data. Goes in on the contract ([bridgeAllowed]), not on
     * a fingerprint.
     */
    /**
     * Options' device password (PasswordScreen): no hook, a receiver registered once SystemUI's
     * Application exists, which the main thread's next message finds (onPackageReady runs while
     * the application is bound, before its onCreate).
     */
    private fun installPassword() = main.post {
        guarded("password: receiver", Unit) {
            val app = android.app.ActivityThread.currentApplication() ?: return@guarded fail("password: no Application, Options' password off")
            password.register(app)
        }
    }

    private fun installMessagesBridge(loader: ClassLoader) {
        val service = try {
            Class.forName(MESSAGES_API_SERVICE, false, loader)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
        val fault = Log.isLoggable(FAULT_TAG, Log.VERBOSE)
        val mounted = File(BOLD7_MOUNT_WITNESS).exists()
        if (!bridgeAllowed(service, fault, mounted)) {
            diag(Log.WARN, "bridge: no hook, service=$service fault=$fault bold7Mounted=$mounted")
            return
        }
        val opened = AtomicInteger()
        after("android.app.SharedPreferencesImpl.getAll()Ljava/util/Map;", { Class.forName("android.app.SharedPreferencesImpl").getDeclaredMethod("getAll") }) { _, prefs ->
            @Suppress("UNCHECKED_CAST")
            val out = (prefs as? Map<String, *>)?.let { bridgeFlags(it, BRIDGE_SIGNATURE) }
            // Phenotype reads its file again when a new configuration comes: rare, logged each time.
            if (out != null) diag(Log.INFO, "bridge: flags opened in PhenotypePrefs (read ${opened.incrementAndGet()})")
            out ?: prefs
        }
        after("android.app.Instrumentation.callApplicationOnCreate(Application)V", {
            Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
        }) { chain, result ->
            result.also { offerConsentPage(chain.getArg(0) as Application) }
        }
        diag(Log.INFO, "bridge: service present, hooks installed=$installed failed=$failed")
    }

    /**
     * Google Messages' consent page (CONSENT_PAGE, not exported) for the launcher's Setup screen:
     * an ordered broadcast to Google Messages, which the system lets through from a holder of
     * OPEN_CONSENT_PERMISSION only (signature, the launcher's), from the launcher alone once its
     * shared identity is read ([consentPageFor]), is answered with a PendingIntent of Google
     * Messages for that page, immutable: the launcher can start it, nothing else. Registered once
     * Google Messages' Application is created, its context the receiver's.
     */
    private fun offerConsentPage(app: Application) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = guarded("bridge: consent page", Unit) {
                val sender = sentFromPackage
                if (!consentPageFor(sender)) return@guarded diag(Log.WARN, "bridge: consent page refused to $sender")
                val page = PendingIntent.getActivity(context, 0, Intent().setClassName(MESSAGES, CONSENT_PAGE), PendingIntent.FLAG_IMMUTABLE)
                setResultExtras(Bundle().apply { putParcelable(CONSENT_PAGE_EXTRA, page) })
                diag(Log.INFO, "bridge: consent page handed to $sender")
            }
        }
        app.registerReceiver(receiver, IntentFilter(OPEN_CONSENT_ACTION), OPEN_CONSENT_PERMISSION, null, Context.RECEIVER_EXPORTED)
        diag(Log.INFO, "bridge: consent page offered behind $OPEN_CONSENT_PERMISSION")
    }

    private fun onMain(target: String, block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post { guarded(target, Unit) { block() } }
    }

    /**
     * Hooks [target] to run [after] once the original returns, with its result, and to return what
     * [after] gives. A failing [after] is counted and the original result returned.
     */
    private fun after(target: String, find: () -> Executable, after: (Chain, Any?) -> Any?): Boolean = install(target, find) { chain ->
        val result = chain.proceed()
        guarded(target, result) { after(chain, result) }
    }

    /**
     * Hooks [target] to run [instead] in place of the original, which runs anyway when [instead]
     * returns false or fails (counted).
     */
    private fun instead(target: String, find: () -> Executable, instead: (Chain) -> Boolean): Boolean = install(target, find) { chain ->
        if (guarded(target, false) { instead(chain) }) null else chain.proceed()
    }

    /**
     * Hooks [target] to return what [replace] gives in place of the original, which runs when
     * [replace] gives null or fails (counted): for methods that return a value.
     */
    private fun replace(target: String, find: () -> Executable, replace: (Chain) -> Any?): Boolean = install(target, find) { chain ->
        guarded(target, null) { replace(chain) } ?: chain.proceed()
    }

    /**
     * Hooks [target] to run the original with the arguments [args] gives, or with its own when
     * [args] gives null or fails (counted).
     */
    private fun withArgs(target: String, find: () -> Executable, args: (Chain) -> Array<Any?>?): Boolean = install(target, find) { chain ->
        val changed = guarded(target, null) { args(chain) }
        if (changed == null) chain.proceed() else chain.proceed(changed)
    }

    /** A missing target is counted, never thrown. */
    private fun install(target: String, find: () -> Executable, hooker: Hooker): Boolean = try {
        hook(find()).intercept(hooker)
        installed++
        true
    } catch (e: ReflectiveOperationException) {
        fail("hook target missing: $target", e)
        false
    } catch (e: HookFailedError) {
        fail("hook refused by Vector: $target", e)
        false
    }

    private inline fun <T> guarded(target: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        fail("hook failed: $target", e)
        fallback
    } catch (e: ReflectiveOperationException) {
        fail("hook failed: $target", e)
        fallback
    }

    private fun fail(what: String, e: Throwable? = null) {
        val count = failed.incrementAndGet()
        diag(Log.ERROR, "$what (installed=$installed failed=$count)", e)
    }

    /**
     * The guard's verdict, the hook count and every failure, in logcat and in Vector's module log
     * (/data/adb/lspd/log/modules_*.log, kept for the boot): logcat's 256 KiB buffer turns over
     * within minutes on this phone, before anyone reads a line written at SystemUI's start.
     */
    private fun diag(priority: Int, message: String, e: Throwable? = null) {
        val text = if (e == null) message else message + "\n" + Log.getStackTraceString(e)
        Log.println(priority, tag, text)
        log(priority, tag, text)
    }

    private fun radioIconShown(view: View, slot: String?, shown: Boolean): Boolean {
        val bar = dressedIcons[view.parent] ?: return shown
        val result = androidIconShown(slot, shown, inDressedBar = true)
        if (shown && !result && loggedHidden.add("$slot@$bar")) Log.i(TAG, "hidden: Android's $slot icon in $bar")
        return result
    }

    /** The status bar as the 9900's banner (StatusBarLayout). */
    private fun dress(bar: ViewGroup) {
        val images = images(bar.context) ?: return
        val battery = find(bar, "battery") ?: return
        val banner = BannerView(bar.context, images, BannerKind.APP, ::fail)
        if (!layout.arrange(bar, banner) { find(bar, it) }) return
        find(bar, "statusIcons")?.let {
            dressedIcons[it as ViewParent] = bar.javaClass.simpleName
            it.requestLayout()
            Log.i(TAG, "blocked: ${RADIO_SLOTS.joinToString()} on statusIcons of ${bar.javaClass.simpleName}")
        }
        Log.i(TAG, "dressed ${bar.javaClass.simpleName}")
        statusBarBattery = WeakReference(battery)
        boltOn(battery, images)
    }

    private fun find(root: View, id: String): View? {
        val res = root.resources.getIdentifier(id, "id", SYSTEMUI)
        return (if (res == 0) null else root.findViewById<View>(res))
            ?: null.also { fail("view target missing: @id/$id in ${root.javaClass.name}") }
    }

    /**
     * The charging bolt for the dressed bar's [battery] (BatteryBolt): its clock (BoltClock), a
     * view of no size at the end of the BatteryMeterView, and its place on one line or two
     * (StatusBarLayout.batteryLine). Nothing when the battery group is off: Android's battery then.
     */
    private fun boltOn(battery: View, images: Images) {
        val icon = batteryIcon?.get(battery) as? ImageView ?: return
        val bolt = BatteryBolt(images, icon)
        (battery as? ViewGroup)?.addView(BoltClock(battery.context, bolt.ticker), 0, 0)
            ?: return fail("view target missing: @id/battery is ${battery.javaClass.name}, not a ViewGroup")
        batteryBolt = bolt
        layout.batteryLine = { oneLine ->
            bolt.oneLine = oneLine
            icon.invalidate()
        }
    }

    /**
     * The 9900's battery (batteryImage) and its bolt. BatteryMeterView only tells the [level] and
     * [plugged] (on power): whether it charges or is full is the status of the sticky
     * ACTION_BATTERY_CHANGED, read here (a null receiver only reads it), as the home's banner and
     * the lock screen read it. Without it, the plug on power, as before.
     */
    private fun showBattery(view: View, level: Int, plugged: Boolean) {
        val images = images ?: return
        val icon = batteryIcon!!.get(view) as ImageView
        val sticky = view.context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val image = images[if (sticky != null) batteryImage(sticky) else batteryImage(level, plugged)]
        val bolt = batteryBolt?.takeIf { it.icon === icon }
        val drawable = bolt?.apply {
            battery = image
            ticker.charging = sticky != null && charging(sticky)
        } ?: Indicator(image)
        icon.setImageDrawable(drawable)
        icon.invalidate()
        // The bolt overflows the frame by BatteryBolt.ABOVE: the view goes up as much (StatusBarLayout
        // never moves it but by its margins).
        view.translationY = if (bolt == null) 0f else -BatteryBolt.ABOVE.toFloat()
        // BatteryMeterView sizes the icon for Android's own battery (scaleBatteryMeterViews).
        val lp = icon.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.width != drawable.intrinsicWidth || lp.height != drawable.intrinsicHeight || lp.bottomMargin != 0) {
            lp.width = drawable.intrinsicWidth
            lp.height = drawable.intrinsicHeight
            lp.setMargins(0, 0, 0, 0)
            icon.layoutParams = lp
        }
    }

    // Simplification: the module's resources opened once on SystemUI's main thread, at the first
    // inflation (a few ms for this small APK); the images themselves decode on core/theme's thread.
    private fun images(context: Context): Images? = images ?: try {
        // In OS 7's language, core/ui's strings included, while SystemUI and what stays
        // Android's in it speak the phone's. Only the locales are overridden: the rest (density,
        // night mode, font scale) follows SystemUI's configuration, its changes included.
        val os7 = Configuration().apply { setLocales(LocaleList(OS7_LOCALE)) }
        val res = context.packageManager.getResourcesForApplication(moduleApplicationInfo, os7)
        Images(res, context) { fail("theme image missing: $it, drawn empty") }.also { images = it }
    } catch (e: PackageManager.NameNotFoundException) {
        fail("module resources unreadable", e)
        null
    }
}
