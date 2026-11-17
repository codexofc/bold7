package dev.bold7.launcher.options

import dev.bold7.ui.ChoiceList

// An options screen of OS 7.1, pure (docs/ARCHITECTURE.md §3): its groups of fields, the trackpad's
// focus, the choice list open over it, and what a key or a tap does to them. FormView draws it.
// Sources: Trackpad Sensitivity (sim-66 to sim-68), Typing (sim-91a, sim-91b), Phone - Normal
// (sim-31), "Changes made!" (sim-47), C; the guide's "Press the Menu key > Save" (B).

/** A field of an options screen. A disabled one is grey and never takes the focus (sim-91a, C). */
sealed interface Field {
    val label: String
    val enabled: Boolean
}

/**
 * A choice field: [values], [chosen] shown; [unavailable] values are listed grey and refused (D).
 * [folder]: Downloads Folder's, at the right without a label, a folder's icon before its value (sim-284, C).
 */
data class Choice(
    override val label: String,
    val values: List<String>,
    val chosen: Int,
    val unavailable: Set<Int> = emptySet(),
    override val enabled: Boolean = true,
    val folder: Boolean = false,
) : Field

data class Check(override val label: String, val checked: Boolean, override val enabled: Boolean = true) : Field

/** A grey button under a group's fields, as Advanced Style Options (sim-91a, C). */
data class Button(override val label: String, override val enabled: Boolean) : Field

/** A wallpaper Save would set: the 9900's own (Reset Wallpaper), or a picture of Pictures (Change Wallpaper). */
sealed interface WallpaperChange {
    data object Default : WallpaperChange

    /** [uri]: the picture's content uri, as Select Picture gave it. */
    data class Picture(val uri: String) : WallpaperChange
}

/** Wallpaper Settings' thumbnail (sim-284, C): the wallpaper in use, or [pending] once chosen; never focused. */
data class Wallpaper(val pending: WallpaperChange? = null) : Field {
    override val label get() = ""
    override val enabled get() = false
}

/** A line of text and its grey note, as Typing's input language (sim-91a, C); the screen acts on its click. */
data class Note(override val label: String, val note: String, override val enabled: Boolean, val red: Boolean = false) : Field

/**
 * The room the form's hand-drawn texts get, pure (FormRoomTest): what `fit` of core/ui cuts them to,
 * so that none leaves its box. Margins are the ones the views draw with.
 */
object FormRoom {
    /** A group's title, between its two margins. */
    fun groupTitle(width: Int, titleX: Int): Float = (width - 2 * titleX).coerceAtLeast(0).toFloat()

    /** A note's line of text, between the margins of a row. */
    fun noteText(width: Int, textX: Int): Float = (width - 2 * textX).coerceAtLeast(0).toFloat()

    /** A button's label, inside the button that sits [right] px off the edge, [pad] px of air each side, never past the left margin [left]. */
    fun buttonLabel(width: Int, right: Int, left: Int, pad: Int): Float = (width - right - left - 2 * pad).coerceAtLeast(0).toFloat()

    /**
     * An open list's width and the field left [ChoiceListView.open] takes for it: OS 7's
     * 78 px for short values (sim-67, C); for longer ones (Time Zone, Display Language) its [widest]
     * value plus the list's 6 px each side, its right edge kept 8 px inside the field's (a field
     * 12 px off the row's right), growing to the left down to the row's margin (D).
     */
    fun choiceList(widest: Float, fieldLeft: Int, width: Int): Pair<Int, Int> {
        val right = width - 12 - 8
        val wanted = kotlin.math.ceil(widest).toInt() + 2 * 6
        if (wanted <= right - (fieldLeft + 8)) return maxOf(78, wanted) to fieldLeft
        val w = minOf(wanted, right - 9)
        return w to right - w - 8
    }
}

/** A white box with its bold grey title (sim-31, sim-66, C). */
data class Group(val title: String, val fields: List<Field>)

enum class FormKey { UP, DOWN, CLICK, SPACE, BACK }

sealed interface FormResult {
    data class Show(val form: Form) : FormResult

