package dev.bold7.contacts

import dev.bold7.ui.textActs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorTest {
    private val dexter = listOf(
        Stored(FieldKey.FIRST, 1, "Dexter"),
        Stored(FieldKey.LAST, 1, "Morgan"),
        Stored(FieldKey.TITLE, 1, ""),
        Stored(FieldKey.PHONE, 2, "123456", PhoneBase.MOBILE),
        Stored(FieldKey.PHONE, 3, "654321", PhoneBase.MOBILE),
        Stored(FieldKey.EMAIL, 4, "dexter@cn.com"),
    )

    private fun Editor.index(key: FieldKey, n: Int = 0) = fields.withIndex().filter { it.value.key == key }[n].index

    private fun Editor.set(key: FieldKey, text: String, n: Int = 0) = edited(index(key, n), text)

    @Test fun aNewContactShowsOs7sFieldsEmpty() {
        // sim-191: Title, First, Last, Company; one Email; one number typed Work; then D: Address,
        // and C: Website, Notes.
        val e = Editor.of(emptyList())
        assertEquals(
            listOf(FieldKey.TITLE, FieldKey.FIRST, FieldKey.LAST, FieldKey.COMPANY, FieldKey.EMAIL, FieldKey.PHONE, FieldKey.ADDRESS, FieldKey.BIRTHDAY, FieldKey.ANNIVERSARY, FieldKey.WEBSITE, FieldKey.NOTES),
            e.fields.map { it.key },
        )
        assertEquals(PhoneType.WORK, e.fields.single { it.key == FieldKey.PHONE }.type)
        assertTrue(e.fields.all { it.value.isEmpty() && it.dataId == null })
        assertFalse(e.changed)
    }

    @Test fun storedNumbersKeepTheirRowsAndGetOs7sTypes() {
        val e = Editor.of(dexter)
        assertEquals(
            listOf(Triple("123456", 2L, PhoneType.MOBILE), Triple("654321", 3L, PhoneType.MOBILE_2), Triple("", null, PhoneType.WORK)),
            e.fields.filter { it.key == FieldKey.PHONE }.map { Triple(it.value, it.dataId, it.type) },
        )
        assertEquals(listOf("dexter@cn.com", ""), e.fields.filter { it.key == FieldKey.EMAIL }.map { it.value })
    }

    @Test fun theSecondOfAKindIsItsTwoAndAFaxIsWorkFax() {
        assertEquals(
            listOf(PhoneType.WORK, PhoneType.HOME, PhoneType.WORK_2, PhoneType.WORK_2, PhoneType.WORK_FAX, PhoneType.OTHER),
            phoneTypes(listOf(PhoneBase.WORK, PhoneBase.HOME, PhoneBase.WORK, PhoneBase.WORK, PhoneBase.FAX, PhoneBase.OTHER)),
        )
    }

    @Test fun fillingTheLastEmptyEmailBringsAnother() {
        // sim-185 then the capture after it (C): a second Email: under the one typed.
        val e = Editor.of(emptyList()).set(FieldKey.EMAIL, "a@b.c")
        assertEquals(listOf("a@b.c", ""), e.fields.filter { it.key == FieldKey.EMAIL }.map { it.value })
        // Typing more into it brings no third.
        assertEquals(2, e.set(FieldKey.EMAIL, "a@b.cd").fields.count { it.key == FieldKey.EMAIL })
    }

    @Test fun aNewNumberTakesTheFirstTypeNotUsed() {
        val e = Editor.of(emptyList()).set(FieldKey.PHONE, "1")
        assertEquals(listOf(PhoneType.WORK, PhoneType.HOME), e.fields.filter { it.key == FieldKey.PHONE }.map { it.type })
    }

    @Test fun nothingChangedSavesNothing() {
        val e = Editor.of(dexter)
        assertFalse(e.changed)
        assertEquals(emptyList(), e.changes())
    }

    @Test fun aChangedNumberIsUpdatedInItsRowKeepingItsKind() {
        val e = Editor.of(dexter).set(FieldKey.PHONE, "777", n = 1)
        assertTrue(e.changed)
        assertEquals(listOf(Change.Write(Group.PHONE, 3, mapOf(FieldKey.PHONE to "777"))), e.changes())
    }

    @Test fun aNumbersTypeChangedIsWritten() {
        val e = Editor.of(dexter).let { it.typed(it.index(FieldKey.PHONE, 1), PhoneType.HOME) }
        assertEquals(listOf(Change.Write(Group.PHONE, 3, mapOf(FieldKey.PHONE to "654321"), PhoneType.HOME)), e.changes())
    }

    @Test fun anEmptiedFieldRemovesItsRow() =
        assertEquals(listOf(Change.Remove(4)), Editor.of(dexter).set(FieldKey.EMAIL, "  ").changes())

    @Test fun aFilledEmptyNumberIsInsertedWithItsType() =
        assertEquals(
            listOf(Change.Write(Group.PHONE, null, mapOf(FieldKey.PHONE to "999"), PhoneType.WORK)),
            Editor.of(dexter).set(FieldKey.PHONE, " 999 ", n = 2).changes(),
        )

    @Test fun titleFirstAndLastShareTheNameRow() {
        assertEquals(
            listOf(Change.Write(Group.NAME, 1, mapOf(FieldKey.TITLE to "", FieldKey.FIRST to "Dex", FieldKey.LAST to "Morgan"))),
            Editor.of(dexter).set(FieldKey.FIRST, "Dex").changes(),
        )
        assertEquals(listOf(Change.Remove(1)), Editor.of(dexter).set(FieldKey.FIRST, "").set(FieldKey.LAST, "").changes())
    }

    @Test fun aNewContactWritesItsNameOnceAndItsCompany() {
        val e = Editor.of(emptyList()).set(FieldKey.LAST, "Doakes").set(FieldKey.COMPANY, "Miami Metro")
        assertEquals(
            listOf(
                Change.Write(Group.NAME, null, mapOf(FieldKey.TITLE to "", FieldKey.FIRST to "", FieldKey.LAST to "Doakes")),
                Change.Write(Group.ORGANIZATION, null, mapOf(FieldKey.COMPANY to "Miami Metro")),
            ),
            e.changes(),
        )
    }

    @Test fun anEmptyNewContactIsNotSaved() {
        assertTrue(Editor.of(emptyList()).set(FieldKey.FIRST, " ").empty)
        assertFalse(Editor.of(emptyList()).set(FieldKey.FIRST, "A").empty)
    }

    @Test fun savingKeepsWhatIsShownAsTheOriginal() {
        val e = Editor.of(dexter).set(FieldKey.PHONE, "1").let { it.typed(it.index(FieldKey.PHONE), PhoneType.PAGER) }.saved()
        assertFalse(e.changed)
    }
}

