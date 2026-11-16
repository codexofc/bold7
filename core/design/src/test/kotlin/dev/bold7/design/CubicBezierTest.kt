package dev.bold7.design

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CubicBezierTest {

    private val curves = listOf(Curves.QuadraticOut, Curves.QuadraticInOut, Curves.Linear)
    private val samples = (0..1000).map { it / 1000f }

    @Test
    fun everyCurveStartsAtZeroAndEndsAtOne() {
        for (curve in curves) {
            assertEquals(0f, curve.transform(0f), "$curve at 0")
            assertEquals(1f, curve.transform(1f), "$curve at 1")
        }
    }

    @Test
    fun inputOutsideTheUnitIntervalIsClamped() {
        assertEquals(0f, Curves.QuadraticOut.transform(-0.5f))
        assertEquals(1f, Curves.QuadraticOut.transform(1.5f))
    }

    @Test
    fun linearIsIdentity() {
        for (x in samples) assertEquals(x, Curves.Linear.transform(x), 1e-5f)
    }

    @Test
    fun easingCurvesWithoutOvershootAreMonotonicAndStayInRange() {
        for (curve in listOf(Curves.QuadraticOut, Curves.QuadraticInOut, Curves.Linear)) {
            val values = samples.map(curve::transform)
            values.zipWithNext().forEach { (a, b) -> assertTrue(b >= a, "$curve decreases: $a then $b") }
            assertTrue(values.all { it in 0f..1f }, "$curve leaves [0, 1]")
        }
    }

    // The Béziers approximate Penner's equations, which theme.xml of BBOS 7 (EASINGCURVE_QUADRATIC_*) names.
    @Test
    fun beziersStayCloseToThePennerEquationsTheyApproximate() {
        val exact = mapOf<CubicBezier, (Float) -> Float>(
            Curves.QuadraticOut to { t -> t * (2f - t) },
            Curves.QuadraticInOut to { t -> if (t < 0.5f) 2f * t * t else 1f - 2f * (1f - t).pow(2) },
        )
        exact.forEach { (curve, f) ->
            val gap = samples.maxOf { abs(curve.transform(it) - f(it)) }
            assertTrue(gap < 0.05f, "$curve is $gap away from its equation")
        }
    }
}
