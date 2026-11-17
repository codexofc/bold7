package dev.bold7.launcher

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.PowerManager
import android.os.UserManager
import android.util.Log

/**
 * Whether the charger opens the Clock: on the home screen (sim-412), over the lock screen or a
 * screen that is off (sim-414), never during a call or while the phone rings. Another app in
 * front keeps its place (D: no capture of the 9900 charging inside an app).
 */
fun opensClock(locked: Boolean, homeShown: Boolean, screenOn: Boolean, inCall: Boolean): Boolean =
    !inCall && (locked || homeShown || !screenOn)

/**
 * Clock Options > When Charging (sim-170, sim-417): the 9900 opens its Clock as soon as the charger
 * is in. Android hands the charger's broadcast only to an app that runs, and lets an app start an
 * activity from the background only if it is the home ("Home app" in BackgroundActivityStartController
 * of the Q25's services.jar): the home of Bold7, always running, starts the Clock, which keeps the
 * choice itself (dev.bold7.clock's Charging.kt: one alias enabled per choice, none for Do Nothing).
 * Listened to on [io], nothing on the main thread.
 */
class ChargingClock(private val context: Context, private val io: Handler) {
    /** The home's activity is started (LauncherActivity). */
    @Volatile var homeShown = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
            val audio = context.getSystemService(AudioManager::class.java).mode
            val open = opensClock(
                locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
                homeShown = homeShown,
                screenOn = context.getSystemService(PowerManager::class.java).isInteractive,
                inCall = audio != AudioManager.MODE_NORMAL,
            )
            if (open) start()
        }
    }

    fun listen() {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_POWER_CONNECTED), null, io, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun start() {
        val clock = Intent(ACTION).setPackage(CLOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(clock)
        } catch (e: ActivityNotFoundException) {
            // Do Nothing, or no Clock of Bold7: the charger changes nothing.
        } catch (e: SecurityException) {
            Log.w(TAG, "the Clock refused the charger's start", e)
        }
    }

    private companion object {
        const val TAG = "Bold7/ChargingClock"
        const val CLOCK = "dev.bold7.clock"
        const val ACTION = "dev.bold7.clock.CHARGING"
    }
}
