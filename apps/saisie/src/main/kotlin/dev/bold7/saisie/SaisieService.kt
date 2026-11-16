package dev.bold7.saisie

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.SuggestionSpan
import android.text.style.UnderlineSpan
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/**
 * OS 7's Direct and Predictive typing styles: nothing on
 * screen but the Sym list while it is open and Predictive's list while there is one, no candidates
 * view, never fullscreen; the app is never resized. The style is Options' (bold7_saisie_style,
 * "predictive" or Direct), read at each field.
 *
 * ViewRootImpl waits up to 2.5 s for this process on each key: onKeyDown and onKeyUp only read
 * this object's fields and hand the key, in order, to the [Typist] on its own thread, which reads
 * and writes the field through the InputConnection. No key and no text is ever logged; nothing is
 * kept but what TypingStore keeps (learned words and their counts, nothing else). The keys come
 * from bold7-trackpad's twin keyboard: nothing here depends on the device they come from.
 */
class SaisieService : InputMethodService() {
    private val thread = HandlerThread("bold7-saisie", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val typing = Handler(thread.looper)
    /** Inputs handed to the typing thread and not done yet: a key that edits waits behind them. */
    private val pending = AtomicInteger()

    // Typing thread only.
    private var substitutions = Substitutions.NONE
    /** The user's own list over the others, made again when either changes. */
    private var all = Substitutions.NONE
    private var allFrom: Pair<Substitutions, Substitutions>? = null
    /** The word lists, French then English, for Predictive and the underlining of unknown words. */
    @Volatile private var lexicons = emptyList<Lexicon>()
    private val store get() = (application as SaisieApp).store
    private val typist = Typist(
        lexicons = {
            val lists = lexicons
            val words = store.words
            // Nothing learned counts before the lists are read (all words known until then).
            if (words == null || lists.isEmpty()) lists else lists + words
        },
        // Only the words neither list knows (sim-92a: "adds new words"), only once they are read.
        learn = { w -> lexicons.let { if (it.isNotEmpty() && !known(w, it)) store.learn(w) } },
        macro = ::macro,
    ) { substitutions() }
    /** The typist's state after its last input, for the keys that depend on it. */
    @Volatile private var composing = false
    @Volatile private var listing = false

    // Main thread only.
    private var kind = Kind.PASS
    private var heldKey = 0
    /** Keys taken whole: a positive code's up follows its down to the app, a negative one's is swallowed. */
    private val consumed = HashSet<Int>()
    /** The Sym key (KEY_RIGHTALT, ALT_RIGHT in Q25_keyboard.kl) down, and no other key since. */
    private var symDown = false
    private var symAlone = false
    private var symbols: SymbolView? = null
    private var symOpen = false
    private var predictive = false
    /** Options' Key Tone and the profile, read at each field. */
    private var keyToneSetting: String? = null
    private var ringing = false
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var words: WordsView? = null
    private var listShown = false
    private var listItems: List<Suggestion> = emptyList()
    private var listFocus = 0
    private val main = Handler(Looper.getMainLooper())
    /** An app that tells nowhere where its word is: the list shown anyway, centred (D). */
    private val unanchored = Runnable { words?.ready = true }

    /** The window shows only the Sym list or Predictive's, and only while one is there. */
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown() // @CallSuper: its own bookkeeping; its answer is not ours
        return symOpen || listShown
    }

    /** Shown for the lists only, physical keyboard or not; never at an app's request. */
    override fun onShowInputRequested(flags: Int, configChange: Boolean) = symOpen || listShown

    override fun onCreateInputView(): View = FrameLayout(this).apply {
        addView(SymbolView(this@SaisieService) { pick(it) }.also { symbols = it })
        addView(WordsView(this@SaisieService) { post(Choose(it)) }.also { words = it })
        words?.show(listItems, listFocus)
        showLists()
    }

