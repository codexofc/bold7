package dev.bold7.contacts

// What another app asks of Contacts, pure: the intents of Android's
// contacts contract (pick, insert, insert or edit, view, edit), read from their action, data, type
// and extras into the screen that answers and what it starts from. Nothing here writes or returns
// anything: the screens do, after the user's own choice or Save.

enum class PickKind { CONTACT, PHONE, EMAIL }

/** A contact named by its id, or by a lookup URI the provider resolves (it survives a re-sync). */
sealed interface ContactRef {
    data class Id(val id: Long) : ContactRef
    data class Lookup(val uri: String) : ContactRef
}

/** What Intents.Insert carries (name, phones with the first's type, emails, company, address, notes). */
data class Prefill(
    val name: String? = null,
    val phones: List<Pair<String, PhoneBase?>> = emptyList(),
    val emails: List<String> = emptyList(),
    val company: String? = null,
    val address: String? = null,
    val notes: String? = null,
)

sealed interface Request {
    data class Pick(val kind: PickKind) : Request
    /** ACTION_INSERT, and ACTION_INSERT_OR_EDIT: OS 7 has no "add to existing contact", so both open New Contact (D). */
    data class Insert(val prefill: Prefill) : Request
    data class View(val target: ContactRef) : Request
    data class Edit(val target: ContactRef) : Request

    companion object {
        private const val BASE = "content://com.android.contacts/"
        private val ID = Regex("""contacts/(\d+)""")

        /** The request of an intent, null when it is not one Contacts answers. [extras] holds what the intent carries. */
        fun parse(action: String?, data: String?, type: String?, extras: Map<String, Any?>): Request? = when (action) {
            "android.intent.action.PICK", "android.intent.action.GET_CONTENT" -> Pick(pickKind(data, type))
            "android.intent.action.INSERT", "android.intent.action.INSERT_OR_EDIT" -> Insert(prefill(extras))
            "android.intent.action.VIEW" -> ref(data)?.let(::View)
            "android.intent.action.EDIT" -> ref(data)?.let(::Edit)
            else -> null
        }

        private fun pickKind(data: String?, type: String?): PickKind = when {
            type?.contains("phone_v2") == true || data?.startsWith(BASE + "data/phones") == true -> PickKind.PHONE
            type?.contains("email_v2") == true || data?.startsWith(BASE + "data/emails") == true -> PickKind.EMAIL
            else -> PickKind.CONTACT
        }

        private fun ref(data: String?): ContactRef? {
            val path = data?.takeIf { it.startsWith(BASE) }?.removePrefix(BASE) ?: return null
            return when {
                path.startsWith("contacts/lookup/") -> ContactRef.Lookup(data)
                else -> ID.matchEntire(path)?.let { ContactRef.Id(it.groupValues[1].toLong()) }
            }
        }

        private fun prefill(x: Map<String, Any?>): Prefill {
            fun s(k: String) = (x[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val first = (x["phone_type"] as? Int)?.let(::phoneBase)
            val phones = listOfNotNull(s("phone")?.let { it to first }, s("secondary_phone")?.let { it to null }, s("tertiary_phone")?.let { it to null })
            return Prefill(s("name"), phones, listOfNotNull(s("email"), s("secondary_email"), s("tertiary_email")), s("company"), s("postal"), s("notes"))
        }

        /** Phone.TYPE_* to OS 7's kinds: home 1, mobile 2, work 3, work fax 4, pager 6; the others are no kind we know (D). */
        private fun phoneBase(type: Int): PhoneBase? = when (type) {
            1 -> PhoneBase.HOME
            2 -> PhoneBase.MOBILE
            3 -> PhoneBase.WORK
            4 -> PhoneBase.FAX
            6 -> PhoneBase.PAGER
            else -> null
        }
    }
}

/** The empty form of a new contact filled with what the other app gave, each in its box; the form then counts as changed. */
fun Editor.prefilled(p: Prefill): Editor {
    var e = this
    fun fill(key: FieldKey, text: String?, type: PhoneType? = null) {
        if (text == null) return
        val i = e.fields.indexOfLast { it.key == key }
        e = e.edited(i, text)
        if (type != null) e = e.typed(i, type)
    }
    p.name?.let { n ->
        val at = n.lastIndexOf(' ')
        if (at < 0) fill(FieldKey.FIRST, n) else { fill(FieldKey.FIRST, n.substring(0, at).trim()); fill(FieldKey.LAST, n.substring(at + 1)) }
    }
    fill(FieldKey.COMPANY, p.company)
    p.emails.forEach { fill(FieldKey.EMAIL, it) }
    p.phones.forEach { (n, base) -> fill(FieldKey.PHONE, n, base?.let { b -> PhoneType.entries.firstOrNull { it.base == b } }) }
    fill(FieldKey.ADDRESS, p.address)
    fill(FieldKey.NOTES, p.notes)
    return e
}
