package dev.bold7.ui

/**
 * A text field's selection entries in a full menu: Start Selection (sim-150, sim-256, C), Cut and
 * Copy ("Press the Menu key. Click Cut or Copy", guide 7.1 p. 213, B), Paste (p. 213, B). Pure.
 */
enum class TextAct { START_SELECTION, CUT, COPY, PASTE }

/** Cut and Copy on a selection, else Start Selection; Paste when the clipboard has something (D: which shows when, and their order). */
fun textActs(selected: Boolean, canPaste: Boolean): List<TextAct> =
    (if (selected) listOf(TextAct.CUT, TextAct.COPY) else listOf(TextAct.START_SELECTION)) + listOfNotNull(TextAct.PASTE.takeIf { canPaste })

/** These acts as a full menu's entries, for an app whose own action enum names its four entries as [TextAct] does (START_SELECTION, CUT, COPY, PASTE). */
fun <A : Enum<A>> List<TextAct>.asEntries(actions: Array<A>): List<MenuEntry<A>> = map { t -> MenuEntry(actions.first { it.name == t.name }) }

/** The [TextAct] this action of an app stands for, null for its other actions. */
fun Enum<*>.textAct(): TextAct? = TextAct.entries.firstOrNull { it.name == name }
