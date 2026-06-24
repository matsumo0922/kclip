package io.github.kclip.core.protocol

import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.Secret16
import io.github.kclip.core.domain.TtyIdentity

/**
 * PAIR request body を表す metadata。
 */
data class PairFrame(
    val requestedCapabilities: Set<ClipboardCapability>,
    val remoteUid: ULong,
    val username: String,
    val hostname: String,
    val ttyIdentity: TtyIdentity,
)

/**
 * PAIR success response body を表す metadata。
 */
data class PairAcceptedFrame(
    val attachmentId: AttachmentId,
    val attachmentNonce: Secret16,
    val grantedCapabilities: Set<ClipboardCapability>,
    val maxCopyBytes: UInt,
    val maxPasteBytes: UInt,
)

/**
 * PAIR / PAIR success body の binary codec。
 */
object PairFrameCodec {
    private const val BODY_VERSION = 1u
    private const val CAPABILITY_COPY = 1u
    private const val CAPABILITY_PASTE = 2u
    private const val MAX_PAIR_BODY_BYTES = 4 * 1024
    private const val PAIR_ACCEPTED_BODY_BYTES = 44
    private const val PAIR_FIXED_BODY_BYTES = 36
    private const val SECRET_BYTES = 16

    fun encodePairFrame(frame: PairFrame): Outcome<ByteArray> {
        val usernameBytes = frame.username.encodeToByteArray()
        val hostnameBytes = frame.hostname.encodeToByteArray()
        val ttyPathBytes = frame.ttyIdentity.displayPath.encodeToByteArray()
        val bodyLength = PAIR_FIXED_BODY_BYTES + usernameBytes.size + hostnameBytes.size + ttyPathBytes.size
        if (bodyLength > MAX_PAIR_BODY_BYTES) {
            return tooLarge(bodyLength, MAX_PAIR_BODY_BYTES)
        }

        val writer = ProtocolBinaryWriter()
        writer.writeUShort(BODY_VERSION)
        writer.writeUShort(capabilityBits(frame.requestedCapabilities))
        writer.writeULong(frame.remoteUid)
        writer.writeULong(frame.ttyIdentity.device)
        writer.writeULong(frame.ttyIdentity.inode)
        writer.writeUShort(usernameBytes.size.toUInt())
        writer.writeUShort(hostnameBytes.size.toUInt())
        writer.writeUShort(ttyPathBytes.size.toUInt())
        writer.writeUShort(0u)
        writer.writeBytes(usernameBytes)
        writer.writeBytes(hostnameBytes)
        writer.writeBytes(ttyPathBytes)

        return Outcome.Ok(writer.toByteArray())
    }

    fun decodePairFrame(bytes: ByteArray): Outcome<PairFrame> {
        if (bytes.size < PAIR_FIXED_BODY_BYTES) {
            return protocolFailure("PAIR body is too short")
        }
        if (bytes.size > MAX_PAIR_BODY_BYTES) {
            return tooLarge(bytes.size, MAX_PAIR_BODY_BYTES)
        }

        val reader = ProtocolBinaryReader(bytes)
        if (reader.readUShort() != BODY_VERSION) {
            return protocolFailure("unsupported PAIR body version")
        }
        val capabilities = capabilitiesFromBits(reader.readUShort()).okValueOrReturn()
        val remoteUid = reader.readULong()
        val ttyDevice = reader.readULong()
        val ttyInode = reader.readULong()
        val usernameLength = reader.readUShort().toInt()
        val hostnameLength = reader.readUShort().toInt()
        val ttyPathLength = reader.readUShort().toInt()
        if (reader.readUShort() != 0u) {
            return protocolFailure("PAIR body reserved field must be zero")
        }
        val expectedLength = PAIR_FIXED_BODY_BYTES + usernameLength + hostnameLength + ttyPathLength
        if (expectedLength != bytes.size) {
            return protocolFailure("PAIR body length mismatch")
        }

        return Outcome.Ok(
            PairFrame(
                requestedCapabilities = capabilities,
                remoteUid = remoteUid,
                username = reader.readUtf8(usernameLength).okValueOrReturn(),
                hostname = reader.readUtf8(hostnameLength).okValueOrReturn(),
                ttyIdentity = TtyIdentity(
                    device = ttyDevice,
                    inode = ttyInode,
                    displayPath = reader.readUtf8(ttyPathLength).okValueOrReturn(),
                ),
            ),
        )
    }

