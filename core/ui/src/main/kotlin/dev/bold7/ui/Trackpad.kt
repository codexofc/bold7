package dev.bold7.ui

import dev.bold7.design.Dimens
import kotlin.math.abs
import kotlin.math.sign

// The trackpad's moves, pure: no android.*, tested on the JVM. The launcher's keys/Focus.kt
// keeps the home's own focus rules.

enum class Dir { UP, DOWN, LEFT, RIGHT }

/**
 * Travel of the trackpad as a relative pointer, in its own counts, cut into focus steps of
 * [TRACKPAD_STEP] one axis at a time: a step on one axis forgets the other's travel, and
 * turning back on an axis starts it again.
 */
data class Travel(val x: Float = 0f, val y: Float = 0f) {
    fun moved(dx: Float, dy: Float): Pair<Travel, List<Dir>> {
        var x = if (sign(dx) == -sign(this.x)) dx else this.x + dx
        var y = if (sign(dy) == -sign(this.y)) dy else this.y + dy
        val steps = mutableListOf<Dir>()
        while (abs(x) >= TRACKPAD_STEP) {
            steps += if (x > 0) Dir.RIGHT else Dir.LEFT
            x -= sign(x) * TRACKPAD_STEP
            y = 0f
        }
        while (abs(y) >= TRACKPAD_STEP) {
            steps += if (y > 0) Dir.DOWN else Dir.UP
            y -= sign(y) * TRACKPAD_STEP
            x = 0f
        }
        return Travel(x, y) to steps
    }
}

/** Pointer counts per focus step, eyeballed: to tune on the Q25. */
const val TRACKPAD_STEP = Dimens.TRACKPAD_STEP
