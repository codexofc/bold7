package dev.bold7.launcher.quickstep

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

// The critically damped spring every motion of the launcher that follows a finger ends on: no
// android.* here, tested on the JVM (SpringTest).

/**
 * Spring of unit mass, critically damped (no oscillation, yet one overshoot when released
 * towards its target faster than √k × the distance left), for the openings it draws.
 */
const val SPRING_STIFFNESS = 1500f

data class SpringState(val position: Float, val velocity: Float, val settled: Boolean)

/**
 * Critically damped spring of unit mass from [from] at [velocity] towards [to], [seconds] after
 * release: x(t) = to + (c1 + c2 t) e^(-wt), so it starts with the finger's own speed.
 */
fun spring(from: Float, velocity: Float, to: Float, seconds: Float, stiffness: Float = SPRING_STIFFNESS): SpringState {
    val w = sqrt(stiffness)
    val c1 = from - to
    val c2 = velocity + w * c1
    val decay = exp(-w * seconds)
    val position = to + (c1 + c2 * seconds) * decay
    val speed = (c2 - w * (c1 + c2 * seconds)) * decay
    return SpringState(position, speed, abs(position - to) < 0.5f && abs(speed) < 20f)
}
