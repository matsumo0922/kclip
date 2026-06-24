package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.kclip.core.application.DefaultClipboardLimits
import io.github.kclip.core.application.PasteOptions
import io.github.kclip.core.application.PasteUseCase
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
    private val maxBytes by option("--max-bytes").int().default(DefaultClipboardLimits.MAX_BYTES)

    override fun run() {
        val options = PasteOptions(
            backendPreference = parseBackendPreference(backend),
            maxBytes = maxBytes,
        )
        val useCase = PasteUseCase(platformServices.clipboardBackendResolver)
        val payload = when (val outcome = useCase.execute(options)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val result = platformServices.standardOutput.writeAll(payload.copyBytes())
        if (result is Outcome.Err) {
            exitWith(result.error)
        }
    }
}
