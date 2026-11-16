package dev.bold7.saisie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A text field as an app keeps it: text, cursor, the selection updates it would report, the
 * composing text as BaseInputConnection keeps it (commitText replaces it), the Predictive list.
 */
private class FakeField(var text: String = "", var readable: Boolean = true) : Editor {
    /** The selection's moving end; [anchor] its other end, null when there is no selection. */
    var cursor = text.length
    var anchor: Int? = null
    /** Every selection Typing set, anchor then focus. */
    val selections = mutableListOf<Pair<Int, Int>>()
    val clips = mutableListOf<Clip>()
    var clipboard = ""
    val unlatched = mutableListOf<Mod>()
    val sent = mutableListOf<Any>()
    val tapped = mutableListOf<Any>()
    var reads = 0
    val updates = mutableListOf<Int>()
    /** The composing text's start, -1 when there is none. */
    var composing = -1
    var composingEnd = -1
    var composedUnknown = false
    /** Words written as unknown (underlined, sim-98a). */
    val marked = mutableListOf<String>()
    var shown: List<Suggestion> = emptyList()
    var focus = 0

    val composed get() = if (composing < 0) null else text.substring(composing, composingEnd)

    override fun before(max: Int): String? {
        reads++
        return if (readable) text.substring(0, minOf(cursor, anchor ?: cursor)).takeLast(max) else null
    }

    override fun after(max: Int): String? {
        reads++
        return if (readable) text.substring(maxOf(cursor, anchor ?: cursor)).take(max) else null
    }

    override fun select(anchor: Int, focus: Int) {
        selections += anchor to focus
        this.anchor = if (anchor == focus) null else anchor
        cursor = focus
    }

    private val selected get() = anchor?.let { text.substring(minOf(it, cursor), maxOf(it, cursor)) } ?: ""

    override fun clip(action: Clip) {
        clips += action
        when (action) {
            Clip.COPY -> clipboard = selected
            Clip.CUT -> { clipboard = selected; commit("") }
            Clip.PASTE -> commit(clipboard)
        }
    }

    override fun unlatch(mod: Mod) { unlatched += mod }

    override fun commit(text: String) {
        val a = anchor
        if (a != null) {
            anchor = null
            return write(minOf(a, cursor), maxOf(a, cursor), text)
        }
        if (composing < 0) return replace(0, text)
        write(composing, composingEnd, text)
        finish()
    }

    override fun replace(count: Int, text: String, unknown: Int) {
        // deleteSurroundingText would take the composing text too: Typing ends it first.
        check(composing < 0) { "replace while composing" }
        write(cursor - count, cursor, text)
        if (unknown > 0) marked += text.take(unknown)
    }

    override fun send(key: Any) {
        check(composing < 0) { "key sent while composing" }
        sent += key
        if (key == DEL && cursor > 0) replace(1, "")
    }

    override fun tap(key: Any) {
        check(composing < 0) { "key tapped while composing" }
        tapped += key
    }

    override fun compose(word: String, unknown: Boolean) {
        val a = if (composing < 0) cursor else composing
        write(a, if (composing < 0) cursor else composingEnd, word)
        composing = a
        composingEnd = a + word.length
        composedUnknown = unknown
    }

    override fun finish() {
        composing = -1
        composingEnd = -1
    }

    override fun suggest(items: List<Suggestion>, focus: Int) {
        shown = items
        this.focus = focus
    }

    private fun write(a: Int, b: Int, t: String) {
        text = text.substring(0, a) + t + text.substring(b)
        cursor = a + t.length
        updates += cursor
    }

    companion object {
        const val DEL = "DEL"
        const val ENTER = "ENTER"
        const val RIGHT = "RIGHT"
    }
}