    /** The click of the note or button at [field], for the screen to act on. */
    data class Act(val field: Int) : FormResult

    /** Back with nothing changed: the screen closes. */
    data object Leave : FormResult

    /** Back with changes: "Changes made!", Save, Discard, Cancel (sim-47, C). */
    data object AskSave : FormResult
}

/**
 * An options screen: [groups] as shown, [initial] as last saved, [focus] an index into [fields],
 * [open] the list of the focused choice. The focus starts on the first field that takes it.
 */
data class Form(
    val title: String,
    val groups: List<Group>,
    val focus: Int = groups.flatMap { it.fields }.indexOfFirst { it.enabled }.coerceAtLeast(0),
    val open: ChoiceList? = null,
    val initial: List<Group> = groups,
) {
    val fields: List<Field> get() = groups.flatMap { it.fields }

    val changed: Boolean get() = groups != initial

    /** The note at [field] with [text]: what Message on Lock Screen's text box gives back. */
    fun withNote(field: Int, text: String): Form = (fields.getOrNull(field) as? Note)?.let { replace(field, it.copy(note = text)) } ?: this

    /** The choice at [field] grey or not, as Password's Lock After follows Enable. */
    fun withEnabled(field: Int, enabled: Boolean): Form = (fields.getOrNull(field) as? Choice)?.let { replace(field, it.copy(enabled = enabled)) } ?: this

    /** The wallpaper Save will set, shown in Wallpaper Settings' thumbnail. */
    fun withWallpaper(change: WallpaperChange): Form =
        fields.indexOfFirst { it is Wallpaper }.takeIf { it >= 0 }?.let { replace(it, Wallpaper(change)) } ?: this

    /** What is shown becomes what is saved. */
    fun saved() = copy(initial = groups)

    fun key(key: FormKey): FormResult {
        val list = open
        if (list != null) return when (key) {
            FormKey.UP -> show(copy(open = list.step(down = false)))
            FormKey.DOWN -> show(copy(open = list.step(down = true)))
            FormKey.CLICK -> show(choose(list.chosen))
            FormKey.BACK -> show(copy(open = null))
            FormKey.SPACE -> show(this)
        }
        return when (key) {
            FormKey.UP -> show(move(-1))
            FormKey.DOWN -> show(move(1))
            FormKey.CLICK -> click()
            FormKey.SPACE -> if (fields.getOrNull(focus) is Check) click() else show(this)
            FormKey.BACK -> if (changed) FormResult.AskSave else FormResult.Leave
        }
    }

    /** A tap on [field]: the focus there, then its click; nothing on a disabled field (D). */
    fun tap(field: Int): FormResult {
        if (open != null || fields.getOrNull(field)?.enabled != true) return show(this)
        return copy(focus = field).click()
    }

    /** Value [index] chosen in the open list, which closes; an unavailable one leaves it open. */
    fun choose(index: Int): Form {
        val c = fields.getOrNull(focus) as? Choice ?: return copy(open = null)
        if (index in c.unavailable) return this
        return replace(focus, c.copy(chosen = index)).copy(open = null)
    }

    private fun click(): FormResult = when (val f = fields.getOrNull(focus)) {
        is Choice -> show(copy(open = ChoiceList(f.values.size, f.chosen)))
        is Check -> show(replace(focus, f.copy(checked = !f.checked)))
        is Button, is Note -> FormResult.Act(focus)
        is Wallpaper, null -> show(this)
    }

    // The next field that takes the focus, or none: the focus stops at the ends.
    private fun move(step: Int): Form {
        var i = focus + step
        while (i in fields.indices) {
            if (fields[i].enabled) return copy(focus = i)
            i += step
        }
        return this
    }

    private fun replace(index: Int, field: Field): Form {
        var i = index
        return copy(groups = groups.map { g ->
            val n = g.fields.size
            val out = if (i in 0 until n) Group(g.title, g.fields.toMutableList().also { it[i] = field }) else g
            i -= n
            out
        })
    }

    private fun show(form: Form) = FormResult.Show(form)
}
