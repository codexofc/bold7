package dev.bold7.saisie

/** The field being typed in, as Typing reaches it (an InputConnection on the device). */
interface Editor {
    /** Up to [max] characters before the cursor (the selection's start); null when the app does not answer. */
    fun before(max: Int): String?
    /** Up to [max] characters after the cursor (the selection's end); null when the app does not answer. */
    fun after(max: Int): String?
    /** Writes [text] at the cursor, in place of the composing text if there is one. */
    fun commit(text: String)
    /**
     * Deletes [count] characters before the cursor and writes [text] there, in one edit; never while
     * composing. Its first [unknown] characters are a word the lists do not know, underlined (sim-98a).
     */
    fun replace(count: Int, text: String, unknown: Int = 0)
    /** Hands a key to the app's field (Delete, Enter, a key that must follow the text typed). */
    fun send(key: Any)
    /** Hands a key down to the app's field, then its up. */
    fun tap(key: Any)
    /** Predictive: [word] as the composing text, in place of the one there is; [unknown] underlined. */
    fun compose(word: String, unknown: Boolean)
    /** Ends the composing text, left as it is. */
    fun finish()
    /** Predictive: the list under the word, [focus] in blue; empty, no list. */
    fun suggest(items: List<Suggestion>, focus: Int)
    /** Selects from [anchor], the end that stays, to [focus], the end that moves; equal, the cursor there. */
    fun select(anchor: Int, focus: Int)
    /** Cut, copy or paste, done by the app with its own rules (a password field copies nothing). */
    fun clip(action: Clip)
    /** The app's own state of [mod], latched by keys it never saw, cleared. */
    fun unlatch(mod: Mod)
}

/** What the service hands to the [Typist], in the order the keys came. */
sealed interface Input
/**
 * A field starts; [before] is the text before the cursor if the app gave it, [cursor] its place;
 * [predictive] the style; [currency] what a key that types $ types (Options' Currency Key).
 */
data class Start(val field: Field, val before: String?, val cursor: Int?, val predictive: Boolean = false, val currency: Char = '$') : Input
/** A character key (kcm of the Q25: [base], [shifted], [alt]), [repeat] > 0 while held. */
data class Press(val base: Char, val shifted: Char, val alt: Char, val shiftHeld: Boolean, val altHeld: Boolean, val repeat: Int) : Input
/** The character key held is released. */
data object Release : Input
data class Space(val repeat: Int) : Input
/** Backspace pressed (or repeated): [key] is the event to hand to the app. */
data class Delete(val key: Any) : Input
/** The trackpad, left (-1) or right (+1), while a character key is held. */
data class Accent(val step: Int) : Input
enum class Mod { SHIFT_LEFT, SHIFT_RIGHT, ALT }
data class Modifier(val mod: Mod, val down: Boolean, val altHeld: Boolean) : Input
/** Any other key: [key] to hand to the app (null: it went by itself); [changes]: the text may have changed. */
data class Foreign(val key: Any?, val changes: Boolean) : Input
/** The selection the app reports. */
data class Selection(val start: Int, val end: Int) : Input
/** A symbol chosen in the Sym list. */
data class Typed(val text: String) : Input
/**
 * Predictive: the trackpad left (-1) or right (+1), or Enter (0); [key] for the app when there is no
 * list. [space]: Enter takes the word with a space (sim-95c), the trackpad's click without (sim-97b).
 */
data class Pick(val step: Int, val key: Any, val space: Boolean = true) : Input
/** Predictive: the word [index] of the list touched. */
data class Choose(val index: Int) : Input
/** Predictive: Back or Escape on the list. */
data object Dismiss : Input
/**
 * Shift held and the trackpad (guide 7.1 p. 37 and 213, B): the selection's moving end one
 * character left (-1) or right (+1) in [dx], one line up or down in [dy]; [key] for the app.
 */
data class Extend(val dx: Int, val dy: Int, val key: Any) : Input
enum class Clip { CUT, COPY, PASTE }
/**
 * Shift and Backspace cut, Alt and the trackpad's click copy, Shift and the click paste (guide 7.1
 * p. 37 and 38, B); [key] for the app when there is nothing to cut (Backspace) or copy (the click).
 */
data class Clipboard(val action: Clip, val key: Any) : Input

