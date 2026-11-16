package dev.bold7.messages

import java.io.ByteArrayOutputStream

/**
 * The few messages of com.google.android.apps.messaging.externalapi.proto.ExternalMessagingApi
 * Messages of Bold7 needs, written by hand from the newMessageInfo of Messages 20261027
 * (including DeleteMessage and MarkMessagesAsRead): no protobuf library.
 */
object Proto {
    /**
     * SendMessageRequest: 1 int64 thread_id, 2 int32 sub_id, 3 text, 4 subject, 5 repeated
     * attachments, 6 request id (a UUID, required). An attachment ([attachment], its type and its
     * URI) is arlc in 20261106: 1 content type, 3 URI; Messages refuses an empty URI and a URI its
     * security filter does not allow. An empty text is left out, as
     * proto3 does: Messages takes text, subject or attachment.
     */
    fun sendMessage(threadId: Long, subId: Int, text: String, requestId: String, attachment: Pair<String, String>? = null): ByteArray = message {
        varint(1, threadId)
        varint(2, subId.toLong())
        if (text.isNotEmpty()) string(3, text)
        attachment?.let { (type, uri) -> bytes(5, message { string(1, type); string(3, uri) }) }
        string(6, requestId)
    }

    /** MarkConversationAsReadRequest: 1 int64 thread_id (Telephony's). */
    fun markConversationAsRead(threadId: Long): ByteArray = message { varint(1, threadId) }

    /** CreateOneOnOneConversationRequest: 1 destination (a phone number); the answer's field 1 is the thread_id. */
    fun createOneOnOne(destination: String): ByteArray = message { string(1, destination) }

    /**
     * CreateMmsGroupConversationRequest (arjt in 20261106): 1 repeated string, the
     * destinations; the answer is CreateOneOnOne's (arjr, 1 thread_id). Messages refuses one
     * destination ("Must specify more than one destination for group"), an empty one and the same
     * one twice ("Duplicate destinations", arnx, army.b).
     */
    fun createMmsGroup(destinations: List<String>): ByteArray = message { destinations.forEach { string(1, it) } }

    /**
     * DeleteMessageRequest (aqsm in 20261027): 1 messageUri, Telephony's content://sms/<id> or
     * content://mms/<id>, which Messages finds in its own store and deletes ("messageUri is not
     * valid", "Message not found in Bugle" otherwise; aqwr, aqxa.q).
     */
    fun deleteMessage(uri: String): ByteArray = message { string(1, uri) }

    /** MarkMessagesAsReadRequest (aqtn): 1 repeated string, the messages' Telephony URIs as DeleteMessage takes them (D: read from its type only). */
    fun markMessagesAsRead(uris: List<String>): ByteArray = message { uris.forEach { string(1, it) } }

    /** GetServiceCapabilitiesResponse: 1 repeated enum Capability, packed or not. */
    fun capabilities(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        Reader(bytes).fields { field, wire, r ->
            when {
                field == 1 && wire == 0 -> out += r.varint().toInt()
                field == 1 && wire == 2 -> Reader(r.bytes()).run { while (more()) out += varint().toInt() }
                else -> r.skip(wire)
            }
        }
        return out
    }

    /** The first varint of [field] (a bool, an int64), or null when proto3 left it out (false, 0). */
    fun varintField(bytes: ByteArray, field: Int): Long? {
        var found: Long? = null
        Reader(bytes).fields { f, wire, r -> if (f == field && wire == 0 && found == null) found = r.varint() else r.skip(wire) }
        return found
    }

    private fun message(build: Writer.() -> Unit) = Writer().apply(build).out.toByteArray()

    private class Writer {
        val out = ByteArrayOutputStream()
        fun raw(v: Long) {
            var x = v
            while (x and 0x7fL.inv() != 0L) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        fun varint(field: Int, v: Long) { raw((field shl 3).toLong()); raw(v) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
        fun bytes(field: Int, b: ByteArray) {
            raw((field shl 3 or 2).toLong()); raw(b.size.toLong()); out.write(b)
        }
    }

    private class Reader(private val b: ByteArray) {
        private var i = 0
        fun more() = i < b.size
        fun varint(): Long {
            var v = 0L; var shift = 0
            while (true) {
                val x = b[i++].toInt()
                v = v or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return v
                shift += 7
            }
        }
        fun bytes(): ByteArray { val n = varint().toInt(); return b.copyOfRange(i, i + n).also { i += n } }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> i += 8
                2 -> bytes()
                5 -> i += 4
                else -> error("wire type $wire")
            }
        }
        fun fields(each: (field: Int, wire: Int, r: Reader) -> Unit) {
            while (more()) { val tag = varint().toInt(); each(tag ushr 3, tag and 7, this) }
        }
    }
}

/** Capability enum of GetServiceCapabilitiesResponse (aqta in 20261027). */
private val CAPABILITIES = listOf(
    "UNKNOWN_CAPABILITY", "MARK_CONVERSATION_AS_READ", "MARK_MESSAGES_AS_READ", "SEND_MESSAGE",
    "SCHEDULE_MESSAGE", "CREATE_ONO_ON_ONE_CONVERSATION", "GET_XMS_CONFIGS", "DELETE_MESSAGE",
    "GET_USER_CONSENT_SETTING", "GET_APP_SETTINGS", "CREATE_MMS_GROUP_CONVERSATION",
    "GET_THREAD_IDS_FOR_ALL_RCS_GROUPS", "GET_RCS_GROUP_METADATA", "SEARCH_MESSAGE",
)

fun capabilityName(n: Int): String = CAPABILITIES.getOrNull(n) ?: "?$n"