class TypistTest {
    private val subs = Substitutions.parse("teh\tthe\tsmart\nhte\tthe\tsmart")
    private val message = Field(Kind.TEXT, capsSentences = true, substitute = true)
    private val f = FakeField()
    private var t = Typist { subs }
    private val en = WordList.parse("this\t200\nthink\t133\nthird\t156\nthe\t222\ntea\t121\nwork\t164\nworld\t145\nworking\t150\nso\t200\n")

    private fun start(field: Field = message, text: String = "", known: Boolean = true, predictive: Boolean = false, lists: Boolean = predictive) {
        if (lists) t = Typist({ listOf(en) }) { subs }
        f.text = text
        f.cursor = text.length
        t.handle(Start(field, if (known) text else null, f.cursor, predictive), f)
    }

    /** A letter key of the Q25 (kcm: base, shift, alt), pressed and released. */
    private fun key(c: Char, alt: Char = '#', shiftHeld: Boolean = false, altHeld: Boolean = false) {
        t.handle(Press(c, c.uppercaseChar(), alt, shiftHeld, altHeld, 0), f)
        t.handle(Release, f)
    }

    private fun type(s: String) = s.forEach { if (it == ' ') t.handle(Space(0), f) else key(it) }
    private fun del() = t.handle(Delete(FakeField.DEL), f)
    private fun pick(step: Int) = t.handle(Pick(step, if (step == 0) FakeField.ENTER else FakeField.RIGHT), f)
    private fun sync() = f.updates.toList().also { f.updates.clear() }.forEach { t.handle(Selection(it, it), f) }

    @Test fun sentenceStartsAreCapitalized() {
        start()
        type("hi. you")
        assertEquals("Hi. You", f.text)
    }

    @Test fun capsFollowTheFieldNotOnlyTheText() {
        start(Field(Kind.TEXT, substitute = true))
        type("hi")
        assertEquals("hi", f.text)
    }

    @Test fun substitutionAtSpaceWithSmartCaseThenUndoneByTwoBackspaces() {
        start(text = "So ")
        type("teh ")
        assertEquals("So the ", f.text)
        del()
        assertEquals("So the", f.text)
        del()
        assertEquals("So teh", f.text)
        type(" ")
        assertEquals("So teh ", f.text, "the word given back is not substituted again")
        type("hte ")
        assertEquals("So teh the ", f.text)
    }

    @Test fun substitutionAtTheStartOfASentence() {
        start()
        t.handle(Press('t', 'T', '(', false, false, 0), f)
        assertEquals("T", f.text, "auto caps")
        t.handle(Release, f)
        type("eh ")
        assertEquals("The ", f.text)
    }

    @Test fun selectionUpdatesFromOurOwnEditsKeepTheUndo() {
        start(text = "So ")
        type("teh ")
        sync()
        del()
        sync()
        del()
        assertEquals("So teh", f.text)
    }

    @Test fun aForeignMoveForgetsTheUndoAndRereadsTheText() {
        start(text = "So ")
        type("teh ")
        t.handle(Selection(0, 0), f)
        f.cursor = 0
        del()
        assertEquals("So the ", f.text)
        assertEquals(listOf<Any>(FakeField.DEL), f.sent)
    }

    @Test fun doubleSpaceGivesAPeriodAndACapital() {
        start()
        type("Ok  so")
        assertEquals("Ok. So", f.text)
    }

    @Test fun doubleSpaceNeedsAWordBefore() {
        start(text = "Ok, ")
        type(" ")
        assertEquals("Ok,  ", f.text)
    }

    @Test fun spacesTypedApartDoNotMakeAPeriod() {
        start()
        type("a ")
        t.handle(Foreign(null, changes = false), f)
        type(" ")
        assertEquals("A  ", f.text)
    }

    @Test fun holdingALetterCapitalizesItOnce() {
        start(text = "a ")
        t.handle(Press('b', 'B', '!', false, false, 0), f)
        t.handle(Press('b', 'B', '!', false, false, 1), f)
        t.handle(Press('b', 'B', '!', false, false, 2), f)
        t.handle(Release, f)
        assertEquals("a B", f.text)
    }

