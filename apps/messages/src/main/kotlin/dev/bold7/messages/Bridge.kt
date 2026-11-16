package dev.bold7.messages

import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import kotlinx.coroutines.delay

// Google Messages' ExternalMessagingApiService as Messages of Bold7 uses it: whether its door is open, and what an action of the inbox asks of it. Pure; the gRPC
// client is GrpcMessagingApi.

/** GetServiceCapabilities' enum Capability (aqta in 20261027), the methods used here. */
object Capability {
    const val MARK_CONVERSATION_AS_READ = 1
    const val MARK_MESSAGES_AS_READ = 2
    const val SEND_MESSAGE = 3
    const val CREATE_ONE_ON_ONE_CONVERSATION = 5
    const val DELETE_MESSAGE = 7

    /** Without one of these the thread cannot work: Open goes back to Google Messages. */
    val REQUIRED = setOf(MARK_CONVERSATION_AS_READ, SEND_MESSAGE, CREATE_ONE_ON_ONE_CONVERSATION, DELETE_MESSAGE)
}

/** Why the door is shut, for the status message and the log. */
enum class Why {
    /** EXTERNAL_MESSAGING_API not granted to Messages of Bold7 (tools/install.sh grants it). */
    NO_PERMISSION,

    /** "Messaging API service disabled", or no service to bind: the bridge is not in, or Google Messages changed. */
    CLOSED,

    /** "Allow Bixby and text on Galaxy devices" is off in Google Messages (tools/install.sh turns it on). */
    NO_CONSENT,

    /** Our signature is not let in: the bridge's whitelist no longer works. */
    REFUSED,

    /** A method Bold7 uses is gone from GetServiceCapabilities: Google changed the contract. */
    CONTRACT,

    /** Anything else; its gRPC code is logged. */
    FAILED,
}

sealed interface ApiState {
    data object Open : ApiState
    data class Shut(val why: Why) : ApiState
}

/** The contract check (a gate): every method Bold7 uses is announced. */
fun contract(capabilities: List<Int>): ApiState =
    if (capabilities.containsAll(Capability.REQUIRED)) ApiState.Open else ApiState.Shut(Why.CONTRACT)

/**
 * Why GetServiceCapabilities failed with gRPC [code], given what GetUserConsentSetting answered
 * ([consent], null when it failed too): it answers with the switch on and without the consent.
 */
fun why(code: String, consent: Boolean?): Why = when {
    consent == false -> Why.NO_CONSENT
    code == "UNAVAILABLE" -> Why.CLOSED
    code == "PERMISSION_DENIED" || code == "UNAUTHENTICATED" -> Why.REFUSED
    else -> Why.FAILED
}

/**
 * Whether [this] reason is known for sure: CLOSED (no service yet, UNAVAILABLE) and FAILED
 * (DEADLINE_EXCEEDED among others) may only be Google Messages' process starting cold, its
 * endpoint created once bound (gkmd, seen on the Q25).
 */
val Why.certain get() = this != Why.CLOSED && this != Why.FAILED

/**
 * The door as [probe] (GrpcMessagingApi.state) answers it, asked again up to [tries] times in all,
 * [pauseMs] apart, while it is shut for a reason that is not [certain]; the last answer otherwise.
 * Suspends between two: the caller's thread stays free and the screen answers meanwhile.
 */
suspend fun awaitDoor(tries: Int, pauseMs: Long, probe: suspend () -> ApiState): ApiState {
    var state = probe()
    repeat(tries - 1) {
        if (state !is ApiState.Shut || (state as ApiState.Shut).why.certain) return state
        delay(pauseMs)
        state = probe()
    }
    return state
}

/** One call to Google Messages. */
sealed interface ApiCall {
    data class MarkConversationRead(val threadId: Long) : ApiCall
    data class MarkMessagesRead(val uris: List<String>) : ApiCall
    data class Delete(val uri: String) : ApiCall
}

/** Telephony's URI of an SMS or MMS item of the Hub (sms:<id>, mms:<id>), as DeleteMessage takes it; null for any other. */
fun telephonyUri(sourceKey: String): String? {
    val table = sourceKey.substringBefore(':')
    if (table != "sms" && table != "mms") return null
    val id = sourceKey.substringAfter(':').toLongOrNull() ?: return null
    return "content://$table/$id"
}

private val HubItem.isText get() = kind == ItemKind.SMS || kind == ItemKind.MMS

/**
 * What an action of the inbox on [items] (those the Hub writes, Actions.writeKeys) asks of Google
 * Messages, so that its state follows (they were first local only): Mark Read marks each
 * conversation read; Mark Prior Read, its unread messages; Delete and Delete Prior delete each
 * message ("to delete all the messages from the history and close the chat, in your list of text
 * messages, highlight a message > Delete", guide 7.1 p. 105, B). Mark Unread has no method: it
 * stays the Hub's. Calls, notifications and invitations are not Messages'.
 */
fun apiCalls(act: Act, items: List<HubItem>): List<ApiCall> {
    val texts = items.filter { it.isText }
    return when (act) {
        Act.MARK_READ -> texts.mapNotNull { it.conversationId?.toLongOrNull() }.distinct().map(ApiCall::MarkConversationRead)
        Act.MARK_PRIOR_READ -> texts.filter { !it.read }.mapNotNull { telephonyUri(it.sourceKey) }
            .takeIf { it.isNotEmpty() }?.let { listOf(ApiCall.MarkMessagesRead(it)) }.orEmpty()
        Act.DELETE, Act.DELETE_PRIOR -> texts.mapNotNull { telephonyUri(it.sourceKey) }.map(ApiCall::Delete)
        else -> emptyList()
    }
}
