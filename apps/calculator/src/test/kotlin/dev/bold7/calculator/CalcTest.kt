package dev.bold7.calculator

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalcTest {
    private fun press(vararg keys: Key): Calc = keys.fold(Calc()) { c, k -> c.press(k) }
    private fun d(vararg digits: Int): Array<Key> = digits.map { Key.Digit(it) }.toTypedArray()

    @Test fun startsOnZero() = assertEquals("0", Calc().display)

    @Test fun digitsAreTypedAndALeadingZeroIsDropped() {
        assertEquals("7", press(*d(0, 7)).display)
        assertEquals("105", press(*d(1, 0, 5)).display)
    }

    @Test fun theEntryStopsAtTwelveDigits() {
        assertEquals("123456789012", press(*d(1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4)).display)
    }

    @Test fun theDotIsTypedOnceAndStartsAtZero() {
        assertEquals("0.", press(Key.Dot).display)
        assertEquals("1.5", press(Key.Digit(1), Key.Dot, Key.Digit(5), Key.Dot).display)
    }

    @Test fun backspaceErasesTheLastCharacterThenFallsToZero() {
        assertEquals("1", press(*d(1, 2), Key.Back).display)
        assertEquals("0", press(Key.Digit(5), Key.Back).display)
        assertEquals("0", press(Key.Negate, Key.Digit(5), Key.Back).display)
    }

    @Test fun negateFlipsTheSignButNotZero() {
        assertEquals("-5", press(Key.Digit(5), Key.Negate).display)
        assertEquals("5", press(Key.Digit(5), Key.Negate, Key.Negate).display)
        assertEquals("0", press(Key.Negate).display)
    }

    @Test fun anOperationIsDoneByEquals() {
        assertEquals("5", press(Key.Digit(2), Key.Op(Op.ADD), Key.Digit(3), Key.Equals).display)
        assertEquals("6", press(Key.Digit(2), Key.Op(Op.MUL), Key.Digit(3), Key.Equals).display)
        assertEquals("-1", press(Key.Digit(2), Key.Op(Op.SUB), Key.Digit(3), Key.Equals).display)
        assertEquals("2.5", press(Key.Digit(5), Key.Op(Op.DIV), Key.Digit(2), Key.Equals).display)
    }

    @Test fun operationsChainFromLeftToRightLikeASimpleCalculator() {
        // 2 + 3 x 4 = 20 (D: the 9900's calculator is not shown to be a scientific one).
        assertEquals("20", press(Key.Digit(2), Key.Op(Op.ADD), Key.Digit(3), Key.Op(Op.MUL), Key.Digit(4), Key.Equals).display)
    }

    @Test fun anotherOperatorReplacesThePendingOne() {
        assertEquals("6", press(Key.Digit(2), Key.Op(Op.ADD), Key.Op(Op.MUL), Key.Digit(3), Key.Equals).display)
    }

    @Test fun theResultCanBeTheFirstOperandOfTheNext() {
        assertEquals("8", press(Key.Digit(2), Key.Op(Op.ADD), Key.Digit(3), Key.Equals, Key.Op(Op.ADD), Key.Digit(3), Key.Equals).display)
    }

    @Test fun decimalsAreExactNotBinary() {
        assertEquals("0.3", press(Key.Digit(0), Key.Dot, Key.Digit(1), Key.Op(Op.ADD), Key.Digit(0), Key.Dot, Key.Digit(2), Key.Equals).display)
    }

    @Test fun aQuotientIsRoundedToTwelveDigits() {
        assertEquals("0.333333333333", press(Key.Digit(1), Key.Op(Op.DIV), Key.Digit(3), Key.Equals).display)
    }

    @Test fun dividingByZeroIsAnErrorUntilCleared() {
        val e = press(Key.Digit(1), Key.Op(Op.DIV), Key.Digit(0), Key.Equals)
        assertTrue(e.error)
        assertEquals("Error", e.display)
        assertEquals("Error", e.press(Key.Digit(5)).display)
        assertEquals("0", e.press(Key.Clear).display)
    }

    @Test fun aResultTooBigForTheDisplayIsAnError() {
        val c = press(*d(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9), Key.Op(Op.MUL), *d(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9), Key.Equals)
        assertTrue(c.error)
    }

    @Test fun theLineAboveShowsTheOperationInProgress() {
        assertEquals("", Calc().expression)
        assertEquals("12 ×", press(*d(1, 2), Key.Op(Op.MUL)).expression)
        assertEquals("12 ×", press(*d(1, 2), Key.Op(Op.MUL), Key.Digit(3)).expression)
    }

    @Test fun clearForgetsTheOperationButNotTheMemory() {
        val c = press(Key.Digit(4), Key.MemStore, Key.Digit(2), Key.Op(Op.ADD), Key.Clear)
        assertEquals("0", c.display)
        assertEquals("", c.expression)
        assertEquals(BigDecimal(4), c.memory)
    }

    @Test fun memoryStoresAddsRecallsAndClears() {
        var c = press(Key.Digit(4), Key.MemStore)
        assertTrue(c.hasMemory)
        c = c.press(Key.Digit(3)).press(Key.MemAdd)
        assertEquals(BigDecimal(7), c.memory)
        c = c.press(Key.Digit(9)).press(Key.MemRecall)
        assertEquals("7", c.display)
        // A recalled number is replaced by the next digit, as a result is.
        assertEquals("2", c.press(Key.Digit(2)).display)
        c = c.press(Key.MemClear)
        assertFalse(c.hasMemory)
    }

    @Test fun aRecalledNumberCanBeAnOperand() {
        val c = press(Key.Digit(4), Key.MemStore, Key.Digit(1), Key.Op(Op.ADD), Key.MemRecall, Key.Equals)
        assertEquals("5", c.display)
    }

    @Test fun typedCharactersMapToKeys() {
        assertEquals(Key.Digit(7), keyOf('7'))
        assertEquals(Key.Dot, keyOf('.'))
        assertEquals(Key.Dot, keyOf(','))
        assertEquals(Key.Op(Op.MUL), keyOf('*'))
        assertEquals(Key.Op(Op.MUL), keyOf('x'))
        assertEquals(Key.Op(Op.DIV), keyOf('/'))
        assertEquals(Key.Op(Op.SUB), keyOf('-'))
        assertEquals(Key.Equals, keyOf('='))
        assertEquals(Key.Clear, keyOf('c'))
        assertEquals(null, keyOf('q'))
    }

    @Test fun conversionsGoToAndFromMetric() {
        val inch = Conversion.INCHES
        assertEquals("12.7", press(*d(5)).convert(inch, toMetric = true).display)
        assertEquals("5", press(*d(1, 2), Key.Dot, Key.Digit(7)).convert(inch, toMetric = false).display)
        assertEquals("100", press(*d(2, 1, 2)).convert(Conversion.FAHRENHEIT, toMetric = true).display)
        assertEquals("212", press(*d(1, 0, 0)).convert(Conversion.FAHRENHEIT, toMetric = false).display)
        assertEquals("-40", press(Key.Digit(4), Key.Digit(0), Key.Negate).convert(Conversion.FAHRENHEIT, toMetric = true).display)
    }

    @Test fun theNineConversionsAreTheCapturesLabels() {
        // sim-251 to sim-253 (C); the last two of From Metric are D.
        assertEquals(
            listOf("in -> cm", "ft -> m", "yd -> m", "mi -> km", "lb -> kg", "°F -> °C", "US gal -> L", "UK gal -> L", "US mpg -> L/100 km"),
            Conversion.entries.map { it.label(true) },
        )
        assertEquals(
            listOf("cm -> in", "m -> ft", "m -> yd", "km -> mi", "kg -> lb", "°C -> °F", "L -> US gal", "L -> UK gal", "L/100 km -> US mpg"),
            Conversion.entries.map { it.label(false) },
        )
    }

    @Test fun gallonsAndMilesPerGallonConvert() {
        assertEquals("3.785411784", press(Key.Digit(1)).convert(Conversion.US_GALLONS, true).display)
        assertEquals("4.54609", press(Key.Digit(1)).convert(Conversion.UK_GALLONS, true).display)
        assertEquals("9.4", press(*d(2, 5)).convert(Conversion.US_MPG, true).display.take(3))
        assertTrue(press(Key.Digit(0)).convert(Conversion.US_MPG, true).error)
    }

    @Test fun aConversionIsTheNextEntrysStartLikeAResult() {
        assertEquals("3", press(*d(5)).convert(Conversion.INCHES, true).press(Key.Digit(3)).display)
    }

    @Test fun theScreenShowsTheFinalPointOfTheCapture() {
        assertEquals("0.", Calc().shown)
        assertEquals("5.", press(Key.Digit(5)).shown)
        assertEquals("1.5", press(Key.Digit(1), Key.Dot, Key.Digit(5)).shown)
        assertEquals("Error", press(Key.Digit(1), Key.Op(Op.DIV), Key.Digit(0), Key.Equals).shown)
    }

    @Test fun clearEntryErasesTheNumberButKeepsTheOperation() {
        val c = press(Key.Digit(2), Key.Op(Op.ADD), Key.Digit(9), Key.ClearEntry)
        assertEquals("0", c.display)
        assertEquals("2 +", c.expression)
        assertEquals("5", c.press(Key.Digit(3)).press(Key.Equals).display)
        assertEquals("0", press(Key.Digit(1), Key.Op(Op.DIV), Key.Digit(0), Key.Equals, Key.ClearEntry).display)
    }

    @Test fun memorySubtractsToo() {
        val c = press(Key.Digit(9), Key.MemStore, Key.Digit(4), Key.MemSub)
        assertEquals(BigDecimal(5), c.memory)
    }

    @Test fun reciprocalAndSquareRoot() {
        assertEquals("0.25", press(Key.Digit(4), Key.Reciprocal).display)
        assertEquals("0.333333333333", press(Key.Digit(3), Key.Reciprocal).display)
        assertTrue(press(Key.Digit(0), Key.Reciprocal).error)
        assertEquals("3", press(Key.Digit(9), Key.Sqrt).display)
        assertEquals("1.41421356237", press(Key.Digit(2), Key.Sqrt).display)
        assertTrue(press(Key.Digit(4), Key.Negate, Key.Sqrt).error)
    }

    @Test fun percentIsAHundredthOrAShareOfTheLeftOperand() {
        assertEquals("0.05", press(Key.Digit(5), Key.Percent).display)
        assertEquals("220", press(*d(2, 0, 0), Key.Op(Op.ADD), *d(1, 0), Key.Percent, Key.Equals).display)
        assertEquals("180", press(*d(2, 0, 0), Key.Op(Op.SUB), *d(1, 0), Key.Percent, Key.Equals).display)
        assertEquals("20", press(*d(2, 0, 0), Key.Op(Op.MUL), *d(1, 0), Key.Percent, Key.Equals).display)
        assertEquals(Key.Percent, keyOf('%'))
    }
}
