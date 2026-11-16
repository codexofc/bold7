package dev.bold7.hub.data

import kotlin.test.Test
import kotlin.test.assertEquals

class MailContractTest {
    @Test
    fun `an address crosses with its name, spaces and all, or alone`() {
        assertEquals("renee@example.org Renée de la Tour" , MailContract.address("renee@example.org", "Renée de la Tour"))
        assertEquals("renee@example.org" to "Renée de la Tour", MailContract.parse(MailContract.address("renee@example.org", "Renée de la Tour")))
        assertEquals("bob@example.org" to null, MailContract.parse(MailContract.address("bob@example.org", " ")))
    }
}
