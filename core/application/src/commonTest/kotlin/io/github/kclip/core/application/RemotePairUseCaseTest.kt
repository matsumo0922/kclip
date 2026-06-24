package io.github.kclip.core.application

import io.github.kclip.core.domain.AttachmentBinding
import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.AttachmentLease
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.EpochSeconds
import io.github.kclip.core.domain.IpcEndpoint
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.PairCredential
import io.github.kclip.core.domain.PairingMaterial
import io.github.kclip.core.domain.Secret16
import io.github.kclip.core.domain.TtyIdentity
import io.github.kclip.core.protocol.PairAcceptedFrame
import io.github.kclip.core.protocol.PairFrame
import io.github.kclip.core.protocol.PairFrameCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * remote PAIR flow と fake local agent の integration test。
 */
class RemotePairUseCaseTest {
    @Test
    fun executeCommitsLeaseBeforeConfirmingPair() {
        val material = pairingMaterial()
        val repository = RecordingAttachmentStateRepository()
        val agentClient = FakePairAgentClient(
            expectedCredential = material.credential,
            acceptedFrame = acceptedFrame(),
            repository = repository,
        )
        val useCase = RemotePairUseCase(agentClient, repository)

        val result = assertIs<Outcome.Ok<RemotePairResult>>(
            useCase.execute(
                options = PairOptions(
                    requestPaste = true,
                    replaceExisting = false,
                ),
                context = remotePairContext(material),
            ),
        ).value

        assertEquals(result.lease, repository.lease)
        assertEquals(result.binding, repository.binding)
        assertTrue(agentClient.confirmObservedCommittedState)
        assertEquals(setOf(ClipboardCapability.COPY, ClipboardCapability.PASTE), result.lease.capabilities)
        assertContentEquals(agentClient.acceptedFrame.attachmentNonce.copyBytes(), result.lease.nonce.copyBytes())
    }

    @Test
    fun executeRejectsBadPairCredentialBeforeCommit() {
        val material = pairingMaterial()
        val repository = RecordingAttachmentStateRepository()
        val agentClient = FakePairAgentClient(
            expectedCredential = PairCredential(secret(ByteArray(size = 16) { 99 })),
            acceptedFrame = acceptedFrame(),
            repository = repository,
        )
        val useCase = RemotePairUseCase(agentClient, repository)

        val error = assertIs<Outcome.Err>(
            useCase.execute(
                options = PairOptions(
                    requestPaste = true,
                    replaceExisting = false,
                ),
                context = remotePairContext(material),
            ),
        ).error

        assertIs<KclipError.ProtocolFailure>(error)
        assertEquals(null, repository.lease)
    }

    private fun pairingMaterial(): PairingMaterial {
        return assertIs<Outcome.Ok<PairingMaterial>>(
            PairingMaterial.fromEntropy(ByteArray(size = 10) { index -> index.toByte() }),
        ).value
    }

    private fun remotePairContext(material: PairingMaterial): RemotePairContext {
        return RemotePairContext(
            material = material,
            endpoint = IpcEndpoint.UnixSocket("/tmp/kclip-a.sock"),
            remoteUid = 501u,
            username = "me",
            hostname = "host",
            ttyIdentity = TtyIdentity(
                device = 111u,
                inode = 222u,
                displayPath = "/dev/ttys001",
            ),
            createdAt = EpochSeconds(1_803_000_000L),
        )
    }

    private fun acceptedFrame(): PairAcceptedFrame {
        return PairAcceptedFrame(
            attachmentId = assertIs<Outcome.Ok<AttachmentId>>(
                AttachmentId.fromBytes(ByteArray(size = 16) { index -> index.toByte() }),
            ).value,
            attachmentNonce = secret(ByteArray(size = 16) { index -> (index + 16).toByte() }),
            grantedCapabilities = setOf(ClipboardCapability.COPY, ClipboardCapability.PASTE),
            maxCopyBytes = 1024u,
            maxPasteBytes = 2048u,
        )
    }

    private fun secret(bytes: ByteArray): Secret16 {
        return assertIs<Outcome.Ok<Secret16>>(Secret16.fromBytes(bytes)).value
    }
}

/**
 * test 用の attachment state repository。
 */
private class RecordingAttachmentStateRepository : AttachmentStateRepository {
    var lease: AttachmentLease? = null
        private set
    var binding: AttachmentBinding? = null
        private set

    override fun commit(lease: AttachmentLease, binding: AttachmentBinding): Outcome<Unit> {
        this.lease = lease
        this.binding = binding

        return Outcome.Ok(Unit)
    }
}

/**
 * test 用の fake local pair agent。
 */
private class FakePairAgentClient(
    private val expectedCredential: PairCredential,
    val acceptedFrame: PairAcceptedFrame,
    private val repository: RecordingAttachmentStateRepository,
) : PairAgentClient {
    var confirmObservedCommittedState = false
        private set

    override fun pair(credential: PairCredential, frame: PairFrame): Outcome<PairAcceptedFrame> {
        val encodedFrame = PairFrameCodec.encodePairFrame(frame)
        if (encodedFrame is Outcome.Err) {
            return encodedFrame
        }
        val decodedFrame = PairFrameCodec.decodePairFrame((encodedFrame as Outcome.Ok<ByteArray>).value)
        if (decodedFrame is Outcome.Err) {
            return decodedFrame
        }
        if (!expectedCredential.secret.constantTimeEquals(credential.secret.copyBytes())) {
            return Outcome.Err(
                KclipError.ProtocolFailure(
                    message = "pair credential was not accepted",
                ),
            )
        }

        return Outcome.Ok(acceptedFrame)
    }

    override fun confirm(acceptedFrame: PairAcceptedFrame): Outcome<Unit> {
        confirmObservedCommittedState = repository.lease?.id == acceptedFrame.attachmentId

        return Outcome.Ok(Unit)
    }
}