    @Test fun holdingALetterAndSlidingGivesItsAccentsInPlace() {
        start(text = "a ")
        t.handle(Press('u', 'U', '_', false, false, 0), f)
        t.handle(Press('u', 'U', '_', false, false, 1), f)
        t.handle(Accent(-1), f)
        assertEquals("a ü", f.text, "the accent replaces the capital of the hold, from the letter pressed")
        t.handle(Accent(1), f)
        assertEquals("a u", f.text)
        t.handle(Release, f)
        t.handle(Accent(1), f)
        assertEquals("a u", f.text, "nothing once released")
    }

    @Test fun altHeldAndAltOneShotGiveTheSecondCharacter() {
        start(text = "a ")
        key('q', '#', altHeld = true)
        t.handle(Modifier(Mod.ALT, down = true, altHeld = true), f)
        t.handle(Modifier(Mod.ALT, down = false, altHeld = false), f)
        key('w', '1')
        key('w', '1')
        assertEquals("a #1w", f.text)
    }

    @Test fun shiftOneShotAndShiftHeld() {
        start(text = "a ")
        t.handle(Modifier(Mod.SHIFT_LEFT, down = true, altHeld = false), f)
        t.handle(Modifier(Mod.SHIFT_LEFT, down = false, altHeld = false), f)
        key('x')
        key('y', shiftHeld = true)
        key('z')
        assertEquals("a XYz", f.text)
    }

    @Test fun shiftPressedTwiceCancels() {
        start(text = "a ")
        repeat(2) {
            t.handle(Modifier(Mod.SHIFT_RIGHT, down = true, altHeld = false), f)
            t.handle(Modifier(Mod.SHIFT_RIGHT, down = false, altHeld = false), f)
        }
        key('x')
        assertEquals("a x", f.text)
    }

    @Test fun altRightShiftLocksCapsAndShiftUnlocks() {
        start(text = "a ")
        t.handle(Modifier(Mod.ALT, down = true, altHeld = true), f)
        t.handle(Modifier(Mod.SHIFT_RIGHT, down = true, altHeld = true), f)
        t.handle(Modifier(Mod.SHIFT_RIGHT, down = false, altHeld = true), f)
        t.handle(Modifier(Mod.ALT, down = false, altHeld = false), f)
        type("ab")
        key('c', '9')
        t.handle(Modifier(Mod.SHIFT_LEFT, down = true, altHeld = false), f)
        t.handle(Modifier(Mod.SHIFT_LEFT, down = false, altHeld = false), f)
        type("d")
        assertEquals("a ABCd", f.text, "neither the lock nor its release leaves a one-shot alt or shift")
    }

    @Test fun altLeftShiftLocksNumbers() {
        start(text = "a ")
        t.handle(Modifier(Mod.ALT, down = true, altHeld = true), f)
        t.handle(Modifier(Mod.SHIFT_LEFT, down = true, altHeld = true), f)
        t.handle(Modifier(Mod.SHIFT_LEFT, down = false, altHeld = true), f)
        t.handle(Modifier(Mod.ALT, down = false, altHeld = false), f)
        key('w', '1')
        key('e', '2')
        t.handle(Modifier(Mod.SHIFT_RIGHT, down = true, altHeld = false), f)
        t.handle(Modifier(Mod.SHIFT_RIGHT, down = false, altHeld = false), f)
        key('w', '1')
        assertEquals("a 12w", f.text)
    }

    @Test fun emailSpaceGivesAtThenPeriods() {
        start(Field(Kind.EMAIL))
        type("robert bold7 dev")
        assertEquals("robert@bold7.dev", f.text)
    }

    @Test fun plainFieldsNeverReadNorSubstituteNorCapitalize() {
        start(Field(Kind.PLAIN), text = "secret", known = false)
        type("teh  x")
        assertEquals("secretteh  x", f.text)
        assertEquals(0, f.reads)
    }

