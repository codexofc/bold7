package dev.bold7.contacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VCardTest {
    @Test fun aWholeCard() {
        val card = vCard(
            listOf(
                Stored(FieldKey.TITLE, 1, "Dr"), Stored(FieldKey.FIRST, 1, "Dexter"), Stored(FieldKey.LAST, 1, "Morgan"),
                Stored(FieldKey.COMPANY, 2, "Miami Metro"),
                Stored(FieldKey.PHONE, 3, "123456", PhoneBase.MOBILE),
                Stored(FieldKey.EMAIL, 4, "d@cn.com"),
                Stored(FieldKey.ADDRESS, 5, "1 Lab Street\nMiami, FL"),
                Stored(FieldKey.BIRTHDAY, 6, "1971-10-01"),
            ),
        )
        assertEquals(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Morgan;Dexter;;Dr;\r\nFN:Dr Dexter Morgan\r\nORG:Miami Metro\r\n" +
                "TEL;TYPE=CELL,VOICE:123456\r\nEMAIL;TYPE=INTERNET:d@cn.com\r\nADR;TYPE=HOME:;;1 Lab Street\\nMiami\\, FL;;;;\r\nBDAY:1971-10-01\r\nEND:VCARD\r\n",
            card,
        )
    }

    @Test fun aCompanyAloneIsItsName() = assertEquals("FN:Miami Metro", vCard(listOf(Stored(FieldKey.COMPANY, 1, "Miami Metro")))!!.lines()[3])

    @Test fun nothingToCallItByGivesNoCard() {
        assertNull(vCard(emptyList()))
        assertNull(vCard(listOf(Stored(FieldKey.PHONE, 1, "123", PhoneBase.HOME))))
    }

    @Test fun semicolonsAndBackslashesAreEscaped() {
        assertEquals("N:O\\;Neil;A\\\\B;;;", vCard(listOf(Stored(FieldKey.FIRST, 1, "A\\B"), Stored(FieldKey.LAST, 1, "O;Neil")))!!.lines()[2])
    }
}
