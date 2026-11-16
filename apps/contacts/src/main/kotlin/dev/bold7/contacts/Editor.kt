package dev.bold7.contacts

// Editing or adding a contact in BBOS 7.1, pure (sim-184 to sim-188, sim-191, sim-192, C; guide
// p. 190 and 191, B; docs/BBOS7-VISUEL.md §30): the fields of OS 7 in their boxes and order, each
// tied to the provider's row it came from, and what Save writes: only what changed, each row
// updated in place, so that what Bold7 does not show (a number's own label, a job title, a second
// account's rows) is never rewritten.

import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.TextAct
import dev.bold7.ui.withSwitchApplication

/** A field of the form; TITLE, FIRST and LAST share the name's row. */
enum class FieldKey(val group: Group, val label: String) {
    TITLE(Group.NAME, "Title:"),
    FIRST(Group.NAME, "First:"),
    LAST(Group.NAME, "Last:"),
    COMPANY(Group.ORGANIZATION, "Company:"),
    EMAIL(Group.EMAIL, "Email:"),
    PHONE(Group.PHONE, ""),
    ADDRESS(Group.ADDRESS, "Address:"),
    BIRTHDAY(Group.DATE, "Birthday:"),
    ANNIVERSARY(Group.DATE, "Anniversary:"),
    WEBSITE(Group.WEBSITE, "Website:"),
    NOTES(Group.NOTE, ""),
}

/** A kind of provider row (a MIME type of ContactsContract.Data), and the form's box it shows in. */
enum class Group { NAME, ORGANIZATION, EMAIL, PHONE, ADDRESS, DATE, WEBSITE, NOTE }

/** Android's kinds of number, as OS 7 names them (Android's others are Other, D). */
enum class PhoneBase { WORK, HOME, MOBILE, PAGER, FAX, OTHER }

/** OS 7's list of a number's type, in its order (sim-192, C), and Other for Android's others (D). */
enum class PhoneType(val label: String, val base: PhoneBase) {
    WORK("Work", PhoneBase.WORK),
    HOME("Home", PhoneBase.HOME),
    MOBILE("Mobile", PhoneBase.MOBILE),
    WORK_2("Work 2", PhoneBase.WORK),
    HOME_2("Home 2", PhoneBase.HOME),
    MOBILE_2("Mobile 2", PhoneBase.MOBILE),
    PAGER("Pager", PhoneBase.PAGER),
    WORK_FAX("Work Fax", PhoneBase.FAX),
    OTHER("Other", PhoneBase.OTHER),
}

/** Each number's type: the second of a kind is its "2" (Work 2), the next ones too (D). */
fun phoneTypes(bases: List<PhoneBase>): List<PhoneType> {
    val seen = HashMap<PhoneBase, Int>()
    return bases.map { b ->
        val n = seen.merge(b, 1, Int::plus)!!
        when (b) {
            PhoneBase.WORK -> if (n == 1) PhoneType.WORK else PhoneType.WORK_2
            PhoneBase.HOME -> if (n == 1) PhoneType.HOME else PhoneType.HOME_2
            PhoneBase.MOBILE -> if (n == 1) PhoneType.MOBILE else PhoneType.MOBILE_2
            PhoneBase.PAGER -> PhoneType.PAGER
            PhoneBase.FAX -> PhoneType.WORK_FAX
            PhoneBase.OTHER -> PhoneType.OTHER
        }
    }
}

/** A value read from the provider: its field, its Data row, and a number's kind. */
data class Stored(val key: FieldKey, val dataId: Long, val value: String, val base: PhoneBase? = null)

data class Field(
    val key: FieldKey,
    val value: String,
    val original: String = value,
    val dataId: Long? = null,
    val type: PhoneType? = null,
    val originalType: PhoneType? = type,
) {
    val changed: Boolean get() = value != original || type != originalType
}

/** What Save does to the provider: a row written (inserted when [dataId] is null), or removed; [type], a number's kind when it is new or changed. */
sealed interface Change {
    data class Write(val group: Group, val dataId: Long?, val values: Map<FieldKey, String>, val type: PhoneType? = null) : Change
    data class Remove(val dataId: Long) : Change
}

data class Editor(val fields: List<Field>) {
    companion object {
        private val SINGLE = listOf(FieldKey.TITLE, FieldKey.FIRST, FieldKey.LAST, FieldKey.COMPANY)

        /**
         * The form of OS 7.1: Title, First, Last, Company; the email addresses then an empty one
         * (sim-185, C: a filled one adds the next); the numbers then an empty one, typed with the
         * first type not used yet (Work on an empty contact, C); the addresses then an empty one (D:
         * OS 7 adds them with Add Address, in five fields); the birthday and the anniversary, each one
         * date or none (Dates, sim-186, C); the website; the notes (sim-187, C).
         */
        fun of(stored: List<Stored>): Editor {
            val out = ArrayList<Field>()
            for (k in SINGLE) out += stored.firstOrNull { it.key == k }?.let { Field(k, it.value, dataId = it.dataId) } ?: Field(k, "")
            out += stored.filter { it.key == FieldKey.EMAIL }.map { Field(FieldKey.EMAIL, it.value, dataId = it.dataId) } + Field(FieldKey.EMAIL, "")
            val phones = stored.filter { it.key == FieldKey.PHONE }
            val types = phoneTypes(phones.map { it.base ?: PhoneBase.OTHER })
            out += phones.mapIndexed { i, s -> Field(FieldKey.PHONE, s.value, dataId = s.dataId, type = types[i]) }
            out += emptyPhone(types)
            out += stored.filter { it.key == FieldKey.ADDRESS }.map { Field(FieldKey.ADDRESS, it.value, dataId = it.dataId) } + Field(FieldKey.ADDRESS, "")
            for (k in listOf(FieldKey.BIRTHDAY, FieldKey.ANNIVERSARY, FieldKey.WEBSITE, FieldKey.NOTES)) out += stored.firstOrNull { it.key == k }?.let { Field(k, it.value, dataId = it.dataId) } ?: Field(k, "")
            return Editor(out)
        }

        private fun emptyPhone(used: List<PhoneType>) = Field(FieldKey.PHONE, "", type = PhoneType.entries.firstOrNull { it !in used } ?: PhoneType.OTHER)
    }

