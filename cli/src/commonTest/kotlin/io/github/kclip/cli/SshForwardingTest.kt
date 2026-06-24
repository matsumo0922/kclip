package io.github.kclip.cli

import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun unlinkRemoteSocketCommandUsesRemoteRmWithoutShell() {
        val arguments = SshForwardingCommands.unlinkRemoteSocket(
            remoteSocketPath = "/tmp/kclip-0123456789abcdef01234567.sock",
            destination = "dev.example.com",
        )

        assertEquals(
            listOf(
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=8",
                "dev.example.com",
                "rm",
                "-f",
                "--",
                "/tmp/kclip-0123456789abcdef01234567.sock",
            ),
            arguments,
        )
    }

    @Test
    fun remoteSocketReclaimAllowsOnlyKclipTmpSocketPaths() {
        assertTrue(isKclipRemoteSocketPath("/tmp/kclip-0123456789abcdef01234567.sock"))
        assertFalse(isKclipRemoteSocketPath("/tmp/not-kclip-0123456789abcdef01234567.sock"))
        assertFalse(isKclipRemoteSocketPath("/tmp/kclip-0123456789abcdef0123456x.sock"))
        assertFalse(isKclipRemoteSocketPath("/tmp/kclip-0123456789abcdef01234567"))
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
}
