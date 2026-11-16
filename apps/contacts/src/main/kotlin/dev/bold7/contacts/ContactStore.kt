package dev.bold7.contacts

import android.graphics.Bitmap
import dev.bold7.showcase.Showcase
import dev.bold7.showcase.isAvatar
import dev.bold7.showcase.pick
import dev.bold7.showcase.Person as Someone

/** What Contacts reads and writes of the phone's contacts, or asks of Telecom: off the main thread only. */
interface ContactStore {
    fun people(): List<Person>

    fun card(id: Long): Card?

    fun stored(id: Long): List<Stored>

    /** The contact's id, null when refused. */
    fun save(id: Long?, changes: List<Change>): Long?

    fun delete(id: Long): Boolean

    fun thumbnail(uri: String): Bitmap?

    /** Whether the call went to Telecom. */
    fun call(number: String): Boolean
}

/**
 * The phone's contacts, or the showcase's made-up people when [showcase] is on: the real store is
 * not even built then. [picture] draws a showcase avatar's URI.
 */
fun contactStore(showcase: Boolean, real: () -> ContactStore, picture: (String) -> Bitmap?): ContactStore =
    pick(showcase, real) { ShowcaseContacts(picture) }

/** Showcase.people in memory: every write refused or dropped, nothing called. */
private class ShowcaseContacts(private val picture: (String) -> Bitmap?) : ContactStore {
    override fun people() = Showcase.people.map { Person(it.id, it.name, it.mobile, it.email, it.photo) }.sortedBy { it.name }

    override fun card(id: Long): Card? {
        val p = Showcase.people.firstOrNull { it.id == id } ?: return null
        val company = listOf(p.jobTitle, p.company).filter { it.isNotEmpty() }.joinToString(", ").takeIf { p.company.isNotEmpty() }.orEmpty()
        return Card(id, p.name, company, details(p).map { (key, label, value, dataId) -> Detail(kindOf(key), label, value, dataId) }, p.groups, p.photo)
    }

    override fun stored(id: Long): List<Stored> {
        val p = Showcase.people.firstOrNull { it.id == id } ?: return emptyList()
        val name = id * 100
        return listOf(Stored(FieldKey.FIRST, name, p.first), Stored(FieldKey.LAST, name, p.last), Stored(FieldKey.COMPANY, name + 1, p.company)) +
            details(p).map { (key, label, value, dataId) ->
                Stored(key, dataId, value, if (key == FieldKey.PHONE) PhoneType.entries.first { it.label == label }.base else null)
            }
    }

    override fun save(id: Long?, changes: List<Change>) = id

    override fun delete(id: Long) = false

    override fun thumbnail(uri: String) = if (isAvatar(uri)) picture(uri) else null

    // Simplification (showcase data): the call pretends to go (Contacts goes on as after a real one); nothing is placed.
    override fun call(number: String) = true

    private data class Value(val key: FieldKey, val label: String, val value: String, val dataId: Long)

    /** A person's numbers, email and address, each with its label and a made-up row id. */
    private fun details(p: Someone): List<Value> {
        val numbers = listOfNotNull(p.mobile to "Mobile", p.work?.to("Work"), p.home?.to("Home"))
            .map { (n, label) -> FieldKey.PHONE to (label to n) }
        val rest = listOfNotNull(p.email?.let { FieldKey.EMAIL to ("Email" to it) },
            p.address?.let { FieldKey.ADDRESS to ((if (p.company.isNotEmpty()) "Work" else "Home") to it) })
        return (numbers + rest).mapIndexed { i, (key, v) -> Value(key, v.first, v.second, p.id * 100 + 2 + i) }
    }

    private fun kindOf(key: FieldKey) = when (key) {
        FieldKey.PHONE -> Kind.PHONE
        FieldKey.EMAIL -> Kind.EMAIL
        else -> Kind.ADDRESS
    }
}