/**
 * OS 7's Direct typing (guide 7.1 p. 213-219 and "Shortcuts: Typing with the physical keyboard", B;
 * sim-98, C): word substitution at Space and its undo by two Backspaces, capitals at the start of a
 * sentence, Space twice for a period, a held letter for its capital, a held letter and the trackpad
 * for its accents, Alt and Shift (held or pressed before the key), CAPS lock (Alt and right Shift),
 * NUM lock (Alt and left Shift), Space in an address field for @ then a period.
 *
 *
 * Predictive (sim-93 to sim-97, C) in a TEXT field that takes substitution: a word started by a
 * letter is the composing text, white on blue (sim-94a), and a list of [predict]'s words floats
 * under it; the trackpad moves its focus, Enter takes the word in focus with a space (sim-95c),
 * Space keeps what is typed (sim-95a) and substitutes as Direct does (sim-96b). Only what is typed
 * is ever in the field: the proposed end that OS 7 writes after it in the field (sim-94a) is left
 * out (D), so that an app that sends, saves or loses its focus never keeps a word not typed.
 *
 * Word Learning (sim-92a): each word ended by Space (punctuation before it included) or taken from
 * the list goes to [learn], in a TEXT field that takes substitution, unless it was substituted or
 * its whole token (from the last blank) is more than the word: an address, a number, a path. What
 * [learn] keeps is the service's business (TypingStore).
 *
 * Selection (guide 7.1 p. 37, 38 and 213, B), only where Typing may read the field (TEXT and
 * EMAIL): Shift and the trackpad move the selection's moving end by a character, by setSelection
 * from where the app last said it was; by a line, the key goes to the app, which alone knows its
 * lines, and the selection is unknown until it reports it. Cut, Copy and Paste are the app's
 * own (performContextMenuAction). Nothing of the selection or the clipboard is kept.
 *
 * Runs on one thread, keys in order. What it knows of the text before the cursor is [tail]: kept
 * up to date by its own edits (the composing word included), dropped when anything else may have
 * changed the text, read again from the [Editor] only when a decision needs it, and never in a
 * PLAIN field.
 */
