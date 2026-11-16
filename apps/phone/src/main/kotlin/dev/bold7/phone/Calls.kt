package dev.bold7.phone

// The calls as Telecom gives them to our InCallService, and the call screen of BBOS 7.1 they make,
// pure (docs/ARCHITECTURE.md §1): docs/BBOS7-VISUEL.md §26, sim-18 (incoming), sim-109 (connected), gsm 019
// (calling), the 7.1 user guide's Phone chapter (pp. 61-64, 70-71). The screen decides nothing that
// Telecom does not say: it only renders this state, and its actions go back to Telecom.

/** Telecom's call states (android.telecom.Call.STATE_*), the ones the screen tells apart. */
enum class CallState { NEW, CONNECTING, SELECT_ACCOUNT, DIALING, RINGING, ACTIVE, HOLDING, DISCONNECTING, DISCONNECTED }

/**
 * A call of a conference ([CallInfo.participants]): Telecom's child call, with what Telecom lets
 * be done to it alone, Split Call (CAPABILITY_SEPARATE_FROM_CONFERENCE) and Drop Call
 * (CAPABILITY_DISCONNECT_FROM_CONFERENCE), guide 7.1 p. 71.
 */
data class Participant(val id: Int, val number: String, val name: String, val canSplit: Boolean, val canDrop: Boolean) {
    val caller: String get() = name.ifEmpty { number }
}

/**
 * A call. [number]: empty when withheld. [name]: the contact's or the network's (CNAP), empty if
 * none. [connectedAt]: when it became active (wall clock, Telecom's connect time), 0 before.
 * [participants]: a conference's calls. [postDialWait]: the digits after a wait, which Telecom holds
 * until told (onPostDialWait), empty otherwise.
 */
data class CallInfo(
    val id: Int,
    val state: CallState,
    val number: String,
    val name: String = "",
    val connectedAt: Long = 0,
    val canHold: Boolean = false,
    val canMute: Boolean = true,
    val canMerge: Boolean = false,
    val participants: List<Participant> = emptyList(),
    val postDialWait: String = "",
) {
    /**
     * The big line of sim-18 and gsm 019: the name, else the number, else a conference's
     * participants (D); empty when withheld (the view says so).
     */
    val caller: String get() = name.ifEmpty { number }.ifEmpty { participants.joinToString(", ") { it.caller } }

    val live: Boolean get() = state != CallState.DISCONNECTED
    val ringing: Boolean get() = state == CallState.RINGING
    val offHook: Boolean get() = state in OFF_HOOK

    private companion object {
        val OFF_HOOK = setOf(CallState.CONNECTING, CallState.DIALING, CallState.ACTIVE, CallState.HOLDING)
    }
}

/** Where the call's sound goes (CallAudioState routes); WIRED is the headset. */
enum class Route { EARPIECE, WIRED, SPEAKER, BLUETOOTH }

data class Audio(
    val route: Route = Route.EARPIECE,
    val routes: Set<Route> = setOf(Route.EARPIECE, Route.SPEAKER),
    val muted: Boolean = false,
)

/** Typed DTMF tones shown under the timer: the last ones only (D, activecallscreen.pme's DTMF field is 115 px wide). */
const val TONES_SHOWN = 12

/** What the call screen shows: Telecom's calls and audio, and what the user did on the screen. */
data class CallBoard(
    val calls: List<CallInfo> = emptyList(),
    val audio: Audio = Audio(),
    val ringerSilenced: Boolean = false,
    val tones: String = "",
)

sealed interface CallEvent {
    data class CallsChanged(val calls: List<CallInfo>) : CallEvent
    data class AudioChanged(val audio: Audio) : CallEvent
    data object RingerSilenced : CallEvent
    data class Tone(val c: Char) : CallEvent
}

fun CallBoard.reduce(e: CallEvent): CallBoard = when (e) {
    is CallEvent.CallsChanged -> {
        val before = foreground(calls)?.id
        copy(
            calls = e.calls,
            ringerSilenced = ringerSilenced && e.calls.any { it.ringing },
            tones = if (foreground(e.calls)?.id == before) tones else "",
        )
    }
    is CallEvent.AudioChanged -> copy(audio = e.audio)
    CallEvent.RingerSilenced -> copy(ringerSilenced = true)
    is CallEvent.Tone -> copy(tones = (tones + e.c).takeLast(TONES_SHOWN))
}

/** The call in front when none rings: the active one, then one being placed, then a held one, then one ending. */
private fun foreground(calls: List<CallInfo>): CallInfo? =
    calls.filter { !it.ringing }.minByOrNull { FOREGROUND.indexOf(it.state).let { i -> if (i < 0) FOREGROUND.size else i } }

private val FOREGROUND = listOf(
    CallState.ACTIVE, CallState.DIALING, CallState.CONNECTING, CallState.SELECT_ACCOUNT, CallState.NEW,
    CallState.HOLDING, CallState.DISCONNECTING, CallState.DISCONNECTED,
)

/** sim-18's whole screen for a ringing call, else sim-109's and gsm 019's. */
enum class Kind { INCOMING, CALL }

