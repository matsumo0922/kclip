package io.github.kclip.cli

import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.platform.CommandOutput
import io.github.kclip.core.platform.CommandRunner
import io.github.kclip.core.platform.CommandSpec
import io.github.kclip.core.platform.Environment
import io.github.kclip.core.platform.FileStore
import io.github.kclip.core.platform.FileType
import io.github.kclip.core.platform.ProcessIdentityResolver
import io.github.kclip.core.platform.Sleeper

/**
 * attachment の SSH forwarding transport 種別。
 */
enum class AttachTransportKind(
    val metadataValue: String,
) {
    /** 既存 OpenSSH ControlMaster に forwarding を追加する transport。 */
    CONTROLMASTER("controlmaster"),

    /** kclip 専用の private master connection を使う transport。 */
    DEDICATED("dedicated"),
    ;

    companion object {
        fun fromMetadata(value: String?): AttachTransportKind {
            return when (value) {
                CONTROLMASTER.metadataValue -> CONTROLMASTER
                DEDICATED.metadataValue -> DEDICATED
                else -> DEDICATED
            }
        }
    }
}

/**
 * attach CLI で指定された transport 選択方針。
 */
enum class AttachTransportPreference {
    /** ControlMaster が使えれば使い、なければ dedicated に fallback する。 */
    AUTO,

    /** 既存 ControlMaster の利用を必須にする。 */
    CONTROLMASTER,

    /** kclip 専用 dedicated connection を必ず使う。 */
    DEDICATED,
}

/**
 * attachment forwarding の現在状態。
 */
enum class AttachTransportHealth(
    val displayValue: String,
) {
    /** SSH master が応答している状態。 */
    ACTIVE("active"),

    /** SSH master が応答せず forwarding が失われている可能性がある状態。 */
    DEGRADED("degraded"),
}

/**
 * SSH reverse forwarding の作成に必要な値。
 */
data class SshForwardingSpec(
    val destination: String,
    val remoteSocketPath: String,
    val localSocketPath: String,
    val privateControlPath: String,
)

/**
 * 選択済み transport と ControlPath。
 */
data class SshForwardingPlan(
    val transportKind: AttachTransportKind,
    val controlPath: String,
    val controlDestination: String,
)

/**
 * `ssh -G` から得た forwarding 関連 config。
 */
data class SshResolvedConfig(
    val controlPath: String?,
    val controlDestination: String?,
) {
    fun withExplicitControlPath(explicitControlPath: String?): SshResolvedConfig {
        if (explicitControlPath.isNullOrBlank()) {
            return this
        }

        return copy(controlPath = explicitControlPath)
    }
}

/**
 * OpenSSH の forwarding 操作を実行する helper。
 */
