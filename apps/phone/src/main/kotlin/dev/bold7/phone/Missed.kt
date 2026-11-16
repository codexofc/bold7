package dev.bold7.phone

/**
 * The missed calls' dialog of OS 7, "You have 2 new missed calls." with View and Close (sim-19,
 * C): the calls nobody answered since it last closed ([count]), and whether it must come now
 * ([show]). It comes when a missed call ends and no other call is left; a call missed during
 * another waits for that one's end, so that nothing covers the call screen (D). Pure: the
 * InCallService feeds it Telecom's removals, the dialog its closing.
 */
data class Missed(val count: Int = 0, val waiting: Boolean = false, val show: Boolean = false)

sealed interface MissedEvent {
    /** A call left Telecom; [missed]: its DisconnectCause was MISSED; [left]: calls still there. */
    data class Removed(val missed: Boolean, val left: Int) : MissedEvent

    /** View or Close: the calls are told, the count starts again. */
    data object Closed : MissedEvent
}

fun Missed.reduce(e: MissedEvent): Missed = when (e) {
    is MissedEvent.Removed -> {
        val pending = waiting || e.missed
        copy(count = count + if (e.missed) 1 else 0, waiting = pending && e.left > 0, show = pending && e.left == 0)
    }
    MissedEvent.Closed -> Missed()
}
