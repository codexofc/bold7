package dev.bold7.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * The ring of the alarm or of the timer a foreground service, so that
 * it sounds whatever the screen, the lock or the battery saver do; started by AlarmReceiver from
 * AlarmManager's exemption.
 *
 * - Sound: the alarm stream (USAGE_ALARM), which the ringer's silent and vibrate modes leave alone
 *   and which every Do Not Disturb policy of the profiles lets through; the stream's
 *   volume set to Options' Volume while it rings and given back after ([ClockStore.savedVolume],
 *   also at boot after a crash); the tone from the module's /product/media/audio/alarms, readable
 *   before the first unlock, else Android's default alarm, else the first of /system/media/audio/
 *   alarms, else the vibrator alone, each failure logged.
 * - Screen: a full-screen notification (category alarm: kept by the SystemUI hook, not held
 *   back by All Alerts Off's Do Not Disturb, which lets alarms through) opens [RingActivity] over the
 *   lock screen; the activity is also started at once, which Android allows the Clock since
 *   tools/install.sh grants it SYSTEM_ALERT_WINDOW. Once [RingActivity] is on the screen ([shown]),
 *   the same notification is posted again on a low importance channel, without its full-screen
 *   intent: Android's card (heads-up) then goes, OS 7's screen alone is seen; the service stays in
 *   the foreground with it. Read in the Q25's MtkSystemUI (jadx): HeadsUpCoordinator.onEntryUpdated
 *   asks checkHeadsUp again at each update and removes an alerting card it now refuses
 *   (HeadsUpManager.removeNotification), and checkHeadsUp refuses any importance under HIGH
 *   ("No heads up: unimportant notification"). If the screen never comes, the card stays: the
 *   way to the ring screen.
 * - It stops on Snooze or Dismiss, or by itself after [RING_LIMIT_MS] (C, sim-162 to sim-164:
 *   the 9900's dialog went by itself between 2.5 and 4 minutes, without ringing again), but then
 *   snoozes when a snooze is set (D: an alarm nobody heard comes back).
 */
class RingService : Service() {
    companion object {
        private const val RING = "dev.bold7.clock.RING"
        private const val SNOOZE = "dev.bold7.clock.SNOOZE"
        private const val DISMISS = "dev.bold7.clock.DISMISS"
        private const val SHOWN = "dev.bold7.clock.SHOWN"
        private const val EXTRA_TIMER = "timer"
        private const val CHANNEL = "alarm"
        private const val CHANNEL_SHOWN = "alarm_shown"
        private const val ID = 1
        const val RING_LIMIT_MS = 3 * 60_000L

        private var cpu: PowerManager.WakeLock? = null

        /** The CPU kept awake from AlarmManager's broadcast until the service holds its own. */
        fun holdCpu(context: Context) = (cpu ?: wakeLock(context.applicationContext).also { cpu = it }).acquire(60_000)

        fun ring(context: Context, timer: Boolean) {
            context.startForegroundService(Intent(context, RingService::class.java).setAction(RING).putExtra(EXTRA_TIMER, timer))
        }

        fun snooze(context: Context) = context.startService(Intent(context, RingService::class.java).setAction(SNOOZE))

        fun dismiss(context: Context) = context.startService(Intent(context, RingService::class.java).setAction(DISMISS))

        /** The ring screen is on the screen (RingActivity.onResume): Android's card may go. */
        fun shown(context: Context) = context.startService(Intent(context, RingService::class.java).setAction(SHOWN))
    }

    private val graph get() = application.graph
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val vibrator by lazy { getSystemService(VibratorManager::class.java).defaultVibrator }
    private val wake by lazy { wakeLock(this) }
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(ALARM_ATTRIBUTES).build()

    // On the io thread.
    private var player: MediaPlayer? = null
    private var volume = 8
    private var startedAt = 0L
    private val ramp = object : Runnable {
        override fun run() {
            val p = player ?: return
            val g = gain(volume, SystemClock.elapsedRealtime() - startedAt)
            p.setVolume(g, g)
            if (g < 1f) graph.io.postDelayed(this, 500)
        }
    }
    private val limit = Runnable { end(snooze = true) }

    // Main thread: what rings, and the foreground type Android took.
    private var timer = false
    private var active = false
    private var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RING -> start(intent.getBooleanExtra(EXTRA_TIMER, false))
            SNOOZE -> end(snooze = true)
            DISMISS -> end(snooze = false)
            SHOWN -> if (active) quiet() else stopSelf()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * The ring's notification: on [CHANNEL], high importance with its full-screen intent (Android's
     * card, or the screen over the lock screen); [shown], on [CHANNEL_SHOWN], low importance and
     * silent, without it, once OS 7's screen is there. Same id, same content, alerting once.
     */
    private fun notification(shown: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val notifications = getSystemService(NotificationManager::class.java)
        val channel = if (shown) CHANNEL_SHOWN else CHANNEL
        notifications.createNotificationChannel(
            NotificationChannel(channel, getString(R.string.channel_alarm), if (shown) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        return Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(if (timer) R.string.timer else R.string.daily_alarm))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (!shown) setFullScreenIntent(open, true) }
            .build()
    }

    /** The ring screen is up: the same notification, quiet, in the foreground still; Android's card goes. */
    private fun quiet() {
        startForeground(ID, notification(shown = true), type)
        Log.i(TAG, "ring screen shown: the notification no longer makes a card")
    }

    private fun start(timer: Boolean) {
        this.timer = timer
        active = true
        // Within the 5 s Android gives a foreground service, before anything that reads the disk.
        val notifications = getSystemService(NotificationManager::class.java)
        // A ring that starts with the ring screen already up (the timer, then the alarm) stays quiet.
        val notification = notification(shown = graph.ringScreen)
        try {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            startForeground(ID, notification, type)
        } catch (e: RuntimeException) {
            // systemExempted needs the exact alarm permission; media playback is the alarm's sound anyway.
            Log.e(TAG, "systemExempted refused, ringing as media playback", e)
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(ID, notification, type)
        }
        wake.acquire(RING_LIMIT_MS + 60_000)
        cpu?.release()
        if (!notifications.canUseFullScreenIntent()) Log.e(TAG, "USE_FULL_SCREEN_INTENT not allowed: the ring screen will not open over the lock screen (tools/install.sh)")
        try {
            startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "ring screen not started directly, the full-screen notification opens it", e)
        }
        graph.io.post {
            startedAt = SystemClock.elapsedRealtime()
            // The timer rings by its own options (sim-173), the alarm by Alarm's (ringSpec, tested).
            val settings = graph.store.settings
            val spec = ringSpec(timer, settings)
            volume = spec.volume
            if (graph.store.savedVolume == null) graph.store.savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM).toLong()
            audio.setStreamVolume(AudioManager.STREAM_ALARM, streamLevel(volume, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)), 0)
            audio.requestAudioFocus(focus)
            player?.release()
            // Silent: the 9900's own choice (sim-177), the screen and the vibration only.
            player = if (volume == SILENT) null else openTone(this, spec.tone, gain(volume, 0))
            graph.io.post(ramp)
            if (spec.vibrate) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            graph.io.removeCallbacks(limit)
            graph.io.postDelayed(limit, RING_LIMIT_MS)
            val ringing = Ringing(timer, System.currentTimeMillis(), spec.snooze, alarmText(settings.alarm))
            mainExecutor.execute { graph.ringing = ringing }
        }
    }

    /** Snooze (when the alarm has one) or Dismiss; the next ring armed, the sound and the volume given back. */
    private fun end(snooze: Boolean) {
        graph.io.post {
            val ringing = graph.ringing
            if (ringing == null || !ringing.timer) {
                val minutes = graph.store.settings.options.snooze
                graph.store.snoozeUntil = if (snooze && minutes > 0) snoozeUntil(System.currentTimeMillis(), minutes) else null
                graph.scheduler.arm()
            }
            stopSound()
            mainExecutor.execute {
                active = false
                graph.ringing = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopSound() {
        graph.io.removeCallbacks(ramp)
        graph.io.removeCallbacks(limit)
        player?.run {
            stop()
            release()
        }
        player = null
        vibrator.cancel()
        audio.abandonAudioFocusRequest(focus)
        graph.store.savedVolume?.let {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, it.toInt(), 0)
            graph.store.savedVolume = null
        }
    }

    override fun onDestroy() {
        // Killed without end(): the sound stops with the process anyway; the volume is given back here or at boot.
        graph.io.post { if (player != null) stopSound() }
        if (wake.isHeld) wake.release()
        super.onDestroy()
    }
}