/**
 * The grey line at the top left: "Calling" (gsm 019), "Connected" (sim-109), "Muted" (sim-136),
 * "On Hold" (sim-139); sim-18's title "Incoming Call"; "Disconnected" D (the 9900 goes home at once).
 */
enum class Status { INCOMING, CALLING, CONNECTED, MUTED, ON_HOLD, DISCONNECTED }

/** What the user can ask; each goes to Telecom (TelecomCalls). */
enum class Action {
    ANSWER, ANSWER_HOLD, ANSWER_DROP, IGNORE, RINGER_OFF,
    END, SPEAKER, HANDSET, BLUETOOTH, MUTE, UNMUTE, HOLD, RESUME, ADD, NEW_CALL, SWAP, JOIN, HOME,
    VOICE_MAIL, SPLIT, DROP, SWITCH,
}

/**
 * The full menu's groups, a line between two (sim-133, sim-135, C): Hold, Add Participant, Mute;
 * New Call; the audio; Home Screen; then End Call, mine (D: the 9900 ends a call with its End key
 * only, which the Q25 may not send). Swap, Join, Split Call and Drop Call join the
 * first group (D); Call Voice Mail follows New Call (sim-133).
 */
fun group(a: Action): Int = when (a) {
    Action.HOLD, Action.RESUME, Action.ADD, Action.MUTE, Action.UNMUTE, Action.SWAP, Action.JOIN, Action.SPLIT, Action.DROP -> 1
    Action.NEW_CALL, Action.VOICE_MAIL -> 2
    Action.SPEAKER, Action.HANDSET, Action.BLUETOOTH -> 3
    Action.HOME -> 4
    Action.END -> 5
    else -> 0
}

/** The bottom bar's places: sim-18 has one, sim-109 four. */
enum class Slot { RINGER_OFF, SPEAKER, MUTE, HOLD, ADD }

/**
 * A button of the bottom bar: its place, what a click does now (its image and label follow), greyed
 * or not (gsm 019, sim-139); [red]: Mute while muted, on activecall_button_mute_p.png (sim-136).
 */
data class Button(val slot: Slot, val action: Action, val enabled: Boolean, val red: Boolean = false)

/**
 * The screen of [call]; [other]: the second call (held, or the one a ringing call would hold or
 * drop). [menu]: the full menu, in order, [highlight] its entry under the focus when it opens.
 */
data class CallScreen(
    val kind: Kind,
    val call: CallInfo,
    val other: CallInfo?,
    val status: Status,
    val buttons: List<Button>,
    val menu: List<Action>,
    val highlight: Int = 0,
    val postDial: String? = null,
) {
    /** Split Call's and Drop Call's choice: the participants Telecom lets go (guide p. 71, "Click a contact"). */
    fun participants(a: Action): List<Participant> = when (a) {
        Action.SPLIT -> call.participants.filter { it.canSplit }
        Action.DROP -> call.participants.filter { it.canDrop }
        else -> emptyList()
    }
}