    @Test fun plainFieldsStillCapitalizeByHoldAndGiveAccents() {
        start(Field(Kind.PLAIN), known = false)
        t.handle(Press('e', 'E', '2', false, false, 0), f)
        t.handle(Accent(2), f)
        t.handle(Release, f)
        t.handle(Press('a', 'A', '*', false, false, 0), f)
        t.handle(Press('a', 'A', '*', false, false, 1), f)
        assertEquals("éA", f.text)
        assertEquals(0, f.reads)
    }

    @Test fun anUnknownTextIsReadOncePerWordStart() {
        start(text = "Hello there. ", known = false)
        type("ok")
        assertEquals("Hello there. Ok", f.text)
        assertEquals(1, f.reads)
    }

    @Test fun anUnreadableTextIsTypedAsIs() {
        start(known = false)
        f.readable = false
        type("teh ")
        assertEquals("teh ", f.text)
    }

    @Test fun deleteIsSentToTheAppAndKeepsTheTextKnown() {
        start(text = "Hi. ")
        del()
        del()
        type("x")
        assertEquals("Hix", f.text)
        assertEquals(2, f.sent.size)
        assertEquals(0, f.reads)
    }

    @Test fun aKeySentForAnotherFieldOperationIsReplayedAndForgetsTheText() {
        start(text = "Hi")
        t.handle(Foreign("ENTER", changes = true), f)
        assertEquals(listOf<Any>("ENTER"), f.sent)
        f.text = "Hi\n"
        f.cursor = 3
        type("x")
        assertEquals("Hi\nX", f.text)
        assertTrue(f.reads > 0)
    }

    @Test fun aSymbolChosenInTheListIsTypedAndEndsTheWordSpaces() {
        start(text = "So ")
        type("teh ")
        t.handle(Typed("€"), f)
        del()
        assertEquals("So the ", f.text, "the symbol ends the undo: Backspace takes the symbol only")
        type(" ")
        assertEquals("So the  ", f.text)
    }

    @Test fun aSymbolInAPasswordReadsNothing() {
        start(Field(Kind.PLAIN), known = false)
        t.handle(Typed("£"), f)
        assertEquals("£", f.text)
        assertEquals(0, f.reads)
    }

    @Test fun privateCharactersOfTheKeyboardAreNeverTyped() {
        start(text = "a ")
        t.handle(Press('k', 'K', '', false, true, 0), f)
        assertEquals("a k", f.text)
    }

    // Predictive (sim-93 to sim-97, C).

    @Test fun predictiveComposesTheWordAndListsItsCompletions() {
        start(predictive = true)
        type("thi")
        assertEquals("Thi", f.text)
        assertEquals("Thi", f.composed)
        assertTrue(f.composedUnknown, "Thi is no word: underlined (sim-94a)")
        assertEquals(listOf("This", "Third", "Think"), f.shown.map { it.text })
        assertEquals(0, f.focus)
        assertTrue(t.composing && t.listing)
        type("s")
        assertTrue(!f.composedUnknown, "This is a word")
    }

    @Test fun theTrackpadMovesTheFocusAndEnterTakesTheWordWithASpace() {
        start(text = "So ", predictive = true)
        type("wor")
        sync()
        assertEquals(listOf("work", "working", "world"), f.shown.map { it.text })
        pick(1)
        pick(1)
        pick(1)
        assertEquals(2, f.focus, "the focus stops at the end")
        pick(-1)
        assertEquals(1, f.focus)
        assertEquals("So wor", f.text, "the field keeps what is typed")
        pick(0)
        assertEquals("So working ", f.text)
        assertEquals(null, f.composed)
        assertEquals(emptyList(), f.shown)
        assertTrue(f.tapped.isEmpty() && !t.composing && !t.listing)
        type("so")
        assertEquals("So working so", f.text)
    }

