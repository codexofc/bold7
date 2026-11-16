package dev.bold7.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallsTest {
    private fun call(id: Int, state: CallState, number: String = "555$id", canHold: Boolean = true, canMerge: Boolean = false, at: Long = 0) =
        CallInfo(id, state, number, connectedAt = at, canHold = canHold, canMerge = canMerge)

    private fun board(vararg calls: CallInfo, audio: Audio = Audio()) = CallBoard().reduce(CallEvent.CallsChanged(calls.toList())).reduce(CallEvent.AudioChanged(audio))

    @Test fun noLiveCallShowsNothing() {
        assertNull(CallBoard().screen(locked = false))
    }

    @Test fun aRingingCallIsTheIncomingScreenWithRingerOff() {
        val s = board(call(1, CallState.RINGING)).screen(locked = false)!!
        assertEquals(Kind.INCOMING, s.kind)
        assertEquals(Status.INCOMING, s.status)
        assertEquals(listOf(Slot.RINGER_OFF), s.buttons.map { it.slot })
        // sim-130: Answer and Ignore, Answer under the focus.
        assertEquals(listOf(Action.ANSWER, Action.IGNORE), s.menu)
        assertEquals(0, s.highlight)
        // sim-131: the trackpad's click is Ringer Off.
        assertEquals(Action.RINGER_OFF, clickAction(s))
    }

    @Test fun ringerOffGreysOnceUsedAndComesBackWithTheNextCall() {
        val silenced = board(call(1, CallState.RINGING)).reduce(CallEvent.RingerSilenced)
        assertFalse(silenced.screen(false)!!.buttons.single().enabled)
        assertNull(clickAction(silenced.screen(false)!!))
        val next = silenced.reduce(CallEvent.CallsChanged(emptyList())).reduce(CallEvent.CallsChanged(listOf(call(2, CallState.RINGING))))
        assertTrue(next.screen(false)!!.buttons.single().enabled)
    }

    @Test fun aSecondCallRingingDuringACallOffersTheGuidesTwoAnswers() {
        val s = board(call(1, CallState.ACTIVE), call(2, CallState.RINGING)).screen(false)!!
        assertEquals(Kind.INCOMING, s.kind)
        assertEquals(2, s.call.id)
        assertEquals(1, s.other?.id)
        assertEquals(listOf(Action.ANSWER_HOLD, Action.ANSWER_DROP, Action.IGNORE), s.menu)
        // A first call that cannot be held: only dropping it.
        val noHold = board(call(1, CallState.ACTIVE, canHold = false), call(2, CallState.RINGING)).screen(false)!!
        assertEquals(listOf(Action.ANSWER_DROP, Action.IGNORE), noHold.menu)
    }

    @Test fun anOutgoingCallIsCallingWithHoldAndAddParticipantGreyed() {
        val s = board(call(1, CallState.DIALING)).screen(false)!!
        assertEquals(Kind.CALL, s.kind)
        assertEquals(Status.CALLING, s.status)
        assertEquals(listOf(Slot.SPEAKER, Slot.MUTE, Slot.HOLD, Slot.ADD), s.buttons.map { it.slot })
        assertEquals(listOf(true, true, false, false), s.buttons.map { it.enabled })
        assertEquals(listOf(Action.SWITCH, Action.MUTE, Action.SPEAKER, Action.HOME, Action.END), s.menu)
        assertEquals(Status.CALLING, board(call(1, CallState.CONNECTING)).screen(false)!!.status)
    }

    @Test fun anActiveCallIsConnectedWithItsFourButtons() {
        val s = board(call(1, CallState.ACTIVE, at = 1000)).screen(false)!!
        assertEquals(Status.CONNECTED, s.status)
        assertEquals(listOf(Action.SPEAKER, Action.MUTE, Action.HOLD, Action.ADD), s.buttons.map { it.action })
        assertTrue(s.buttons.all { it.enabled })
        // sim-133 to sim-135, what Bold7 does; the focus on Mute (sim-134); the click does nothing.
        assertEquals(listOf(Action.SWITCH, Action.HOLD, Action.ADD, Action.MUTE, Action.NEW_CALL, Action.VOICE_MAIL, Action.SPEAKER, Action.HOME, Action.END), s.menu)
        assertEquals(3, s.highlight)
        assertEquals(listOf(0, 1, 1, 1, 2, 2, 3, 4, 5), s.menu.map(::group))
        assertNull(clickAction(s))
    }

    @Test fun locked_addParticipantIsGreyedAndLeavesTheMenu() {
        val s = board(call(1, CallState.ACTIVE)).screen(locked = true)!!
        assertFalse(s.buttons.single { it.slot == Slot.ADD }.enabled)
        assertFalse(Action.ADD in s.menu)
        assertFalse(Action.NEW_CALL in s.menu)
        assertFalse(Action.VOICE_MAIL in s.menu)
    }

    @Test fun theTogglesShowWhatTheyWouldDo() {
        // sim-136 to sim-138: Muted, the red Mute Off, Turn Mute Off; Handset on the speaker.
        val on = board(call(1, CallState.ACTIVE), audio = Audio(Route.SPEAKER, muted = true)).screen(false)!!
        assertEquals(Status.MUTED, on.status)
        assertEquals(listOf(Action.HANDSET, Action.UNMUTE, Action.HOLD, Action.ADD), on.buttons.map { it.action })
        assertEquals(listOf(false, true, false, false), on.buttons.map { it.red })
        assertEquals(listOf(Action.SWITCH, Action.HOLD, Action.ADD, Action.UNMUTE, Action.NEW_CALL, Action.VOICE_MAIL, Action.HANDSET, Action.HOME, Action.END), on.menu)
        // sim-139, sim-140: On Hold, Resume, Mute greyed and gone from the menu, the focus on Resume.
        val held = board(call(1, CallState.HOLDING)).screen(false)!!
        assertEquals(Status.ON_HOLD, held.status)
        assertEquals(listOf(Action.SPEAKER, Action.MUTE, Action.RESUME, Action.ADD), held.buttons.map { it.action })
        assertEquals(listOf(true, false, true, true), held.buttons.map { it.enabled })
        assertEquals(listOf(Action.SWITCH, Action.RESUME, Action.ADD, Action.NEW_CALL, Action.VOICE_MAIL, Action.SPEAKER, Action.HOME, Action.END), held.menu)
        assertEquals(1, held.highlight)
    }

    @Test fun bluetoothIsOfferedWhenAvailableAndNotInUse() {
        val bt = setOf(Route.EARPIECE, Route.SPEAKER, Route.BLUETOOTH)
        assertTrue(Action.BLUETOOTH in board(call(1, CallState.ACTIVE), audio = Audio(Route.EARPIECE, bt)).screen(false)!!.menu)
        assertFalse(Action.BLUETOOTH in board(call(1, CallState.ACTIVE), audio = Audio(Route.BLUETOOTH, bt)).screen(false)!!.menu)
        // On Bluetooth, Activate Handset brings the call back to the phone.
        assertTrue(Action.HANDSET in board(call(1, CallState.ACTIVE), audio = Audio(Route.BLUETOOTH, bt)).screen(false)!!.menu)
    }

    @Test fun twoCallsShowTheActiveOneAndOfferSwapAndJoin() {
        val s = board(call(1, CallState.HOLDING), call(2, CallState.ACTIVE, canMerge = true)).screen(false)!!
        assertEquals(2, s.call.id)
        assertEquals(1, s.other?.id)
        assertEquals(listOf(Action.SWITCH, Action.HOLD, Action.MUTE, Action.SWAP, Action.JOIN, Action.SPEAKER, Action.HOME, Action.END), s.menu)
        // A second call: Add Participant is greyed (D).
        assertFalse(s.buttons.single { it.slot == Slot.ADD }.enabled)
        assertFalse(Action.JOIN in board(call(1, CallState.HOLDING), call(2, CallState.ACTIVE)).screen(false)!!.menu)
    }

    @Test fun aDisconnectedCallStaysShownUntilTelecomRemovesIt() {
        val s = board(call(1, CallState.DISCONNECTED)).screen(false)!!
        assertEquals(Status.DISCONNECTED, s.status)
        assertTrue(s.buttons.none { it.enabled })
        assertEquals(emptyList(), s.menu)
        // A held call left after the other ended is the one shown.
        assertEquals(1, board(call(1, CallState.HOLDING), call(2, CallState.DISCONNECTED)).screen(false)!!.call.id)
    }

    @Test fun theCallAndEndKeysOfTheGuide() {
        val ringing = board(call(1, CallState.RINGING)).screen(false)!!
        assertEquals(KeyOutcome.Do(Action.ANSWER), callKey(ringing))
        assertEquals(KeyOutcome.Do(Action.IGNORE), endKey(ringing))
        val waiting = board(call(1, CallState.ACTIVE), call(2, CallState.RINGING)).screen(false)!!
        assertEquals(KeyOutcome.Menu, callKey(waiting))
        assertEquals(KeyOutcome.Do(Action.IGNORE), endKey(waiting))
        val active = board(call(1, CallState.ACTIVE)).screen(false)!!
        assertEquals(KeyOutcome.None, callKey(active))
        assertEquals(KeyOutcome.Do(Action.END), endKey(active))
        assertEquals(KeyOutcome.None, endKey(board(call(1, CallState.DISCONNECTED)).screen(false)!!))
    }

    @Test fun keypadTonesGoOnlyToAConnectedCall() {
        assertTrue(sendsTones(board(call(1, CallState.ACTIVE)).screen(false)!!))
        assertFalse(sendsTones(board(call(1, CallState.DIALING)).screen(false)!!))
        assertFalse(sendsTones(board(call(1, CallState.ACTIVE), call(2, CallState.RINGING)).screen(false)!!))
        val typed = board(call(1, CallState.ACTIVE)).reduce(CallEvent.Tone('1')).reduce(CallEvent.Tone('#'))
        assertEquals("1#", typed.tones)
        // A new call starts with none.
        assertEquals("", typed.reduce(CallEvent.CallsChanged(listOf(call(2, CallState.ACTIVE)))).tones)
        assertEquals("1#", typed.reduce(CallEvent.CallsChanged(listOf(call(1, CallState.ACTIVE, at = 5)))).tones)
        assertEquals(TONES_SHOWN, (1..30).fold(typed) { b, _ -> b.reduce(CallEvent.Tone('9')) }.tones.length)
    }

    @Test fun theProximitySensorTurnsTheScreenOffOnlyAtTheEar() {
        assertTrue(wantsProximity(board(call(1, CallState.ACTIVE))))
        assertTrue(wantsProximity(board(call(1, CallState.DIALING))))
        assertTrue(wantsProximity(board(call(1, CallState.ACTIVE), call(2, CallState.RINGING))))
        assertFalse(wantsProximity(board(call(1, CallState.RINGING))))
        assertFalse(wantsProximity(board(call(1, CallState.ACTIVE), audio = Audio(Route.SPEAKER))))
        assertFalse(wantsProximity(board(call(1, CallState.ACTIVE), audio = Audio(Route.BLUETOOTH))))
        assertFalse(wantsProximity(board(call(1, CallState.ACTIVE), audio = Audio(Route.WIRED))))
        assertFalse(wantsProximity(board(call(1, CallState.DISCONNECTED))))
    }

    @Test fun theTimerOfSim109() {
        assertEquals("0:06", elapsed(6_999))
        assertEquals("12:00", elapsed(720_000))
        assertEquals("1:02:03", elapsed(3_723_000))
        assertEquals("0:00", elapsed(-5))
    }

    @Test fun theReceiverIconOverTheStatus() {
        assertEquals(0, receiverFrame(Audio()))
        assertEquals(1, receiverFrame(Audio(muted = true)))
        assertEquals(2, receiverFrame(Audio(Route.SPEAKER)))
        assertEquals(6, receiverFrame(Audio(Route.BLUETOOTH, muted = true)))
        assertEquals(7, receiverFrame(Audio(Route.WIRED)))
    }

    @Test fun theVolumeBarHasElevenFrames() {
        assertEquals(0, volumeFrame(1, 1, 7))
        assertEquals(10, volumeFrame(7, 1, 7))
        assertEquals(5, volumeFrame(4, 1, 7))
        assertEquals(10, volumeFrame(9, 1, 7))
        assertEquals(-1, volumeFrame(3, 3, 3))
    }

    @Test fun theCallerLine() {
        assertEquals("5551234", call(1, CallState.RINGING, number = "5551234").caller)
        assertEquals("Dexter", CallInfo(1, CallState.RINGING, "123456", name = "Dexter").caller)
        assertEquals("", CallInfo(1, CallState.RINGING, "").caller)
    }

    @Test fun aWaitAsksBeforeTheRestIsSent() {
        // Add Wait's digits: Telecom waits (onPostDialWait), the screen asks (D) until it is told.
        val s = board(call(1, CallState.ACTIVE).copy(postDialWait = "1234")).screen(false)!!
        assertEquals("1234", s.postDial)
        assertNull(board(call(1, CallState.ACTIVE)).screen(false)!!.postDial)
        assertNull(board(call(1, CallState.ACTIVE).copy(postDialWait = "1234"), call(2, CallState.RINGING)).screen(false)!!.postDial)
    }

    @Test fun aConferenceOffersSplitAndDropForItsParticipants() {
        val a = Participant(5, "5550001", "Ann", canSplit = true, canDrop = true)
        val b = Participant(6, "5550002", "", canSplit = false, canDrop = true)
        val conf = CallInfo(1, CallState.ACTIVE, "", canHold = true, participants = listOf(a, b))
        val s = board(conf).screen(false)!!
        // Guide 7.1 p. 71: Split Call, Drop Call, in the first group (D for their place).
        assertEquals(listOf(Action.SWITCH, Action.HOLD, Action.ADD, Action.MUTE, Action.SPLIT, Action.DROP), s.menu.take(6))
        assertEquals(listOf(a), s.participants(Action.SPLIT))
        assertEquals(listOf(a, b), s.participants(Action.DROP))
        // The conference's line: its participants (D).
        assertEquals("Ann, 5550002", conf.caller)
        // Neither while it is on hold, nor for a call without participants.
        assertFalse(Action.SPLIT in board(conf.copy(state = CallState.HOLDING)).screen(false)!!.menu)
        assertFalse(Action.DROP in board(call(1, CallState.ACTIVE)).screen(false)!!.menu)
    }
}
