package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.kclip.core.diagnostics.Doctor
import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.platform.PlatformServices

/**
 * kclip の現在の状態を診断する command。
 */
class DoctorCommand(
    private val platformServices: PlatformServices,
) : CliktCommand(
    name = "doctor",
) {
    override fun run() {
        val backendOutcome = platformServices.clipboardBackendResolver.resolve(BackendPreference.AUTO)
        val backendStatus = when (backendOutcome) {
            is Outcome.Ok -> "available (${backendOutcome.value.id})"
            is Outcome.Err -> "unavailable (${backendOutcome.error.message})"
        }
        val report = Doctor().createLocalReport(backendStatus)

        report.lines.forEach { line ->
            echo(line)
        }
    }
}
