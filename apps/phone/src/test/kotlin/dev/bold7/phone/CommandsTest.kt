package dev.bold7.phone

import kotlin.test.Test
import kotlin.test.assertEquals

/** What each action asks of Telecom, through a fake that only writes the commands down. */
class CommandsTest {
    private class FakeTelecom : Telecom {
        val sent = mutableListOf<Command>()
        override fun run(command: Command) {
            sent += command
        }
    }

    private fun screen(vararg calls: CallInfo, audio: Audio = Audio()) =
        CallBoard().reduce(CallEvent.CallsChanged(calls.toList())).reduce(CallEvent.AudioChanged(audio)).screen(locked = false)!!

    private fun sent(action: Action, s: CallScreen) = FakeTelecom().also { perform(action, s, it) }.sent

    private val ringing = CallInfo(2, CallState.RINGING, "5551234")
    private val active = CallInfo(1, CallState.ACTIVE, "5559876", canHold = true)

    @Test fun answeringAndIgnoring() {
        val alone = screen(ringing)
        assertEquals<List<Command>>(listOf(Command.Answer(2)), sent(Action.ANSWER, alone))
        assertEquals<List<Command>>(listOf(Command.Reject(2)), sent(Action.IGNORE, alone))
        assertEquals<List<Command>>(listOf(Command.SilenceRinger), sent(Action.RINGER_OFF, alone))
    }

    @Test fun aSecondCallHoldsOrDropsTheFirst() {
        val waiting = screen(active, ringing)
        // Telecom holds the active call itself when one is answered (CallsManager.answerCall).
        assertEquals<List<Command>>(listOf(Command.Answer(2)), sent(Action.ANSWER_HOLD, waiting))
        assertEquals<List<Command>>(listOf(Command.Disconnect(1), Command.Answer(2)), sent(Action.ANSWER_DROP, waiting))
    }

    @Test fun duringACall() {
        val s = screen(active)
        assertEquals<List<Command>>(listOf(Command.Disconnect(1)), sent(Action.END, s))
        assertEquals<List<Command>>(listOf(Command.Hold(1)), sent(Action.HOLD, s))
        assertEquals<List<Command>>(listOf(Command.Unhold(1)), sent(Action.RESUME, screen(active.copy(state = CallState.HOLDING))))
        assertEquals<List<Command>>(listOf(Command.Mute(true)), sent(Action.MUTE, s))
        assertEquals<List<Command>>(listOf(Command.Mute(false)), sent(Action.UNMUTE, s))
        assertEquals<List<Command>>(listOf(Command.AudioRoute(Route.SPEAKER)), sent(Action.SPEAKER, s))
        assertEquals<List<Command>>(listOf(Command.AudioRoute(Route.EARPIECE)), sent(Action.HANDSET, s))
        assertEquals<List<Command>>(listOf(Command.AudioRoute(Route.BLUETOOTH)), sent(Action.BLUETOOTH, s))
        // Add Participant opens the Phone screen: nothing for Telecom yet.
        assertEquals<List<Command>>(emptyList(), sent(Action.ADD, s))
    }

    @Test fun twoCalls() {
        val held = CallInfo(3, CallState.HOLDING, "5550000", canHold = true)
        val s = screen(held, active)
        // Telecom holds the active call itself when the held one comes back (CallsManager.unholdCall).
        assertEquals<List<Command>>(listOf(Command.Unhold(3)), sent(Action.SWAP, s))
        assertEquals<List<Command>>(listOf(Command.Conference(1, 3)), sent(Action.JOIN, s))
    }

    @Test fun aConferencesParticipantAndAWait() {
        val p = Participant(5, "5550001", "", canSplit = true, canDrop = true)
        assertEquals(Command.Split(5), participantCommand(Action.SPLIT, p))
        assertEquals(Command.Disconnect(5), participantCommand(Action.DROP, p))
        assertEquals(null, participantCommand(Action.END, p))
        // Call Voice Mail places a call (the screens): nothing for Telecom from the call screen.
        assertEquals<List<Command>>(emptyList(), sent(Action.VOICE_MAIL, screen(active)))
    }
}
