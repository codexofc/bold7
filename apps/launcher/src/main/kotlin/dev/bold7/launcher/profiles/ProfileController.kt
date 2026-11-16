package dev.bold7.launcher.profiles

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * The file of the launcher's preferences that holds the profiles' state, in device-protected
 * storage (readable before the first unlock, where the quickstep service may start this process,
 * and by root: tools/uninstall.sh reads [USER_POLICY_KEY] there and deletes the file):
 * /data/user_de/0/dev.bold7.launcher/shared_prefs/profiles.xml.
 */
internal const val PREFS = "profiles"

/** The user's Do Not Disturb policy while a profile's stands in for it (DndPolicy.encode). */
internal const val USER_POLICY_KEY = "dnd_policy"

/** The profile last chosen (Profile.id), which tells Loud and Medium from Normal; kept only while Android still rings. */
internal const val PROFILE_KEY = "profile"

private fun volumeKey(profile: Profile) = "volume_${profile.id}"

/** The profiles' ring volumes as Options reads and sets them: [ProfileController], a fake in the tests. */
internal interface ProfileVolumes {
    val current: Profile

    fun volumeTenths(profile: Profile): Int

    fun setVolumeTenths(profile: Profile, tenths: Int): Boolean

    /** Makes [profile] the one in use (Profile Management); runs on the controller's io. */
    fun select(profile: Profile)
}

/**
 * BBOS 7's profiles on Android's state, in the launcher's own process through
 * Android's public APIs: the ringer mode and the ring volume (AudioManager, which needs no
 * permission for a ringing mode) and Do Not Disturb (NotificationManager, which needs the policy
 * access tools/install.sh gives the launcher with `cmd notification allow_dnd`). What is kept
 * (the user's policy, the profile chosen, the ring volume each ringing profile had) is in [PREFS].
 * Everything runs on [io], never on the main thread; the profile is read again at each change
 * Android broadcasts (ringer, Do Not Disturb, its policy), never polled, and [onChange] runs on the
 * main thread after. A failure is logged, the profile stays what Android says.
 */
internal class ProfileController(private val context: Context, private val io: Handler) : ProfileVolumes {
    /** The profile last read, Normal until then. Written on [io]. */
    @Volatile override var current: Profile = Profile.NORMAL
        private set

    /** What [PROFILE_SETTING] last got from this process. Written on [io]. */
    private var published: String? = null

    /** Main thread, after each change of [current]. */
    var onChange: ((Profile) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var attached = false
    private val prefs: SharedPreferences by lazy { context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = guarded("read") { read() }
    }

    /**
     * Once, from the first home or the quickstep service (the process lives on): listens for good,
     * so that a policy left saved is given back when Do Not Disturb ends whatever ended it.
     */
    @Synchronized fun attach() {
        if (attached) return
        attached = true
        io.post {
            guarded("listen") {
                val filter = IntentFilter().apply {
                    addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                    addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                    addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
                }
                context.registerReceiver(receiver, filter, null, io, Context.RECEIVER_NOT_EXPORTED)
                read()
            }
        }
    }

    /** Sets [profile]: Android's calls in the order [profileSteps] gives. */
    override fun select(profile: Profile) {
        io.post {
            guarded("select ${profile.id}") {
                val nm = context.getSystemService(NotificationManager::class.java)
                check(nm.isNotificationPolicyAccessGranted) { "no Do Not Disturb access: tools/install.sh --permissions" }
                val audio = context.getSystemService(AudioManager::class.java)
                val leaving = state(nm, audio)
                Log.i(TAG, "profile ${leaving.id} -> ${profile.id}")
                // A ringing profile keeps the volume it is left with, as a profile of the 9900 holds its own.
                if (leaving in RINGING) keepVolume(leaving, audio)
                for (step in profileSteps(profile, saved() != null)) when (step) {
                    ProfileStep.SaveUserPolicy -> nm.notificationPolicy.run {
                        val user = DndPolicy(priorityCategories, priorityCallSenders, priorityMessageSenders, suppressedVisualEffects, priorityConversationSenders)
                        check(prefs.edit().putString(USER_POLICY_KEY, user.encode()).commit()) { "cannot save the user's policy" }
                    }
                    is ProfileStep.SetPolicy -> nm.notificationPolicy = step.policy.toAndroid()
                    is ProfileStep.SetFilter -> nm.setInterruptionFilter(step.filter)
                    is ProfileStep.SetRinger -> audio.ringerMode = step.ringer
                    is ProfileStep.SetVolume -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
                        audio.setStreamVolume(AudioManager.STREAM_RING, volumeFor(step.profile, savedVolume(step.profile), max), 0)
                    }
                    ProfileStep.RestoreUserPolicy -> restore(nm)
                }
                prefs.edit().putString(PROFILE_KEY, profile.id).apply()
                read()
            }
        }
    }

