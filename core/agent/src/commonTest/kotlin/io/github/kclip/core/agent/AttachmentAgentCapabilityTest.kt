package io.github.kclip.core.agent

import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.ClipboardBackend
import io.github.kclip.core.domain.ClipboardBackendResolver
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.IpcEndpoint
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.PairCredential
import io.github.kclip.core.domain.Secret16
import io.github.kclip.core.domain.TtyIdentity
import io.github.kclip.core.platform.IpcServer
import io.github.kclip.core.platform.MonotonicClock
import io.github.kclip.core.protocol.AgentOperation
import io.github.kclip.core.protocol.AgentProtocolVersion
import io.github.kclip.core.protocol.AgentRequest
import io.github.kclip.core.protocol.AgentStatus
import io.github.kclip.core.protocol.PairFrame
import io.github.kclip.core.protocol.PairFrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * AttachmentAgent の capability enforcement test。
 */
class AttachmentAgentCapabilityTest {
    @Test
    fun pasteIsDeniedWhenRemoteDidNotRequestPaste() {
        val pairCredential = PairCredential(secret(value = 1))
        val attachmentNonce = secret(value = 2)
        val agent = AttachmentAgent(
            config = AttachmentAgentConfig(
                endpoint = IpcEndpoint.UnixSocket("/tmp/kclip-test.sock"),
                expectedPairCredential = pairCredential,
                attachmentId = attachmentId(),
                attachmentNonce = attachmentNonce,
                controlSecret = secret(value = 3),
                allowPaste = true,
            ),
            ipcServer = UnusedIpcServer,
            clock = FixedClock,
            clipboardBackendResolver = UnusedClipboardBackendResolver,
        )
        val pairPayload = assertIs<Outcome.Ok<ByteArray>>(
            PairFrameCodec.encodePairFrame(
                PairFrame(
                    requestedCapabilities = setOf(ClipboardCapability.COPY),
                    remoteUid = 501u,
                    username = "remote",
                    hostname = "host",
                    ttyIdentity = TtyIdentity(
                        device = 1u,
                        inode = 2u,
                        displayPath = "/dev/ttys001",
                    ),
                ),
            ),
        ).value

        val pairResponse = agent.handleRequestForTest(
            AgentRequest(
                version = AgentProtocolVersion.CURRENT,
                operation = AgentOperation.PAIR,
                credential = pairCredential.secret,
                payload = pairPayload,
            ),
        )
        assertEquals(AgentStatus.OK, pairResponse.status)
        val confirmResponse = agent.handleRequestForTest(
            AgentRequest(
                version = AgentProtocolVersion.CURRENT,
                operation = AgentOperation.PAIR_CONFIRM,
                credential = attachmentNonce,
                payload = attachmentNonce.copyBytes(),
            ),
        )
        assertEquals(AgentStatus.OK, confirmResponse.status)

        val pasteResponse = agent.handleRequestForTest(
            AgentRequest(
                version = AgentProtocolVersion.CURRENT,
                operation = AgentOperation.PASTE,
                credential = attachmentNonce,
                payload = ByteArray(size = 0),
            ),
        )

        assertEquals(AgentStatus.CAPABILITY_DENIED, pasteResponse.status)
    }

    private fun attachmentId(): AttachmentId {
        return assertIs<Outcome.Ok<AttachmentId>>(
            AttachmentId.fromBytes(ByteArray(size = 16) { index -> index.toByte() }),
        ).value
    }

    private fun secret(value: Int): Secret16 {
        return assertIs<Outcome.Ok<Secret16>>(
            Secret16.fromBytes(ByteArray(size = 16) { value.toByte() }),
        ).value
    }
}

/**
 * test で使わない IPC server。
 */
private object UnusedIpcServer : IpcServer {
    override fun listen(endpoint: IpcEndpoint): Outcome<io.github.kclip.core.platform.IpcListener> {
        return Outcome.Err(
            KclipError.Internal(
                message = "unused",
            ),
        )
    }
}

/**
 * test 用 clock。
 */
private object FixedClock : MonotonicClock {
    override fun nowMillis(): Long {
        return 0
    }
}

/**
 * test で呼ばれない clipboard resolver。
 */
private object UnusedClipboardBackendResolver : ClipboardBackendResolver {
    override fun resolve(preference: BackendPreference): Outcome<ClipboardBackend> {
        return Outcome.Err(
            KclipError.Internal(
                message = "unused",
            ),
        )
    }
}
