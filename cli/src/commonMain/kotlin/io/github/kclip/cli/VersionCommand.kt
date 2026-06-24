package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand

/**
 * kclip の version を表示する command。
 */
class VersionCommand : CliktCommand(
    name = "version",
) {
    override fun run() {
        echo("kclip 0.1.0-dev")
    }
}
