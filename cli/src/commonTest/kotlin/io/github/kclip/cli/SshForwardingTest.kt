package io.github.kclip.cli

import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.Secret16
import io.github.kclip.core.platform.CommandOutput
import io.github.kclip.core.platform.CommandRunner
import io.github.kclip.core.platform.CommandSpec
import io.github.kclip.core.platform.CurrentProcessIdentity
import io.github.kclip.core.platform.Environment
import io.github.kclip.core.platform.FileMetadata
import io.github.kclip.core.platform.FileStore
import io.github.kclip.core.platform.FileType
import io.github.kclip.core.platform.ProcessIdentityResolver
import io.github.kclip.core.platform.Sleeper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SSH forwarding helper の test。
 */
class SshForwardingTest {
    @Test
    fun parseSshConfigExtractsExpandedControlPathAndHostname() {
        val config = SshConfigParser.parse(
            """
            host dev
            hostname 192.0.2.10
            user daichi
            controlpath /tmp/kclip-cm-daichi@192.0.2.10:22
            """.trimIndent(),
        )

        assertEquals("/tmp/kclip-cm-daichi@192.0.2.10:22", config.controlPath)
        assertEquals("192.0.2.10", config.controlDestination)
    }

    @Test
    fun parseSshConfigTreatsMissingControlPathAsUnavailable() {
        val config = SshConfigParser.parse(
            """
            host dev
            hostname dev.example.com
            controlmaster false
            """.trimIndent(),
        )

        assertNull(config.controlPath)
        assertEquals("dev.example.com", config.controlDestination)
    }

    @Test
    fun forwardCommandIsIsolatedFromUserConfig() {
        val arguments = SshForwardingCommands.forward(
            controlPath = "/tmp/cm.sock",
            remoteSocketPath = "/tmp/remote.sock",
            localSocketPath = "/tmp/local.sock",
            destination = "dev.example.com",
        )

        assertEquals(
            listOf(
                "-F",
                "/dev/null",
                "-S",
                "/tmp/cm.sock",
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
                "/tmp/remote.sock:/tmp/local.sock",
                "dev.example.com",
            ),
            arguments,
        )
    }

    @Test
    fun cancelCommandCancelsOnlyTheExactKclipForward() {
        val arguments = SshForwardingCommands.cancel(
            controlPath = "/tmp/cm.sock",
            remoteSocketPath = "/tmp/remote.sock",
            localSocketPath = "/tmp/local.sock",
            destination = "dev.example.com",
        )

        assertTrue("-O" in arguments)
        assertTrue("cancel" in arguments)
        assertTrue("-R" in arguments)
        assertTrue("/tmp/remote.sock:/tmp/local.sock" in arguments)
        assertTrue("ClearAllForwardings=no" in arguments)
    }

    @Test
    fun autoChoosesControlMasterWhenItIsUsable() {
        val plan = assertIs<Outcome.Ok<SshForwardingPlan>>(
            chooseForwardingPlan(
                preference = AttachTransportPreference.AUTO,
                controlMasterPath = "/tmp/cm.sock",
                controlDestination = "dev.example.com",
                canUseControlMaster = true,
                privateControlPath = "/tmp/private.sock",
                privateControlDestination = "dev.example.com",
            ),
        ).value

        assertEquals(AttachTransportKind.CONTROLMASTER, plan.transportKind)
        assertEquals("/tmp/cm.sock", plan.controlPath)
    }

    @Test
    fun autoFallsBackToDedicatedWhenControlMasterIsUnavailable() {
        val plan = assertIs<Outcome.Ok<SshForwardingPlan>>(
            chooseForwardingPlan(
                preference = AttachTransportPreference.AUTO,
                controlMasterPath = null,
                controlDestination = "dev.example.com",
                canUseControlMaster = false,
                privateControlPath = "/tmp/private.sock",
                privateControlDestination = "dev.example.com",
            ),
        ).value

        assertEquals(AttachTransportKind.DEDICATED, plan.transportKind)
        assertEquals("/tmp/private.sock", plan.controlPath)
    }

    @Test
    fun explicitControlMasterFailsWhenNoMasterIsUsable() {
        val outcome = assertIs<Outcome.Err>(
            chooseForwardingPlan(
                preference = AttachTransportPreference.CONTROLMASTER,
                controlMasterPath = "/tmp/cm.sock",
                controlDestination = "dev.example.com",
                canUseControlMaster = false,
                privateControlPath = "/tmp/private.sock",
                privateControlDestination = "dev.example.com",
            ),
        )

        assertIs<KclipError.ForwardingRejected>(outcome.error)
    }

    @Test
    fun autoEstablishFallsBackToDedicatedWhenNoControlPathExists() {
        val commandRunner = RecordingCommandRunner(
            outputs = mutableListOf(
                commandOutput(stdout = "hostname dev.example.com\n".encodeToByteArray()),
                commandOutput(),
                commandOutput(),
            ),
        )
        val controller = controller(commandRunner)
        val plan = assertIs<Outcome.Ok<SshForwardingPlan>>(
            controller.establish(
                spec = forwardingSpec(),
                preference = AttachTransportPreference.AUTO,
                explicitControlPath = null,
            ),
        ).value

        assertEquals(AttachTransportKind.DEDICATED, plan.transportKind)
        assertEquals(listOf("-G", "dev"), commandRunner.commands[0].arguments)
        assertTrue("-fNT" in commandRunner.commands[1].arguments)
        assertTrue("forward" in commandRunner.commands[2].arguments)
    }

