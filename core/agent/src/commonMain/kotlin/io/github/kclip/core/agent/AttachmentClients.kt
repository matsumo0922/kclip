package io.github.kclip.core.agent

import io.github.kclip.core.application.AttachmentStateRepository
import io.github.kclip.core.application.PairAgentClient
import io.github.kclip.core.domain.AttachmentBinding
import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.AttachmentLease
import io.github.kclip.core.domain.AttachmentStateCodec
import io.github.kclip.core.domain.ClipboardBackend
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.ClipboardPayload
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.PairCredential
import io.github.kclip.core.domain.Secret16
import io.github.kclip.core.domain.TtyIdentity
import io.github.kclip.core.platform.FileStore
import io.github.kclip.core.platform.IpcConnector
import io.github.kclip.core.platform.MonotonicClock
import io.github.kclip.core.platform.RuntimePaths
import io.github.kclip.core.platform.TtyIdentityResolver
import io.github.kclip.core.protocol.AgentOperation
import io.github.kclip.core.protocol.AgentProtocolCodec
import io.github.kclip.core.protocol.AgentProtocolVersion
import io.github.kclip.core.protocol.AgentRequest
import io.github.kclip.core.protocol.AgentResponse
import io.github.kclip.core.protocol.AgentStatus
import io.github.kclip.core.protocol.DefaultAgentProtocolCodec
import io.github.kclip.core.protocol.PairAcceptedFrame
import io.github.kclip.core.protocol.PairFrame
import io.github.kclip.core.protocol.PairFrameCodec

/**
 * local agent へ PAIR / PAIR_CONFIRM を送る protocol client。
 */
