package io.github.kclip.core.application

import io.github.kclip.core.domain.AttachmentBinding
import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.AttachmentLease
import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.ClipboardBackendResolver
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.ClipboardPayload
import io.github.kclip.core.domain.EpochSeconds
import io.github.kclip.core.domain.IpcEndpoint
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.PairCredential
import io.github.kclip.core.domain.PairingCode
import io.github.kclip.core.domain.PairingMaterial
import io.github.kclip.core.domain.TtyIdentity
import io.github.kclip.core.domain.flatMap
import io.github.kclip.core.protocol.PairAcceptedFrame
import io.github.kclip.core.protocol.PairFrame

/**
 * copy command の実行 option。
 */
data class CopyOptions(
    val backendPreference: BackendPreference = BackendPreference.AUTO,
    val maxBytes: Int = DefaultClipboardLimits.MAX_BYTES,
)

/**
 * paste command の実行 option。
 */
data class PasteOptions(
    val backendPreference: BackendPreference = BackendPreference.AUTO,
    val maxBytes: Int = DefaultClipboardLimits.MAX_BYTES,
)

/**
 * clipboard payload の既定制限値。
 */
object DefaultClipboardLimits {
    /** 既定の copy/paste 最大 byte 数。 */
    const val MAX_BYTES = 1 * 1024 * 1024
}

/**
 * pair command の実行 option。
 */
data class PairOptions(
    val requestPaste: Boolean,
    val replaceExisting: Boolean,
)

/**
 * attach command の実行 option。
 */
data class AttachOptions(
    val destination: String,
    val pairingCode: PairingCode,
    val allowPaste: Boolean,
)

/**
 * clipboard へ payload を書き込む use case。
 */
class CopyUseCase(
    private val backendResolver: ClipboardBackendResolver,
) {
    fun execute(options: CopyOptions, payload: ClipboardPayload): Outcome<Unit> {
        val hasSupportedSize = payload.size <= options.maxBytes
        if (!hasSupportedSize) {
            return Outcome.Err(
                KclipError.TooLarge(
                    actualBytes = payload.size.toLong(),
                    maxBytes = options.maxBytes,
                ),
            )
        }

        return backendResolver
            .resolve(options.backendPreference)
            .flatMap { backend -> backend.copy(payload) }
    }
}

/**
 * clipboard から payload を読み込む use case。
 */
class PasteUseCase(
    private val backendResolver: ClipboardBackendResolver,
) {
    fun execute(options: PasteOptions): Outcome<ClipboardPayload> {
        return backendResolver
            .resolve(options.backendPreference)
            .flatMap { backend -> backend.paste(options.maxBytes) }
    }
}

/**
 * Phase 0 の placeholder pair use case。
 */
class PairUseCase {
    fun execute(options: PairOptions): Outcome<PairingCode> {
        val suffix = if (options.requestPaste) "PASTE" else "COPY"
        val replacement = if (options.replaceExisting) "R" else "N"

        return Outcome.Ok(PairingCode("KC1-$suffix-$replacement"))
    }
}

/**
 * Phase 0 の placeholder attach use case。
 */
class AttachUseCase {
    fun execute(options: AttachOptions): Outcome<AttachmentId> {
        return Outcome.Ok(AttachmentId("KC-${options.destination.hashCode().toUInt().toString(radix = 16)}"))
    }
}

/**
 * remote 側 PAIR protocol に必要な runtime context。
 */
data class RemotePairContext(
    val material: PairingMaterial,
    val endpoint: IpcEndpoint,
    val remoteUid: ULong,
    val username: String,
    val hostname: String,
    val ttyIdentity: TtyIdentity,
    val createdAt: EpochSeconds,
)

/**
 * remote 側 PAIR 完了後に作成される state。
 */
data class RemotePairResult(
    val lease: AttachmentLease,
    val binding: AttachmentBinding,
)

/**
 * local agent へ PAIR / PAIR_CONFIRM を送る client。
 */
interface PairAgentClient {
    fun pair(credential: PairCredential, frame: PairFrame): Outcome<PairAcceptedFrame>

    fun confirm(acceptedFrame: PairAcceptedFrame): Outcome<Unit>
}

/**
 * remote lease と TTY binding を atomic commit する repository。
 */
interface AttachmentStateRepository {
    fun commit(lease: AttachmentLease, binding: AttachmentBinding): Outcome<Unit>
}

/**
 * remote `kclip pair` の PAIR / PAIR_CONFIRM flow。
 */
class RemotePairUseCase(
    private val agentClient: PairAgentClient,
    private val stateRepository: AttachmentStateRepository,
) {
    fun execute(options: PairOptions, context: RemotePairContext): Outcome<RemotePairResult> {
        val pairFrame = PairFrame(
            requestedCapabilities = requestedCapabilities(options),
            remoteUid = context.remoteUid,
            username = context.username,
            hostname = context.hostname,
            ttyIdentity = context.ttyIdentity,
        )
        val acceptedFrame = when (val outcome = agentClient.pair(context.material.credential, pairFrame)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val lease = AttachmentLease(
            formatVersion = 1u,
            id = acceptedFrame.attachmentId,
            endpoint = context.endpoint,
            nonce = acceptedFrame.attachmentNonce,
            capabilities = acceptedFrame.grantedCapabilities,
            scope = context.ttyIdentity,
            createdAt = context.createdAt,
        )
        val binding = AttachmentBinding(
            attachmentId = acceptedFrame.attachmentId,
            ttyIdentity = context.ttyIdentity,
        )
        val commit = stateRepository.commit(lease, binding)
        if (commit is Outcome.Err) {
            return commit
        }
        val confirm = agentClient.confirm(acceptedFrame)
        if (confirm is Outcome.Err) {
            return confirm
        }

        return Outcome.Ok(
            RemotePairResult(
                lease = lease,
                binding = binding,
            ),
        )
    }

    private fun requestedCapabilities(options: PairOptions): Set<ClipboardCapability> {
        val capabilities = mutableSetOf(ClipboardCapability.COPY)
        if (options.requestPaste) {
            capabilities.add(ClipboardCapability.PASTE)
        }

        return capabilities
    }
}
