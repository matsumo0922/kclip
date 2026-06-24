package io.github.kclip.core.application

import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.ClipboardPayload
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.PairingCode

/**
 * copy command の実行 option。
 */
data class CopyOptions(
    val backendPreference: BackendPreference,
    val maxBytes: Int,
)

/**
 * paste command の実行 option。
 */
data class PasteOptions(
    val backendPreference: BackendPreference,
    val maxBytes: Int,
)

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
 * Phase 0 の placeholder copy use case。
 */
class CopyUseCase {
    fun execute(options: CopyOptions, payload: ClipboardPayload): Outcome<Unit> {
        val hasSupportedSize = payload.size <= options.maxBytes
        if (!hasSupportedSize) {
            return Outcome.Err(
                io.github.kclip.core.domain.KclipError.TooLarge(
                    actualBytes = payload.size.toLong(),
                    maxBytes = options.maxBytes,
                ),
            )
        }

        return Outcome.Ok(Unit)
    }
}

/**
 * Phase 0 の placeholder paste use case。
 */
class PasteUseCase {
    fun execute(options: PasteOptions): Outcome<ClipboardPayload> {
        return ClipboardPayload.fromUtf8Bytes(ByteArray(size = 0), options.maxBytes)
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
