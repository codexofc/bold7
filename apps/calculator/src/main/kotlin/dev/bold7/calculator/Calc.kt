package dev.bold7.calculator

import java.math.BigDecimal
import java.math.MathContext

// The calculator of BBOS 7.1, pure: a
// simple calculator in BigDecimal, which adds 0.1 and 0.2 and gets 0.3. No capture of its
// behaviour but the screen of sim-249 to sim-253 (C): the arithmetic below is
// D, a simple calculator chaining from left to right, with the keys the capture shows.

enum class Op(val sign: Char) {
    ADD('+'), SUB('−'), MUL('×'), DIV('÷');

    fun apply(a: BigDecimal, b: BigDecimal, mc: MathContext): BigDecimal? = when (this) {
        ADD -> a.add(b)
        SUB -> a.subtract(b)
        MUL -> a.multiply(b)
        DIV -> if (b.signum() == 0) null else a.divide(b, mc)
    }
}

sealed interface Key {
    data class Digit(val d: Int) : Key
    data object Dot : Key
    data class Op(val op: dev.bold7.calculator.Op) : Key
    data object Equals : Key
    data object Clear : Key
    /** CE: clears the number being typed, keeps the pending operation (sim-249, C). */
    data object ClearEntry : Key
    data object Back : Key
    data object Negate : Key
    data object MemClear : Key
    data object MemRecall : Key
    data object MemStore : Key
    data object MemAdd : Key
    data object MemSub : Key
    data object Reciprocal : Key
    data object Sqrt : Key
    data object Percent : Key
}

/** The key a typed character presses on the smartphone's keyboard (guide p. 362, B: "press the corresponding key"), or none. */
fun keyOf(c: Char): Key? = when (c) {
    in '0'..'9' -> Key.Digit(c - '0')
    '.', ',' -> Key.Dot
    '+' -> Key.Op(Op.ADD)
    '-' -> Key.Op(Op.SUB)
    '*', 'x', 'X' -> Key.Op(Op.MUL)
    '/' -> Key.Op(Op.DIV)
    '=' -> Key.Equals
    '%' -> Key.Percent
    'c', 'C' -> Key.Clear
    else -> null
}

/**
 * A conversion of the To Metric and From Metric menus, "in -> cm" (sim-251 to sim-253, C; the last
 * of From Metric, D). A factor, Fahrenheit's offset, or the reciprocal of miles per gallon.
 */
enum class Conversion(val imperial: String, val metric: String, val factor: BigDecimal?) {
    INCHES("in", "cm", BigDecimal("2.54")),
    FEET("ft", "m", BigDecimal("0.3048")),
    YARDS("yd", "m", BigDecimal("0.9144")),
    MILES("mi", "km", BigDecimal("1.609344")),
    POUNDS("lb", "kg", BigDecimal("0.45359237")),
    /** No factor: an offset and a ratio. */
    FAHRENHEIT("°F", "°C", null),
    US_GALLONS("US gal", "L", BigDecimal("3.785411784")),
    UK_GALLONS("UK gal", "L", BigDecimal("4.54609")),
    /** No factor: 235.214583 / mpg, its own inverse. */
    US_MPG("US mpg", "L/100 km", null);

    fun label(toMetric: Boolean) = if (toMetric) "$imperial -> $metric" else "$metric -> $imperial"

    /** The converted value, or null when it does not exist (miles per gallon of zero). */
    fun convert(v: BigDecimal, toMetric: Boolean, mc: MathContext): BigDecimal? = when {
        this == US_MPG -> if (v.signum() == 0) null else MPG.divide(v, mc)
        this == FAHRENHEIT -> if (toMetric) v.subtract(BigDecimal(32)).multiply(BigDecimal(5)).divide(BigDecimal(9), mc)
            else v.multiply(BigDecimal(9)).divide(BigDecimal(5), mc).add(BigDecimal(32))
        toMetric -> v.multiply(factor)
        else -> v.divide(factor, mc)
    }

    private companion object {
        val MPG = BigDecimal("235.214583")
    }
}

