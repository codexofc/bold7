package dev.bold7.contacts

import dev.bold7.showcase.Showcase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShowcaseContactsTest {
    private val pictures = ArrayList<String>()
    private val store = contactStore(showcase = true, real = { error("real contacts opened") }, picture = { pictures += it; null })

    @Test
    fun theListIsThePeopleOfTheShowcaseWithTheirAvatars() {
        val p = store.people()
        assertEquals(Showcase.people.size, p.size)
        assertEquals(p.sortedBy { it.name }, p)
        val olivia = p.first { it.id == 1L }
        assertEquals(Person(1, "Olivia Bennett", "+1 519-555-0101", "olivia.bennett@example.com", "showcase:avatar/1"), olivia)
    }

    @Test
    fun aCardHasEveryDetailWithItsLabel() {
        val c = store.card(1)!!
        assertEquals("Olivia Bennett", c.name)
        assertEquals("Project Lead, Northgate Studio", c.company)
        assertEquals(listOf("Mobile" to "+1 519-555-0101", "Work" to "+1 519-555-0151", "Email" to "olivia.bennett@example.com",
            "Work" to "48 King Street West, Waterloo"), c.ordered.map { it.label to it.value })
        assertEquals(listOf("Work"), c.groups)
        assertEquals("showcase:avatar/1", c.photo)
        assertEquals(c.details.size, c.details.map { it.dataId }.toSet().size)
        assertEquals(listOf("Home" to "12 Maple Crescent, Guelph"), store.card(5)!!.details.filter { it.kind == Kind.ADDRESS }.map { it.label to it.value })
        assertEquals("", store.card(2)!!.company)
        assertNull(store.card(999))
    }

    @Test
    fun theFormShowsTheSameValues() {
        val s = store.stored(1)
        assertEquals("Olivia", s.single { it.key == FieldKey.FIRST }.value)
        assertEquals("Bennett", s.single { it.key == FieldKey.LAST }.value)
        assertEquals(listOf(PhoneBase.MOBILE, PhoneBase.WORK), s.filter { it.key == FieldKey.PHONE }.map { it.base })
        assertTrue(s.any { it.key == FieldKey.EMAIL } && s.any { it.key == FieldKey.ADDRESS })
    }

    @Test
    fun nothingIsWrittenNorCalled() {
        val before = store.card(1)
        assertEquals(1L, store.save(1, listOf(Change.Remove(100))))
        assertNull(store.save(null, emptyList()), "no contact is created")
        assertEquals(false, store.delete(1))
        assertTrue(store.call("+1 519-555-0101"), "a call pretends to go, nothing is placed")
        assertEquals(before, store.card(1))
        assertEquals(Showcase.people.size, store.people().size)
    }

    @Test
    fun onlyTheAvatarsAreDrawn() {
        store.thumbnail("showcase:avatar/3")
        assertNull(store.thumbnail("content://com.android.contacts/contacts/3/photo"))
        assertEquals(listOf("showcase:avatar/3"), pictures)
    }
}
