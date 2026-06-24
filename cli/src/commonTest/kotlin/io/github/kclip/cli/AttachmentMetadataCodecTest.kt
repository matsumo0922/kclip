package io.github.kclip.cli

import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.Secret16
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * LocalAttachmentMetadataCodec の test。
 */
class AttachmentMetadataCodecTest {
    @Test
    fun decodeLegacyMetadataDefaultsToDedicatedTransport() {
        val metadata = assertIs<Outcome.Ok<LocalAttachmentMetadata>>(
            LocalAttachmentMetadataCodec.decode(
                """
                version=1
                attachmentId=000102030405060708090A0B0C0D0E0F
                agentProcessId=123
                destination=dev
                localSocketPath=/tmp/kclip-local.sock
                controlPath=/tmp/kclip-control.sock
                controlSecret=000102030405060708090A0B0C0D0E0F
                allowsPaste=true
                """.trimIndent().encodeToByteArray(),
            ),
        ).value

        assertEquals(AttachTransportKind.DEDICATED, metadata.transportKind)
        assertEquals("", metadata.remoteSocketPath)
    }

    @Test
    fun encodeDecodeRoundTripPreservesTransportMetadata() {
        val metadata = LocalAttachmentMetadata(
            attachmentId = attachmentId(),
            agentProcessId = 456,
            destination = "dev",
            transportKind = AttachTransportKind.CONTROLMASTER,
            remoteSocketPath = "/tmp/kclip-remote.sock",
            localSocketPath = "/tmp/kclip-local.sock",
            controlPath = "/tmp/ssh-control.sock",
            controlSecret = secret(),
            allowsPaste = false,
        )
        val decoded = assertIs<Outcome.Ok<LocalAttachmentMetadata>>(
            LocalAttachmentMetadataCodec.decode(LocalAttachmentMetadataCodec.encode(metadata)),
        ).value

        assertEquals(AttachTransportKind.CONTROLMASTER, decoded.transportKind)
        assertEquals("/tmp/kclip-remote.sock", decoded.remoteSocketPath)
        assertEquals("/tmp/ssh-control.sock", decoded.controlPath)
    }

    @Test
    fun decodeIgnoresFutureMetadataKeys() {
        val metadata = assertIs<Outcome.Ok<LocalAttachmentMetadata>>(
            LocalAttachmentMetadataCodec.decode(
                """
                version=1
                attachmentId=000102030405060708090A0B0C0D0E0F
                agentProcessId=123
                destination=dev
                transport=controlmaster
                remoteSocketPath=/tmp/kclip-remote.sock
                localSocketPath=/tmp/kclip-local.sock
                controlPath=/tmp/kclip-control.sock
                controlSecret=000102030405060708090A0B0C0D0E0F
                allowsPaste=true
                futureKey=future-value
                """.trimIndent().encodeToByteArray(),
            ),
        ).value

        assertEquals(AttachTransportKind.CONTROLMASTER, metadata.transportKind)
        assertEquals("/tmp/kclip-remote.sock", metadata.remoteSocketPath)
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
}
