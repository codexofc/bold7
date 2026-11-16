package dev.bold7.messages

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SubscriptionManager
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.StatusRuntimeException
import io.grpc.binder.AndroidComponentAddress
import io.grpc.binder.BinderChannelBuilder
import io.grpc.binder.SecurityPolicies
import io.grpc.stub.ClientCalls
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
private const val SERVICE = "$GOOGLE_MESSAGES.externalapi.service.ExternalMessagingApiService"
private const val API = "$GOOGLE_MESSAGES.externalapi.proto.ExternalMessagingApi"
const val EXTERNAL_MESSAGING_API = "$GOOGLE_MESSAGES.EXTERNAL_MESSAGING_API"

/** Google Messages' signers (apksigner, 20261027): the v3 one for SDK 33+, and the older one. */
private val SIGNERS = listOf(
    "3284b53cbc699d6adf924945cc5a26dc5a0066b1d6767ffcd7bca44d0ea9fab8",
    "cc75526d6c0f80ac3b3a84eb44838440dbbe6b124443a7c7d7bd19182ac022c9",
).map { h -> h.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }

/** Requests and answers as bytes: Proto.kt writes and reads them. */
private object Raw : MethodDescriptor.Marshaller<ByteArray> {
    override fun stream(value: ByteArray): InputStream = value.inputStream()
    override fun parse(stream: InputStream): ByteArray = stream.readBytes()
}

/**
 * Google Messages' ExternalMessagingApiService over gRPC on binder (see Proto.kt), opened to this
 * app by the bridge of dev.bold7.sysui (MessagesBridge.kt). The server must be signed by Google
 * Messages' key. Blocking calls, 15 s each: off the main thread only. The binding goes after a
 * minute without a call.
 */
class GrpcMessagingApi(private val context: Context) : MessagingApi {
    private val channel: ManagedChannel by lazy {
        BinderChannelBuilder
            .forAddress(AndroidComponentAddress.forRemoteComponent(GOOGLE_MESSAGES, SERVICE), context)
            .securityPolicy(SecurityPolicies.oneOfSignatureSha256Hash(context.packageManager, GOOGLE_MESSAGES, SIGNERS))
            .idleTimeout(1, TimeUnit.MINUTES)
            .build()
    }

    override fun state(): ApiState {
        if (context.checkSelfPermission(EXTERNAL_MESSAGING_API) != PackageManager.PERMISSION_GRANTED) return ApiState.Shut(Why.NO_PERMISSION)
        return try {
            contract(Proto.capabilities(unary("GetServiceCapabilities", ByteArray(0))))
        } catch (e: MessagingApiException) {
            // GetUserConsentSetting answers without the consent: it says whether that is the reason.
            ApiState.Shut(why(e.code, consent()))
        }
    }

    /** GetUserConsentSetting: whether "Allow Bixby and text on Galaxy devices" is on; null when Google Messages does not answer. */
    fun consent(): Boolean? = try {
        Proto.varintField(unary("GetUserConsentSetting", ByteArray(0)), 1) == 1L
    } catch (_: MessagingApiException) {
        null
    }

    override fun create(to: String): Long =
        Proto.varintField(unary("CreateOneOnOneConversation", Proto.createOneOnOne(to)), 1) ?: throw MessagingApiException("NO_THREAD")

    override fun createGroup(to: List<String>): Long =
        Proto.varintField(unary("CreateMmsGroupConversation", Proto.createMmsGroup(to)), 1) ?: throw MessagingApiException("NO_THREAD")

    /**
     * The default SMS subscription, as Google Messages' own composer takes it. An [attachment] is
     * lent to Google Messages alone, read only, for this call: it copies the file into its own
     * storage before it answers (its compose row, dex 20261106), then
     * the grant goes, whatever the answer.
     */
    override fun send(threadId: Long, text: String, attachment: Attachment?) {
        val uri = attachment?.let { Uri.parse(it.uri) }
        uri?.let { context.grantUriPermission(GOOGLE_MESSAGES, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        try {
            unary("SendMessage", Proto.sendMessage(threadId, SubscriptionManager.getDefaultSmsSubscriptionId(), text, UUID.randomUUID().toString(), attachment?.let { it.type to it.uri }))
        } finally {
            uri?.let { context.revokeUriPermission(GOOGLE_MESSAGES, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    override fun run(call: ApiCall) {
        when (call) {
            is ApiCall.MarkConversationRead -> unary("MarkConversationAsRead", Proto.markConversationAsRead(call.threadId))
            is ApiCall.MarkMessagesRead -> unary("MarkMessagesAsRead", Proto.markMessagesAsRead(call.uris))
            is ApiCall.Delete -> unary("DeleteMessage", Proto.deleteMessage(call.uri))
        }
    }

    private fun unary(name: String, request: ByteArray): ByteArray = try {
        val method = MethodDescriptor.newBuilder(Raw, Raw).setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("$API/$name").build()
        ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT.withDeadlineAfter(15, TimeUnit.SECONDS), request)
    } catch (e: StatusRuntimeException) {
        throw MessagingApiException(e.status.code.name, e)
    }
}