/** The calculator's state: what the display shows and what is pending. */
data class Calc(
    val display: String = "0",
    /** The left operand of the pending [op]. */
    val acc: BigDecimal? = null,
    val op: Op? = null,
    /** The next digit starts a new number: after an operator, a result, a recall. */
    val fresh: Boolean = true,
    val memory: BigDecimal = BigDecimal.ZERO,
    val error: Boolean = false,
) {
    companion object {
        const val MAX_DIGITS = 12
        private val ROUND = MathContext(MAX_DIGITS)
    }

    val hasMemory: Boolean get() = memory.signum() != 0

    /** What the screen shows: the number and its final point, "0." (sim-249, C). */
    val shown: String get() = if (error || '.' in display) display else "$display."

    /** The small line over the display: the pending operation, "12 ×". */
    val expression: String get() = if (op != null && acc != null) "${format(acc)} ${op.sign}" else ""

    /** Nothing typed, nothing pending: Escape then leaves instead of clearing. */
    val pristine: Boolean get() = display == "0" && op == null && !error

    private val value: BigDecimal get() = BigDecimal(display)

    fun press(key: Key): Calc {
        if (error && key != Key.Clear && key != Key.ClearEntry) return this
        return when (key) {
            is Key.Digit -> when {
                fresh -> copy(display = key.d.toString(), fresh = false)
                display == "0" -> copy(display = key.d.toString())
                display.count { it.isDigit() } >= MAX_DIGITS -> this
                else -> copy(display = display + key.d)
            }
            Key.Dot -> when {
                fresh -> copy(display = "0.", fresh = false)
                '.' in display -> this
                else -> copy(display = "$display.")
            }
            Key.Back -> if (fresh) this else display.dropLast(1).let { copy(display = if (it.isEmpty() || it == "-") "0" else it) }
            Key.Negate -> if (display == "0") this else copy(display = if (display.startsWith("-")) display.drop(1) else "-$display")
            is Key.Op -> {
                // After an operator and no new number, the operator is replaced; else the pending one is done first.
                val left = if (op != null && !fresh) (op.apply(acc!!, value, ROUND) ?: return failed()) else value
                if (tooBig(left)) return failed()
                copy(display = format(left), acc = left, op = key.op, fresh = true)
            }
            Key.Equals -> {
                if (op == null) return copy(fresh = true)
                val r = op.apply(acc!!, value, ROUND) ?: return failed()
                if (tooBig(r)) failed() else copy(display = format(r), acc = null, op = null, fresh = true)
            }
            Key.Clear -> Calc(memory = memory)
            Key.ClearEntry -> if (error) Calc(memory = memory) else copy(display = "0", fresh = false)
            Key.MemSub -> copy(memory = memory.subtract(value).round(ROUND), fresh = true)
            Key.Reciprocal -> if (value.signum() == 0) failed() else result(BigDecimal.ONE.divide(value, ROUND))
            Key.Sqrt -> if (value.signum() < 0) failed() else result(value.sqrt(ROUND))
            // After + or -, a percentage of the left operand (200 + 10 % = 220); else a hundredth.
            Key.Percent -> result(
                if (acc != null && (op == Op.ADD || op == Op.SUB)) acc.multiply(value).divide(BigDecimal(100), ROUND)
                else value.divide(BigDecimal(100), ROUND),
            )
            Key.MemClear -> copy(memory = BigDecimal.ZERO)
            Key.MemRecall -> copy(display = format(memory), fresh = true)
            Key.MemStore -> copy(memory = value, fresh = true)
            Key.MemAdd -> copy(memory = memory.add(value).round(ROUND), fresh = true)
        }
    }

    /** The displayed number converted; its result is a result, replaced by the next digit. */
    fun convert(c: Conversion, toMetric: Boolean): Calc {
        if (error) return this
        return result(c.convert(value, toMetric, ROUND) ?: return failed())
    }

    private fun result(v: BigDecimal): Calc = v.round(ROUND).let { if (tooBig(it)) failed() else copy(display = format(it), fresh = true) }

    private fun failed() = Calc(display = "Error", memory = memory, error = true)

    private fun tooBig(v: BigDecimal) = v.abs() >= BigDecimal.TEN.pow(MAX_DIGITS)

    private fun format(v: BigDecimal): String {
        val r = v.round(ROUND)
        return if (r.signum() == 0) "0" else r.stripTrailingZeros().toPlainString()
    }
}
