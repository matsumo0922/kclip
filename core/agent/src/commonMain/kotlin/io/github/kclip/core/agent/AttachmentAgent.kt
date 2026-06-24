package io.github.kclip.core.agent

import io.github.kclip.core.application.DefaultClipboardLimits
import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.ClipboardBackendResolver
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.ClipboardPayload
import io.github.kclip.core.domain.IpcEndpoint
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.PairCredential
import io.github.kclip.core.domain.Secret16
import io.github.kclip.core.platform.IpcServer
import io.github.kclip.core.platform.MonotonicClock
import io.github.kclip.core.protocol.AgentOperation
import io.github.kclip.core.protocol.AgentProtocolCodec
import io.github.kclip.core.protocol.AgentProtocolVersion
import io.github.kclip.core.protocol.AgentRequest
import io.github.kclip.core.protocol.AgentResponse
import io.github.kclip.core.protocol.AgentStatus
import io.github.kclip.core.protocol.DefaultAgentProtocolCodec
import io.github.kclip.core.protocol.PairAcceptedFrame
import io.github.kclip.core.protocol.PairFrameCodec

/**
 * attachment agent の lifecycle state。
 */
enum class AgentLifecycleState {
    PAIRING,
    PENDING_CONFIRM,
    ACTIVE,
    DEGRADED,
    STOPPING,
    CLOSED,
}

/**
 * `_attach-agent` の起動 config。
 */
data class AttachmentAgentConfig(
    val endpoint: IpcEndpoint.UnixSocket,
    val expectedPairCredential: PairCredential,
    val attachmentId: AttachmentId,
    val attachmentNonce: Secret16,
    val controlSecret: Secret16,
    val allowPaste: Boolean,
    val maxCopyBytes: Int = DefaultClipboardLimits.MAX_BYTES,
    val maxPasteBytes: Int = DefaultClipboardLimits.MAX_BYTES,
)

/**
 * local clipboard を attachment protocol へ公開する per-attachment agent。
 */