class SshForwardingController(
    private val commandRunner: CommandRunner,
    private val environment: Environment,
    private val fileStore: FileStore? = null,
    private val processIdentityResolver: ProcessIdentityResolver? = null,
    private val sleeper: Sleeper? = null,
    private val sshExecutable: String,
) {
    fun establish(
        spec: SshForwardingSpec,
        preference: AttachTransportPreference,
        explicitControlPath: String?,
    ): Outcome<SshForwardingPlan> {
        val resolvedConfig = when (val outcome = resolveSshConfig(spec.destination)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val controlConfig = resolvedConfig.withExplicitControlPath(explicitControlPath)
        val controlPath = controlConfig.controlPath
        val controlDestination = controlConfig.controlDestination ?: spec.destination
        val shouldProbeControlMaster = preference != AttachTransportPreference.DEDICATED
        val controlMasterCheck = if (!shouldProbeControlMaster || controlPath == null) {
            Outcome.Err(noUsableControlMasterError(spec.destination, null))
        } else {
            checkControlMaster(controlDestination, controlPath)
        }
        if (preference == AttachTransportPreference.CONTROLMASTER && controlMasterCheck is Outcome.Err) {
            return controlMasterCheck
        }
        val canUseControlMaster = controlMasterCheck is Outcome.Ok
        val plan = when (
            val outcome = chooseForwardingPlan(
                preference = preference,
                controlMasterPath = controlPath,
                controlDestination = controlDestination,
                canUseControlMaster = canUseControlMaster,
                privateControlPath = spec.privateControlPath,
                privateControlDestination = controlDestination,
            )
        ) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return when (plan.transportKind) {
            AttachTransportKind.CONTROLMASTER -> establishControlMasterOrFallback(spec, preference, plan)
            AttachTransportKind.DEDICATED -> startDedicated(spec, plan.controlPath, plan.controlDestination)
        }
    }

    fun reconnect(metadata: LocalAttachmentMetadata): Outcome<Unit> {
        val validation = validateRemoteSocketPath(metadata)
        if (validation is Outcome.Err) {
            return validation
        }
        val spec = SshForwardingSpec(
            destination = metadata.destination,
            remoteSocketPath = metadata.remoteSocketPath,
            localSocketPath = metadata.localSocketPath,
            privateControlPath = metadata.controlPath,
        )

        return when (metadata.transportKind) {
            AttachTransportKind.CONTROLMASTER -> {
                val check = checkControlMaster(metadata.controlDestination, metadata.controlPath)
                if (check is Outcome.Err) {
                    return check
                }

                forwardControlMaster(spec, metadata.controlPath, metadata.controlDestination).toUnit()
            }
            AttachTransportKind.DEDICATED -> {
                if (checkControlMaster(metadata.controlDestination, metadata.controlPath) is Outcome.Ok) {
                    val cancel = cancelControlMaster(metadata)
                    if (cancel is Outcome.Err) {
                        return cancel
                    }
                    val wait = sleeper?.sleepMillis(REMOTE_SOCKET_RECLAIM_DELAY_MILLIS)
                    if (wait is Outcome.Err) {
                        return wait
                    }

                    return reconnectDedicatedForward(spec, metadata)
                }
                fileStore?.delete(metadata.controlPath)

                startDedicated(spec, metadata.controlPath, metadata.controlDestination).toUnit()
            }
        }
    }

    private fun reconnectDedicatedForward(
        spec: SshForwardingSpec,
        metadata: LocalAttachmentMetadata,
    ): Outcome<Unit> {
        val forward = addForward(
            spec = spec,
            controlPath = metadata.controlPath,
            controlDestination = metadata.controlDestination,
            message = "failed to add dedicated SSH forwarding",
        )
        if (forward is Outcome.Ok) {
            return forward
        }

        return forward
    }

    fun detach(metadata: LocalAttachmentMetadata): Outcome<Unit> {
        return when (metadata.transportKind) {
            AttachTransportKind.CONTROLMASTER -> cancelControlMaster(metadata)
            AttachTransportKind.DEDICATED -> stopDedicated(metadata.controlDestination, metadata.controlPath)
        }
    }

    fun health(metadata: LocalAttachmentMetadata): Outcome<AttachTransportHealth> {
        val check = checkControlMaster(metadata.controlDestination, metadata.controlPath)
        val health = when (check) {
            is Outcome.Ok -> AttachTransportHealth.ACTIVE
            is Outcome.Err -> AttachTransportHealth.DEGRADED
        }

        return Outcome.Ok(health)
    }

    private fun resolveSshConfig(destination: String): Outcome<SshResolvedConfig> {
        val output = runCommand(
            arguments = SshForwardingCommands.config(destination),
            message = "failed to resolve SSH configuration",
        )
        if (output is Outcome.Err) {
            return output
        }

        return Outcome.Ok(SshConfigParser.parse((output as Outcome.Ok).value.stdout.decodeToString()))
    }

    private fun checkControlMaster(destination: String, controlPath: String): Outcome<Unit> {
        val validation = validateControlPath(controlPath)
        if (validation is Outcome.Err) {
            return validation
        }

        return runCommand(
            arguments = SshForwardingCommands.check(controlPath, destination),
            message = "no usable ControlMaster was found",
        ).toUnit()
    }

    private fun establishControlMasterOrFallback(
        spec: SshForwardingSpec,
        preference: AttachTransportPreference,
        plan: SshForwardingPlan,
    ): Outcome<SshForwardingPlan> {
        val forward = forwardControlMaster(spec, plan.controlPath, plan.controlDestination)
        if (forward is Outcome.Ok) {
            return forward
        }
        val masterStillAlive = checkControlMaster(plan.controlDestination, plan.controlPath) is Outcome.Ok
        val shouldFallback = preference == AttachTransportPreference.AUTO && !masterStillAlive
        if (shouldFallback) {
            return startDedicated(spec, spec.privateControlPath, plan.controlDestination)
        }

        return forward
    }

    private fun forwardControlMaster(
        spec: SshForwardingSpec,
        controlPath: String,
        controlDestination: String,
    ): Outcome<SshForwardingPlan> {
        val forward = addForward(
            spec = spec,
            controlPath = controlPath,
            controlDestination = controlDestination,
            message = "failed to add ControlMaster SSH forwarding",
        )
        if (forward is Outcome.Err) {
            return forward
        }

        return Outcome.Ok(
            SshForwardingPlan(
                transportKind = AttachTransportKind.CONTROLMASTER,
                controlPath = controlPath,
                controlDestination = controlDestination,
            ),
        )
    }

    private fun startDedicated(
        spec: SshForwardingSpec,
        controlPath: String,
        controlDestination: String,
    ): Outcome<SshForwardingPlan> {
        val master = runCommand(
            arguments = SshForwardingCommands.startDedicated(controlPath, spec.destination),
            message = "failed to start dedicated SSH master",
        )
        if (master is Outcome.Err) {
            return master
        }
        val forward = addForward(
            spec = spec,
            controlPath = controlPath,
            controlDestination = controlDestination,
            message = "failed to add dedicated SSH forwarding",
        )
        if (forward is Outcome.Err) {
            stopDedicated(spec.destination, controlPath)

            return forward
        }

        return Outcome.Ok(
            SshForwardingPlan(
                transportKind = AttachTransportKind.DEDICATED,
                controlPath = controlPath,
                controlDestination = controlDestination,
            ),
        )
    }

    private fun addForward(
        spec: SshForwardingSpec,
        controlPath: String,
        controlDestination: String,
        message: String,
    ): Outcome<Unit> {
        val validation = validateControlPath(controlPath)
        if (validation is Outcome.Err) {
            return validation
        }

        return runCommand(
            arguments = SshForwardingCommands.forward(
                controlPath = controlPath,
                remoteSocketPath = spec.remoteSocketPath,
                localSocketPath = spec.localSocketPath,
                destination = controlDestination,
            ),
            message = message,
        ).toUnit()
    }

    private fun cancelControlMaster(metadata: LocalAttachmentMetadata): Outcome<Unit> {
        val validation = validateControlPath(metadata.controlPath)
        if (validation is Outcome.Err) {
            return validation
        }

        return runCommand(
            arguments = SshForwardingCommands.cancel(
                controlPath = metadata.controlPath,
                remoteSocketPath = metadata.remoteSocketPath,
                localSocketPath = metadata.localSocketPath,
                destination = metadata.controlDestination,
            ),
            message = "failed to cancel ControlMaster SSH forwarding",
        ).toUnit()
    }

    private fun stopDedicated(destination: String, controlPath: String): Outcome<Unit> {
        val validation = validateControlPath(controlPath)
        if (validation is Outcome.Err) {
            return validation
        }

        return runCommand(
            arguments = SshForwardingCommands.exitDedicated(controlPath, destination),
            message = "failed to stop dedicated SSH master",
        ).toUnit()
    }

    private fun validateRemoteSocketPath(metadata: LocalAttachmentMetadata): Outcome<Unit> {
        if (metadata.remoteSocketPath.isNotBlank()) {
            return Outcome.Ok(Unit)
        }

        return Outcome.Err(
            KclipError.AttachmentUnavailable(
                attachmentId = metadata.attachmentId,
                message = "attachment metadata does not contain a remote socket path",
                detail = "run `kclip pair --replace --paste` remotely and attach again",
            ),
        )
    }

    private fun validateControlPath(controlPath: String): Outcome<Unit> {
        val fileStore = fileStore ?: return Outcome.Ok(Unit)
        val processIdentityResolver = processIdentityResolver ?: return Outcome.Ok(Unit)
        val metadata = when (val outcome = fileStore.lstat(controlPath)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return unsafeControlPath(controlPath, outcome.error.message)
        }
        val identity = when (val outcome = processIdentityResolver.current()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val hasExpectedOwner = metadata.ownerUid == identity.uid
        val isSocket = metadata.type == FileType.SOCKET
        val isOwnerOnly = (metadata.permissionMode.toInt() and GROUP_OR_OTHER_PERMISSION_MASK) == 0
        if (hasExpectedOwner && isSocket && isOwnerOnly) {
            return Outcome.Ok(Unit)
        }

        return unsafeControlPath(
            controlPath = controlPath,
            detail = "owner=${metadata.ownerUid}, mode=${metadata.permissionMode}, type=${metadata.type}",
        )
    }

    private fun unsafeControlPath(controlPath: String, detail: String): Outcome.Err {
        return Outcome.Err(
            KclipError.ForwardingRejected(
                message = "unsafe SSH ControlPath: $controlPath",
                detail = detail,
            ),
        )
    }

    private fun runCommand(arguments: List<String>, message: String): Outcome<CommandOutput> {
        val output = commandRunner.run(
            spec = CommandSpec(
                executable = sshExecutable,
                arguments = arguments,
                environment = environment.snapshot(),
                timeoutMillis = SSH_TIMEOUT_MILLIS,
                maxStderrBytes = MAX_SSH_STDERR_BYTES,
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
                    message = message,
                    detail = commandOutput.stderr.decodeToString(),
                ),
            )
        }

        return Outcome.Ok(commandOutput)
    }

    private companion object {
        /** SSH 操作の timeout。 */
        const val SSH_TIMEOUT_MILLIS = 15_000L

        /** SSH stderr を保持する最大 byte 数。 */
        const val MAX_SSH_STDERR_BYTES = 16 * 1024

        /** reconnect 時に remote listener の解放を待つ時間。 */
        const val REMOTE_SOCKET_RECLAIM_DELAY_MILLIS = 1_500L

        /** group / other permission bit の mask。 */
        const val GROUP_OR_OTHER_PERMISSION_MASK = 0x3f
    }
}

/**
 * OpenSSH 呼び出しの argv を組み立てる helper。
 */
object SshForwardingCommands {
    fun config(destination: String): List<String> {
        return listOf("-G", destination)
    }

    fun check(controlPath: String, destination: String): List<String> {
        return listOf(
            "-F",
            "/dev/null",
            "-S",
            controlPath,
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=8",
            "-O",
            "check",
            destination,
        )
    }

    fun startDedicated(controlPath: String, destination: String): List<String> {
        return listOf(
            "-fNT",
            "-M",
            "-S",
            controlPath,
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=8",
            "-o",
            "ControlPersist=no",
            "-o",
            "ExitOnForwardFailure=yes",
            "-o",
            "ClearAllForwardings=yes",
            "-o",
            "PermitLocalCommand=no",
            "-o",
            "StreamLocalBindMask=0177",
            "-o",
            "ForwardAgent=no",
            "-o",
            "ForwardX11=no",
            "-o",
            "RequestTTY=no",
            "-o",
            "Tunnel=no",
            destination,
        )
    }

    fun forward(
        controlPath: String,
        remoteSocketPath: String,
        localSocketPath: String,
        destination: String,
    ): List<String> {
        return listOf(
            "-F",
            "/dev/null",
            "-S",
            controlPath,
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=8",
            "-o",
            "ClearAllForwardings=no",
            "-o",
            "PermitLocalCommand=no",
            "-o",
            "StreamLocalBindUnlink=yes",
            "-O",
            "forward",
            "-R",
            "$remoteSocketPath:$localSocketPath",
            destination,
        )
    }

    fun cancel(
        controlPath: String,
        remoteSocketPath: String,
        localSocketPath: String,
        destination: String,
    ): List<String> {
        return listOf(
            "-F",
            "/dev/null",
            "-S",
            controlPath,
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=8",
            "-o",
            "ClearAllForwardings=no",
            "-o",
            "PermitLocalCommand=no",
            "-O",
            "cancel",
            "-R",
            "$remoteSocketPath:$localSocketPath",
            destination,
        )
    }

    fun exitDedicated(controlPath: String, destination: String): List<String> {
        return listOf(
            "-F",
            "/dev/null",
            "-S",
            controlPath,
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=8",
            "-O",
            "exit",
            destination,
        )
    }
}

/**
 * `ssh -G` の出力を ControlPath 解決に使う parser。
 */
object SshConfigParser {
    fun parse(output: String): SshResolvedConfig {
        val entries = output
            .lineSequence()
            .mapNotNull { line -> parseLine(line) }
            .toMap()
        val controlPath = entries["controlpath"]
            ?.takeUnless { value -> value == "none" }
            ?.takeIf { value -> value.isNotBlank() }
        val hostName = entries["hostname"]?.takeIf { value -> value.isNotBlank() }
        val controlDestination = hostName ?: entries["host"]?.takeIf { value -> value.isNotBlank() }

        return SshResolvedConfig(
            controlPath = controlPath,
            controlDestination = controlDestination,
        )
    }

    private fun parseLine(line: String): Pair<String, String>? {
        val trimmedLine = line.trim()
        if (trimmedLine.isBlank()) {
            return null
        }
        val separatorIndex = trimmedLine.indexOfFirst { character -> character.isWhitespace() }
        if (separatorIndex < 0) {
            return null
        }
        val key = trimmedLine.take(separatorIndex).lowercase()
        val value = trimmedLine.drop(separatorIndex + 1).trim()

        return key to value
    }
}

internal fun parseTransportPreference(value: String): Outcome<AttachTransportPreference> {
    return when (value) {
        "auto" -> Outcome.Ok(AttachTransportPreference.AUTO)
        "controlmaster" -> Outcome.Ok(AttachTransportPreference.CONTROLMASTER)
        "dedicated" -> Outcome.Ok(AttachTransportPreference.DEDICATED)
        else -> Outcome.Err(
            KclipError.InvalidInput(
                message = "unsupported transport: $value",
                detail = "expected one of: auto, controlmaster, dedicated",
            ),
        )
    }
}

internal fun chooseForwardingPlan(
    preference: AttachTransportPreference,
    controlMasterPath: String?,
    controlDestination: String,
    canUseControlMaster: Boolean,
    privateControlPath: String,
    privateControlDestination: String,
): Outcome<SshForwardingPlan> {
    val hasUsableControlMaster = controlMasterPath != null && canUseControlMaster

    return when {
        preference == AttachTransportPreference.DEDICATED -> Outcome.Ok(
            SshForwardingPlan(
                transportKind = AttachTransportKind.DEDICATED,
                controlPath = privateControlPath,
                controlDestination = privateControlDestination,
            ),
        )
        hasUsableControlMaster -> Outcome.Ok(
            SshForwardingPlan(
                transportKind = AttachTransportKind.CONTROLMASTER,
                controlPath = requireNotNull(controlMasterPath),
                controlDestination = controlDestination,
            ),
        )
        preference == AttachTransportPreference.AUTO -> Outcome.Ok(
            SshForwardingPlan(
                transportKind = AttachTransportKind.DEDICATED,
                controlPath = privateControlPath,
                controlDestination = privateControlDestination,
            ),
        )
        else -> Outcome.Err(noUsableControlMasterError(controlDestination, controlMasterPath))
    }
}

private fun noUsableControlMasterError(destination: String, controlPath: String?): KclipError {
    val checkedPath = controlPath?.let { path -> " checked control path: $path" }.orEmpty()

    return KclipError.ForwardingRejected(
        message = "no active ControlMaster matches $destination",
        detail = "pass the same SSH alias/options, pass --control-path, or use --transport=auto.$checkedPath",
    )
}

private fun <T> Outcome<T>.toUnit(): Outcome<Unit> {
    return when (this) {
        is Outcome.Ok -> Outcome.Ok(Unit)
        is Outcome.Err -> this
    }
}
