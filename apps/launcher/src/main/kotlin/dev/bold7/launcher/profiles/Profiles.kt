package dev.bold7.launcher.profiles

import android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
import android.app.NotificationManager.INTERRUPTION_FILTER_ALL
import android.app.NotificationManager.INTERRUPTION_FILTER_NONE
import android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
import android.app.NotificationManager.Policy.CONVERSATION_SENDERS_NONE
import android.app.NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
import android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CALLS
import android.app.NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
import android.app.NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS
import android.app.NotificationManager.Policy.PRIORITY_SENDERS_ANY
import android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT
import android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
import android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS
import android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK
import android.media.AudioManager.RINGER_MODE_NORMAL
import android.media.AudioManager.RINGER_MODE_SILENT
import android.media.AudioManager.RINGER_MODE_VIBRATE
import kotlin.math.roundToInt

// BBOS 7's sound profiles (docs/BBOS7-VISUEL.md §8, manual p. 159 to 161) on Android's ringer mode,
// ring volume and Do Not Disturb. Pure (docs/ARCHITECTURE.md §1): Android's constants only, inlined
// at compile time. The logic is checked against the Q25's ZenModeHelper, with the seven profiles of OS 7.

/**
 * The seven preloaded profiles. [frame] is the profile's image in the banner's strip
 * (net_rim_Ribbon_Profile-icons43x43.png: Normal, Vibrate Only, Silent, Loud, Phone Calls Only,
 * All Alerts Off, a custom profile, Medium; the custom profile is not made, Add Custom Profile
 * is not offered).
 */
enum class Profile(val id: String, val frame: Int) {
    NORMAL("normal", 0),
    VIBRATE("vibrate", 1),
    SILENT("silent", 2),
    LOUD("loud", 3),
    PHONE_ONLY("phoneonly", 4),
    ALERTS_OFF("alertsoff", 5),
    MEDIUM("medium", 7);

    companion object {
        /** "Select a Profile" (sim-28, C). */
        val LIST = listOf(ALERTS_OFF, NORMAL, LOUD, MEDIUM, SILENT, VIBRATE, PHONE_ONLY)

        fun of(id: String?): Profile? = entries.firstOrNull { it.id == id }
    }
}

/** The Settings.Global key SystemUI reads to draw the lock screen's speaker: the id of the profile in use. */
internal const val PROFILE_SETTING = "bold7_profile"

/** What to write to [PROFILE_SETTING] for [profile], null when [published] already says it. */
internal fun profileToPublish(profile: Profile, published: String?): String? = profile.id.takeIf { it != published }

/** The fields of a NotificationManager.Policy that Do Not Disturb filters by. */
data class DndPolicy(val categories: Int, val callSenders: Int, val messageSenders: Int, val visualEffects: Int, val conversationSenders: Int) {
    fun encode() = "$categories,$callSenders,$messageSenders,$visualEffects,$conversationSenders"

    companion object {
        fun decode(s: String?): DndPolicy? {
            val n = s?.split(',')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
            return if (n.size == 5) DndPolicy(n[0], n[1], n[2], n[3], n[4]) else null
        }
    }
}

/**
 * Phone Calls Only: every caller and repeat caller rings, no message, conversation, reminder,
 * event nor system sound, nothing visual held back. Alarms and media go through (D).
 */
val PHONE_ONLY_POLICY = DndPolicy(
    PRIORITY_CATEGORY_CALLS or PRIORITY_CATEGORY_REPEAT_CALLERS or PRIORITY_CATEGORY_ALARMS or PRIORITY_CATEGORY_MEDIA,
    PRIORITY_SENDERS_ANY, PRIORITY_SENDERS_ANY, 0, CONVERSATION_SENDERS_NONE,
)

/**
 * Silent, "flashing LED alerts only" (manual p. 161): only alarms and media, nothing held back, so
 * the LED and the previews stay (D). Do Not Disturb, not the ringer's silent mode: Android's
 * external silent mode turns Do Not Disturb on with whatever policy stands, and a later change
 * of Do Not Disturb sets the ringer back to normal (ZenModeHelper.onSetRingerModeExternal and
 * applyZenToRingerMode, read in the Q25's services.jar).
 */
val SILENT_POLICY = DndPolicy(
    PRIORITY_CATEGORY_ALARMS or PRIORITY_CATEGORY_MEDIA, PRIORITY_SENDERS_ANY, PRIORITY_SENDERS_ANY, 0, CONVERSATION_SENDERS_NONE,
)

/**
 * All Alerts Off, "including contact alerts" (manual p. 161): only alarms and media, no LED, no
 * preview, nothing on the ambient display nor full screen (D). The legacy SCREEN_ON and SCREEN_OFF
 * bits are left to Android, which derives them from these.
 */
val ALERTS_OFF_POLICY = DndPolicy(
    PRIORITY_CATEGORY_ALARMS or PRIORITY_CATEGORY_MEDIA, PRIORITY_SENDERS_ANY, PRIORITY_SENDERS_ANY,
    SUPPRESSED_EFFECT_FULL_SCREEN_INTENT or SUPPRESSED_EFFECT_LIGHTS or SUPPRESSED_EFFECT_PEEK or SUPPRESSED_EFFECT_AMBIENT, CONVERSATION_SENDERS_NONE,
)

fun callsAllowed(categories: Int): Boolean = categories and PRIORITY_CATEGORY_CALLS != 0

/** Whether the policy holds the LED back: what tells All Alerts Off from Silent. */
fun lightsHeld(visualEffects: Int): Boolean = visualEffects and SUPPRESSED_EFFECT_LIGHTS != 0

