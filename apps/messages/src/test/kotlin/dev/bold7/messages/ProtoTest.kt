package dev.bold7.messages

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class ProtoTest {
    @Test fun sendMessageFieldsInOrder() {
        // thread_id 300 (1), sub_id 1 (2), text "hi" (3), request id "u" (6); subject left out.
        assertEquals("08ac02" + "1001" + "1a026869" + "320175", hex(Proto.sendMessage(threadId = 300, subId = 1, text = "hi", requestId = "u")))
    }

    @Test fun negativeSubIdTakesTenBytes() {
        // int32 -1, Android's "no subscription", sign-extended as protobuf does.
        assertEquals("0801" + "10ffffffffffffffffff01" + "1a0161" + "320175", hex(Proto.sendMessage(threadId = 1, subId = -1, text = "a", requestId = "u")))
    }

    @Test fun utf8Text() {
        assertContentEquals(bytes("0801" + "1001" + "1a02c3a9" + "320175"), Proto.sendMessage(1, 1, "é", "u"))
    }

    @Test fun anAttachmentIsItsTypeThenItsUri() {
        // 5: Attachment (arlc in 20261106): 1 content type, 3 URI; then the request id (6).
        assertEquals(
            "0801" + "1001" + "2a0a" + "0a03612f62" + "1a03753a76" + "320175",
            hex(Proto.sendMessage(threadId = 1, subId = 1, text = "", requestId = "u", attachment = "a/b" to "u:v")),
        )
    }

    @Test fun markConversationAsRead() {
        assertEquals("082a", hex(Proto.markConversationAsRead(42)))
    }

    @Test fun createOneOnOne() {
        assertEquals("0a03313233", hex(Proto.createOneOnOne("123")))
    }

    @Test fun createMmsGroupRepeatsItsDestinations() {
        assertEquals("0a0131" + "0a023232", hex(Proto.createMmsGroup(listOf("1", "22"))))
    }

    @Test fun capabilitiesPackedOrNot() {
        assertEquals(listOf(1, 3, 13), Proto.capabilities(bytes("0a0301030d")))
        assertEquals(listOf(1, 3), Proto.capabilities(bytes("08010803")))
        assertEquals(emptyList(), Proto.capabilities(ByteArray(0)))
    }

    @Test fun unknownFieldsAreSkipped() {
        // field 2 string "x", field 3 fixed32, field 4 fixed64, then field 1 = 7.
        assertEquals(listOf(7), Proto.capabilities(bytes("120178" + "1d00000000" + "210000000000000000" + "0807")))
    }

    @Test fun firstVarint() {
        assertEquals(1L, Proto.varintField(bytes("0801"), 1))
        assertEquals(300L, Proto.varintField(bytes("120178" + "08ac02"), 1))
        assertNull(Proto.varintField(ByteArray(0), 1)) // proto3: false or 0 is not on the wire
    }

    @Test fun deleteMessageNamesTheTelephonyUri() {
        // DeleteMessageRequest (aqsm in 20261027): 1 string messageUri, content://sms/… or content://mms/….
        assertEquals("0a0d" + "636f6e74656e743a2f2f736d73", hex(Proto.deleteMessage("content://sms")))
    }

    @Test fun markMessagesAsReadRepeatsTheUris() {
        // MarkMessagesAsReadRequest (aqtn): 1 repeated string.
        assertEquals("0a0161" + "0a0162", hex(Proto.markMessagesAsRead(listOf("a", "b"))))
    }

    @Test fun capabilityNames() {
        assertEquals("SEND_MESSAGE", capabilityName(3))
        assertEquals("SEARCH_MESSAGE", capabilityName(13))
        assertEquals("?99", capabilityName(99))
    }
}