class AttachmentAgent(
    private val config: AttachmentAgentConfig,
    private val ipcServer: IpcServer,
    private val clock: MonotonicClock,
    private val clipboardBackendResolver: ClipboardBackendResolver,
    private val codec: AgentProtocolCodec = DefaultAgentProtocolCodec(),
) {
    private var state = AgentLifecycleState.PAIRING
    private var activeCapabilities: Set<ClipboardCapability> = emptySet()

    fun status(): Outcome<AgentStatusSnapshot> {
        return Outcome.Ok(
            AgentStatusSnapshot(
                id = config.attachmentId,
                state = state,
            ),
        )
    }

    fun runForever(): Outcome<Unit> {
        val listener = when (val outcome = ipcServer.listen(config.endpoint)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        while (state != AgentLifecycleState.STOPPING && state != AgentLifecycleState.CLOSED) {
            val deadline = clock.deadlineAfterMillis(PROTOCOL_TIMEOUT_MILLIS)
            val channel = when (val outcome = listener.accept(deadline)) {
                is Outcome.Ok -> outcome.value
                is Outcome.Err -> return outcome
            }

            val response = handleOneRequest(channel)
            if (response is Outcome.Err) {
                channel.close()
                listener.close()

                return response
            }

            channel.close()
        }

        state = AgentLifecycleState.CLOSED
        return listener.close()
    }

    private fun handleOneRequest(channel: io.github.kclip.core.domain.ProtocolByteChannel): Outcome<Unit> {
        val deadline = clock.deadlineAfterMillis(PROTOCOL_TIMEOUT_MILLIS)
        val request = when (val outcome = codec.readRequest(channel, deadline)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> {
                val write = writeError(channel, AgentStatus.BAD_REQUEST, outcome.error.message)
                if (write is Outcome.Err) return write

                return Outcome.Ok(Unit)
            }
        }
        val response = dispatch(request)

        return codec.writeResponse(channel, response, deadline)
    }

    private fun dispatch(request: AgentRequest): AgentResponse {
        return when (request.operation) {
            AgentOperation.PAIR -> handlePair(request)
            AgentOperation.PAIR_CONFIRM -> handlePairConfirm(request)
            AgentOperation.COPY -> handleCopy(request)
            AgentOperation.PASTE -> handlePaste(request)
            AgentOperation.PING -> handlePing(request)
            AgentOperation.SHUTDOWN -> handleShutdown(request)
        }
    }

    internal fun handleRequestForTest(request: AgentRequest): AgentResponse {
        return dispatch(request)
    }

    private fun handlePair(request: AgentRequest): AgentResponse {
        if (!hasPairCredential(request)) {
            return errorResponse(AgentStatus.UNAUTHORIZED, "pair credential was rejected")
        }
        if (state != AgentLifecycleState.PAIRING) {
            return errorResponse(AgentStatus.PAIRING_CONSUMED, "pairing code has already been consumed")
        }
        val pairFrame = when (val outcome = PairFrameCodec.decodePairFrame(request.payload)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return errorResponse(AgentStatus.BAD_REQUEST, outcome.error.message)
        }
        val acceptedFrame = PairAcceptedFrame(
            attachmentId = config.attachmentId,
            attachmentNonce = config.attachmentNonce,
            grantedCapabilities = grantedCapabilities(pairFrame.requestedCapabilities),
            maxCopyBytes = config.maxCopyBytes.toUInt(),
            maxPasteBytes = config.maxPasteBytes.toUInt(),
        )
        val payload = when (val outcome = PairFrameCodec.encodePairAcceptedFrame(acceptedFrame)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return errorResponse(AgentStatus.INTERNAL, outcome.error.message)
        }

        state = AgentLifecycleState.PENDING_CONFIRM
        activeCapabilities = acceptedFrame.grantedCapabilities
        return okResponse(payload)
    }

    private fun handlePairConfirm(request: AgentRequest): AgentResponse {
        if (state != AgentLifecycleState.PENDING_CONFIRM) {
            return errorResponse(AgentStatus.ATTACHMENT_NOT_ACTIVE, "attachment is not waiting for confirm")
        }
        if (!hasAttachmentNonce(request)) {
            return errorResponse(AgentStatus.UNAUTHORIZED, "attachment nonce was rejected")
        }
        if (!config.attachmentNonce.constantTimeEquals(request.payload)) {
            return errorResponse(AgentStatus.UNAUTHORIZED, "confirm body was rejected")
        }

        state = AgentLifecycleState.ACTIVE
        return okResponse(ByteArray(size = 0))
    }

    private fun handleCopy(request: AgentRequest): AgentResponse {
        if (!canUseAttachment(request)) {
            return errorResponse(AgentStatus.ATTACHMENT_NOT_ACTIVE, "attachment is not active")
        }
        if (ClipboardCapability.COPY !in activeCapabilities) {
            return errorResponse(AgentStatus.CAPABILITY_DENIED, "copy is denied for attachment")
        }
        val payload = when (val outcome = ClipboardPayload.fromUtf8Bytes(request.payload, config.maxCopyBytes)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return errorResponse(AgentStatus.BAD_REQUEST, outcome.error.message)
        }
        val backend = when (val outcome = clipboardBackendResolver.resolve(BackendPreference.SYSTEM)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return errorResponse(AgentStatus.BACKEND_UNAVAILABLE, outcome.error.message)
        }
        val copy = backend.copy(payload)
        if (copy is Outcome.Err) {
            return errorResponse(AgentStatus.BACKEND_FAILURE, copy.error.message)
        }

        return okResponse(ByteArray(size = 0))
    }

    private fun handlePaste(request: AgentRequest): AgentResponse {
        if (!canUseAttachment(request)) {
            return errorResponse(AgentStatus.ATTACHMENT_NOT_ACTIVE, "attachment is not active")
        }
        if (ClipboardCapability.PASTE !in activeCapabilities) {
            return errorResponse(AgentStatus.CAPABILITY_DENIED, "paste is denied for attachment")
        }
        val backend = when (val outcome = clipboardBackendResolver.resolve(BackendPreference.SYSTEM)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return errorResponse(AgentStatus.BACKEND_UNAVAILABLE, outcome.error.message)
        }
        val payload = when (val outcome = backend.paste(config.maxPasteBytes)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return errorResponse(AgentStatus.BACKEND_FAILURE, outcome.error.message)
        }

        return okResponse(payload.copyBytes())
    }

    private fun handlePing(request: AgentRequest): AgentResponse {
        if (!hasAttachmentNonce(request)) {
            return errorResponse(AgentStatus.UNAUTHORIZED, "attachment nonce was rejected")
        }
        if (state != AgentLifecycleState.ACTIVE) {
            return errorResponse(AgentStatus.ATTACHMENT_NOT_ACTIVE, "attachment is not active")
        }

        return okResponse("state=ACTIVE\n".encodeToByteArray())
    }

    private fun handleShutdown(request: AgentRequest): AgentResponse {
        if (!config.controlSecret.constantTimeEquals(request.credential.copyBytes())) {
            return errorResponse(AgentStatus.UNAUTHORIZED, "control secret was rejected")
        }

        state = AgentLifecycleState.STOPPING
        return okResponse(ByteArray(size = 0))
    }

    private fun hasPairCredential(request: AgentRequest): Boolean {
        return config.expectedPairCredential.secret.constantTimeEquals(request.credential.copyBytes())
    }

    private fun hasAttachmentNonce(request: AgentRequest): Boolean {
        return config.attachmentNonce.constantTimeEquals(request.credential.copyBytes())
    }

    private fun canUseAttachment(request: AgentRequest): Boolean {
        return state == AgentLifecycleState.ACTIVE && hasAttachmentNonce(request)
    }

    private fun grantedCapabilities(requestedCapabilities: Set<ClipboardCapability>): Set<ClipboardCapability> {
        val capabilities = mutableSetOf(ClipboardCapability.COPY)
        val grantsPaste = config.allowPaste && ClipboardCapability.PASTE in requestedCapabilities
        if (grantsPaste) {
            capabilities.add(ClipboardCapability.PASTE)
        }

        return capabilities
    }

    private fun writeError(
        channel: io.github.kclip.core.domain.ProtocolByteChannel,
        status: AgentStatus,
        message: String,
    ): Outcome<Unit> {
        val deadline = clock.deadlineAfterMillis(PROTOCOL_TIMEOUT_MILLIS)

        return codec.writeResponse(channel, errorResponse(status, message), deadline)
    }

    private fun okResponse(payload: ByteArray): AgentResponse {
        return AgentResponse(
            version = AgentProtocolVersion.CURRENT,
            status = AgentStatus.OK,
            errorCode = 0u,
            payload = payload,
        )
    }

    private fun errorResponse(status: AgentStatus, message: String): AgentResponse {
        return AgentResponse(
            version = AgentProtocolVersion.CURRENT,
            status = status,
            errorCode = 1u,
            payload = message.encodeToByteArray(),
        )
    }

    private companion object {
        const val PROTOCOL_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * `_attach-agent` 起動 config の text codec。
 */
object AttachmentAgentConfigCodec {
    fun encode(config: AttachmentAgentConfig): ByteArray {
        val lines = listOf(
            "version=1",
            "socketPath=${config.endpoint.path}",
            "pairCredential=${HexCodec.encode(config.expectedPairCredential.secret.copyBytes())}",
            "attachmentId=${config.attachmentId.value}",
            "attachmentNonce=${HexCodec.encode(config.attachmentNonce.copyBytes())}",
            "controlSecret=${HexCodec.encode(config.controlSecret.copyBytes())}",
            "allowPaste=${config.allowPaste}",
            "maxCopyBytes=${config.maxCopyBytes}",
            "maxPasteBytes=${config.maxPasteBytes}",
        )

        return (lines.joinToString(separator = "\n") + "\n").encodeToByteArray()
    }

    fun decode(bytes: ByteArray): Outcome<AttachmentAgentConfig> {
        val values = bytes
            .decodeToString()
            .lineSequence()
            .filter { line -> line.isNotBlank() }
            .associate { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex < 0) {
                    return invalidConfig("agent config line must contain '='")
                }

                line.take(separatorIndex) to line.drop(separatorIndex + 1)
            }
        if (values["version"] != "1") {
            return invalidConfig("unsupported agent config version")
        }

        return decodeValues(values)
    }

    private fun decodeValues(values: Map<String, String>): Outcome<AttachmentAgentConfig> {
        val pairCredential = when (val outcome = decodeSecret(values, "pairCredential")) {
            is Outcome.Ok -> PairCredential(outcome.value)
            is Outcome.Err -> return outcome
        }
        val attachmentIdText = when (val outcome = required(values, "attachmentId")) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val socketPath = when (val outcome = required(values, "socketPath")) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val allowPasteText = when (val outcome = required(values, "allowPaste")) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val maxCopyBytes = when (val outcome = requiredInt(values, "maxCopyBytes")) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val maxPasteBytes = when (val outcome = requiredInt(values, "maxPasteBytes")) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val attachmentId = when (val outcome = AttachmentId.parse(attachmentIdText)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val attachmentNonce = when (val outcome = decodeSecret(values, "attachmentNonce")) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val controlSecret = when (val outcome = decodeSecret(values, "controlSecret")) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return Outcome.Ok(
            AttachmentAgentConfig(
                endpoint = IpcEndpoint.UnixSocket(socketPath),
                expectedPairCredential = pairCredential,
                attachmentId = attachmentId,
                attachmentNonce = attachmentNonce,
                controlSecret = controlSecret,
                allowPaste = allowPasteText.toBooleanStrictOrNull() ?: false,
                maxCopyBytes = maxCopyBytes,
                maxPasteBytes = maxPasteBytes,
            ),
        )
    }

    private fun decodeSecret(values: Map<String, String>, key: String): Outcome<Secret16> {
        val value = when (val outcome = required(values, key)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val bytes = when (val outcome = HexCodec.decode(value)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return Secret16.fromBytes(bytes)
    }

    private fun required(values: Map<String, String>, key: String): Outcome<String> {
        return values[key]
            ?.let { value -> Outcome.Ok(value) }
            ?: invalidConfig("missing agent config key: $key")
    }

    private fun requiredInt(values: Map<String, String>, key: String): Outcome<Int> {
        val value = when (val outcome = required(values, key)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val intValue = value.toIntOrNull()
            ?: return invalidConfig("agent config key must be an integer: $key")

        return Outcome.Ok(intValue)
    }

    private fun invalidConfig(message: String): Outcome.Err {
        return Outcome.Err(
            KclipError.InvalidInput(
                message = message,
            ),
        )
    }
}

/**
 * agent config 用の hex codec。
 */
private object HexCodec {
    private const val BYTE_MASK = 0xff
    private const val HEX_BITS = 4
    private const val HEX_ALPHABET = "0123456789ABCDEF"
    private const val HEX_CHARS_PER_BYTE = 2

    fun encode(bytes: ByteArray): String {
        val output = StringBuilder(bytes.size * HEX_CHARS_PER_BYTE)
        for (byteValue in bytes) {
            val unsignedValue = byteValue.toInt() and BYTE_MASK
            output.append(HEX_ALPHABET[unsignedValue ushr HEX_BITS])
            output.append(HEX_ALPHABET[unsignedValue and 0x0f])
        }

        return output.toString()
    }

    fun decode(value: String): Outcome<ByteArray> {
        val normalizedValue = value.uppercase()
        if (normalizedValue.length % HEX_CHARS_PER_BYTE != 0) {
            return invalidHex()
        }
        val output = ByteArray(normalizedValue.length / HEX_CHARS_PER_BYTE)
        for (byteIndex in output.indices) {
            val high = HEX_ALPHABET.indexOf(normalizedValue[byteIndex * HEX_CHARS_PER_BYTE])
            val low = HEX_ALPHABET.indexOf(normalizedValue[byteIndex * HEX_CHARS_PER_BYTE + 1])
            if (high < 0 || low < 0) {
                return invalidHex()
            }

            output[byteIndex] = ((high shl HEX_BITS) or low).toByte()
        }

        return Outcome.Ok(output)
    }

    private fun invalidHex(): Outcome.Err {
        return Outcome.Err(
            KclipError.InvalidInput(
                message = "invalid hex text",
            ),
        )
    }
}

/**
 * agent の状態表示用 snapshot。
 */
data class AgentStatusSnapshot(
    val id: AttachmentId,
    val state: AgentLifecycleState,
)