    @Test fun spaceKeepsWhatIsTyped() {
        start(text = "So ", predictive = true)
        type("thi ")
        assertEquals("So thi ", f.text)
        assertEquals(null, f.composed)
        assertEquals(emptyList(), f.shown)
    }

    @Test fun spaceStillSubstitutesAndTheListShowsTheTypedWordFirst() {
        start(predictive = true)
        type("teh")
        assertEquals(Suggestion("Teh", Look.TYPED), f.shown[0])
        assertEquals("The", f.shown[1].text)
        type(" ")
        assertEquals("The ", f.text)
        del()
        del()
        assertEquals("Teh", f.text, "two Backspaces still give the word back")
    }

    @Test fun backspaceShortensTheWordThenEndsIt() {
        start(text = "So ", predictive = true)
        type("wor")
        del()
        assertEquals("So wo", f.text)
        assertEquals("wo", f.composed)
        assertEquals(listOf("work", "working", "world"), f.shown.map { it.text })
        del()
        del()
        assertEquals("So ", f.text)
        assertEquals(null, f.composed)
        assertEquals(emptyList(), f.shown)
        assertTrue(f.sent.isEmpty(), "the app got no Backspace yet")
        del()
        assertEquals("So", f.text)
        assertEquals(listOf<Any>(FakeField.DEL), f.sent)
    }

    @Test fun withoutAListEnterAndTheTrackpadGoToTheApp() {
        start(text = "So ", predictive = true)
        pick(0)
        pick(1)
        assertEquals(listOf<Any>(FakeField.ENTER, FakeField.RIGHT), f.tapped)
        type("zzz")
        assertEquals("zzz", f.composed)
        assertEquals(emptyList(), f.shown, "nothing to propose")
        pick(0)
        assertEquals("So zzz", f.text)
        assertEquals(null, f.composed, "the word ends before the key goes")
        assertEquals(listOf<Any>(FakeField.ENTER, FakeField.RIGHT, FakeField.ENTER), f.tapped)
    }

    @Test fun aHeldLetterAndItsAccentsStayInTheWord() {
        start(text = "So ", predictive = true)
        type("th")
        t.handle(Press('e', 'E', '#', false, false, 0), f)
        t.handle(Press('e', 'E', '#', false, false, 1), f)
        assertEquals("thE", f.composed)
        t.handle(Release, f)
        t.handle(Press('e', 'E', '#', false, false, 0), f)
        t.handle(Accent(1), f)
        assertEquals("thEè", f.composed)
    }

    @Test fun aCharacterThatIsNoLetterEndsTheWord() {
        start(text = "So ", predictive = true)
        type("wor")
        t.handle(Press('w', 'W', '?', false, true, 0), f)
        assertEquals("So wor?", f.text)
        assertEquals(null, f.composed)
        assertEquals(emptyList(), f.shown)
        t.handle(Typed("€"), f)
        assertEquals("So wor?€", f.text)
    }

    @Test fun anApostropheStaysInTheWordButStartsNone() {
        start(text = "So ", predictive = true)
        type("j'ai")
        assertEquals("j'ai", f.composed)
        type(" '")
        assertEquals(null, f.composed)
    }

    @Test fun onlyAtTheStartOfAWord() {
        start(text = "So wo", predictive = true)
        type("r")
        assertEquals("So wor", f.text)
        assertEquals(null, f.composed)
        start(text = "4", predictive = true)
        type("5")
        assertEquals(null, f.composed, "a digit starts no word")
    }

    @Test fun aMoveElsewhereEndsTheWord() {
        start(text = "So ", predictive = true)
        type("wor")
        t.handle(Selection(0, 0), f)
        assertEquals("So wor", f.text)
        assertEquals(null, f.composed)
        assertEquals(emptyList(), f.shown)
    }

