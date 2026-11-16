package dev.bold7.contacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestTest {
    private val authority = "content://com.android.contacts"

    private fun parse(action: String?, data: String? = null, type: String? = null, extras: Map<String, Any?> = emptyMap()) =
        Request.parse(action, data, type, extras)

    @Test fun aPickIsKnownByItsTypeOrItsUri() {
        assertEquals(Request.Pick(PickKind.CONTACT), parse("android.intent.action.PICK", "$authority/contacts", "vnd.android.cursor.dir/contact"))
        assertEquals(Request.Pick(PickKind.PHONE), parse("android.intent.action.PICK", "$authority/data/phones"))
        assertEquals(Request.Pick(PickKind.PHONE), parse("android.intent.action.PICK", null, "vnd.android.cursor.dir/phone_v2"))
        assertEquals(Request.Pick(PickKind.EMAIL), parse("android.intent.action.GET_CONTENT", null, "vnd.android.cursor.item/email_v2"))
        assertEquals(Request.Pick(PickKind.EMAIL), parse("android.intent.action.PICK", "$authority/data/emails"))
        // Nothing said: a contact.
        assertEquals(Request.Pick(PickKind.CONTACT), parse("android.intent.action.PICK"))
    }

    @Test fun viewAndEditNameTheContactByIdOrByLookupKey() {
        assertEquals(Request.View(ContactRef.Id(42)), parse("android.intent.action.VIEW", "$authority/contacts/42", "vnd.android.cursor.item/contact"))
        assertEquals(Request.Edit(ContactRef.Id(7)), parse("android.intent.action.EDIT", "$authority/contacts/7"))
        val lookup = "$authority/contacts/lookup/0r1-2A/9"
        assertEquals(Request.View(ContactRef.Lookup(lookup)), parse("android.intent.action.VIEW", lookup))
        assertNull(parse("android.intent.action.VIEW", "$authority/contacts/as_vcard"))
        assertNull(parse("android.intent.action.VIEW", "https://example.com/contacts/42"))
        assertNull(parse("android.intent.action.VIEW"))
    }

    @Test fun anInsertReadsTheExtrasOfAndroidsContract() {
        val r = parse(
            "android.intent.action.INSERT_OR_EDIT", null, "vnd.android.cursor.item/contact",
            mapOf("name" to "Ada Lovelace", "phone" to "0601", "phone_type" to 2, "secondary_phone" to "0602", "email" to "ada@x.org", "company" to "Analytical", "postal" to "1 rue X", "notes" to "n", "junk" to 3),
        ) as Request.Insert
        assertEquals(Prefill(name = "Ada Lovelace", phones = listOf("0601" to PhoneBase.MOBILE, "0602" to null), emails = listOf("ada@x.org"), company = "Analytical", address = "1 rue X", notes = "n"), r.prefill)
        assertEquals(Request.Insert(Prefill()), parse("android.intent.action.INSERT", "$authority/contacts"))
    }

    @Test fun extrasOfTheWrongTypeOrBlankAreIgnored() {
        val r = parse("android.intent.action.INSERT", extras = mapOf("name" to 5, "phone" to "  ", "email" to "a@b.c", "phone_type" to "x")) as Request.Insert
        assertEquals(Prefill(emails = listOf("a@b.c")), r.prefill)
    }

    @Test fun otherActionsAreNotOurs() {
        assertNull(parse("android.intent.action.MAIN"))
        assertNull(parse(null))
    }

    @Test fun aPrefilledFormFillsTheEmptyFieldsAndCountsAsChanged() {
        val e = Editor.of(emptyList()).prefilled(Prefill(name = "Ada Lovelace", phones = listOf("0601" to PhoneBase.MOBILE, "0602" to null), emails = listOf("a@b.c"), company = "Co", address = "1 rue X", notes = "n"))
        fun values(k: FieldKey) = e.fields.filter { it.key == k }.map { it.value }
        assertEquals(listOf("Ada"), values(FieldKey.FIRST))
        assertEquals(listOf("Lovelace"), values(FieldKey.LAST))
        assertEquals(listOf("0601", "0602", ""), values(FieldKey.PHONE))
        assertEquals(PhoneType.MOBILE, e.fields.first { it.key == FieldKey.PHONE }.type)
        assertEquals(listOf("a@b.c", ""), values(FieldKey.EMAIL))
        assertEquals(listOf("Co"), values(FieldKey.COMPANY))
        assertEquals(listOf("1 rue X", ""), values(FieldKey.ADDRESS))
        assertEquals(listOf("n"), values(FieldKey.NOTES))
        assertTrue(e.changed)
        // A one-word name is a first name.
        assertEquals("Ada", Editor.of(emptyList()).prefilled(Prefill(name = "Ada")).fields.first { it.key == FieldKey.FIRST }.value)
        // Nothing to fill: the form is as it was, unchanged.
        assertTrue(!Editor.of(emptyList()).prefilled(Prefill()).changed)
    }
}
