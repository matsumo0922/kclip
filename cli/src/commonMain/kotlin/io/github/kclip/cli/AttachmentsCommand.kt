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

        stopSshMaster(metadata)
        stopAgent(metadata)
        platformServices.fileStore.delete(metadata.localSocketPath)
        store.delete(attachmentId)

        echo("Detached ${attachmentId.displayValue()}.")
    }

    private fun stopSshMaster(metadata: LocalAttachmentMetadata) {
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
            exitWith(output.error)
        }
        val commandOutput = (output as Outcome.Ok).value
        if (commandOutput.exitStatus != 0) {
            exitWith(
                KclipError.ForwardingRejected(
                    message = "failed to stop dedicated SSH master",
                    detail = commandOutput.stderr.decodeToString(),
                ),
            )
        }
    }

    private fun stopAgent(metadata: LocalAttachmentMetadata) {
        val output = platformServices.commandRunner.run(
            spec = CommandSpec(
                executable = "/bin/kill",
                arguments = listOf(metadata.agentProcessId.toString()),
                environment = platformServices.environment.snapshot(),
                timeoutMillis = KILL_TIMEOUT_MILLIS,
            ),
            stdin = null,
        )
        if (output is Outcome.Err) {
            exitWith(output.error)
        }
        val commandOutput = (output as Outcome.Ok).value
        val isMissingProcess = commandOutput.exitStatus != 0
        if (isMissingProcess) {
            echo("kclip: attachment agent was already stopped", err = true)
        }
    }

    private companion object {
        const val KILL_TIMEOUT_MILLIS = 5_000L
        const val SSH_TIMEOUT_MILLIS = 10_000L
    }
}
