package io.github.kclip.core.application

import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.ClipboardBackendResolver
import io.github.kclip.core.domain.ClipboardPayload
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.PairingCode
import io.github.kclip.core.domain.flatMap

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