class Typist(
    private val lexicons: () -> List<Lexicon> = { emptyList() },
    private val learn: (String) -> Unit = {},
    /** The value of a substitution's macro (Substitutions.MACROS), null for none. */
    private val macro: (Char) -> String? = { null },
    private val substitutions: () -> Substitutions,
) {
    private var field = Field(Kind.PASS)
    private var currency = '$'
    private var tail: String? = null
    private var complete = false // tail reaches the start of the text
    private var cursor: Int? = null
    /** A selection (anchor, focus) the app reported or Typing set; null when none or unknown (then [cursor]). */
    private var selected: Pair<Int, Int>? = null
    /** Selections (anchor, focus; a cursor is both) our own edits went through, not yet reported by the app. */
    private val ours = ArrayDeque<Pair<Int, Int>>()

    private var held: Held? = null
    private class Held(val pressed: Char, var shown: Char, var step: Int = 0)

    private var undo: Undo? = null
    private class Undo(val original: String, val replacement: String, var spaceGone: Boolean = false)
    private var keepWord: String? = null // a word given back by the undo, not substituted again
    private var lastWasSpace = false

    private var predictive = false
    /** Predictive: the word being typed, the composing text. */
    private var word: String? = null
    private var items: List<Suggestion> = emptyList()
    private var focus = 0
    val composing get() = word != null
    val listing get() = items.isNotEmpty()

    private var shiftDown = false
    private var shiftAlone = false
    private var oneShotShift = false
    private var altDown = false
    private var altAlone = false
    private var oneShotAlt = false
    private var capsLock = false
    private var numLock = false

    fun handle(input: Input, editor: Editor) {
        when (input) {
            is Start -> start(input, editor)
            is Press -> press(input, editor)
            Release -> held = null
            is Space -> space(input, editor)
            is Delete -> delete(input, editor)
            is Accent -> accent(input, editor)
            is Modifier -> modifier(input, editor)
            is Foreign -> foreign(input, editor)
            is Selection -> selection(input, editor)
            is Typed -> {
                otherKey()
                held = null
                undo = null
                lastWasSpace = false
                settle(editor)
                commit(editor, input.text)
            }
            is Pick -> pick(input, editor)
            is Choose -> if (word != null && input.index in items.indices) take(editor, items[input.index].text + " ")
            Dismiss -> settle(editor)
            is Extend -> extend(input, editor)
            is Clipboard -> clipboard(input, editor)
        }
    }

    private val selecting get() = field.kind == Kind.TEXT || field.kind == Kind.EMAIL

    private fun extend(e: Extend, editor: Editor) {
        otherKey()
        val (a, f) = selected ?: cursor?.let { it to it } ?: (null to null)
        if (!selecting || a == null || f == null || e.dy != 0) {
            // The app moves it (its own Shift held), and says where by onUpdateSelection.
            foreign(Foreign(null, changes = true), editor)
            editor.tap(e.key)
            return
        }
        settle(editor)
        held = null
        undo = null
        lastWasSpace = false
        // The character crossed: outside the selection when it grows, read for the text's ends and
        // a pair of surrogates; inside when it shrinks, one step (known limit: half an emoji at the
        // edge of a shrinking selection; the selected text read whole would say).
        val step = when {
            e.dx > 0 && f >= a -> editor.after(2)?.let { if (it.isEmpty()) return else if (it.length == 2 && it[0].isHighSurrogate() && it[1].isLowSurrogate()) 2 else 1 }
            e.dx < 0 && f <= a -> editor.before(2)?.let { if (it.isEmpty()) return else if (it.length == 2 && it[0].isHighSurrogate() && it[1].isLowSurrogate()) 2 else 1 }
            else -> 1
        } ?: return
        val to = f + e.dx * step
        forget()
        editor.select(a, to)
        ours.addLast(a to to)
        selected = if (to == a) null else a to to
        cursor = if (to == a) a else null
    }

    private fun clipboard(c: Clipboard, editor: Editor) {
        otherKey()
        if (!selecting || c.action != Clip.PASTE && selected == null) {
            // Nothing to cut or copy (or a field Typing does not read): the key as it is without them.
            if (c.action == Clip.CUT) return delete(Delete(c.key), editor)
            foreign(Foreign(null, changes = true), editor)
            editor.tap(c.key)
            return
        }
        settle(editor)
        editor.clip(c.action)
        held = null
        undo = null
        lastWasSpace = false
        if (c.action == Clip.COPY) return
        // Cut and Paste change the text: what the app reports next is read again.
        forget()
        cursor = null
        selected = null
    }

    private fun start(s: Start, editor: Editor) {
        field = s.field
        currency = s.currency
        predictive = s.predictive && field.kind == Kind.TEXT && field.substitute
        word = null
        hide(editor)
        forget()
        cursor = s.cursor
        selected = null
        if (s.before != null && field.kind != Kind.PLAIN) {
            tail = s.before.takeLast(TAIL)
            complete = s.before.length < TAIL
        }
        undo = null
        keepWord = null
        lastWasSpace = false
        held = null
        oneShotShift = false
        oneShotAlt = false
        capsLock = false
        numLock = false
    }

    private fun press(p: Press, editor: Editor) {
        otherKey()
        val h = held
        if (p.repeat > 0) {
            // Held: its capital once (B), nothing more: OS 7 does not repeat letters.
            if (p.repeat == 1 && h != null && h.step == 0 && h.shown.isLowerCase()) {
                replaceLast(editor, h.shown.uppercaseChar())
                h.shown = h.shown.uppercaseChar()
            }
            return
        }
        val alt = (numLock || p.altHeld || altDown || oneShotAlt) && usable(p.alt)
        val c = when {
            alt -> p.alt
            p.shiftHeld || shiftDown || oneShotShift || capsLock -> p.shifted
            p.base.isLetter() && field.kind == Kind.TEXT && known(editor) && capsAt(tail!!, complete, field) -> p.shifted
            else -> p.base
        }.let { if (it == '$') currency else it }
        oneShotShift = false
        oneShotAlt = false
        undo = null
        lastWasSpace = false
        if (!usable(c)) return
        val w = word
        if (predictive && (w != null && (c.isLetter() || c == '\'') || w == null && c.isLetter() && wordStart(editor))) {
            compose(editor, (w ?: "") + c)
        } else {
            settle(editor)
            commit(editor, c.toString())
        }
        held = Held(c, c)
    }

    /** A word with a letter and no digit that neither list knows (none before they are read). */
    private fun unknown(word: String): Boolean {
        val w = word.trim('\'')
        return w.any { it.isLetter() } && w.none { it.isDigit() } && !known(w, lexicons())
    }

    /** The cursor is not after a letter, a digit or an apostrophe. */
    private fun wordStart(editor: Editor) = known(editor) && tail!!.lastOrNull().let { it == null || !it.isLetterOrDigit() && it != '\'' }

    /** Predictive: [w] the composing word (none when empty), its list shown. */
    private fun compose(editor: Editor, w: String) {
        val old = word?.length ?: 0
        val lists = lexicons()
        collapse()
        editor.compose(w, w.isNotEmpty() && !known(w, lists))
        tail = tail?.let { grow(it.dropLast(old) + w) }
        moved(w.length - old)
        if (w.isEmpty()) {
            editor.finish()
            word = null
            hide(editor)
            return
        }
        word = w
        items = predict(w, lists, substitutions(), macro = macro)
        focus = 0
        editor.suggest(items, focus)
    }

    /** Predictive: the composing word ends as typed, the list goes. */
    private fun settle(editor: Editor) {
        if (word != null) editor.finish()
        word = null
        hide(editor)
    }

    private fun hide(editor: Editor) {
        if (items.isEmpty()) return
        items = emptyList()
        editor.suggest(items, 0)
    }

    /** Predictive: the trackpad moves the list's focus, Enter takes its word; without a list, the key goes to the app. */
    private fun pick(p: Pick, editor: Editor) {
        otherKey()
        if (word == null || items.isEmpty()) {
            foreign(Foreign(null, changes = true), editor)
            editor.tap(p.key)
            return
        }
        if (p.step == 0) return take(editor, items[focus].text + if (p.space) " " else "")
        val f = (focus + p.step).coerceIn(0, items.size - 1)
        if (f == focus) return
        focus = f
        editor.suggest(items, focus)
    }

    /** Predictive: [text] in place of the word typed (sim-95c). */
    private fun take(editor: Editor, text: String) {
        val n = word?.length ?: return
        settle(editor)
        tail?.let { learnLast(it.dropLast(n) + text.trimEnd()) }
        replace(editor, n, text)
        held = null
        undo = null
        lastWasSpace = text.endsWith(' ')
    }

    /** The character just typed becomes [c]: in the composing word if there is one. */
    private fun replaceLast(editor: Editor, c: Char) {
        val w = word
        if (w != null) compose(editor, w.dropLast(1) + c) else replace(editor, 1, c.toString())
    }

    private fun accent(a: Accent, editor: Editor) {
        val h = held ?: return
        h.step += a.step
        val c = accent(h.pressed, h.step)
        if (c != h.shown) replaceLast(editor, c)
        h.shown = c
    }

    private fun space(s: Space, editor: Editor) {
        otherKey()
        held = null
        if (s.repeat > 0) return
        settle(editor)
        val wasSpace = lastWasSpace
        lastWasSpace = false
        undo = null
        when (field.kind) {
            Kind.PASS, Kind.PLAIN -> commit(editor, " ")
            Kind.EMAIL -> {
                val t = if (known(editor)) tail!! else null
                commit(editor, if (t == null) " " else if ('@' in t.substringAfterLast(' ')) "." else "@")
            }
            Kind.TEXT -> {
                val t = if (known(editor)) tail!! else null
                val keep = keepWord
                keepWord = null
                if (t == null) {
                    commit(editor, " ")
                } else if (wasSpace && t.length >= 2 && t.last() == ' ' && t[t.length - 2].isLetterOrDigit()) {
                    // Space twice: a period, and the next letter a capital (B).
                    replace(editor, 1, ". ")
                } else {
                    val word = t.takeLastWhile { it.isLetterOrDigit() || it == '\'' }
                    val with = if (word.isEmpty() || !field.substitute || word == keep) null
                    else substitutions().find(word, capsAt(t.dropLast(word.length), complete, field), macro)
                    if (with == null) {
                        // Learned first: a word typed for the second time is known, not underlined.
                        learnLast(t)
                        // An unknown word written again, underlined (sim-98a), where substitution is.
                        if (field.substitute && unknown(word)) replace(editor, word.length, "$word ", unknown = word.length)
                        else commit(editor, " ")
                        lastWasSpace = true
                    } else {
                        replace(editor, word.length, "$with ")
                        undo = Undo(word, with)
                        lastWasSpace = true
                    }
                }
            }
        }
    }

    private fun delete(d: Delete, editor: Editor) {
        otherKey()
        held = null
        lastWasSpace = false
        word?.let { return compose(editor, it.dropLast(1)) }
        val u = undo
        val t = tail
        if (u != null && t != null) {
            if (!u.spaceGone && t.endsWith(u.replacement + " ")) {
                sendDelete(editor, d.key)
                u.spaceGone = true
                return
            }
            if (u.spaceGone && t.endsWith(u.replacement)) {
                // The second Backspace gives the word typed back (guide 7.1 p. 217, B).
                replace(editor, u.replacement.length, u.original)
                keepWord = u.original
                undo = null
                return
            }
        }
        undo = null
        sendDelete(editor, d.key)
    }

    private fun sendDelete(editor: Editor, key: Any) {
        editor.send(key)
        val t = tail ?: return run { cursor = null }
        if (t.isEmpty()) {
            if (!complete) forget()
            return
        }
        val n = if (t.length >= 2 && t.last().isLowSurrogate() && t[t.length - 2].isHighSurrogate()) 2 else 1
        tail = t.dropLast(n)
        moved(-n)
    }

    private fun modifier(m: Modifier, editor: Editor) {
        if (!m.down) editor.unlatch(m.mod)
        val altNow = m.altHeld || altDown
        when (m.mod) {
            Mod.ALT -> if (m.down) {
                altDown = true
                altAlone = true
            } else {
                if (altAlone) oneShotAlt = !oneShotAlt
                altDown = false
            }
            Mod.SHIFT_LEFT, Mod.SHIFT_RIGHT -> if (m.down) {
                shiftDown = true
                shiftAlone = true
                if (altNow) {
                    // Alt and right Shift: CAPS lock; Alt and left Shift: NUM lock (B).
                    if (m.mod == Mod.SHIFT_RIGHT) { capsLock = !capsLock; numLock = false } else { numLock = !numLock; capsLock = false }
                    altAlone = false
                    shiftAlone = false
                } else if (capsLock || numLock) {
                    // Either Shift turns a lock off (B).
                    capsLock = false
                    numLock = false
                    shiftAlone = false
                }
            } else {
                if (shiftAlone) oneShotShift = !oneShotShift
                shiftDown = false
            }
        }
    }

    private fun foreign(f: Foreign, editor: Editor) {
        otherKey()
        if (f.changes) settle(editor)
        if (f.key != null) editor.send(f.key)
        lastWasSpace = false
        oneShotShift = false
        oneShotAlt = false
        if (f.changes) {
            forget()
            cursor = null
            selected = null
            undo = null
            held = null
        }
    }

    private fun selection(s: Selection, editor: Editor) {
        val at = s.start to s.end
        if (at in ours) {
            while (ours.removeFirst() != at) Unit
            return
        }
        settle(editor)
        forget()
        cursor = if (s.start == s.end) s.start else null
        selected = if (s.start == s.end) null else at
        undo = null
        held = null
        lastWasSpace = false
    }

    /** A key other than a modifier came while a modifier was down: no one-shot from it. */
    private fun otherKey() {
        if (shiftDown) shiftAlone = false
        if (altDown) altAlone = false
    }

    /** The word that ends [before], to [learn] if its token is that word alone (opening and closing punctuation aside). */
    private fun learnLast(before: String) {
        if (field.kind != Kind.TEXT || !field.substitute) return
        val token = before.takeLastWhile { !it.isWhitespace() }.trimStart { !it.isLetterOrDigit() }.trimEnd { it in CLOSING }
        if (token.isNotEmpty() && token.all { it.isLetter() || it == '\'' }) learn(token)
    }

    /** Whether [tail] is known, reading it if needed (never in a PLAIN field). */
    private fun known(editor: Editor): Boolean {
        if (tail != null) return true
        if (field.kind == Kind.PLAIN || field.kind == Kind.PASS) return false
        val t = editor.before(TAIL) ?: return false
        tail = t
        complete = t.length < TAIL
        return true
    }

    private fun commit(editor: Editor, text: String) {
        collapse()
        editor.commit(text)
        tail = tail?.let { grow(it + text) }
        moved(text.length)
    }

    private fun replace(editor: Editor, count: Int, text: String, unknown: Int = 0) {
        collapse()
        editor.replace(count, text, unknown)
        tail = tail?.let { grow(it.dropLast(count) + text) }
        moved(text.length - count)
    }

    private fun grow(t: String): String {
        if (t.length <= TAIL) return t
        complete = false
        return t.takeLast(TAIL)
    }

    private fun moved(by: Int) {
        val c = cursor ?: return
        cursor = c + by
        ours.addLast(c + by to c + by)
        if (ours.size > TAIL) ours.removeFirst()
    }

    /** Our edit takes the selection's place: the cursor is where it started. */
    private fun collapse() {
        val s = selected ?: return
        selected = null
        cursor = minOf(s.first, s.second)
    }

    private fun forget() {
        tail = null
        complete = false
        ours.clear()
    }

    private fun usable(c: Char) = c != '\u0000' && !Character.isISOControl(c) && c !in ''..''

    private companion object {
        const val TAIL = 64
        /** What may follow a word before the Space that ends it. */
        const val CLOSING = ".,;:!?…)]}»\"'"
    }
}
