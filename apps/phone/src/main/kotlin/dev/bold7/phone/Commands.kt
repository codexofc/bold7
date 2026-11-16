package dev.bold7.phone

/** What the call screen asks of Telecom. Calls are named by [CallInfo.id]. */
sealed interface Command {
    data class Answer(val id: Int) : Command
    data class Reject(val id: Int) : Command
    data class Disconnect(val id: Int) : Command
    data class Hold(val id: Int) : Command
    data class Unhold(val id: Int) : Command
    data class Conference(val id: Int, val with: Int) : Command
    data class Mute(val on: Boolean) : Command
    data class AudioRoute(val route: Route) : Command
    data object SilenceRinger : Command
    data class PlayTone(val id: Int, val c: Char) : Command
    data class StopTone(val id: Int) : Command
    /** A participant out of its conference, on its own (Split Call). */
    data class Split(val id: Int) : Command
    /** Add Wait's digits sent ([go]) or dropped. */
    data class PostDial(val id: Int, val go: Boolean) : Command
}

/** Telecom, as our InCallService reaches it (TelecomCalls); a fake in the tests. */
fun interface Telecom {
    fun run(command: Command)
}

/** Sends [action] on [s] to Telecom. Add Participant, New Call and Home Screen send nothing: the screens do them. */
fun perform(action: Action, s: CallScreen, telecom: Telecom) {
    val id = s.call.id
    val commands = when (action) {
        // Telecom holds an active call itself when another is answered (CallsManager.answerCall).
        Action.ANSWER, Action.ANSWER_HOLD -> listOf(Command.Answer(id))
        Action.ANSWER_DROP -> listOfNotNull(s.other?.let { Command.Disconnect(it.id) }, Command.Answer(id))
        Action.IGNORE -> listOf(Command.Reject(id))
        Action.RINGER_OFF -> listOf(Command.SilenceRinger)
        Action.END -> listOf(Command.Disconnect(id))
        Action.HOLD -> listOf(Command.Hold(id))
        Action.RESUME -> listOf(Command.Unhold(id))
        // The held one back; Telecom holds the active one itself (CallsManager.unholdCall).
        Action.SWAP -> listOfNotNull(listOf(s.call, s.other).firstOrNull { it?.state == CallState.HOLDING }?.let { Command.Unhold(it.id) })
        Action.JOIN -> listOfNotNull(s.other?.let { Command.Conference(id, it.id) })
        Action.MUTE -> listOf(Command.Mute(true))
        Action.UNMUTE -> listOf(Command.Mute(false))
        Action.SPEAKER -> listOf(Command.AudioRoute(Route.SPEAKER))
        Action.HANDSET -> listOf(Command.AudioRoute(Route.EARPIECE))
        Action.BLUETOOTH -> listOf(Command.AudioRoute(Route.BLUETOOTH))
        // Split Call and Drop Call act on a participant (participantCommand); Call Voice Mail places a call.
        Action.ADD, Action.NEW_CALL, Action.HOME, Action.VOICE_MAIL, Action.SPLIT, Action.DROP, Action.SWITCH -> emptyList()
    }
    commands.forEach(telecom::run)
}

/** Split Call and Drop Call on the participant chosen (guide p. 71): Telecom's child call split off or ended. */
fun participantCommand(action: Action, p: Participant): Command? = when (action) {
    Action.SPLIT -> Command.Split(p.id)
    Action.DROP -> Command.Disconnect(p.id)
    else -> null
}
