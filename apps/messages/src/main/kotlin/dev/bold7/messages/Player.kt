package dev.bold7.messages

// The media player's state, pure. Guide 7.1 p. 139, B: "If the media controls
// don't appear at the bottom of the screen, click the trackpad. To play a media file, click the
// play icon. To pause ... To stop ... To move to the next media file ... to the previous media file
// or the beginning of the media file that's currently playing ... To fast-forward or rewind a
// file, click the progress bar. On the trackpad, slide your finger to the right or left. Click the
// progress bar again." The guide has no capture of it: the focus order, the step of a slide and
// the controls' hiding are mine (D).

/** The controls in focus order: the four buttons of the bar, then the progress bar above it. */
enum class Control { PREVIOUS, PLAY_PAUSE, STOP, NEXT, PROGRESS }

enum class PlayerKey { CLICK, LEFT, RIGHT, UP, DOWN, BACK }

/** What a key asks of the screen besides the new state. */
sealed interface PlayerEffect {
    data class Seek(val ms: Int) : PlayerEffect
    data object Close : PlayerEffect
}

/** One file, so Next is dimmed and never takes the focus (D). */
data class PlayerState(
    val playing: Boolean = false,
    val position: Int = 0,
    val duration: Int = 0,
    val shown: Boolean = true,
    val focus: Control = Control.PLAY_PAUSE,
    val seeking: Boolean = false,
) {
    /** The keys, as the trackpad sends them; a hidden bar only comes back, on a click (guide) or any key (D). */
    fun key(k: PlayerKey): Pair<PlayerState, PlayerEffect?> {
        if (k == PlayerKey.BACK) return if (seeking) copy(seeking = false) to null else this to PlayerEffect.Close
        if (!shown) return copy(shown = true) to null
        if (seeking) return when (k) {
            PlayerKey.LEFT -> seek(-SLIDE_MS)
            PlayerKey.RIGHT -> seek(SLIDE_MS)
            else -> copy(seeking = false) to null
        }
        return when (k) {
            PlayerKey.CLICK -> activate()
            PlayerKey.LEFT -> copy(focus = if (focus == Control.PREVIOUS || focus == Control.PROGRESS) focus else Control.entries[focus.ordinal - 1]) to null
            PlayerKey.RIGHT -> copy(focus = if (focus == Control.STOP || focus == Control.PROGRESS) focus else Control.entries[focus.ordinal + 1]) to null
            PlayerKey.UP -> copy(focus = Control.PROGRESS) to null
            PlayerKey.DOWN -> if (focus == Control.PROGRESS) copy(focus = Control.PLAY_PAUSE) to null else this to null
            PlayerKey.BACK -> this to null
        }
    }

    private fun seek(by: Int): Pair<PlayerState, PlayerEffect?> {
        val to = (position + by).coerceIn(0, duration.coerceAtLeast(0))
        return copy(position = to) to PlayerEffect.Seek(to)
    }

    private fun activate(): Pair<PlayerState, PlayerEffect?> = when (focus) {
        Control.PLAY_PAUSE -> copy(playing = !playing) to null
        // Previous: the beginning of the file (the only one there is).
        Control.PREVIOUS -> copy(position = 0) to PlayerEffect.Seek(0)
        Control.STOP -> copy(playing = false, position = 0, shown = true) to PlayerEffect.Seek(0)
        Control.PROGRESS -> copy(seeking = true) to null
        Control.NEXT -> this to null
    }

    fun prepared(durationMs: Int) = copy(duration = durationMs.coerceAtLeast(0), playing = true)

    /** The player's clock, read only while the bar shows and the file plays. */
    fun tick(positionMs: Int) = copy(position = positionMs.coerceIn(0, duration.coerceAtLeast(positionMs)))

    /** The end: stopped at the start, the bar back. */
    fun completed() = copy(playing = false, position = 0, shown = true)

    /** The bar goes after a few seconds of play with no key (D); never while the progress bar is being slid. */
    fun idle() = if (playing && !seeking) copy(shown = false) else this

    /** 0..1, the filled part of the progress bar. */
    val fraction: Float get() = if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)

    companion object {
        /** A slide of the trackpad: ten seconds (D). */
        const val SLIDE_MS = 10_000
        /** The bar's time without a key before it hides (D). */
        const val HIDE_MS = 4_000L
    }
}

/** m:ss, h:mm:ss from an hour. */
fun timeText(ms: Int): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A bubble opens the player for a video or a sound, the system's app for anything else (a card, an appointment). */
fun plays(type: String) = type.lowercase().let { it.startsWith("video/") || it.startsWith("audio/") }
