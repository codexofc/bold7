package dev.bold7.sysui

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Field
import java.lang.reflect.Method

// The SystemUI classes the Call key's hook reaches by name; LockTest finds each in
// stubs/systemui.jar, LockTargets.kt compiles the members. KEYGUARD_STATE is ShadeHooks'.
internal const val SHADE_WINDOW_VIEW = "com.android.systemui.shade.NotificationShadeWindowView"
internal const val SHADE_WINDOW_HANDLER = "com.android.systemui.shade.NotificationShadeWindowViewController\$1"
internal const val SHADE_WINDOW_CONTROLLER = "com.android.systemui.shade.NotificationShadeWindowViewController"
internal const val CENTRAL_SURFACES = "com.android.systemui.statusbar.phone.CentralSurfacesImpl"

/** The Phone's Place Call, exported behind STATUS_BAR_SERVICE, which SystemUI holds. */
private val PLACE_CALL = ComponentName("dev.bold7.phone", "dev.bold7.phone.PlaceCallActivity")

/**
 * Place Call under the lock, one hook, after
 * NotificationShadeWindowView.dispatchKeyEvent(KeyEvent)Z. On the lock screen that window has the
 * focus and no view of it takes the Call key (MtkSystemUI of the Q25, jadx: Back, Space, Menu and
 * the volume only), and no fallback handler sits behind it: the key went nowhere. Now its release,
 * when the press reached this window too ([CallKeyPress]) and [placeCallOpens] says so, starts the
 * Phone's PlaceCallActivity over the lock screen. Nothing else changes: PhoneWindowManager answers
 * a ringing call on the press before any window, the emergency button and the bouncer are not
 * touched, and the key is Android's again when anything is off (a key a view of the window took, a
 * press not seen, the bouncer, a call, an alarm). A missing target or a failure (the Phone absent,
 * Telecom refusing) is counted and the key left as Android had it.
 */
internal fun installPlaceCall(loader: ClassLoader, after: (String, () -> Method, (Chain, Any?) -> Any?) -> Boolean) {
    var handler: Field? = null
    var controller: Field? = null
    var service: Field? = null
    var state: Field? = null
    var interactive: Field? = null
    var keyguard: Field? = null
    var showing: Field? = null
    var occluded: Field? = null
    var bouncer: Field? = null
    val press = CallKeyPress()
    after("$SHADE_WINDOW_VIEW.dispatchKeyEvent(KeyEvent)Z + mInteractionEventHandler.this\$0.mService, $CENTRAL_SURFACES.mState, mDeviceInteractive, mKeyguardStateController, $KEYGUARD_STATE.mShowing, mOccluded, mPrimaryBouncerShowing", {
        val window = loader.loadClass(SHADE_WINDOW_VIEW)
        handler = window.getField("mInteractionEventHandler")
        controller = loader.loadClass(SHADE_WINDOW_HANDLER).getField("this\$0")
        service = loader.loadClass(SHADE_WINDOW_CONTROLLER).getField("mService")
        val surfaces = loader.loadClass(CENTRAL_SURFACES)
        state = surfaces.getField("mState")
        interactive = surfaces.getField("mDeviceInteractive")
        keyguard = surfaces.getField("mKeyguardStateController")
        val keyguardState = loader.loadClass(KEYGUARD_STATE)
        showing = keyguardState.getField("mShowing")
        occluded = keyguardState.getField("mOccluded")
        bouncer = keyguardState.getField("mPrimaryBouncerShowing")
        window.getDeclaredMethod("dispatchKeyEvent", KeyEvent::class.java)
    }) { chain, handled ->
        val e = chain.getArg(0) as KeyEvent
        if (handled == true || !press.released(e.action, e.keyCode, e.repeatCount, e.downTime, e.isCanceled)) return@after handled
        val view = chain.thisObject as View
        val surfaces = service!!.get(controller!!.get(handler!!.get(view)))
        val lock = keyguard!!.get(surfaces)
        val context = view.context
        // Two binder calls, on the release of a Call key on the lock screen only.
        val inCall = context.getSystemService(TelecomManager::class.java).isInCall
        val playing = context.getSystemService(AudioManager::class.java).activePlaybackConfigurations.map { it.audioAttributes.usage }
        val barState = state!!.getInt(surfaces)
        val shown = showing!!.getBoolean(lock)
        val covered = occluded!!.getBoolean(lock)
        val entry = bouncer!!.getBoolean(lock)
        val awake = interactive!!.getBoolean(surfaces)
        val opens = placeCallOpens(barState, shown, covered, entry, awake, inCall, playing)
        Log.i(TAG, "call key on the lock screen: ${if (opens) "Place Call" else "left to Android"} (state=$barState showing=$shown occluded=$covered bouncer=$entry interactive=$awake inCall=$inCall playing=$playing)")
        if (!opens) return@after handled
        context.startActivity(Intent().setComponent(PLACE_CALL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }
}
