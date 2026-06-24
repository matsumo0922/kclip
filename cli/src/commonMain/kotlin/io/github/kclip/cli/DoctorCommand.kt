package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.kclip.core.diagnostics.Doctor

/**
 * kclip の現在の状態を診断する command。
 */
class DoctorCommand : CliktCommand(
    name = "doctor",
) {
    override fun run() {
        val report = Doctor().createLocalReport()

        report.lines.forEach { line ->
            echo(line)
        }
    }
}