    @Test fun dismissedTheListGoesAndTheWordStays() {
        start(text = "So ", predictive = true)
        type("wor")
        t.handle(Dismiss, f)
        assertEquals("So wor", f.text)
        assertEquals(null, f.composed)
        assertEquals(emptyList(), f.shown)
    }

    @Test fun aTouchTakesTheWord() {
        start(text = "So ", predictive = true)
        type("wor")
        t.handle(Choose(2), f)
        assertEquals("So world ", f.text)
    }

    @Test fun aNewFieldHidesTheList() {
        start(text = "So ", predictive = true)
        type("wor")
        t.handle(Start(message, "", 0, true), f)
        assertEquals(emptyList(), f.shown)
        assertTrue(!t.composing)
    }

    @Test fun noPredictionWhereTypingNeitherReadsNorSubstitutes() {
        for (field in listOf(Field(Kind.TEXT, substitute = false), Field(Kind.PLAIN), Field(Kind.EMAIL))) {
            start(field, predictive = true)
            type("wor")
            assertEquals(null, f.composed, "$field")
            assertEquals(emptyList(), f.shown)
        }
    }

    @Test fun directStaysDirect() {
        start(text = "So ")
        type("wor")
        assertEquals(null, f.composed)
        assertEquals(emptyList(), f.shown)
        pick(0)
        assertEquals(listOf<Any>(FakeField.ENTER), f.tapped)
    }

    // Unknown words underlined (sim-98a, sim-97b, C).

    @Test fun anUnknownWordIsWrittenAgainMarkedAtSpace() {
        start(text = "So ", lists = true)
        type("reciev work teh b5 ")
        assertEquals("So reciev work the b5 ", f.text)
        assertEquals(listOf("reciev"), f.marked, "known, substituted and with a digit: not marked")
    }

    @Test fun theWordGivenBackByTheUndoIsMarked() {
        start(text = "So ", lists = true)
        type("teh ")
        del()
        del()
        type(" ")
        assertEquals("So teh ", f.text)
        assertEquals(listOf("teh"), f.marked)
    }

    @Test fun inPredictiveTooOnceTheWordEnds() {
        start(text = "So ", predictive = true)
        type("thi ")
        assertEquals("So thi ", f.text)
        assertEquals(listOf("thi"), f.marked)
    }

    @Test fun nothingMarkedWithoutListsNorWhereTypingDoesNotSubstitute() {
        start(text = "So ")
        type("reciev ")
        for (field in listOf(Field(Kind.TEXT, substitute = false), Field(Kind.PLAIN))) {
            start(field, lists = true)
            type("reciev ")
        }
        assertEquals(emptyList(), f.marked)
    }

    // Word Learning (sim-92a, C).

    private val learned = mutableListOf<String>()

    private fun learning(field: Field = message, text: String = "So ", predictive: Boolean = false, lists: List<Lexicon> = listOf(en)) {
        t = Typist({ lists }, learn = { learned += it }) { subs }
        f.text = text
        f.cursor = text.length
        t.handle(Start(field, text, f.cursor, predictive), f)
    }

    @Test fun aWordIsLearnedOnceItEndsNeverLetterByLetter() {
        learning()
        type("zinwa")
        assertEquals(emptyList(), learned)
        type(" ")
        assertEquals(listOf("zinwa"), learned)
        type("benos, (bold) ")
        assertEquals(listOf("zinwa", "benos", "bold"), learned, "punctuation before the space ends the word too")
    }

    @Test fun neitherSubstitutedNorAddressesNorDigits() {
        learning()
        type("teh robert@bold b5 bold7 foo.bar www.bold e-mail ")
        assertEquals(emptyList(), learned)
    }

    @Test fun nothingLearnedWhereTypingDoesNotLearn() {
        for (field in listOf(Field(Kind.PLAIN), Field(Kind.EMAIL), Field(Kind.PASS), Field(Kind.TEXT, substitute = false))) {
            learning(field)
            type("zinwa ")
        }
        assertEquals(emptyList(), learned)
    }

