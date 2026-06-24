package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.kclip.core.agent.AttachmentAgentConfig
import io.github.kclip.core.agent.AttachmentAgentConfigCodec
import io.github.kclip.core.agent.AttachmentClipboardBackend
import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.AttachmentLease
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.EpochSeconds
import io.github.kclip.core.domain.IpcEndpoint
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.PairingCode
import io.github.kclip.core.domain.PairingMaterial
import io.github.kclip.core.domain.Secret16
import io.github.kclip.core.domain.TtyIdentity
import io.github.kclip.core.platform.BackgroundProcess
import io.github.kclip.core.platform.BackgroundProcessSpec
import io.github.kclip.core.platform.PlatformServices

/**
 * local machine から remote pairing request へ attach する command。
 */
class AttachCommand(
    private val platformServices: PlatformServices,
) : CliktCommand(
    name = "attach",
) {
    private val transport by option("--transport").default("auto")
    private val controlPath by option("--control-path")
    private val paste by option("--paste").default("deny")
    private val pairingCodeStdin by option("--pairing-code-stdin").flag(default = false)
    private val agentExecutable by option("--agent-executable").default("kclip")
    private val sshExecutable by option("--ssh", "--ssh-executable").default("/usr/bin/ssh")
    private val destination by argument("destination")

    override fun run() {
        val transportPreference = parseTransport()
        val material = readPairingMaterial()
        val allowPaste = parsePasteMode()
        val attachmentId = createAttachmentId()
        val attachmentNonce = createAttachmentNonce()
        val controlSecret = createAttachmentNonce()
        val localEndpoint = createLocalEndpoint(attachmentId)
        val config = AttachmentAgentConfig(
            endpoint = localEndpoint,
            expectedPairCredential = material.credential,
            attachmentId = attachmentId,
            attachmentNonce = attachmentNonce,
            controlSecret = controlSecret,
            allowPaste = allowPaste,
        )
        val privateControlPath = createControlPath(attachmentId)
        val remoteSocketPath = remoteSocketPath(material)

        val agentProcess = startAgent(attachmentId, config)
        val forwardingSpec = SshForwardingSpec(
            destination = destination,
            remoteSocketPath = remoteSocketPath,
            localSocketPath = localEndpoint.path,
            privateControlPath = privateControlPath,
        )
        val forwarding = sshForwardingController().establish(
            spec = forwardingSpec,
            preference = transportPreference,
            explicitControlPath = this.controlPath,
        )
        if (forwarding is Outcome.Err) {
            cleanupLocalResources(attachmentId, agentProcess, localEndpoint.path, privateControlPath, controlSecret)
            exitWith(forwarding.error)
        }
        val plan = (forwarding as Outcome.Ok).value
        val metadata = LocalAttachmentMetadata(
            attachmentId = attachmentId,
            agentProcessId = agentProcess.processId,
            destination = destination,
            controlDestination = plan.controlDestination,
            transportKind = plan.transportKind,
            remoteSocketPath = remoteSocketPath,
            localSocketPath = localEndpoint.path,
            controlPath = plan.controlPath,
            controlSecret = controlSecret,
            allowsPaste = allowPaste,
        )
        val metadataWrite = LocalAttachmentMetadataStore(platformServices).write(metadata)
        if (metadataWrite is Outcome.Err) {
            cleanupPartialAttachment(metadata)
            exitWith(metadataWrite.error)
        }
        val active = waitUntilActive(attachmentId, attachmentNonce, localEndpoint, allowPaste)
        if (active is Outcome.Err) {
            cleanupPartialAttachment(metadata)
            exitWith(active.error)
        }

        echo("Attached ${attachmentId.displayValue()} using ${metadata.transportKind.metadataValue} transport.")
    }

    private fun parseTransport(): AttachTransportPreference {
        return when (val outcome = parseTransportPreference(transport)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
    }

    private fun readPairingMaterial(): PairingMaterial {
        if (!pairingCodeStdin) {
            echo("Paste pairing code, then send EOF:", err = true)
        }
        val bytes = when (val outcome = platformServices.standardInput.readAll(MAX_PAIRING_CODE_BYTES)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val code = when (val outcome = PairingCode.parse(bytes.decodeToString())) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }

        return when (val outcome = PairingMaterial.fromCode(code)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
    }

    private fun parsePasteMode(): Boolean {
        return when (paste) {
            "allow" -> true
            "deny" -> false
            else -> exitWith(
                KclipError.InvalidInput(
                    message = "invalid paste mode: $paste",
                    detail = "expected allow or deny",
                ),
            )
        }
    }

    private fun createAttachmentId(): AttachmentId {
        val bytes = when (val outcome = platformServices.secureRandom.readBytes(SECRET_BYTES)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }

        return when (val outcome = AttachmentId.fromBytes(bytes)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
    }

    private fun createAttachmentNonce(): Secret16 {
        val bytes = when (val outcome = platformServices.secureRandom.readBytes(SECRET_BYTES)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }

        return when (val outcome = Secret16.fromBytes(bytes)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
    }

    private fun createLocalEndpoint(attachmentId: AttachmentId): IpcEndpoint.UnixSocket {
        val directory = when (val outcome = platformServices.runtimePaths.agentsDirectory()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val path = "$directory/${attachmentId.value.lowercase()}.sock"
        val delete = platformServices.fileStore.delete(path)
        if (delete is Outcome.Err) {
            exitWith(delete.error)
        }

        return IpcEndpoint.UnixSocket(path)
    }

    private fun startAgent(attachmentId: AttachmentId, config: AttachmentAgentConfig): BackgroundProcess {
        val directory = when (val outcome = platformServices.runtimePaths.agentsDirectory()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val process = platformServices.backgroundProcessLauncher.launch(
            spec = BackgroundProcessSpec(
                executable = agentExecutable,
                arguments = listOf("_attach-agent"),
                environment = platformServices.environment.snapshot(),
                stdoutPath = "$directory/${attachmentId.value.lowercase()}.out.log",
                stderrPath = "$directory/${attachmentId.value.lowercase()}.err.log",
            ),
            configBytes = AttachmentAgentConfigCodec.encode(config),
        )
        if (process is Outcome.Err) {
            exitWith(process.error)
        }

        return (process as Outcome.Ok).value
    }

    private fun createControlPath(attachmentId: AttachmentId): String {
        val directory = when (val outcome = platformServices.runtimePaths.sshDirectory()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }

        return "$directory/${attachmentId.value.lowercase()}.ctl"
    }

    private fun waitUntilActive(
        attachmentId: AttachmentId,
        attachmentNonce: Secret16,
        localEndpoint: IpcEndpoint.UnixSocket,
        allowPaste: Boolean,
    ): Outcome<Unit> {
        val lease = AttachmentLease(
            formatVersion = 1u,
            id = attachmentId,
            endpoint = localEndpoint,
            nonce = attachmentNonce,
            capabilities = capabilities(allowPaste),
            scope = TtyIdentity(
                device = 0u,
                inode = 0u,
                displayPath = "local",
            ),
            createdAt = EpochSeconds(platformServices.clock.nowMillis() / MILLIS_PER_SECOND),
        )
        val backend = AttachmentClipboardBackend(
            lease = lease,
            connector = platformServices.ipcConnector,
            clock = platformServices.clock,
        )
        val deadlineMillis = platformServices.clock.nowMillis() + ATTACH_TIMEOUT_MILLIS
        var lastError: KclipError? = null

        while (platformServices.clock.nowMillis() <= deadlineMillis) {
            val ping = backend.ping()
            if (ping is Outcome.Ok) {
                return Outcome.Ok(Unit)
            }
            lastError = (ping as Outcome.Err).error

            val sleep = platformServices.sleeper.sleepMillis(RETRY_DELAY_MILLIS)
            if (sleep is Outcome.Err) {
                return sleep
            }
        }

        return Outcome.Err(
            KclipError.TimedOut(
                message = "no matching pairing request completed before the deadline",
                detail = lastError?.message,
            ),
        )
    }

    private fun cleanupPartialAttachment(metadata: LocalAttachmentMetadata) {
        sshForwardingController().detach(metadata)
        shutdownLocalAgent(metadata, platformServices)
        platformServices.fileStore.delete(metadata.localSocketPath)
        if (metadata.transportKind == AttachTransportKind.DEDICATED) {
            platformServices.fileStore.delete(metadata.controlPath)
        }
        LocalAttachmentMetadataStore(platformServices).delete(metadata.attachmentId)
    }

    private fun cleanupLocalResources(
        attachmentId: AttachmentId,
        agentProcess: BackgroundProcess,
        localSocketPath: String,
        privateControlPath: String,
        controlSecret: Secret16,
    ) {
        val metadata = LocalAttachmentMetadata(
            attachmentId = attachmentId,
            agentProcessId = agentProcess.processId,
            destination = destination,
            controlDestination = destination,
            transportKind = AttachTransportKind.DEDICATED,
            remoteSocketPath = "",
            localSocketPath = localSocketPath,
            controlPath = privateControlPath,
            controlSecret = controlSecret,
            allowsPaste = false,
        )
        shutdownLocalAgent(metadata, platformServices)
        platformServices.fileStore.delete(localSocketPath)
        platformServices.fileStore.delete(privateControlPath)
        LocalAttachmentMetadataStore(platformServices).delete(attachmentId)
    }

    private fun sshForwardingController(): SshForwardingController {
        return SshForwardingController(
            commandRunner = platformServices.commandRunner,
            environment = platformServices.environment,
            fileStore = platformServices.fileStore,
            processIdentityResolver = platformServices.processIdentityResolver,
            sleeper = platformServices.sleeper,
            sshExecutable = sshExecutable,
        )
    }

    private fun capabilities(allowPaste: Boolean): Set<ClipboardCapability> {
        val capabilities = mutableSetOf(ClipboardCapability.COPY)
        if (allowPaste) {
            capabilities.add(ClipboardCapability.PASTE)
        }

        return capabilities
    }

    private companion object {
        const val ATTACH_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        const val MAX_PAIRING_CODE_BYTES = 256
        const val MILLIS_PER_SECOND = 1_000L
        const val RETRY_DELAY_MILLIS = 200L
        const val SECRET_BYTES = 16
    }
}