    @Test
    fun controlMasterDetachCancelsForwardWithoutExitingMaster() {
        val commandRunner = RecordingCommandRunner(mutableListOf(commandOutput()))
        val controller = controller(commandRunner)
        val outcome = controller.detach(metadata(AttachTransportKind.CONTROLMASTER))

        assertIs<Outcome.Ok<Unit>>(outcome)
        assertTrue("cancel" in commandRunner.commands.single().arguments)
        assertTrue("exit" !in commandRunner.commands.single().arguments)
    }

    @Test
    fun dedicatedReconnectDoesNotRemoveRemoteSocketOnForwardFailure() {
        val commandRunner = RecordingCommandRunner(
            outputs = mutableListOf(
                commandOutput(),
                commandOutput(),
                commandOutput(exitStatus = 1, stderr = "remote port forwarding failed".encodeToByteArray()),
            ),
        )
        val controller = controller(commandRunner)
        val outcome = controller.reconnect(metadata(AttachTransportKind.DEDICATED))

        assertIs<Outcome.Err>(outcome)
        assertEquals(3, commandRunner.commands.size)
        assertTrue(commandRunner.commands.none { command -> "rm" in command.arguments })
    }

    @Test
    fun unsafeControlPathIsRejectedBeforeRunningSsh() {
        val commandRunner = RecordingCommandRunner(mutableListOf(commandOutput()))
        val controller = controller(
            commandRunner = commandRunner,
            fileStore = FakeFileStore(
                metadata = FileMetadata(
                    ownerUid = 999uL,
                    permissionMode = 384u,
                    type = FileType.SOCKET,
                ),
            ),
        )
        val outcome = controller.detach(metadata(AttachTransportKind.CONTROLMASTER))

        assertIs<Outcome.Err>(outcome)
        assertEquals(emptyList(), commandRunner.commands)
    }

    private fun controller(
        commandRunner: RecordingCommandRunner,
        fileStore: FileStore = FakeFileStore(),
    ): SshForwardingController {
        return SshForwardingController(
            commandRunner = commandRunner,
            environment = FakeEnvironment(),
            fileStore = fileStore,
            processIdentityResolver = FakeProcessIdentityResolver(),
            sleeper = FakeSleeper(),
            sshExecutable = "/usr/bin/ssh",
        )
    }

    private fun forwardingSpec(): SshForwardingSpec {
        return SshForwardingSpec(
            destination = "dev",
            remoteSocketPath = "/tmp/kclip-remote.sock",
            localSocketPath = "/tmp/kclip-local.sock",
            privateControlPath = "/tmp/kclip-private.ctl",
        )
    }

    private fun metadata(transportKind: AttachTransportKind): LocalAttachmentMetadata {
        return LocalAttachmentMetadata(
            attachmentId = attachmentId(),
            agentProcessId = 123,
            destination = "dev",
            controlDestination = "dev.example.com",
            transportKind = transportKind,
            remoteSocketPath = "/tmp/kclip-remote.sock",
            localSocketPath = "/tmp/kclip-local.sock",
            controlPath = "/tmp/cm.sock",
            controlSecret = secret(),
            allowsPaste = true,
        )
    }

    private fun attachmentId(): AttachmentId {
        return assertIs<Outcome.Ok<AttachmentId>>(
            AttachmentId.fromBytes(ByteArray(size = 16) { byteIndex -> byteIndex.toByte() }),
        ).value
    }

    private fun secret(): Secret16 {
        return assertIs<Outcome.Ok<Secret16>>(
            Secret16.fromBytes(ByteArray(size = 16) { byteIndex -> byteIndex.toByte() }),
        ).value
    }

    private fun commandOutput(
        exitStatus: Int = 0,
        stdout: ByteArray = ByteArray(size = 0),
        stderr: ByteArray = ByteArray(size = 0),
    ): CommandOutput {
        return CommandOutput(
            exitStatus = exitStatus,
            stdout = stdout,
            stderr = stderr,
        )
    }
}

/**
 * test 用 command runner。
 */
private class RecordingCommandRunner(
    private val outputs: MutableList<CommandOutput>,
) : CommandRunner {
    val commands = mutableListOf<CommandSpec>()

    override fun run(spec: CommandSpec, stdin: ByteArray?): Outcome<CommandOutput> {
        commands.add(spec)

        return Outcome.Ok(outputs.removeFirst())
    }
}

/**
 * test 用 file store。
 */
private class FakeFileStore(
    private val metadata: FileMetadata = FileMetadata(
        ownerUid = 501uL,
        permissionMode = 384u,
        type = FileType.SOCKET,
    ),
) : FileStore {
    override fun exists(path: String): Outcome<Boolean> = Outcome.Ok(true)

    override fun lstat(path: String): Outcome<FileMetadata> = Outcome.Ok(metadata)

    override fun readBytes(path: String, maxBytes: Int): Outcome<ByteArray> = Outcome.Ok(ByteArray(size = 0))

    override fun writePrivateBytes(path: String, bytes: ByteArray): Outcome<Unit> = Outcome.Ok(Unit)

    override fun delete(path: String): Outcome<Unit> = Outcome.Ok(Unit)
}

/**
 * test 用 process identity resolver。
 */
private class FakeProcessIdentityResolver : ProcessIdentityResolver {
    override fun current(): Outcome<CurrentProcessIdentity> {
        return Outcome.Ok(
            CurrentProcessIdentity(
                uid = 501uL,
                username = "daichi",
                hostname = "local",
            ),
        )
    }
}

/**
 * test 用 environment。
 */
private class FakeEnvironment : Environment {
    override fun get(name: String): String? = null

    override fun snapshot(): Map<String, String> = emptyMap()
}

/**
 * test 用 sleeper。
 */
private class FakeSleeper : Sleeper {
    override fun sleepMillis(durationMillis: Long): Outcome<Unit> = Outcome.Ok(Unit)
}