    val changed: Boolean get() = fields.any { it.changed }

    /** Nothing but blanks: a new contact like this is not saved (D). */
    val empty: Boolean get() = fields.all { it.value.isBlank() }

    /** Text typed into field [index]; the last empty email, number or address filled brings the next empty one (C for the email). */
    fun edited(index: Int, text: String): Editor {
        if (index !in fields.indices) return this
        val list = fields.toMutableList()
        val f = list[index]
        list[index] = f.copy(value = text)
        val grows = f.key == FieldKey.EMAIL || f.key == FieldKey.PHONE || f.key == FieldKey.ADDRESS
        val lastOfKind = list.indexOfLast { it.key == f.key } == index
        if (grows && lastOfKind && f.value.isBlank() && text.isNotBlank()) {
            list.add(index + 1, if (f.key == FieldKey.PHONE) emptyPhone(list.mapNotNull { it.type }) else Field(f.key, ""))
        }
        return copy(fields = list)
    }

    /** A number's type chosen in its list (sim-192, C). */
    fun typed(index: Int, type: PhoneType): Editor =
        if (fields.getOrNull(index)?.key != FieldKey.PHONE) this else copy(fields = fields.toMutableList().also { it[index] = it[index].copy(type = type) })

    /** What is shown becomes what is saved. */
    fun saved(): Editor = copy(fields = fields.map { it.copy(original = it.value, originalType = it.type) })

    /** The rows to write: the name whole when one of its fields changed. */
    fun changes(): List<Change> {
        val out = mutableListOf<Change>()
        val name = fields.filter { it.key.group == Group.NAME }
        if (name.any { it.changed }) {
            val id = name.firstNotNullOfOrNull { it.dataId }
            val values = name.associate { it.key to it.value.trim() }
            if (values.values.all { it.isEmpty() }) id?.let { out += Change.Remove(it) } else out += Change.Write(Group.NAME, id, values)
        }
        for (f in fields) {
            if (f.key.group == Group.NAME || !f.changed) continue
            val v = f.value.trim()
            when {
                v.isEmpty() -> if (f.dataId != null) out += Change.Remove(f.dataId)
                else -> out += Change.Write(f.key.group, f.dataId, mapOf(f.key to v), f.type?.takeIf { f.dataId == null || f.type != f.originalType })
            }
        }
        return out
    }
}

/** A date as Android stores it (Event.START_DATE): "yyyy-MM-dd", or "--MM-dd" without a year. */
fun parseDate(value: String): java.time.LocalDate? = try {
    java.time.LocalDate.parse(value)
} catch (e: java.time.format.DateTimeParseException) {
    null
}

/** A date as the form's button shows it ("Nov 16, 2026"; a date without a year "Nov 16"; anything else as stored; D, sim-186 shows only "Add"). */
fun dateLabel(value: String): String {
    parseDate(value)?.let { return "${monthOf(it.monthValue)} ${it.dayOfMonth}, ${it.year}" }
    Regex("--(\\d\\d)-(\\d\\d)").matchEntire(value)?.let { m ->
        val month = m.groupValues[1].toInt()
        if (month in 1..12) return "${monthOf(month)} ${m.groupValues[2].toInt()}"
    }
    return value
}

private fun monthOf(m: Int) = java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)

/** The form's menu actions; START_SELECTION, CUT, COPY and PASTE are core/ui's [TextAct], named as it names them. */
enum class ContactAct { SWITCH, SAVE, CLEAR, HELP, CLOSE, START_SELECTION, CUT, COPY, PASTE }

/**
 * The form's full menu (sim-188, C): Switch Application, Save focused, the focused field's selection
 * entries [text] after it (guide 7.1 p. 213, B; their place D), Clear Field on a date that has one
 * (D), Help, Close.
 */
fun contactMenu(onDate: Boolean, text: List<TextAct>): Menu<ContactAct> {
    val entries = listOf(MenuEntry(ContactAct.SAVE)) + text.map { MenuEntry(ContactAct.valueOf(it.name)) } + listOfNotNull(
        MenuEntry(ContactAct.CLEAR, separatorAbove = true).takeIf { onDate },
        MenuEntry(ContactAct.HELP, separatorAbove = true),
        MenuEntry(ContactAct.CLOSE),
    )
    return Menu(entries, highlighted = 0).withSwitchApplication(ContactAct.SWITCH)
}
