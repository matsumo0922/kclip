package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.platform.CommandSpec
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

        for (entry in metadata) {
            val paste = if (entry.allowsPaste) "paste=allow" else "paste=deny"
            echo("${entry.attachmentId.displayValue()}  $paste  ${entry.destination}")
        }
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
        val attachmentId = when (val outcome = AttachmentId.parse(attachment)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val store = LocalAttachmentMetadataStore(platformServices)
        val metadata = when (val outcome = store.read(attachmentId)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }

        val agentStop = stopAgent(metadata)
        val sshStop = stopSshMaster(metadata)
        platformServices.fileStore.delete(metadata.localSocketPath)
        store.delete(attachmentId)
        if (agentStop is Outcome.Err) {
            exitWith(agentStop.error)
        }
        if (sshStop is Outcome.Err) {
            exitWith(sshStop.error)
        }

        echo("Detached ${attachmentId.displayValue()}.")
    }

    private fun stopSshMaster(metadata: LocalAttachmentMetadata): Outcome<Unit> {
        val output = platformServices.commandRunner.run(
            spec = CommandSpec(
                executable = "/usr/bin/ssh",
                arguments = listOf(
                    "-S",
                    metadata.controlPath,
                    "-o",
                    "BatchMode=yes",
                    "-o",
                    "ConnectTimeout=8",
                    "-O",
                    "exit",
                    metadata.destination,
                ),
                environment = platformServices.environment.snapshot(),
                timeoutMillis = SSH_TIMEOUT_MILLIS,
            ),
            stdin = null,
        )
        if (output is Outcome.Err) {
            return output
        }
        val commandOutput = (output as Outcome.Ok).value
        if (commandOutput.exitStatus != 0) {
            return Outcome.Err(
                KclipError.ForwardingRejected(
                    message = "failed to stop dedicated SSH master",
                    detail = commandOutput.stderr.decodeToString(),
                ),
            )
        }

        return Outcome.Ok(Unit)
    }

    private fun stopAgent(metadata: LocalAttachmentMetadata): Outcome<Unit> {
        return shutdownLocalAgent(metadata, platformServices)
    }

    private companion object {
        const val SSH_TIMEOUT_MILLIS = 10_000L
    }
}