class DefaultPairAgentClient(
    private val endpoint: io.github.kclip.core.domain.IpcEndpoint,
    private val connector: IpcConnector,
    private val clock: MonotonicClock,
    private val codec: AgentProtocolCodec = DefaultAgentProtocolCodec(),
) : PairAgentClient {
    override fun pair(credential: PairCredential, frame: PairFrame): Outcome<PairAcceptedFrame> {
        val payload = when (val outcome = PairFrameCodec.encodePairFrame(frame)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val response = send(
            credential = credential.secret,
            operation = AgentOperation.PAIR,
            payload = payload,
        )
        if (response is Outcome.Err) {
            return response
        }

        return PairFrameCodec.decodePairAcceptedFrame((response as Outcome.Ok<AgentResponse>).value.payload)
    }

    override fun confirm(acceptedFrame: PairAcceptedFrame): Outcome<Unit> {
        val response = send(
            credential = acceptedFrame.attachmentNonce,
            operation = AgentOperation.PAIR_CONFIRM,
            payload = acceptedFrame.attachmentNonce.copyBytes(),
        )
        if (response is Outcome.Err) {
            return response
        }

        return Outcome.Ok(Unit)
    }

    private fun send(
        credential: Secret16,
        operation: AgentOperation,
        payload: ByteArray,
    ): Outcome<AgentResponse> {
        val deadline = clock.deadlineAfterMillis(PROTOCOL_TIMEOUT_MILLIS)
        val channel = when (val outcome = connector.connect(endpoint, deadline)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val write = codec.writeRequest(
            channel = channel,
            request = AgentRequest(
                version = AgentProtocolVersion.CURRENT,
                operation = operation,
                credential = credential,
                payload = payload,
            ),
            deadline = deadline,
        )
        if (write is Outcome.Err) {
            channel.close()

            return write
        }

        val response = codec.readResponse(channel, deadline)
        if (response is Outcome.Err) {
            channel.close()

            return response
        }
        val close = channel.close()
        if (close is Outcome.Err) {
            return close
        }
        val value = (response as Outcome.Ok<AgentResponse>).value
        if (value.status != AgentStatus.OK) {
            return Outcome.Err(value.toKclipError(null))
        }

        return Outcome.Ok(value)
    }

    private companion object {
        const val PROTOCOL_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * remote 側から attachment agent を使う clipboard backend。
 */
class AttachmentClipboardBackend(
    private val lease: AttachmentLease,
    private val connector: IpcConnector,
    private val clock: MonotonicClock,
    private val codec: AgentProtocolCodec = DefaultAgentProtocolCodec(),
) : ClipboardBackend {
    override val id: String = "attachment:${lease.id.value}"

    override val capabilities: Set<ClipboardCapability> = lease.capabilities

    override fun copy(payload: ClipboardPayload): Outcome<Unit> {
        if (ClipboardCapability.COPY !in lease.capabilities) {
            return denied("copy is denied for attachment")
        }

        val response = send(AgentOperation.COPY, payload.copyBytes())
        if (response is Outcome.Err) {
            return response
        }

        return Outcome.Ok(Unit)
    }

    override fun paste(maxBytes: Int): Outcome<ClipboardPayload> {
        if (ClipboardCapability.PASTE !in lease.capabilities) {
            return denied("paste is denied for attachment")
        }

        val response = send(AgentOperation.PASTE, ByteArray(size = 0))
        if (response is Outcome.Err) {
            return response
        }

        return ClipboardPayload.fromUtf8Bytes((response as Outcome.Ok<AgentResponse>).value.payload, maxBytes)
    }

    fun ping(): Outcome<Unit> {
        val response = send(AgentOperation.PING, ByteArray(size = 0))
        if (response is Outcome.Err) {
            return response
        }

        return Outcome.Ok(Unit)
    }

    private fun send(operation: AgentOperation, payload: ByteArray): Outcome<AgentResponse> {
        val deadline = clock.deadlineAfterMillis(PROTOCOL_TIMEOUT_MILLIS)
        val channel = when (val outcome = connector.connect(lease.endpoint, deadline)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return stale(outcome.error)
        }
        val write = codec.writeRequest(
            channel = channel,
            request = AgentRequest(
                version = AgentProtocolVersion.CURRENT,
                operation = operation,
                credential = lease.nonce,
                payload = payload,
            ),
            deadline = deadline,
        )
        if (write is Outcome.Err) {
            channel.close()

            return write
        }

        val response = codec.readResponse(channel, deadline)
        if (response is Outcome.Err) {
            channel.close()

            return response
        }
        val close = channel.close()
        if (close is Outcome.Err) {
            return close
        }
        val value = (response as Outcome.Ok<AgentResponse>).value
        if (value.status != AgentStatus.OK) {
            return Outcome.Err(value.toKclipError(lease.id))
        }

        return Outcome.Ok(value)
    }

    private fun denied(message: String): Outcome.Err {
        return Outcome.Err(
            KclipError.PermissionDenied(
                message = message,
            ),
        )
    }

    private fun stale(error: KclipError): Outcome.Err {
        return Outcome.Err(
            KclipError.AttachmentUnavailable(
                attachmentId = lease.id,
                message = "attachment ${lease.id.displayValue()} is not reachable",
                detail = error.message,
            ),
        )
    }

    private companion object {
        const val PROTOCOL_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * runtime state file に lease と TTY binding を保存する repository。
 */
class FileAttachmentStateRepository(
    private val fileStore: FileStore,
    private val runtimePaths: RuntimePaths,
    private val ttyIdentityResolver: TtyIdentityResolver,
) : AttachmentStateRepository {
    override fun findBinding(ttyIdentity: TtyIdentity): Outcome<AttachmentBinding?> {
        val path = when (val outcome = bindingPath(ttyIdentity)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val exists = when (val outcome = fileStore.exists(path)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        if (!exists) {
            return Outcome.Ok(null)
        }
        val bytes = when (val outcome = fileStore.readBytes(path, MAX_STATE_BYTES)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val binding = when (val outcome = AttachmentStateCodec.decodeBinding(bytes)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return Outcome.Ok(binding)
    }

    override fun commit(lease: AttachmentLease, binding: AttachmentBinding): Outcome<Unit> {
        val leaseWrite = writeLease(lease)
        if (leaseWrite is Outcome.Err) {
            return leaseWrite
        }

        return writeBinding(binding)
    }

    override fun rollback(
        lease: AttachmentLease,
        binding: AttachmentBinding,
        previousBinding: AttachmentBinding?,
    ): Outcome<Unit> {
        val leasePath = when (val outcome = leasePath(lease.id)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val bindingPath = when (val outcome = bindingPath(binding.ttyIdentity)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val leaseDelete = fileStore.delete(leasePath)
        if (leaseDelete is Outcome.Err) {
            return leaseDelete
        }
        if (previousBinding != null) {
            return writeBinding(previousBinding)
        }

        return fileStore.delete(bindingPath)
    }

    fun readCurrentLease(): Outcome<AttachmentLease> {
        val ttyIdentity = when (val outcome = ttyIdentityResolver.current()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val binding = when (val outcome = findBinding(ttyIdentity)) {
            is Outcome.Ok -> outcome.value ?: return missingBinding()
            is Outcome.Err -> return outcome
        }

        return readLease(binding.attachmentId)
    }

    fun readLease(id: AttachmentId): Outcome<AttachmentLease> {
        val path = when (val outcome = leasePath(id)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val bytes = when (val outcome = fileStore.readBytes(path, MAX_STATE_BYTES)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return AttachmentStateCodec.decodeLease(bytes)
    }

    fun writeLease(lease: AttachmentLease): Outcome<Unit> {
        val bytes = when (val outcome = AttachmentStateCodec.encodeLease(lease)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val path = when (val outcome = leasePath(lease.id)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return fileStore.writePrivateBytes(path, bytes)
    }

    fun writeBinding(binding: AttachmentBinding): Outcome<Unit> {
        val bytes = when (val outcome = AttachmentStateCodec.encodeBinding(binding)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val path = when (val outcome = bindingPath(binding.ttyIdentity)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return fileStore.writePrivateBytes(path, bytes)
    }

    private fun leasePath(id: AttachmentId): Outcome<String> {
        val directory = when (val outcome = runtimePaths.attachmentsDirectory()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return Outcome.Ok("$directory/${id.value}.lease")
    }

    private fun bindingPath(ttyIdentity: TtyIdentity): Outcome<String> {
        val directory = when (val outcome = runtimePaths.bindingsDirectory()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val filename = "${ttyIdentity.device.toString(radix = 16)}-${ttyIdentity.inode.toString(radix = 16)}.binding"

        return Outcome.Ok("$directory/$filename")
    }

    private fun missingBinding(): Outcome.Err {
        return Outcome.Err(
            KclipError.AttachmentUnavailable(
                attachmentId = null,
                message = "no local clipboard attachment is bound to this terminal",
            ),
        )
    }

    private companion object {
        const val MAX_STATE_BYTES = 16 * 1024
    }
}

private fun AgentResponse.toKclipError(attachmentId: AttachmentId?): KclipError {
    val detail = payload
        .takeIf { bytes -> bytes.isNotEmpty() }
        ?.decodeToString()

    return when (status) {
        AgentStatus.BAD_REQUEST,
        AgentStatus.VERSION_UNSUPPORTED,
        AgentStatus.INTERNAL,
        -> KclipError.ProtocolFailure(
            message = "attachment agent rejected the request",
            detail = detail,
        )

        AgentStatus.UNAUTHORIZED,
        AgentStatus.PAIRING_EXPIRED,
        AgentStatus.PAIRING_CONSUMED,
        -> KclipError.PermissionDenied(
            message = "attachment agent authorization failed",
            detail = detail,
        )

        AgentStatus.CAPABILITY_DENIED,
        -> KclipError.PermissionDenied(
            message = "attachment capability is denied",
            detail = detail,
        )

        AgentStatus.TOO_LARGE,
        -> KclipError.TooLarge(
            actualBytes = null,
            maxBytes = 0,
            detail = detail,
        )

        AgentStatus.BACKEND_UNAVAILABLE,
        AgentStatus.BACKEND_FAILURE,
        -> KclipError.BackendUnavailable(
            message = "local clipboard backend failed",
            detail = detail,
        )

        AgentStatus.TIMEOUT,
        -> KclipError.TimedOut(
            message = "attachment agent request timed out",
            detail = detail,
        )

        AgentStatus.ATTACHMENT_NOT_ACTIVE,
        -> KclipError.AttachmentUnavailable(
            attachmentId = attachmentId,
            message = "attachment is not active",
            detail = detail,
        )

        AgentStatus.OK,
        -> KclipError.Internal(
            message = "OK response was converted to an error",
        )
    }
}