class DatesTest {
    @Test fun theFormHasTheBirthdayAndTheAnniversaryAfterTheAddresses() {
        val e = Editor.of(listOf(Stored(FieldKey.BIRTHDAY, 7, "1971-10-01")))
        val keys = e.fields.map { it.key }
        assertEquals(listOf(FieldKey.ADDRESS, FieldKey.BIRTHDAY, FieldKey.ANNIVERSARY, FieldKey.WEBSITE), keys.subList(keys.indexOf(FieldKey.ADDRESS), keys.indexOf(FieldKey.ADDRESS) + 4))
        assertEquals("1971-10-01", e.fields.first { it.key == FieldKey.BIRTHDAY }.value)
    }

    @Test fun aDateSetWritesItsOwnRowAndAClearedOneRemovesIt() {
        val e = Editor.of(listOf(Stored(FieldKey.BIRTHDAY, 7, "1971-10-01")))
        val b = e.fields.indexOfFirst { it.key == FieldKey.BIRTHDAY }
        val a = e.fields.indexOfFirst { it.key == FieldKey.ANNIVERSARY }
        assertEquals(listOf(Change.Write(Group.DATE, 7, mapOf(FieldKey.BIRTHDAY to "1980-01-02"))), e.edited(b, "1980-01-02").changes())
        assertEquals(listOf(Change.Remove(7)), e.edited(b, "").changes())
        assertEquals(listOf(Change.Write(Group.DATE, null, mapOf(FieldKey.ANNIVERSARY to "2001-05-06"))), e.edited(a, "2001-05-06").changes())
        assertEquals(emptyList(), e.changes())
    }

    @Test fun datesAreShownAsOs7WritesThem() {
        assertEquals("Oct 1, 1971", dateLabel("1971-10-01"))
        assertEquals("Oct 1", dateLabel("--10-01"))
        assertEquals("sometime", dateLabel("sometime"))
    }

    @Test fun theFormMenuHasTheSelectionEntriesAfterSave() {
        assertEquals(
            listOf(ContactAct.SWITCH, ContactAct.SAVE, ContactAct.START_SELECTION, ContactAct.HELP, ContactAct.CLOSE),
            contactMenu(onDate = false, text = textActs(selected = false, canPaste = false)).entries.map { it.action },
        )
        assertEquals(
            listOf(ContactAct.SWITCH, ContactAct.SAVE, ContactAct.CUT, ContactAct.COPY, ContactAct.PASTE, ContactAct.CLEAR, ContactAct.HELP, ContactAct.CLOSE),
            contactMenu(onDate = true, text = textActs(selected = true, canPaste = true)).entries.map { it.action },
        )
        // On a button of the form (a date, a type), no field: none of them.
        val button = contactMenu(onDate = true, text = emptyList())
        assertEquals(listOf(ContactAct.SWITCH, ContactAct.SAVE, ContactAct.CLEAR, ContactAct.HELP, ContactAct.CLOSE), button.entries.map { it.action })
        assertEquals(ContactAct.SAVE, button.entries[button.highlighted!!].action)
    }
}
