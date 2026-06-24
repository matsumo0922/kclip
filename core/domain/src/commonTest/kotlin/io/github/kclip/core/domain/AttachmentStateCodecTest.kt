package io.github.kclip.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * attachment lease と binding の binary codec テスト。
 */
class AttachmentStateCodecTest {
    @Test
    fun leaseRoundTripsWithoutExposingNonceInMetadata() {
        val lease = createLease()
        val encoded = assertIs<Outcome.Ok<ByteArray>>(AttachmentStateCodec.encodeLease(lease)).value
        val decoded = assertIs<Outcome.Ok<AttachmentLease>>(AttachmentStateCodec.decodeLease(encoded)).value

        assertEquals(lease.id, decoded.id)
        assertEquals(lease.endpoint, decoded.endpoint)
        assertEquals(lease.capabilities, decoded.capabilities)
        assertEquals(lease.scope.device, decoded.scope.device)
        assertEquals(lease.scope.inode, decoded.scope.inode)
        assertEquals(lease.createdAt, decoded.createdAt)
        assertEquals("Secret16(REDACTED)", decoded.nonce.toString())
    }

    @Test
    fun leaseRejectsChecksumMismatch() {
        val encoded = assertIs<Outcome.Ok<ByteArray>>(AttachmentStateCodec.encodeLease(createLease())).value
        val corrupted = encoded.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }

        assertIs<Outcome.Err>(AttachmentStateCodec.decodeLease(corrupted))
    }

    @Test
    fun leaseRejectsUnknownCapabilityBitsWithoutThrowing() {
        val encoded = assertIs<Outcome.Ok<ByteArray>>(AttachmentStateCodec.encodeLease(createLease())).value
        val corrupted = encoded.copyOf()
        corrupted[CAPABILITY_OFFSET] = 0
        corrupted[CAPABILITY_OFFSET + 1] = UNKNOWN_CAPABILITY_BIT
        val body = corrupted.copyOfRange(fromIndex = 0, toIndex = corrupted.size - CHECKSUM_BYTES)
        Sha256.digest(body).copyInto(corrupted, destinationOffset = corrupted.size - CHECKSUM_BYTES)

        assertIs<Outcome.Err>(AttachmentStateCodec.decodeLease(corrupted))
    }

    @Test
    fun bindingRoundTripsTtyIdentity() {
        val binding = AttachmentBinding(
            attachmentId = attachmentId(),
            ttyIdentity = TtyIdentity(
                device = 111u,
                inode = 222u,
                displayPath = "/dev/ttys001",
            ),
        )
        val encoded = assertIs<Outcome.Ok<ByteArray>>(AttachmentStateCodec.encodeBinding(binding)).value
        val decoded = assertIs<Outcome.Ok<AttachmentBinding>>(AttachmentStateCodec.decodeBinding(encoded)).value

        assertEquals(binding.attachmentId, decoded.attachmentId)
        assertEquals(binding.ttyIdentity.device, decoded.ttyIdentity.device)
        assertEquals(binding.ttyIdentity.inode, decoded.ttyIdentity.inode)
    }

    private fun createLease(): AttachmentLease {
        return AttachmentLease(
            formatVersion = 1u,
            id = attachmentId(),
            endpoint = IpcEndpoint.UnixSocket("/tmp/kclip-a.sock"),
            nonce = secret(ByteArray(size = 16) { index -> (index + 16).toByte() }),
            capabilities = setOf(ClipboardCapability.COPY, ClipboardCapability.PASTE),
            scope = TtyIdentity(
                device = 111u,
                inode = 222u,
                displayPath = "/dev/ttys001",
            ),
            createdAt = EpochSeconds(1_803_000_000L),
        )
    }

    private fun attachmentId(): AttachmentId {
        val bytes = ByteArray(size = 16) { index -> index.toByte() }

        return assertIs<Outcome.Ok<AttachmentId>>(AttachmentId.fromBytes(bytes)).value
    }

    private fun secret(bytes: ByteArray): Secret16 {
        return assertIs<Outcome.Ok<Secret16>>(Secret16.fromBytes(bytes)).value
    }

    private companion object {
        const val CAPABILITY_OFFSET = 40
        const val CHECKSUM_BYTES = 32
        const val UNKNOWN_CAPABILITY_BIT = 0x04.toByte()
    }
}
