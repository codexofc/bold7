package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Q25KeysTest {
    private fun read(name: String) = javaClass.getResource("/q25/$name")!!.readText()

    /** Q25_keyboard.kl: scan code to key name (not the FUNCTION keys, Fn and a letter). */
    private val layout = read("Q25_keyboard.kl").lines()
        .mapNotNull { Regex("""^key\s+(\d+)\s+(\w+)\s*$""").find(it) }
        .associate { it.groupValues[1].toInt() to it.groupValues[2] }

    /** Q25_keyboard.kcm: key name to its behaviours ("base", "shift", "alt"…) and their character. */
    private val chars: Map<String, Map<String, Char>> = buildMap {
        var key: String? = null
        var rules = mutableMapOf<String, Char>()
        for (line in read("Q25_keyboard.kcm").lines()) {
            Regex("""^key\s+(\w+)\s*\{""").find(line)?.let {
                key = it.groupValues[1]
                rules = mutableMapOf()
                put(key!!, rules)
            }
            val rule = Regex("""^\s*([a-z+, ]+):\s*'(\\?.)'""").find(line) ?: continue
            val c = rule.groupValues[2].let { if (it == "\\n") '\n' else it.last() }
            rule.groupValues[1].split(',').forEach { rules[it.trim()] = c }
        }
    }

    @Test fun `every key of the table is the Q25's own map`() {
        var checked = 0
        for ((scan, name) in layout) {
            val key = Q25Keys.of(scan) ?: continue
            if (scan == 41) continue // the $ key, no character in the map (below)
            val kcm = chars.getValue(name)
            assertEquals(Q25Keys.Key(kcm.getValue("base"), kcm.getValue("shift"), kcm.getValue("alt")), key, "scan $scan ($name)")
            checked++
        }
        assertEquals(27, checked, "26 letters and 0")
    }

    @Test fun `every character key of the map is in the table`() {
        for ((scan, name) in layout) {
            val base = chars[name]?.get("base") ?: continue
            if (base.isLetterOrDigit()) assertNotNull(Q25Keys.of(scan), "scan $scan ($name)")
        }
    }

    @Test fun `what each layer types`() {
        val a = Q25Keys.of(30)!!
        assertEquals('a', a.base)
        assertEquals('A', a.shift)
        assertEquals('*', a.alt)
        assertEquals('1', Q25Keys.of(17)!!.alt, "Alt+W")
        assertEquals('.', Q25Keys.of(50)!!.alt, "Alt+M")
        assertEquals(',', Q25Keys.of(49)!!.alt, "Alt+N")
        assertEquals('m', Q25Keys.of(50)!!.base, "M, not the comma of AZERTY")
        assertEquals('q', Q25Keys.of(16)!!.base, "Q, not the A of AZERTY")
        assertEquals(Q25Keys.Key('0', '0', '0'), Q25Keys.of(11))
        assertEquals(Q25Keys.Key('$', '$', '$'), Q25Keys.of(41), "KEY_GRAVE, the \$ key")
    }

    @Test fun `the Alt layer is the Sym grid's second page`() {
        for ((r, row) in Symbols.ROWS.withIndex()) for ((c, letter) in row.withIndex()) {
            val scan = layout.entries.single { it.value == letter.toString() }.key
            assertEquals(Symbols.PAGES[1][r][c], Q25Keys.of(scan)!!.alt, "Alt+$letter")
        }
    }

    @Test fun `Sym types the glyph over the key's letter`() {
        assertEquals('[', Symbols.of(Q25Keys.of(16)!!.base, 0), "Sym, Q")
        assertEquals('€', Symbols.of(Q25Keys.of(45)!!.base, 0), "Sym, X")
    }

    @Test fun `other keys are not in the table`() {
        for (scan in listOf(0, 14, 28, 42, 54, 56, 57, 100, 103, 158)) assertNull(Q25Keys.of(scan), "scan $scan")
    }
}
