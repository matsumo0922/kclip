package io.github.kclip

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import io.github.kclip.cli.DoctorCommand
import io.github.kclip.cli.VersionCommand

fun main(args: Array<String>) {
    KclipCommand()
        .subcommands(
            VersionCommand(),
            DoctorCommand(),
        )
        .main(args)
}

/**
 * kclip CLI の root command。
 */
class KclipCommand : CliktCommand(
    name = "kclip",
) {
    override fun run() = Unit
}
