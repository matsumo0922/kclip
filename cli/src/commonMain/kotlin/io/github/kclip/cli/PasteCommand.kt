package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.kclip.core.application.DefaultClipboardLimits
import io.github.kclip.core.application.PasteOptions
import io.github.kclip.core.application.PasteUseCase
import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.platform.PlatformServices

/**
 * local clipboard の内容を標準出力へ paste する command。
 */
class PasteCommand(
    private val platformServices: PlatformServices,
) : CliktCommand(
    name = "paste",
) {
    private val backend by option("--backend").default("auto")
    private val attachment by option("--attachment")
    private val maxBytes by option("--max-bytes").int().default(DefaultClipboardLimits.MAX_BYTES)

    override fun run() {
        val backendPreference = when (val outcome = parseBackendPreference(backend)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val attachmentId = parseAttachmentId(attachment)
        val options = PasteOptions(
            backendPreference = backendPreference,
            maxBytes = maxBytes,
        )
        val backendOutcome = resolveClipboardBackend(backendPreference, attachmentId, platformServices)
        val backend = when (backendOutcome) {
            is Outcome.Ok -> backendOutcome.value
            is Outcome.Err -> exitWith(backendOutcome.error)
        }
        val useCase = PasteUseCase(
            backendResolver = FixedClipboardBackendResolver(backend),
        )
        val payload = when (val outcome = useCase.execute(options)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val result = platformServices.standardOutput.writeAll(payload.copyBytes())
        if (result is Outcome.Err) {
            exitWith(result.error)
        }
    }

    private fun parseAttachmentId(value: String?): AttachmentId? {
        if (value == null) return null

        return when (val outcome = AttachmentId.parse(value)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
    }
}
