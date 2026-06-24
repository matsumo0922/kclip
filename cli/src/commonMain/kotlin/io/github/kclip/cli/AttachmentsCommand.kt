package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.platform.PlatformServices

/**
 * local attachment registry を表示する command。
 */
class AttachmentsCommand(
    private val platformServices: PlatformServices,
) : CliktCommand(
    name = "attachments",
) {
    override fun run() {
        val metadata = when (val outcome = LocalAttachmentMetadataStore(platformServices).list()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        if (metadata.isEmpty()) {
            echo("No local attachments.")

            return
        }

        val forwardingController = sshForwardingController()
        for (entry in metadata) {
            val paste = if (entry.allowsPaste) "paste=allow" else "paste=deny"
            val health = when (val outcome = forwardingController.health(entry)) {
                is Outcome.Ok -> outcome.value.displayValue
                is Outcome.Err -> AttachTransportHealth.DEGRADED.displayValue
            }
            echo("${entry.attachmentId.displayValue()}  ${entry.transportKind.metadataValue}  $paste  $health  ${entry.destination}")
        }
    }

    private fun sshForwardingController(): SshForwardingController {
        return SshForwardingController(
            commandRunner = platformServices.commandRunner,
            environment = platformServices.environment,
            fileStore = platformServices.fileStore,
            sleeper = platformServices.sleeper,
            sshExecutable = "/usr/bin/ssh",
        )
    }
}

/**
 * local attachment を停止する command。
 */
class DetachCommand(
    private val platformServices: PlatformServices,
) : CliktCommand(
    name = "detach",
) {
    private val attachment by argument("attachment-id")

    override fun run() {
        val store = LocalAttachmentMetadataStore(platformServices)
        val attachmentId = when (val outcome = resolveLocalAttachmentId(attachment, store)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val metadata = when (val outcome = store.read(attachmentId)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }

        val agentStop = stopAgent(metadata)
        val sshStop = sshForwardingController().detach(metadata)
        platformServices.fileStore.delete(metadata.localSocketPath)
        store.delete(attachmentId)
        if (agentStop is Outcome.Err) {
            exitWith(agentStop.error)
        }
        if (sshStop is Outcome.Err) {
            if (metadata.transportKind == AttachTransportKind.CONTROLMASTER) {
                echo("warning: ${sshStop.error.message}", err = true)
                echo("Detached ${attachmentId.displayValue()}.")

                return
            }

            exitWith(sshStop.error)
        }

        echo("Detached ${attachmentId.displayValue()}.")
    }

    private fun sshForwardingController(): SshForwardingController {
        return SshForwardingController(
            commandRunner = platformServices.commandRunner,
            environment = platformServices.environment,
            fileStore = platformServices.fileStore,
            sleeper = platformServices.sleeper,
            sshExecutable = "/usr/bin/ssh",
        )
    }

    private fun stopAgent(metadata: LocalAttachmentMetadata): Outcome<Unit> {
        return shutdownLocalAgent(metadata, platformServices)
    }
}

/**
 * local attachment の SSH forwarding を metadata から再作成する command。
 */
class ReconnectCommand(
    private val platformServices: PlatformServices,
) : CliktCommand(
    name = "reconnect",
) {
    private val attachment by argument("attachment-id")
    private val sshExecutable by option("--ssh", "--ssh-executable").default("/usr/bin/ssh")

    override fun run() {
        val store = LocalAttachmentMetadataStore(platformServices)
        val attachmentId = when (val outcome = resolveLocalAttachmentId(attachment, store)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val metadata = when (val outcome = store.read(attachmentId)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val reconnect = sshForwardingController().reconnect(metadata)
        if (reconnect is Outcome.Err) {
            exitWith(reconnect.error)
        }

        echo("Reconnected ${attachmentId.displayValue()} using ${metadata.transportKind.metadataValue} transport.")
    }

    private fun sshForwardingController(): SshForwardingController {
        return SshForwardingController(
            commandRunner = platformServices.commandRunner,
            environment = platformServices.environment,
            fileStore = platformServices.fileStore,
            sleeper = platformServices.sleeper,
            sshExecutable = sshExecutable,
        )
    }
}
