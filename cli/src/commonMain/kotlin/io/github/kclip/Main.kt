package io.github.kclip

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import io.github.kclip.cli.CopyCommand
import io.github.kclip.cli.DoctorCommand
import io.github.kclip.cli.PasteCommand
import io.github.kclip.cli.VersionCommand
import io.github.kclip.core.platform.createPlatformServices

fun main(args: Array<String>) {
    val platformServices = createPlatformServices()

    KclipCommand()
        .subcommands(
            CopyCommand(platformServices),
            PasteCommand(platformServices),
            VersionCommand(),
            DoctorCommand(platformServices),
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