/**
 * The profile Android's state stands for. Priority Do Not Disturb is Phone Calls Only when calls
 * get through, All Alerts Off when the LED is held back too, Silent otherwise; Android's stricter modes (total silence, alarms only) are
 * All Alerts Off; without Do Not Disturb, the ringer mode. Android rings the same for Normal, Loud
 * and Medium: [chosen], the profile last set here, tells them apart while the ringer still rings.
 */
fun profileOf(filter: Int, ringer: Int, callsAllowed: Boolean, lightsHeld: Boolean, chosen: Profile?): Profile = when (filter) {
    INTERRUPTION_FILTER_PRIORITY -> if (callsAllowed) Profile.PHONE_ONLY else if (lightsHeld) Profile.ALERTS_OFF else Profile.SILENT
    INTERRUPTION_FILTER_NONE, INTERRUPTION_FILTER_ALARMS -> Profile.ALERTS_OFF
    else -> when (ringer) {
        RINGER_MODE_VIBRATE -> Profile.VIBRATE
        RINGER_MODE_SILENT -> Profile.SILENT
        else -> chosen.takeIf { it == Profile.LOUD || it == Profile.MEDIUM } ?: Profile.NORMAL
    }
}

sealed interface ProfileStep {
    /** Keep the user's own Do Not Disturb policy before ours replaces it. */
    data object SaveUserPolicy : ProfileStep
    data class SetPolicy(val policy: DndPolicy) : ProfileStep
    data class SetFilter(val filter: Int) : ProfileStep
    data class SetRinger(val ringer: Int) : ProfileStep
    /** The ring volume of [profile]: the one it last had, else its default (see [volumeFor]). */
    data class SetVolume(val profile: Profile) : ProfileStep
    /** Give the user's policy back and forget it. */
    data object RestoreUserPolicy : ProfileStep
}

/** The profiles that ring, each with a ring volume of its own, as on the 9900 (Volume, sim-31). */
val RINGING = listOf(Profile.NORMAL, Profile.LOUD, Profile.MEDIUM)

/**
 * Default ring volume of a ringing profile, in tenths: Normal's 7 is the 9900's (sim-31, C), Loud
 * the top and Medium the middle (D, no source for either). Used until the profile has a volume of
 * its own, which leaving it keeps.
 */
private fun defaultTenths(profile: Profile): Int = when (profile) {
    Profile.LOUD -> 10
    Profile.MEDIUM -> 5
    else -> 7
}

/** [tenths] of [max] steps, rounded, never 0: a ringing profile never reaches silent through its volume. */
fun volumeIndex(tenths: Int, max: Int): Int = (tenths * max / 10f).roundToInt().coerceAtLeast(1)

/** [index] of [max] steps in tenths, as Options shows a profile's volume (sim-31), 1 at least. */
fun tenthsOf(index: Int, max: Int): Int = (index * 10f / max).roundToInt().coerceIn(1, 10)

/** The ring volume [profile] sets, in [max] steps: the [saved] one, else its default. */
fun volumeFor(profile: Profile, saved: Int?, max: Int): Int = saved?.coerceIn(1, max) ?: volumeIndex(defaultTenths(profile), max)

/**
 * Android's calls to reach [target], in order. Do Not Disturb ends first (the filter), so that the
 * ringer is set while it is off; the user's policy is saved once before ours replaces it, and given
 * back once Do Not Disturb is off. Ours goes before the filter, so that Do Not Disturb never starts
 * under the user's policy. Phone Calls Only sets the ringer to normal before entering Do Not
 * Disturb, so that calls ring; Silent and All Alerts Off set none: in priority mode Android keeps
 * the ringer as it was and mutes the streams Do Not Disturb covers (isZenOverridingRinger).
 * The ringer is never set to silent: Android answers it with Do Not Disturb and a later change
 * brings the ringer back to normal (see [SILENT_POLICY]).
 */
fun profileSteps(target: Profile, userPolicySaved: Boolean): List<ProfileStep> = when (target) {
    Profile.PHONE_ONLY, Profile.ALERTS_OFF, Profile.SILENT -> {
        val phone = target == Profile.PHONE_ONLY
        listOfNotNull(
            ProfileStep.SaveUserPolicy.takeUnless { userPolicySaved },
            ProfileStep.SetFilter(INTERRUPTION_FILTER_ALL).takeIf { phone },
            ProfileStep.SetRinger(RINGER_MODE_NORMAL).takeIf { phone },
            ProfileStep.SetPolicy(when (target) { Profile.PHONE_ONLY -> PHONE_ONLY_POLICY; Profile.SILENT -> SILENT_POLICY; else -> ALERTS_OFF_POLICY }),
            ProfileStep.SetFilter(INTERRUPTION_FILTER_PRIORITY),
        )
    }
    else -> {
        val ringer = when (target) {
            Profile.VIBRATE -> RINGER_MODE_VIBRATE
            else -> RINGER_MODE_NORMAL
        }
        listOfNotNull(
            ProfileStep.SetFilter(INTERRUPTION_FILTER_ALL),
            ProfileStep.SetRinger(ringer),
            ProfileStep.SetVolume(target).takeIf { target in RINGING },
            ProfileStep.RestoreUserPolicy.takeIf { userPolicySaved },
        )
    }
}

/**
 * Whether the user's saved policy comes back now: Do Not Disturb is off ([filter] is "all"), ended
 * by us or by anything else. Judged on the filter, not on the profile: Android's own silent ringer
 * reads as Silent with Do Not Disturb off.
 */
fun restoresUserPolicy(filter: Int, userPolicySaved: Boolean): Boolean = userPolicySaved && filter == INTERRUPTION_FILTER_ALL