    /** The app keeps its whole height (the content inset is the window's bottom); only the popup is touched. */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val height = window.window?.decorView?.height ?: return
        outInsets.contentTopInsets = height
        outInsets.visibleTopInsets = height
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
        val (view, panel) = when {
            symOpen -> symbols?.let { it to it.panel }
            listShown -> words?.let { it to it.panel }
            else -> null
        } ?: return
        val at = IntArray(2)
        view.getLocationInWindow(at)
        outInsets.touchableRegion.set(panel)
        outInsets.touchableRegion.translate(at[0], at[1])
    }

    /** Where the word being typed is, on the screen (CURSOR_UPDATE_MONITOR, asked at a Predictive field's start). */
    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val view = words ?: return
        val start = info.composingTextStart
        if (start < 0) {
            // No word being typed: the next list waits for its own place.
            view.ready = false
            return
        }
        val r = info.getCharacterBounds(start)
        val pts = if (r != null) floatArrayOf(r.left, r.top, r.left, r.bottom)
        else floatArrayOf(info.insertionMarkerHorizontal, info.insertionMarkerTop, info.insertionMarkerHorizontal, info.insertionMarkerBottom)
        if (pts[0].isNaN()) return
        info.matrix.mapPoints(pts)
        view.anchor(pts[0], pts[1], pts[3])
        main.removeCallbacks(unanchored)
        view.ready = true
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreate() {
        super.onCreate()
        load()
        store // its files read now, on its own thread, not at the first key
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val field = fieldOf(attribute.inputType, attribute.imeOptions)
        kind = field.kind
        heldKey = 0
        consumed.clear()
        closeSymbols()
        setList(emptyList(), 0)
        if (kind == Kind.PASS) return
        // In memory since Android 11 (the app hands it over with the EditorInfo); never asked in a PLAIN field.
        val before = if (kind == Kind.PLAIN) null else attribute.getInitialTextBeforeCursor(64, 0)?.toString()
        val cursor = attribute.initialSelStart.takeIf { it >= 0 && it == attribute.initialSelEnd }
        // Options' Typing screen writes it; absent, Direct.
        predictive = kind == Kind.TEXT && field.substitute && Settings.Global.getString(contentResolver, STYLE) == "predictive"
        // Options > Keyboard writes them; the profile now: Silent and Vibrate Only are not RINGER_MODE_NORMAL.
        keyToneSetting = Settings.Global.getString(contentResolver, KEY_TONE)
        ringing = keyToneSetting == "1" && audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL
        post(Start(field, before, cursor, predictive, currencyOf(Settings.Global.getString(contentResolver, CURRENCY))))
        val ic = currentInputConnection
        if (predictive && ic != null) typing.post { ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE) }
    }

    override fun onFinishInput() {
        kind = Kind.PASS
        heldKey = 0
        predictive = false
        closeSymbols()
        setList(emptyList(), 0)
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (kind != Kind.PASS) post(Selection(newSelStart, newSelEnd))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (kind == Kind.PASS || currentInputConnection == null) return super.onKeyDown(keyCode, event)
        // A oneway call to AudioService; the variant with a volume plays even with Android's touch
        // sounds off (tools/silence.sh), on the system stream, which the profile mutes.
        val keyboard = event.isPrintingKey || KeyEvent.isModifierKey(keyCode) || keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_ENTER
        if (keyTone(keyToneSetting, kind, ringing, keyboard, event.repeatCount)) audio?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
        // Sym: pressed and released alone, it opens the grid, and turns its page while open; with another key, nothing.
        if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            if (event.repeatCount == 0) {
                symDown = true
                symAlone = true
            }
            return true
        }
        symAlone = false
        if (symOpen) return symbolKey(keyCode, event)
        // Shift and the trackpad select, Shift and Backspace cut, Alt and the click copy, Shift and
        // the click paste (guide 7.1 p. 37, 38 and 213, B), where Typing reads the field; a held
        // letter's accents first. Their ups are swallowed: Typing hands the app a whole key when
        // it leaves one to it.
        if ((kind == Kind.TEXT || kind == Kind.EMAIL) && heldKey == 0) {
            val shift = event.isShiftPressed
            val input = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (shift) Extend(-1, 0, event) else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (shift) Extend(1, 0, event) else null
                KeyEvent.KEYCODE_DPAD_UP -> if (shift) Extend(0, -1, event) else null
                KeyEvent.KEYCODE_DPAD_DOWN -> if (shift) Extend(0, 1, event) else null
                KeyEvent.KEYCODE_DPAD_CENTER -> when {
                    event.repeatCount > 0 -> null
                    event.altLeft -> Clipboard(Clip.COPY, event)
                    shift -> Clipboard(Clip.PASTE, event)
                    else -> null
                }
                else -> null
            }
            if (input != null) {
                consumed += -keyCode
                post(input)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DEL && shift) {
                // Backspace's up follows its down, as without Shift: Typing may delete instead.
                consumed += keyCode
                post(Clipboard(Clip.CUT, event))
                return true
            }
        }
        // Back or Escape on Predictive's list closes it (D); the app does not get them.
        if (listing && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            consumed += -keyCode
            post(Dismiss)
            return true
        }
        // Predictive, a word typed or keys still pending: Enter and the trackpad go to the typist,
        // which moves the list's focus, takes a word, or hands the key to the app. The Q25's
        // trackpad click is Enter today (bold7-trackpad): it takes the word with a space, as Enter
        // (sim-95c), not without as the 9900's click (sim-97b): the two cannot be told apart here.
        if (predictive && (composing || pending.get() > 0)) {
            val step = when (keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> 0
                KeyEvent.KEYCODE_DPAD_LEFT -> if (heldKey == 0) -1 else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (heldKey == 0) 1 else null
                else -> null
            }
            if (step != null) {
                consumed += -keyCode
                // A click (DPAD_CENTER) takes the word without a space (sim-97b); none comes from the
                // Q25 until bold7-trackpad sends its click as KEY_SELECT.
                post(Pick(step, event, space = keyCode != KeyEvent.KEYCODE_DPAD_CENTER))
                return true
            }
        }
        val input = when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> Space(event.repeatCount)
            KeyEvent.KEYCODE_DEL -> Delete(event)
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT -> {
                post(modifier(keyCode, event, down = true))
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (heldKey != 0) Accent(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1) else return foreign(keyCode, event, true)
            else -> press(event) ?: return foreign(keyCode, event, true)
        }
        if (input is Press) heldKey = keyCode
        // An arrow taken for an accent: its up is swallowed too, never replayed.
        consumed += if (input is Accent) -keyCode else keyCode
        post(input)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (kind == Kind.PASS || currentInputConnection == null) return super.onKeyUp(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            if (symDown && symAlone) if (symOpen) flipSymbols() else openSymbols()
            symDown = false
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT -> {
                post(modifier(keyCode, event, down = false))
                return super.onKeyUp(keyCode, event)
            }
        }
        if (keyCode == heldKey) {
            heldKey = 0
            post(Release)
        }
        if (consumed.remove(-keyCode)) return true
        if (!consumed.remove(keyCode)) return super.onKeyUp(keyCode, event)
        // Its down went through the typing thread: the up follows it there (Backspace's too, as a
        // keyboard sends it, even when the undo took the down).
        if (keyCode in EDIT_KEYS || keyCode == KeyEvent.KEYCODE_DEL) post(Foreign(event, changes = false))
        return true
    }

    /** A character key of the keyboard's map, or null. */
    private fun press(event: KeyEvent): Press? {
        if (event.isCtrlPressed || event.isMetaPressed) return null
        // The Q25's $ key (KEY_GRAVE) has no character in its map: the dollar, whatever the modifiers.
        val base = keyChar(event.keyCode, event.getUnicodeChar(0)).code
        if (base == 0 || base and KeyCharacterMap.COMBINING_ACCENT != 0 || Character.isISOControl(base)) return null
        val dollar = event.keyCode == KeyEvent.KEYCODE_GRAVE
        return Press(
            base.toChar(),
            if (dollar) '$' else event.getUnicodeChar(KeyEvent.META_SHIFT_ON).toChar(),
            if (dollar) '$' else event.getUnicodeChar(KeyEvent.META_ALT_ON).toChar(),
            event.isShiftPressed,
            event.altLeft,
            event.repeatCount,
        )
    }

    private fun modifier(keyCode: Int, event: KeyEvent, down: Boolean) = Modifier(
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT -> Mod.SHIFT_LEFT
            KeyEvent.KEYCODE_SHIFT_RIGHT -> Mod.SHIFT_RIGHT
            else -> Mod.ALT
        },
        down,
        event.altLeft,
    )

    /** Alt is the left one only: the right one is Sym on the Q25 (getevent). */
    private val KeyEvent.altLeft get() = metaState and KeyEvent.META_ALT_LEFT_ON != 0

    /**
     * A key while the Sym grid is open (sim-303 to sim-305, C): a letter types the glyph over its key, the
     * trackpad moves the focus, its click or Enter types the focused one, Back or Escape closes;
     * anything else is ignored. Every key is taken whole, its up swallowed.
     */
    private fun symbolKey(keyCode: Int, event: KeyEvent): Boolean {
        consumed += -keyCode
        val view = symbols ?: return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> view.focus = view.focus.move(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> view.focus = view.focus.move(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> view.focus = view.focus.move(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> view.focus = view.focus.move(0, 1)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> if (event.repeatCount == 0) pick(view.focus)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> closeSymbols()
            else -> if (event.repeatCount == 0) Symbols.of(event.getUnicodeChar(0).toChar(), view.focus.page)?.let { choose(it) }
        }
        return true
    }

    private fun openSymbols() {
        symOpen = true
        symbols?.focus = Symbols.Focus(0, 0, 0)
        showLists()
        requestShowSelf(0)
    }

    /** The arrow over SYM, or the SYM key itself while the grid is open: the other page. */
    private fun flipSymbols() {
        symbols?.let { it.focus = it.focus.flipped() }
    }

    private fun pick(f: Symbols.Focus) = if (f.flips) symbols?.let { it.focus = f.flipped() } else choose(f.symbol)

    private fun closeSymbols() {
        if (!symOpen) return
        symOpen = false
        showLists()
        if (!listShown) requestHideSelf(0)
    }

    /** Predictive's list, from the typing thread; the window shows when one comes and hides when it goes. */
    private fun setList(items: List<Suggestion>, focus: Int) {
        if (kind == Kind.PASS && items.isNotEmpty()) return // a field gone since
        val was = listShown
        listItems = items
        listFocus = focus
        listShown = items.isNotEmpty()
        words?.show(items, focus)
        if (listShown == was) return
        showLists()
        if (symOpen) return
        if (listShown) {
            main.removeCallbacks(unanchored)
            main.postDelayed(unanchored, ANCHOR_WAIT_MS)
            requestShowSelf(0)
        } else {
            requestHideSelf(0)
        }
    }

    /** One list at a time: Sym's over Predictive's. */
    private fun showLists() {
        symbols?.visibility = if (symOpen) View.VISIBLE else View.GONE
        words?.visibility = if (listShown && !symOpen) View.VISIBLE else View.GONE
    }

    private fun choose(symbol: Char) {
        post(Typed(symbol.toString()))
        closeSymbols()
    }

    /**
     * Any other key. One that edits the field (Enter, arrows…) waits behind the text still being
     * typed, handed to the app by the typing thread; with nothing pending, and for every other key
     * (Back, Menu, volume…), the app gets it directly.
     */
    private fun foreign(keyCode: Int, event: KeyEvent, changes: Boolean): Boolean {
        if (keyCode in EDIT_KEYS && pending.get() > 0) {
            consumed += keyCode
            post(Foreign(event, changes))
            return true
        }
        post(Foreign(null, changes))
        return super.onKeyDown(keyCode, event)
    }

    private fun post(input: Input) {
        val ic = currentInputConnection ?: return
        pending.incrementAndGet()
        typing.post {
            typist.handle(input, Connection(ic))
            composing = typist.composing
            listing = typist.listing
            pending.decrementAndGet()
        }
    }

    /**
     * Both languages at once, whatever the subtype: the user writes French with English words. Ours
     * in French (in git) and OS 7's in English (assets-gen); until the French word list is read,
     * the French list alone, so that no English entry ever replaces a French word ("dont", "il",
     * "sec"); then the English entries whose typed form is a French word are left out. Read on a
     * thread of its own: about a second of work that no key may wait behind.
     */
    private fun load() {
        fun list(name: String) = runCatching { assets.open("substitution/$name.tsv").bufferedReader().use { Substitutions.parse(it.readText()) } }
            .getOrDefault(Substitutions.NONE)
        fun words(lang: String) = runCatching { assets.open("dictionary/$lang.tsv").bufferedReader().use { WordList.parse(it.readText()) } }.getOrNull()
        Thread({
            val fr = list("fr")
            typing.post { substitutions = fr }
            val french = words("fr")
            val english = words("en")
            val both = if (french == null) fr else fr + list("en").without(french::contains)
            typing.post { substitutions = both }
            lexicons = listOfNotNull(french, english)
        }, "bold7-saisie-words").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Typing thread: the user's list over Typing's own, the sum made again only when one changed. */
    private fun substitutions(): Substitutions {
        val from = store.user to substitutions
        if (from != allFrom) {
            all = from.first + from.second
            allFrom = from
        }
        return all
    }

    /**
     * A macro's value (Substitutions.MACROS), at Space on the typing thread: the date and the time
     * (guide p. 217, B) in the phone's language, short and long (D); the battery as "80%" (D), from
     * BatteryManager, which asks no permission; the version, Android's build (D). The number and
     * the PIN have none (D: a new permission, no equivalent): the word typed stays.
     */
    private fun macro(code: Char): String? = when (code) {
        'd' -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date())
        'D' -> DateFormat.getDateInstance(DateFormat.LONG).format(Date())
        't' -> android.text.format.DateFormat.getTimeFormat(this).format(Date())
        'T' -> DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        'b' -> getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }?.let { "$it%" }
        'V' -> Build.DISPLAY
        else -> null
    }

    private inner class Connection(private val ic: InputConnection) : Editor {
        override fun before(max: Int) = ic.getTextBeforeCursor(max, 0)?.toString()
        override fun after(max: Int) = ic.getTextAfterCursor(max, 0)?.toString()
        override fun select(anchor: Int, focus: Int) { ic.setSelection(anchor, focus) }
        override fun clip(action: Clip) {
            ic.performContextMenuAction(when (action) {
                Clip.CUT -> android.R.id.cut
                Clip.COPY -> android.R.id.copy
                Clip.PASTE -> android.R.id.paste
            })
        }
        override fun unlatch(mod: Mod) {
            ic.clearMetaKeyStates(if (mod == Mod.ALT) KeyEvent.META_ALT_MASK else KeyEvent.META_SHIFT_MASK)
        }
        override fun commit(text: String) { ic.commitText(text, 1) }
        override fun replace(count: Int, text: String, unknown: Int) {
            val t: CharSequence = if (unknown == 0) text
            else SpannableString(text).apply { setSpan(unknownSpan(), 0, unknown, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            ic.beginBatchEdit()
            ic.deleteSurroundingText(count, 0)
            ic.commitText(t, 1)
            ic.endBatchEdit()
        }
        override fun send(key: Any) { ic.sendKeyEvent(key as KeyEvent) }
        override fun tap(key: Any) {
            ic.sendKeyEvent(key as KeyEvent)
            ic.sendKeyEvent(KeyEvent.changeAction(key, KeyEvent.ACTION_UP))
        }

        /**
         * White on the blue of sim-94a (C), underlined when unknown (sim-94a, sim-96a: dashes
         * there, a plain line here, D). Composing spans: Android takes them away with the
         * composition (BaseInputConnection.removeComposingSpans), nothing stays in the text.
         */
        override fun compose(word: String, unknown: Boolean) {
            val s = SpannableString(word)
            if (word.isNotEmpty()) {
                s.setSpan(BackgroundColorSpan(COMPOSING_BLUE), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(ForegroundColorSpan(Color.WHITE), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (unknown) s.setSpan(UnderlineSpan(), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            ic.setComposingText(s, 1)
        }

        override fun finish() { ic.finishComposingText() }
        override fun suggest(items: List<Suggestion>, focus: Int) { main.post { setList(items, focus) } }
    }

    /**
     * The underline of an unknown word (sim-98a: black dashes, 2 px, 4 on and 2 off; here a plain
     * line of their mean grey, D). A SuggestionSpan marked misspelled, the span Android's own spell
     * checker puts in a field: no formatting (Html.toHtml leaves it, a copy in a TextView drops it,
     * Android takes it away when the word is edited), and it alone carries its own colour from one
     * app to another. Its colour and thickness come from the context it is made with, Android's
     * theme values, so it is made from a Parcel in the order of the Q25's SuggestionSpan(Parcel)
     * (framework.jar, dexdump): suggestions, flags, locale, language tag, hash code, then
     * colour and thickness of the easy correction, the misspelling, the auto-correction and the
     * grammar error. SuggestionSpan(Parcel) is public; the order is checked again after an OTA.
     */
    private fun unknownSpan(): SuggestionSpan {
        val p = Parcel.obtain()
        try {
            p.writeStringArray(emptyArray())
            p.writeInt(SuggestionSpan.FLAG_MISSPELLED)
            p.writeString("")
            p.writeString("")
            p.writeInt(++spans)
            for (kind in 0 until 4) {
                p.writeInt(if (kind == 1) UNKNOWN_GREY else 0)
                p.writeFloat(if (kind == 1) UNKNOWN_THICKNESS else 0f)
            }
            p.setDataPosition(0)
            return SuggestionSpan(p)
        } finally {
            p.recycle()
        }
    }

    /** Each span its own hash code (SuggestionSpan.equals compares them). Typing thread only. */
    private var spans = 0

    private companion object {
        /** The dashes' mean: 4 black pixels in 6 (sim-98a). */
        const val UNKNOWN_GREY = 0xFF555555.toInt()
        const val UNKNOWN_THICKNESS = 2f
        /** Options' Typing style (written by Options). */
        const val STYLE = "bold7_saisie_style"
        /** Options' Keyboard (written by Options, the launcher's OptionsStore). */
        const val KEY_TONE = "bold7_saisie_key_tone"
        const val CURRENCY = "bold7_saisie_currency"
        /** The word being typed in Predictive (sim-94a, C). */
        const val COMPOSING_BLUE = 0xFF2B8FFF.toInt()
        /** How long a new list waits for the app to tell where the word is. */
        const val ANCHOR_WAIT_MS = 100L

        val EDIT_KEYS = setOf(
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        )
    }
}