/** The screen, or null when no call is left (the activity closes). [locked]: nothing that opens another app (CONTRIBUTING.md). */
fun CallBoard.screen(locked: Boolean): CallScreen? {
    calls.firstOrNull { it.ringing }?.let { ringing ->
        val other = foreground(calls.filter { it !== ringing && it.live })
        val answers = when {
            other?.state != CallState.ACTIVE -> listOf(Action.ANSWER)
            other.canHold -> listOf(Action.ANSWER_HOLD, Action.ANSWER_DROP)
            else -> listOf(Action.ANSWER_DROP)
        }
        // Answer and Ignore only (sim-130, C); the guide's two answers during a call (p. 62, B).
        val menu = answers + Action.IGNORE
        return CallScreen(Kind.INCOMING, ringing, other, Status.INCOMING, listOf(Button(Slot.RINGER_OFF, Action.RINGER_OFF, !ringerSilenced)), menu)
    }
    val call = foreground(calls) ?: return null
    val other = calls.firstOrNull { it !== call && it.live }
    val status = when (call.state) {
        CallState.ACTIVE -> if (audio.muted) Status.MUTED else Status.CONNECTED
        CallState.HOLDING -> Status.ON_HOLD
        CallState.DISCONNECTING, CallState.DISCONNECTED -> Status.DISCONNECTED
        else -> Status.CALLING
    }
    if (status == Status.DISCONNECTED) {
        val off = listOf(Slot.SPEAKER to Action.SPEAKER, Slot.MUTE to Action.MUTE, Slot.HOLD to Action.HOLD, Slot.ADD to Action.ADD)
        return CallScreen(Kind.CALL, call, other, status, off.map { Button(it.first, it.second, false) }, emptyList())
    }
    val talking = status == Status.CONNECTED || status == Status.MUTED
    val speaker = if (audio.route == Route.SPEAKER) Action.HANDSET else Action.SPEAKER
    val mute = if (audio.muted) Action.UNMUTE else Action.MUTE
    val hold = if (status == Status.ON_HOLD) Action.RESUME else Action.HOLD
    val canHold = call.canHold && (talking || status == Status.ON_HOLD)
    // Mute greyed while held (sim-139); Add Participant still there (C).
    val canMute = call.canMute && status != Status.ON_HOLD
    val canAdd = (talking || status == Status.ON_HOLD) && other == null && !locked
    val buttons = listOf(
        Button(Slot.SPEAKER, speaker, true),
        Button(Slot.MUTE, mute, canMute, red = audio.muted && canMute),
        Button(Slot.HOLD, hold, canHold),
        Button(Slot.ADD, Action.ADD, canAdd),
    )
    // sim-133 to sim-140, in their order; what Bold7 does not do yet is left out.
    val menu = buildList {
        add(Action.SWITCH) // first row of OS 7's menus, alone in its group (sim-133, C)
        if (canHold) add(hold)
        if (canAdd) add(Action.ADD)
        if (canMute) add(mute)
        val swaps = other != null && (talking || status == Status.ON_HOLD) && other.state in setOf(CallState.HOLDING, CallState.ACTIVE)
        if (swaps) add(Action.SWAP)
        if (other != null && call.canMerge) add(Action.JOIN)
        if (talking && call.participants.any { it.canSplit }) add(Action.SPLIT)
        if (talking && call.participants.any { it.canDrop }) add(Action.DROP)
        if (canAdd) add(Action.NEW_CALL)
        if (canAdd) add(Action.VOICE_MAIL)
        add(speaker)
        // On Bluetooth, "Activate Handset" (guide p. 63) also brings the call back to the phone.
        if (audio.route == Route.BLUETOOTH) add(Action.HANDSET)
        if (Route.BLUETOOTH in audio.routes && audio.route != Route.BLUETOOTH) add(Action.BLUETOOTH)
        add(Action.HOME)
        add(Action.END)
    }
    // The focus opens on Mute (sim-134), on Resume while held (sim-140).
    val highlight = menu.indexOfFirst { it == Action.MUTE || it == Action.UNMUTE || it == Action.RESUME }.coerceAtLeast(1)
    // Add Wait's digits, asked for while the call is up (D: the 9900's prompt has no capture).
    val postDial = call.postDialWait.takeIf { it.isNotEmpty() && status != Status.DISCONNECTED }
    return CallScreen(Kind.CALL, call, other, status, buttons, menu, highlight, postDial)
}
/** What a key of the 9900 does on the call screen. */
sealed interface KeyOutcome {
    data class Do(val action: Action) : KeyOutcome
    /** The full menu, to choose between the guide's two answers. */
    data object Menu : KeyOutcome
    data object None : KeyOutcome
}

/** The Send (Call) key: answers (guide p. 61); during a call, a second call's choice (p. 62). */
fun callKey(s: CallScreen): KeyOutcome = when {
    s.kind != Kind.INCOMING -> KeyOutcome.None
    Action.ANSWER in s.menu -> KeyOutcome.Do(Action.ANSWER)
    else -> KeyOutcome.Menu
}

/** The End key: ignores a ringing call, ends the one shown (guide p. 61). */
fun endKey(s: CallScreen): KeyOutcome = when {
    s.kind == Kind.INCOMING -> KeyOutcome.Do(Action.IGNORE)
    Action.END in s.menu -> KeyOutcome.Do(Action.END)
    else -> KeyOutcome.None
}

/** A click of the trackpad: on a ringing call, Ringer Off (sim-131, C); during a call, nothing (sim-135, C). */
fun clickAction(s: CallScreen): Action? = s.buttons.singleOrNull()?.takeIf { s.kind == Kind.INCOMING && it.enabled }?.action

/**
 * The frame of net_rim_Phone_Receiver-icons58x33.png (A, read by eye) above the status: none on
 * the handset, the speaker (sim-138), Bluetooth, the headset; the red microphone with it when
 * muted (sim-136).
 */
fun receiverFrame(a: Audio): Int = when (a.route) {
    Route.EARPIECE -> 0
    Route.SPEAKER -> 2
    Route.BLUETOOTH -> 5
    Route.WIRED -> 7
} + if (a.muted) 1 else 0

/** The keyboard's digits are DTMF tones only for a connected call, none ringing (D). */
fun sendsTones(s: CallScreen) = s.kind == Kind.CALL && s.call.state == CallState.ACTIVE

/** Screen off at the ear: a call off the hook, its sound in the earpiece (AOSP's InCallUI does the same, D). */
fun wantsProximity(b: CallBoard) = b.audio.route == Route.EARPIECE && b.calls.any { it.offHook }

/** The frame, 0 to 10, of net_rim_Phone_Volume-icons110x6.png (A) for a call volume [v] of [min] to [max]; -1 if unknown. */
fun volumeFrame(v: Int, min: Int, max: Int): Int =
    if (max <= min) -1 else Math.round(10f * (v.coerceIn(min, max) - min) / (max - min))

/** "0:06" (sim-109): minutes and seconds, hours in front past one (D). */
fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 3600) "%d:%02d".format(s / 60, s % 60) else "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