    /**
     * The ring volume of a ringing [profile] in tenths, as Options shows it: Android's
     * while it is the profile in use, else the one it keeps, else its default. Blocks: io only.
     */
    override fun volumeTenths(profile: Profile): Int {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
        val live = audio.getStreamVolume(AudioManager.STREAM_RING).takeIf { profile == current && it > 0 }
        return tenthsOf(live ?: volumeFor(profile, savedVolume(profile), max), max)
    }

    /** Keeps [tenths] as [profile]'s ring volume, and sets it now if it is the profile in use. Blocks: io only. */
    override fun setVolumeTenths(profile: Profile, tenths: Int): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val index = volumeIndex(tenths, audio.getStreamMaxVolume(AudioManager.STREAM_RING))
        if (!prefs.edit().putInt(volumeKey(profile), index).commit()) return false
        if (profile == current) try {
            audio.setStreamVolume(AudioManager.STREAM_RING, index, 0)
        } catch (e: SecurityException) {
            Log.e(TAG, "ring volume refused", e)
            return false
        }
        return true
    }

    /** What Android says now, without touching anything. */
    private fun state(nm: NotificationManager, audio: AudioManager): Profile {
        val policy = nm.notificationPolicy
        return profileOf(nm.currentInterruptionFilter, audio.ringerMode, callsAllowed(policy.priorityCategories), lightsHeld(policy.suppressedVisualEffects), Profile.of(prefs.getString(PROFILE_KEY, null)))
    }

    private fun read() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val profile = state(nm, audio)
        // Do Not Disturb ended elsewhere (Android's Settings, a schedule): the user's policy back.
        if (restoresUserPolicy(nm.currentInterruptionFilter, saved() != null)) restore(nm)
        // Loud and Medium mean nothing once Android stopped ringing (vibrate, Do Not Disturb, silent).
        if (profile !in RINGING && prefs.getString(PROFILE_KEY, null) in listOf(Profile.LOUD.id, Profile.MEDIUM.id)) prefs.edit().remove(PROFILE_KEY).apply()
        // Normal starts with the volume the phone has, whatever profile it starts in (only Loud and
        // Medium, which set theirs, do not count): choosing Normal never sets a volume unasked
        // while the phone had a ring volume to keep.
        if (profile != Profile.LOUD && profile != Profile.MEDIUM && savedVolume(Profile.NORMAL) == null) keepVolume(Profile.NORMAL, audio)
        // Written at the first read after the launcher starts too, so the lock screen never shows a stale one.
        profileToPublish(profile, published)?.let { id ->
            if (Settings.Global.putString(context.contentResolver, PROFILE_SETTING, id)) published = id else Log.e(TAG, "cannot publish the profile")
        }
        if (profile != current) {
            current = profile
            main.post { onChange?.invoke(profile) }
        }
    }

    // 0 is muted by the ringer (getStreamVolume answers 0 for a muted stream): nothing to keep.
    private fun keepVolume(profile: Profile, audio: AudioManager) {
        val volume = audio.getStreamVolume(AudioManager.STREAM_RING)
        if (volume > 0) prefs.edit().putInt(volumeKey(profile), volume).apply()
    }

    private fun savedVolume(profile: Profile): Int? = prefs.getInt(volumeKey(profile), 0).takeIf { it > 0 }

    private fun saved(): String? = prefs.getString(USER_POLICY_KEY, null)

    private fun restore(nm: NotificationManager) {
        val user = DndPolicy.decode(saved())
        if (user == null) Log.e(TAG, "unreadable $USER_POLICY_KEY, dropped") else nm.notificationPolicy = user.toAndroid()
        // commit, not apply: tools/uninstall.sh reads the file right after.
        prefs.edit().remove(USER_POLICY_KEY).commit()
        Log.i(TAG, "user's Do Not Disturb policy given back")
    }

    private fun DndPolicy.toAndroid() = NotificationManager.Policy(categories, callSenders, messageSenders, visualEffects, conversationSenders)

    // Nothing may escape into the launcher's process: a refused call is logged.
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.e(TAG, "profiles: $what", e)
        }
    }

    private companion object {
        const val TAG = "Bold7/Profiles"
    }
}
