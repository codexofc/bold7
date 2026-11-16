package dev.bold7.phone

// OS 7's speed dial, pure (guide 7.1 p. 65-66, B): a letter key held on the Phone screen calls the
// number assigned to it; the Speed Dial Numbers list assigns, edits, moves and deletes them. Kept
// by the app alone (SpeedDialStore), never in Android's providers.

/** The key held for the voice mail: W, which types 1 on the 9900 and in the Q25's kcm (guide p. 64, "Press and hold 1"). */
const val VOICE_MAIL_KEY = 'w'

/** A speed dial's number, and the contact's name it was picked with (empty for a number of the log without one). */
data class SpeedNumber(val number: String, val name: String)

/** The keys assigned, by their lowercase letter. */
data class SpeedDial(val keys: Map<Char, SpeedNumber> = emptyMap()) {
    operator fun get(key: Char): SpeedNumber? = keys[key.lowercaseChar()]

    /** [key] takes [n]; the voice mail's key and what is not a letter are refused (unchanged). */
    fun assign(key: Char, n: SpeedNumber): SpeedDial {
        val k = key.lowercaseChar()
        return if (assignable(k)) SpeedDial(keys + (k to n)) else this
    }

    fun delete(key: Char) = SpeedDial(keys - key.lowercaseChar())

    /** [from]'s number to [to], if [to] is free (guide p. 66, Move). */
    fun move(from: Char, to: Char): SpeedDial {
        val n = this[from] ?: return this
        return if (free(to)) delete(from).assign(to, n) else this
    }

    fun free(key: Char) = assignable(key.lowercaseChar()) && this[key] == null

    /** One line a key: the letter, the number, the name, apart by tabs (tabs and new lines in them become spaces). */
    fun encode(): String = keys.entries.sortedBy { it.key }.joinToString("\n") { (k, n) -> "$k\t${clean(n.number)}\t${clean(n.name)}" }

    /** Every letter in order, the list of Speed Dial Numbers (D: the 9900's list has no capture). */
    fun rows(): List<SpeedRow> = ('a'..'z').map { SpeedRow(it, keys[it], voiceMail = it == VOICE_MAIL_KEY) }

    companion object {
        /** [encode]'s text back; a line that does not read is skipped. */
        fun decode(text: String): SpeedDial = text.lineSequence().fold(SpeedDial()) { d, line ->
            val parts = line.split('\t')
            val key = parts[0].singleOrNull()
            if (parts.size == 3 && key != null && parts[1].isNotEmpty()) d.assign(key, SpeedNumber(parts[1], parts[2])) else d
        }

        private fun assignable(k: Char) = k in 'a'..'z' && k != VOICE_MAIL_KEY
        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
    }
}

/** A row of the list: the key, its number if assigned, or the voice mail's key. */
data class SpeedRow(val key: Char, val number: SpeedNumber?, val voiceMail: Boolean = false)

/** What the next click on the list does with a number: add one (Add Speed Dial of the Phone screen) or move one (Move). */
sealed interface Pending {
    data class Add(val number: SpeedNumber) : Pending
    data class Move(val from: Char) : Pending
}

sealed interface SpeedClick {
    /** Assigned or moved: the list to keep. */
    data class Changed(val dial: SpeedDial) : SpeedClick
    /** A free key: Select Contact, then its number (guide p. 65). */
    data class Choose(val key: Char) : SpeedClick
    data object Menu : SpeedClick
    data object None : SpeedClick
}

/** A click on [key]'s row; moving onto itself puts it back (D). */
fun SpeedDial.click(key: Char, pending: Pending?): SpeedClick = when (pending) {
    is Pending.Add -> if (free(key)) SpeedClick.Changed(assign(key, pending.number)) else SpeedClick.None
    is Pending.Move -> when {
        key.lowercaseChar() == pending.from.lowercaseChar() -> SpeedClick.Changed(this)
        free(key) -> SpeedClick.Changed(move(pending.from, key))
        else -> SpeedClick.None
    }
    null -> if (free(key)) SpeedClick.Choose(key.lowercaseChar()) else SpeedClick.Menu
}

/** The list's menu: the guide's Edit, Move, Delete (p. 66, B) after Call (D); a free key's Add Speed Dial, the voice mail's Call (D). */
enum class SpeedAct { CALL, EDIT, MOVE, DELETE, ASSIGN }

fun speedMenu(row: SpeedRow): List<SpeedAct> = when {
    row.voiceMail -> listOf(SpeedAct.CALL)
    row.number == null -> listOf(SpeedAct.ASSIGN)
    else -> listOf(SpeedAct.CALL, SpeedAct.EDIT, SpeedAct.MOVE, SpeedAct.DELETE)
}
