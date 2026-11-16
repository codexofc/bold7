package dev.bold7.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecipientsTest {
    private val batman = Contact("Batman", listOf(PhoneNumber("Mobile", "+33 1 99 00 12 34")))
    private val robin = Contact("Dick Grayson", listOf(PhoneNumber("Mobile", "0199001111"), PhoneNumber("Work", "0199002222")))
    private val eloise = Contact("Éloïse Martin", listOf(PhoneNumber("Home", "0199003333")))
    private val contacts = listOf(batman, robin, eloise)

    @Test fun aRecipientShowsItsNameAndTypeOrItsNumber() {
        assertEquals("Batman (Mobile)", batman.recipient(batman.numbers[0]).shown)
        assertEquals("0199", Recipient("0199").shown)
        assertEquals("Batman", Recipient("0199", "Batman").shown)
    }

    @Test fun aNameTypedProposesTheContactsWhoseWordsStartWithIt() {
        assertEquals(listOf(batman), suggestions(contacts, "bat"))
        assertEquals(listOf(robin), suggestions(contacts, "d g"))
        assertEquals(listOf(eloise), suggestions(contacts, "elo"))
        // A number, or nothing typed, proposes nothing: To takes the number.
        assertEquals(emptyList(), suggestions(contacts, "01"))
        assertEquals(emptyList(), suggestions(contacts, " "))
        assertEquals(emptyList(), suggestions(contacts, "zorro"))
    }

    @Test fun aNumberIsDigitsAndDiallingMarks() {
        assertEquals("+33 1 99-00", dialable(" +33 1 99-00 "))
        assertEquals("(01) 23.45", dialable("(01) 23.45"))
        assertNull(dialable("Bat"))
        assertNull(dialable("+-"))
        assertNull(dialable(""))
    }

    @Test fun aContactWithOneNumberIsChosenAtOnceSeveralAsk() {
        assertEquals(ToChoice.One(Recipient("+33 1 99 00 12 34", "Batman", "Mobile")), choice(batman))
        assertEquals(ToChoice.Numbers(robin), choice(robin))
    }

    @Test fun theSameNumberWrittenTwoWays() {
        assertEquals(true, sameNumber("+33 1 99 00 12 34", "01.99.00.12.34"))
        assertEquals(false, sameNumber("0199001234", "0199001235"))
        // A short code matches whole only.
        assertEquals(false, sameNumber("36179", "636179"))
    }

    @Test fun anExistingConversationIsFoundByItsOnlyMember() {
        val threads = listOf(
            3L to listOf("+33199001234", "+33199001111"), // a group: never a one-to-one's
            5L to listOf("+33199001234"),
            9L to listOf("0199001234"), // older, the same person
        )
        assertEquals(5L, threadFor("01 99 00 12 34", threads))
        assertNull(threadFor("0199009999", threads))
        assertNull(threadFor("0199001111", threads))
    }

    @Test fun theTypesOfAndroidAsOs7NamesThem() {
        assertEquals("Mobile", phoneLabel(2))
        assertEquals("Home", phoneLabel(1))
        assertEquals("Work", phoneLabel(3))
        assertEquals("Work Fax", phoneLabel(4))
        assertEquals("Pager", phoneLabel(6))
        assertEquals("Other", phoneLabel(7))
    }
}