    fun encodePairAcceptedFrame(frame: PairAcceptedFrame): Outcome<ByteArray> {
        val attachmentIdBytes = when (val outcome = frame.attachmentId.fullBytes()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val writer = ProtocolBinaryWriter()
        writer.writeBytes(attachmentIdBytes)
        writer.writeBytes(frame.attachmentNonce.copyBytes())
        writer.writeUShort(capabilityBits(frame.grantedCapabilities))
        writer.writeUShort(0u)
        writer.writeUInt(frame.maxCopyBytes)
        writer.writeUInt(frame.maxPasteBytes)

        return Outcome.Ok(writer.toByteArray())
    }

    fun decodePairAcceptedFrame(bytes: ByteArray): Outcome<PairAcceptedFrame> {
        if (bytes.size != PAIR_ACCEPTED_BODY_BYTES) {
            return protocolFailure("PAIR accepted body has invalid length")
        }

        val reader = ProtocolBinaryReader(bytes)
        val attachmentId = AttachmentId.fromBytes(reader.readBytes(SECRET_BYTES)).okValueOrReturn()
        val attachmentNonce = Secret16.fromBytes(reader.readBytes(SECRET_BYTES)).okValueOrReturn()
        val capabilities = capabilitiesFromBits(reader.readUShort()).okValueOrReturn()
        if (reader.readUShort() != 0u) {
            return protocolFailure("PAIR accepted body reserved field must be zero")
        }

        return Outcome.Ok(
            PairAcceptedFrame(
                attachmentId = attachmentId,
                attachmentNonce = attachmentNonce,
                grantedCapabilities = capabilities,
                maxCopyBytes = reader.readUInt(),
                maxPasteBytes = reader.readUInt(),
            ),
        )
    }

    private fun capabilityBits(capabilities: Set<ClipboardCapability>): UInt {
        var bits = 0u
        if (ClipboardCapability.COPY in capabilities) {
            bits = bits or CAPABILITY_COPY
        }
        if (ClipboardCapability.PASTE in capabilities) {
            bits = bits or CAPABILITY_PASTE
        }

        return bits
    }

    private fun capabilitiesFromBits(bits: UInt): Outcome<Set<ClipboardCapability>> {
        val knownBits = CAPABILITY_COPY or CAPABILITY_PASTE
        if (bits and knownBits.inv() != 0u) {
            return protocolFailure("unknown capability bits")
        }

        val capabilities = mutableSetOf<ClipboardCapability>()
        if (bits and CAPABILITY_COPY != 0u) {
            capabilities.add(ClipboardCapability.COPY)
        }
        if (bits and CAPABILITY_PASTE != 0u) {
            capabilities.add(ClipboardCapability.PASTE)
        }

        return Outcome.Ok(capabilities)
    }

    private fun protocolFailure(message: String): Outcome.Err {
        return Outcome.Err(
            KclipError.ProtocolFailure(
                message = message,
            ),
        )
    }

    private fun tooLarge(actualBytes: Int, maxBytes: Int): Outcome.Err {
        return Outcome.Err(
            KclipError.TooLarge(
                actualBytes = actualBytes.toLong(),
                maxBytes = maxBytes,
            ),
        )
    }

    private fun <T> Outcome<T>.okValueOrReturn(): T {
        return (this as Outcome.Ok<T>).value
    }
}

/**
 * protocol body 用 big-endian writer。
 */
private class ProtocolBinaryWriter {
    private val bytes = mutableListOf<Byte>()

    fun writeBytes(value: ByteArray) {
        value.forEach { byteValue -> bytes.add(byteValue) }
    }

    fun writeUInt(value: UInt) {
        writeByte((value shr 24).toInt())
        writeByte((value shr 16).toInt())
        writeByte((value shr 8).toInt())
        writeByte(value.toInt())
    }

    fun writeULong(value: ULong) {
        for (byteIndex in 0 until LONG_BYTES) {
            val shift = (LONG_BYTES - 1 - byteIndex) * Byte.SIZE_BITS
            writeByte((value shr shift).toInt())
        }
    }

    fun writeUShort(value: UInt) {
        writeByte((value shr Byte.SIZE_BITS).toInt())
        writeByte(value.toInt())
    }

    fun toByteArray(): ByteArray {
        return bytes.toByteArray()
    }

    private fun writeByte(value: Int) {
        bytes.add(value.toByte())
    }

    private companion object {
        const val LONG_BYTES = 8
    }
}

/**
 * protocol body 用 big-endian reader。
 */
private class ProtocolBinaryReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun readBytes(length: Int): ByteArray {
        val value = bytes.copyOfRange(fromIndex = offset, toIndex = offset + length)
        offset += length

        return value
    }

    fun readUInt(): UInt {
        return (readByte().toUInt() shl 24) or
            (readByte().toUInt() shl 16) or
            (readByte().toUInt() shl 8) or
            readByte().toUInt()
    }

    fun readULong(): ULong {
        var value = 0uL
        repeat(LONG_BYTES) {
            value = (value shl Byte.SIZE_BITS) or readByte().toULong()
        }

        return value
    }

    fun readUShort(): UInt {
        return (readByte().toUInt() shl Byte.SIZE_BITS) or readByte().toUInt()
    }

    fun readUtf8(length: Int): Outcome<String> {
        return try {
            Outcome.Ok(readBytes(length).decodeToString(throwOnInvalidSequence = true))
        } catch (_: Throwable) {
            Outcome.Err(
                KclipError.ProtocolFailure(
                    message = "PAIR body string must be valid UTF-8",
                ),
            )
        }
    }

    private fun readByte(): Int {
        val value = bytes[offset].toInt() and BYTE_MASK
        offset += 1

        return value
    }

    private companion object {
        const val BYTE_MASK = 0xff
        const val LONG_BYTES = 8
    }
}