    @Test fun aWordTakenFromTheListIsLearnedAsAnyOther() {
        learning(predictive = true)
        type("wor")
        assertEquals(emptyList(), learned)
        pick(0)
        assertEquals("So work ", f.text)
        assertEquals(listOf("work"), learned, "the service keeps only what its lists do not know")
    }

    @Test fun aLearnedWordIsNoLongerUnderlined() {
        val words = Learned()
        learning(lists = listOf(en, words))
        t = Typist({ listOf(en, words) }, learn = { if (!known(it, listOf(en))) words.learn(it) }) { subs }
        t.handle(Start(message, "So ", 3, false), f)
        type("zinwa zinwa zinwa work ")
        assertEquals(listOf("zinwa"), f.marked, "typed once: still unknown; twice: learned")
        assertTrue(words.contains("zinwa"))
        assertTrue(!words.contains("work"))
    }

    @Test fun theTrackpadClickTakesTheWordWithoutASpace() {
        start(text = "So ", predictive = true)
        type("wor")
        t.handle(Pick(0, FakeField.ENTER, space = false), f)
        assertEquals("So work", f.text)
        type("s")
        assertEquals("So works", f.text)
    }

    @Test fun macrosComeFromTheService() {
        t = Typist(macro = { if (it == 'd') "25/12/2009" else null }) { Substitutions.parse("mydate\t%d") }
        f.text = ""
        f.cursor = 0
        t.handle(Start(message, "", 0), f)
        type("mydate ")
        assertEquals("25/12/2009 ", f.text)
    }

    // Selection from the keyboard (guide 7.1 p. 37, 38 and 213, B).

    private fun extend(dx: Int, dy: Int = 0) = t.handle(Extend(dx, dy, if (dy != 0) "LINE" else "CHAR"), f)
    private fun clip(action: Clip) = t.handle(Clipboard(action, if (action == Clip.CUT) FakeField.DEL else "CLICK"), f)

    @Test fun shiftAndTheTrackpadSelectCharacterByCharacterFromTheCursor() {
        start(text = "hello world")
        repeat(3) { extend(-1) }
        assertEquals(Pair(11, 8), f.selections.last(), "the anchor stays at the cursor, the other end moves")
        extend(1)
        assertEquals(Pair(11, 9), f.selections.last())
        extend(1)
        extend(1)
        assertEquals(Pair(11, 11), f.selections.last(), "back to the cursor: no selection")
        extend(1)
        assertEquals(3 + 3, f.selections.size, "nothing past the end of the text")
    }

    @Test fun theSelectionStopsAtTheStartAndStepsOverAPairOfSurrogates() {
        start(text = "a\uD83D\uDE00b")
        extend(-1)
        extend(-1)
        assertEquals(Pair(4, 1), f.selections.last(), "the emoji whole")
        extend(-1)
        extend(-1)
        assertEquals(Pair(4, 0), f.selections.last())
        assertEquals(3, f.selections.size)
    }

    @Test fun ourOwnSelectionReportedLateDoesNotMoveItBack() {
        start(text = "hello world")
        extend(-1)
        extend(-1)
        t.handle(Selection(11, 10), f)
        t.handle(Selection(11, 9), f)
        extend(-1)
        assertEquals(Pair(11, 8), f.selections.last())
    }

    @Test fun aSelectionMadeElsewhereIsExtendedFromItsMovingEnd() {
        start(text = "hello world")
        t.handle(Selection(2, 5), f)
        f.anchor = 2
        f.cursor = 5
        extend(1)
        assertEquals(Pair(2, 6), f.selections.last())
    }

    @Test fun linesGoToTheAppAndTheSelectionWaitsForItsReport() {
        start(text = "hello world")
        extend(0, -1)
        assertEquals(listOf<Any>("LINE"), f.tapped, "the app knows its lines: the key goes to it, Shift held")
        extend(1)
        assertEquals(listOf<Any>("LINE", "CHAR"), f.tapped, "until the app says where the selection is, the app moves it")
        t.handle(Selection(11, 3), f)
        f.anchor = 11
        f.cursor = 3
        extend(1)
        assertEquals(Pair(11, 4), f.selections.last())
    }

