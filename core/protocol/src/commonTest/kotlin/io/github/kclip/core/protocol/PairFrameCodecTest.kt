package io.github.kclip.core.protocol

import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.Secret16
import io.github.kclip.core.domain.TtyIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * PAIR body codec の golden test。
 */
class PairFrameCodecTest {
    @Test
    fun pairFrameMatchesGoldenBytesAndRoundTrips() {
        val frame = PairFrame(
            requestedCapabilities = setOf(ClipboardCapability.COPY, ClipboardCapability.PASTE),
            remoteUid = 501u,
            username = "me",
            hostname = "host",
            ttyIdentity = TtyIdentity(
                device = 111u,
                inode = 222u,
                displayPath = "/dev/ttys001",
            ),
        )

        val encoded = assertIs<Outcome.Ok<ByteArray>>(PairFrameCodec.encodePairFrame(frame)).value
        val decoded = assertIs<Outcome.Ok<PairFrame>>(PairFrameCodec.decodePairFrame(encoded)).value

        assertContentEquals(
            hexBytes("0001000300000000000001F5000000000000006F00000000000000DE00020004000C00006D65686F73742F6465762F74747973303031"),
            encoded,
        )
        assertEquals(frame, decoded)
    }

    @Test
    fun pairAcceptedFrameMatchesGoldenBytesAndRoundTrips() {
        val frame = PairAcceptedFrame(
            attachmentId = attachmentId(),
            attachmentNonce = secret(ByteArray(size = 16) { index -> (index + 16).toByte() }),
            grantedCapabilities = setOf(ClipboardCapability.COPY, ClipboardCapability.PASTE),
            maxCopyBytes = 1024u,
            maxPasteBytes = 2048u,
        )

        val encoded = assertIs<Outcome.Ok<ByteArray>>(PairFrameCodec.encodePairAcceptedFrame(frame)).value
        val decoded = assertIs<Outcome.Ok<PairAcceptedFrame>>(PairFrameCodec.decodePairAcceptedFrame(encoded)).value

        assertContentEquals(
            hexBytes("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F000300000000040000000800"),
            encoded,
        )
        assertEquals(frame.attachmentId, decoded.attachmentId)
        assertContentEquals(frame.attachmentNonce.copyBytes(), decoded.attachmentNonce.copyBytes())
        assertEquals(frame.grantedCapabilities, decoded.grantedCapabilities)
        assertEquals(frame.maxCopyBytes, decoded.maxCopyBytes)
        assertEquals(frame.maxPasteBytes, decoded.maxPasteBytes)
    }

    private fun attachmentId(): AttachmentId {
        return assertIs<Outcome.Ok<AttachmentId>>(
            AttachmentId.fromBytes(ByteArray(size = 16) { index -> index.toByte() }),
        ).value
    }

    private fun secret(bytes: ByteArray): Secret16 {
        return assertIs<Outcome.Ok<Secret16>>(Secret16.fromBytes(bytes)).value
    }

    private fun hexBytes(value: String): ByteArray {
        require(value.length % 2 == 0)

        return ByteArray(size = value.length / 2) { index ->
            value.substring(startIndex = index * 2, endIndex = index * 2 + 2).toInt(radix = 16).toByte()
        }
    }
}
