package dev.bold7.launcher.profiles

import dev.bold7.launcher.profiles.Profile.ALERTS_OFF
import dev.bold7.launcher.profiles.Profile.LOUD
import dev.bold7.launcher.profiles.Profile.MEDIUM
import dev.bold7.launcher.profiles.Profile.NORMAL
import dev.bold7.launcher.profiles.Profile.PHONE_ONLY
import dev.bold7.launcher.profiles.Profile.SILENT
import dev.bold7.launcher.profiles.Profile.VIBRATE
import dev.bold7.launcher.profiles.ProfileStep.RestoreUserPolicy
import dev.bold7.launcher.profiles.ProfileStep.SaveUserPolicy
import dev.bold7.launcher.profiles.ProfileStep.SetFilter
import dev.bold7.launcher.profiles.ProfileStep.SetPolicy
import dev.bold7.launcher.profiles.ProfileStep.SetRinger
import dev.bold7.launcher.profiles.ProfileStep.SetVolume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilesTest {
    // AudioManager.RINGER_MODE_*, NotificationManager.INTERRUPTION_FILTER_*.
    private val silent = 0
    private val vibrate = 1
    private val normal = 2
    private val all = 1
    private val priority = 2
    private val none = 3
    private val alarms = 4

    @Test fun withoutDoNotDisturbTheRingerModeIsTheProfile() {
        assertEquals(NORMAL, profileOf(all, normal, callsAllowed = false, lightsHeld = false, chosen = null))
        assertEquals(VIBRATE, profileOf(all, vibrate, callsAllowed = true, lightsHeld = false, chosen = null))
        // Android's own silent ringer, the same label as the profile.
        assertEquals(SILENT, profileOf(all, silent, callsAllowed = true, lightsHeld = false, chosen = null))
    }

    // Loud and Medium ring like Normal for Android: only the profile chosen tells them apart.
    @Test fun aRingingPhoneKeepsTheRingingProfileChosen() {
        assertEquals(LOUD, profileOf(all, normal, callsAllowed = false, lightsHeld = false, chosen = LOUD))
        assertEquals(MEDIUM, profileOf(all, normal, callsAllowed = false, lightsHeld = false, chosen = MEDIUM))
        // The ringer moved elsewhere (Android's Settings): what Android says wins.
        assertEquals(VIBRATE, profileOf(all, vibrate, callsAllowed = false, lightsHeld = false, chosen = LOUD))
        assertEquals(NORMAL, profileOf(all, normal, callsAllowed = false, lightsHeld = false, chosen = SILENT))
        assertEquals(NORMAL, profileOf(all, normal, callsAllowed = false, lightsHeld = false, chosen = ALERTS_OFF))
    }

    // Under priority Do Not Disturb the ringer says nothing: Android keeps it as it was and mutes
    // the streams Do Not Disturb covers (isZenOverridingRinger). The policy tells the three apart.
    @Test fun priorityDoNotDisturbIsReadOnItsPolicy() {
        assertEquals(PHONE_ONLY, profileOf(priority, normal, callsAllowed = true, lightsHeld = false, chosen = LOUD))
        assertEquals(PHONE_ONLY, profileOf(priority, silent, callsAllowed = true, lightsHeld = true, chosen = null))
        assertEquals(ALERTS_OFF, profileOf(priority, silent, callsAllowed = false, lightsHeld = true, chosen = null))
        assertEquals(SILENT, profileOf(priority, normal, callsAllowed = false, lightsHeld = false, chosen = null))
    }

    @Test fun androidsStricterModesAreAllAlertsOff() {
        assertEquals(ALERTS_OFF, profileOf(none, normal, callsAllowed = true, lightsHeld = false, chosen = null))
        assertEquals(ALERTS_OFF, profileOf(alarms, vibrate, callsAllowed = true, lightsHeld = false, chosen = null))
    }

    // INTERRUPTION_FILTER_UNKNOWN (0): never taken for Do Not Disturb.
    @Test fun anUnknownFilterFallsBackOnTheRinger() {
        assertEquals(VIBRATE, profileOf(0, vibrate, callsAllowed = false, lightsHeld = false, chosen = null))
    }

    @Test fun callsAllowedReadsThePolicysCallsCategory() {
        assertTrue(callsAllowed(PHONE_ONLY_POLICY.categories))
        assertFalse(callsAllowed(ALERTS_OFF_POLICY.categories))
        assertFalse(callsAllowed(SILENT_POLICY.categories))
        // The Q25's own policy (alarms, media, messages, calls, repeat callers, conversations).
        assertTrue(callsAllowed(32 or 64 or 4 or 8 or 16 or 256))
    }

    // Phone Calls Only: every caller and repeat caller rings, no message, conversation, reminder,
    // event or system sound, nothing visual held back; alarms and media go through (D).
    @Test fun phoneOnlyLetsEveryCallThroughAndNoVisualIsSuppressed() {
        assertEquals(DndPolicy(categories = 8 or 16 or 32 or 64, callSenders = 0, messageSenders = 0, visualEffects = 0, conversationSenders = 3), PHONE_ONLY_POLICY)
    }

    // All Alerts Off: no sound, vibration, LED nor preview; alarms still ring (D).
    @Test fun allAlertsOffLetsOnlyAlarmsAndMediaThroughWithoutLightsOrPeek() {
        assertEquals(DndPolicy(categories = 32 or 64, callSenders = 0, messageSenders = 0, visualEffects = 4 or 8 or 16 or 128, conversationSenders = 3), ALERTS_OFF_POLICY)
        assertTrue(lightsHeld(ALERTS_OFF_POLICY.visualEffects))
    }

    // Silent: "flashing LED alerts only" (manual p. 161), so the same sounds as All Alerts Off and
    // nothing held back (D).
    @Test fun silentLetsOnlyAlarmsAndMediaThroughAndHoldsNothingBack() {
        assertEquals(DndPolicy(categories = 32 or 64, callSenders = 0, messageSenders = 0, visualEffects = 0, conversationSenders = 3), SILENT_POLICY)
        assertFalse(lightsHeld(SILENT_POLICY.visualEffects))
    }

    @Test fun aSavedPolicyReadsBackTheSame() {
        val q25 = DndPolicy(32 or 64 or 4 or 8 or 16 or 256, 2, 2, 157, 2)
        assertEquals(q25, DndPolicy.decode(q25.encode()))
        assertNull(DndPolicy.decode("1,2,3"))
        assertNull(DndPolicy.decode("a,b,c,d,e"))
        assertNull(DndPolicy.decode(null))
    }

    // The user's policy saved once, ours before the filter (entering Do Not Disturb with nothing
    // let through would silence the ringer), the ringer set while Do Not Disturb is off (set under
    // it, Android ends Do Not Disturb, ZenModeHelper.onSetRingerModeExternal).
    @Test fun phoneOnlyEndsDoNotDisturbRingsThenPutsItOnWithItsPolicy() {
        val steps = listOf(SetFilter(all), SetRinger(normal), SetPolicy(PHONE_ONLY_POLICY), SetFilter(priority))
        assertEquals(listOf(SaveUserPolicy) + steps, profileSteps(PHONE_ONLY, userPolicySaved = false))
        assertEquals(steps, profileSteps(PHONE_ONLY, userPolicySaved = true))
    }

    // No ringer: Do Not Disturb mutes the streams itself, and the external ringer set to silent
    // would turn Do Not Disturb on with whatever policy stands.
    @Test fun silentAndAllAlertsOffNeverTouchTheRinger() {
        assertEquals(listOf(SaveUserPolicy, SetPolicy(ALERTS_OFF_POLICY), SetFilter(priority)), profileSteps(ALERTS_OFF, userPolicySaved = false))
        assertEquals(listOf(SetPolicy(ALERTS_OFF_POLICY), SetFilter(priority)), profileSteps(ALERTS_OFF, userPolicySaved = true))
        assertEquals(listOf(SaveUserPolicy, SetPolicy(SILENT_POLICY), SetFilter(priority)), profileSteps(SILENT, userPolicySaved = false))
        assertEquals(listOf(SetPolicy(SILENT_POLICY), SetFilter(priority)), profileSteps(SILENT, userPolicySaved = true))
    }

    @Test fun ringingProfilesEndDoNotDisturbThenSetTheRingerThenGiveThePolicyBack() {
        assertEquals(listOf(SetFilter(all), SetRinger(normal), SetVolume(NORMAL), RestoreUserPolicy), profileSteps(NORMAL, userPolicySaved = true))
        assertEquals(listOf(SetFilter(all), SetRinger(vibrate)), profileSteps(VIBRATE, userPolicySaved = false))
        assertEquals(listOf(SetFilter(all), SetRinger(vibrate), RestoreUserPolicy), profileSteps(VIBRATE, userPolicySaved = true))
        assertEquals(listOf(SetFilter(all), SetRinger(normal), SetVolume(LOUD)), profileSteps(LOUD, userPolicySaved = false))
        assertEquals(listOf(SetFilter(all), SetRinger(normal), SetVolume(MEDIUM)), profileSteps(MEDIUM, userPolicySaved = false))
    }

    // Normal's 7 of 10 is read on the 9900 (sim-31); Loud and Medium are D. The Q25's ring stream
    // has 15 steps (dumpsys audio), 8 at the time.
    @Test fun aProfileWithoutVolumeOfItsOwnSetsItsDefaultInTenthsOfTheRange() {
        assertEquals(11, volumeFor(NORMAL, null, 15))
        assertEquals(15, volumeFor(LOUD, null, 15))
        assertEquals(8, volumeFor(MEDIUM, null, 15))
        assertEquals(5, volumeFor(NORMAL, null, 7))
    }

    @Test fun aProfileSetsTheVolumeItHadWhenItWasLeftWithinTheRangeAndNeverSilent() {
        assertEquals(8, volumeFor(NORMAL, 8, 15))
        assertEquals(15, volumeFor(LOUD, 40, 15))
        assertEquals(1, volumeFor(MEDIUM, 0, 15))
    }

    @Test fun tenthsScaleToAndroidsStepsWithoutEverReachingSilent() {
        assertEquals(5, volumeIndex(7, 7))
        assertEquals(7, volumeIndex(10, 7))
        assertEquals(1, volumeIndex(1, 7))
        assertEquals(1, volumeIndex(0, 7))
        assertEquals(15, volumeIndex(10, 15))
    }

    @Test fun androidsStepsReadBackInTenthsAsOptionsShowsThem() {
        assertEquals(7, tenthsOf(11, 15))
        assertEquals(10, tenthsOf(15, 15))
        assertEquals(5, tenthsOf(8, 15))
        assertEquals(1, tenthsOf(0, 15))
        // Every tenth survives the round trip through the Q25's 15 steps.
        for (t in 1..10) assertEquals(t, tenthsOf(volumeIndex(t, 15), 15))
    }

    @Test fun thePolicyComesBackAsSoonAsDoNotDisturbIsOffWhateverTurnedItOff() {
        assertTrue(restoresUserPolicy(all, userPolicySaved = true))
        assertFalse(restoresUserPolicy(priority, userPolicySaved = true))
        assertFalse(restoresUserPolicy(none, userPolicySaved = true))
        assertFalse(restoresUserPolicy(0, userPolicySaved = true))
        assertFalse(restoresUserPolicy(all, userPolicySaved = false))
    }

    // sim-28: All Alerts Off first, then Normal, Loud, Medium, Silent, Vibrate Only, Phone Calls Only.
    @Test fun theListIsSim28sOrder() {
        assertEquals(listOf(ALERTS_OFF, NORMAL, LOUD, MEDIUM, SILENT, VIBRATE, PHONE_ONLY), Profile.LIST)
        assertEquals(Profile.entries.toSet(), Profile.LIST.toSet())
    }

    // net_rim_Ribbon_Profile-icons43x43.png: Normal, Vibrate Only, Silent, Loud, Phone Calls Only,
    // All Alerts Off, a custom profile, Medium.
    @Test fun eachProfileHasItsFrameOfTheBannersStrip() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 7), listOf(NORMAL, VIBRATE, SILENT, LOUD, PHONE_ONLY, ALERTS_OFF, MEDIUM).map { it.frame })
    }

    @Test fun anIdReadsBackItsProfile() {
        for (p in Profile.entries) assertEquals(p, Profile.of(p.id))
        assertNull(Profile.of("custom"))
        assertNull(Profile.of(null))
    }

    /**
     * Android's side of the calls, as the Q25's services.jar does them (decompiled AudioService
     * .setRingerMode and ZenModeHelper: RingerModeDelegate.onSetRingerModeInternal and
     * onSetRingerModeExternal, setManualZenMode, setNotificationPolicy, applyZenToRingerMode;
     * `doNotDisturbWhenSilent` is false there). The external ringer mode goes through the delegate,
     * which ends Do Not Disturb for normal and vibrate, turns it on for a silent that changes, and
     * answers the internal ringer. A change of Do Not Disturb posts, on a handler, one deferred
     * applyZenToRingerMode, which each new post replaces (removeMessages); changes the delegate
     * makes post it without the ringer (it cancels the one waiting), a policy changed under priority
     * Do Not Disturb posts it, any other policy call cancels it. The handler runs between two
     * calls when [handlerAfter] says so: every interleaving is tried.
     */
    private class FakeAndroid(
        var zen: Int = 0,
        var internal: Int = 2,
        var external: Int = internal,
        var policy: DndPolicy = USER,
        var previous: Int? = null,
    ) {
        /** null: nothing waits; true: applyZenToRingerMode waits; false: a message that does nothing. */
        var pending: Boolean? = null
        var saved: DndPolicy? = null
        var original = policy

        fun copy() = FakeAndroid(zen, internal, external, policy, previous).also { it.pending = pending; it.saved = saved; it.original = original }

        private fun allMuted(categories: Int) = categories and (1 or 2 or 4 or 8 or 16 or 128 or 256) == 0

        fun runHandler() {
            val apply = pending ?: return
            pending = null
            if (apply) applyZenToRingerMode()
        }

        private fun setManualZen(to: Int, ringer: Boolean) {
            val changed = to != zen
            zen = to
            pending = ringer && changed
        }

        private fun applyZenToRingerMode() {
            var r = internal
            if (zen != 0) {
                if ((zen == 2 || zen == 3) && r != 0) { previous = r; r = 0 }
            } else if (r == 0) {
                r = previous ?: 2
                previous = null
            }
            setRingerInternal(r)
        }

        private fun setRingerInternal(new: Int) {
            val old = internal
            internal = new
            val change = old != new
            if (zen == 0 || (zen == 1 && !allMuted(policy.categories))) previous = new
            var out = new
            var newZen = -1
            if (new == 1 || new == 2) {
                if (change && old == 0 && (zen == 2 || zen == 3 || (zen == 1 && allMuted(policy.categories)))) newZen = 0
                else if (zen != 0) out = 0
            }
            if (newZen != -1) setManualZen(newZen, false)
            external = out
        }

        private fun setRingerExternal(new: Int) {
            val old = external
            val inner = internal
            external = new
            val out = if (new != 0) {
                if (zen != 0) setManualZen(0, false)
                new
            } else if (old != new) {
                if (zen == 0) setManualZen(1, false)
                if (inner == 1) 1 else 0
            } else {
                inner
            }
            if (out != internal) internal = out
        }

        fun call(step: ProfileStep) {
            when (step) {
                SaveUserPolicy -> saved = policy
                is SetPolicy -> {
                    val changed = step.policy != policy
                    policy = step.policy
                    pending = zen == 1 && changed
                }
                is SetFilter -> setManualZen(if (step.filter == 1) 0 else 1, true)
                is SetRinger -> setRingerExternal(step.ringer)
                // Setting the ring volume above 0 sets the internal ringer to normal (onSetStreamVolume).
                is SetVolume -> setRingerInternal(2)
                RestoreUserPolicy -> { policy = saved ?: policy; saved = null; pending = false }
            }
        }

        fun run(steps: List<ProfileStep>, handlerAfter: Int) {
            steps.forEachIndexed { i, step ->
                call(step)
                if (handlerAfter shr i and 1 == 1) runHandler()
            }
        }

        val filter get() = when (zen) { 0 -> 1; 1 -> 2; 2 -> 3; else -> 4 }

        fun profile(chosen: Profile?) = profileOf(filter, external, callsAllowed(policy.categories), lightsHeld(policy.visualEffects), chosen)

        /** What is wrong with the state reached for [to], once the handler has run; empty when nothing. */
        fun problems(to: Profile): List<String> {
            runHandler()
            val errs = mutableListOf<String>()
            if (profile(to) != to) errs += "reads ${profile(to)}"
            val mutedByZen = zen == 2 || zen == 3 || (zen == 1 && allMuted(policy.categories))
            val rings = (zen == 0 || zen == 1) && internal == 2 && !mutedByZen && (zen == 0 || policy.categories and 8 != 0)
            if ((to in RINGING || to == PHONE_ONLY) && !rings) errs += "does not ring (zen=$zen internal=$internal)"
            if (to == VIBRATE && !(zen == 0 && internal == 1)) errs += "not vibrate (zen=$zen internal=$internal)"
            if ((to == SILENT || to == ALERTS_OFF) && zen == 0) errs += "Do Not Disturb off"
            if ((to == SILENT || to == ALERTS_OFF) && rings) errs += "RINGS"
            if ((to in RINGING || to == VIBRATE) && (saved != null || policy != original)) errs += "the user's policy is not back"
            return errs
        }
    }

    private companion object {
        val USER = DndPolicy(32 or 64 or 4 or 8 or 16 or 256, 2, 2, 157, 2)
    }

    // Every profile from every other, from the phone as a user may have left it (Android's ringer
    // modes, and a Do Not Disturb of his own), whichever way the handler of applyZenToRingerMode
    // falls between our calls: the state stands for the profile asked, rings or not as it should, and
    // the user's policy is back wherever Do Not Disturb is off.
    @Test fun everyProfileIsReachedFromEveryOtherUnderEveryInterleavingOfAndroidsHandler() {
        val starts = mapOf<String, () -> FakeAndroid>(
            "android normal" to { FakeAndroid() },
            "android vibrate" to { FakeAndroid(internal = 1) },
            "android silent" to { FakeAndroid(internal = 0) },
            "his priority Do Not Disturb" to { FakeAndroid(zen = 1, internal = 2, external = 0) },
            "his priority, all muted" to { FakeAndroid(zen = 1, internal = 2, external = 0, policy = DndPolicy(32 or 64, 2, 2, 157, 2)) },
            "his total silence" to { FakeAndroid(zen = 2, internal = 0, previous = 2) },
            "his alarms only, was vibrating" to { FakeAndroid(zen = 3, internal = 0, previous = 1) },
        )
        val failures = mutableListOf<String>()
        val kinds = sortedSetOf<String>()
        var runs = 0
        for ((startName, start) in starts) for (from in listOf<Profile?>(null) + Profile.entries) for (to in Profile.entries) {
            val first = from?.let { profileSteps(it, userPolicySaved = false) }.orEmpty()
            for (m0 in 0 until (1 shl first.size)) {
                val a = start()
                a.run(first, m0)
                a.runHandler()
                val second = profileSteps(to, userPolicySaved = a.saved != null)
                for (m1 in 0 until (1 shl second.size)) {
                    val b = a.copy()
                    b.run(second, m1)
                    runs++
                    val errs = b.problems(to)
                    if (errs.isNotEmpty()) kinds += errs.map { it.substringBefore(" (") }
                    if (errs.isNotEmpty()) failures += "$startName, $from -> $to, handler after calls ${m1.toString(2)}: $errs"
                }
            }
        }
        assertTrue(runs > 10_000, "$runs runs")
        assertEquals(emptyList(), failures.take(5), "${failures.size} of $runs runs fail, kinds $kinds")
    }

    // The external silent ringer, which the first version used: it turns Do Not Disturb on with the
    // user's own policy (calls get through), and the phone rings.
    @Test fun theFakeShowsWhyTheExternalSilentRingerIsNotUsed() {
        val a = FakeAndroid()
        a.run(listOf(SetFilter(all), SetRinger(silent)), 0)
        a.runHandler()
        assertEquals(1, a.zen)
        assertEquals(USER, a.policy)
        assertEquals(PHONE_ONLY, a.profile(null))
    }

    // The format SystemUI reads from Settings.Global bold7_profile: the profile's id, written
    // only when it differs from what was last written.
    @Test fun publishesTheProfileIdOnlyWhenItChanged() {
        assertEquals(listOf("normal", "vibrate", "silent", "loud", "phoneonly", "alertsoff", "medium"), Profile.entries.map { profileToPublish(it, null) })
        assertEquals("loud", profileToPublish(LOUD, "normal"))
        assertNull(profileToPublish(LOUD, "loud"))
    }
}