    @Test fun anUnknownCursorLeavesTheKeyToTheApp() {
        f.text = "hello"
        t.handle(Start(message, null, null), f)
        extend(-1)
        assertEquals(listOf<Any>("CHAR"), f.tapped)
        assertTrue(f.selections.isEmpty())
    }

    @Test fun neverInAFieldTypingDoesNotRead() {
        start(Field(Kind.PLAIN), text = "secret")
        extend(-1)
        clip(Clip.COPY)
        assertEquals(listOf<Any>("CHAR", "CLICK"), f.tapped)
        assertEquals(0, f.reads)
        assertTrue(f.clips.isEmpty())
    }

    @Test fun typingReplacesTheSelectionAndKeepsTheUndoOfSubstitution() {
        start(text = "So hello")
        repeat(5) { extend(-1) }
        sync()
        type("teh")
        assertEquals("So teh", f.text)
        sync()
        type(" ")
        assertEquals("So the ", f.text)
    }

    @Test fun shiftAndBackspaceCutTheSelectionElseDelete() {
        start(text = "hello world")
        repeat(6) { extend(-1) }
        clip(Clip.CUT)
        assertEquals("hello", f.text)
        assertEquals(" world", f.clipboard)
        clip(Clip.CUT)
        assertEquals("hell", f.text, "no selection: Backspace")
        assertEquals(listOf(Clip.CUT), f.clips)
    }

    @Test fun altAndTheClickCopyTheSelectionElseTheClickGoesOn() {
        start(text = "hello world")
        repeat(5) { extend(-1) }
        clip(Clip.COPY)
        assertEquals("world", f.clipboard)
        assertEquals("hello world", f.text)
        assertTrue(f.tapped.isEmpty())
        t.handle(Selection(11, 11), f)
        clip(Clip.COPY)
        assertEquals(listOf<Any>("CLICK"), f.tapped)
        assertEquals(listOf(Clip.COPY), f.clips)
    }

    @Test fun shiftAndTheClickPaste() {
        start(text = "hello ")
        f.clipboard = "world"
        clip(Clip.PASTE)
        assertEquals("hello world", f.text)
        assertEquals(listOf(Clip.PASTE), f.clips)
    }

    @Test fun theWordBeingTypedEndsBeforeASelection() {
        start(text = "So ", predictive = true)
        type("wo")
        assertEquals("wo", f.composed)
        extend(-1)
        assertEquals(null, f.composed)
        assertTrue(f.shown.isEmpty())
        assertEquals(Pair(5, 4), f.selections.last())
    }

    @Test fun releasingAModifierClearsItInTheAppAndSelectingLeavesNoOneShot() {
        start(Field(Kind.TEXT, substitute = true), text = "hello")
        t.handle(Modifier(Mod.SHIFT_LEFT, down = true, altHeld = false), f)
        extend(-1)
        t.handle(Modifier(Mod.SHIFT_LEFT, down = false, altHeld = false), f)
        assertEquals(listOf(Mod.SHIFT_LEFT), f.unlatched, "the app's own Shift, latched by a key it never saw, is cleared")
        key('a')
        assertEquals("hella", f.text)
    }

    @Test fun theDollarKeyTypesTheCurrencyOfOptions() {
        f.text = ""
        t.handle(Start(Field(Kind.TEXT, substitute = true), "", 0, currency = '€'), f)
        t.handle(Press('$', '$', '$', false, false, 0), f)
        t.handle(Release, f)
        key('w', '$', altHeld = true)
        t.handle(Typed("$"), f)
        assertEquals("€€$", f.text, "the dollar of Sym's list is chosen as it is")
    }
}
