package io.github.kclip.core.domain

/**
 * 利用者にも表示する attachment の識別子。
 */
data class AttachmentId(
    val value: String,
) {
    fun fullBytes(): Outcome<ByteArray> {
        val decoded = HexEncoding.decode(value)
        if (decoded is Outcome.Err) {
            return decoded
        }
        val bytes = (decoded as Outcome.Ok<ByteArray>).value
        if (bytes.size != BYTE_COUNT) {
            return invalidAttachmentId()
        }

        return Outcome.Ok(bytes)
    }

    fun displayValue(): String {
        return "KC-${value.take(DISPLAY_HEX_LENGTH)}"
    }

    override fun toString(): String {
        return "AttachmentId(${displayValue()})"
    }

    companion object {
        private const val BYTE_COUNT = 16
        private const val DISPLAY_HEX_LENGTH = 4

        fun fromBytes(bytes: ByteArray): Outcome<AttachmentId> {
            if (bytes.size != BYTE_COUNT) {
                return invalidAttachmentId()
            }

            return Outcome.Ok(AttachmentId(HexEncoding.encodeUpper(bytes)))
        }

        fun parse(value: String): Outcome<AttachmentId> {
            val normalizedValue = value.removePrefix("KC-").uppercase()
            val decoded = HexEncoding.decode(normalizedValue)
            if (decoded is Outcome.Err) {
                return invalidAttachmentId()
            }
            val bytes = (decoded as Outcome.Ok<ByteArray>).value
            if (bytes.size != BYTE_COUNT) {
                return invalidAttachmentId()
            }

            return Outcome.Ok(AttachmentId(normalizedValue))
        }

        private fun invalidAttachmentId(): Outcome.Err {
            return Outcome.Err(
                KclipError.InvalidInput(
                    message = "attachment id must be 16 bytes",
                ),
            )
        }
    }
}

/**
 * remote TTY を path 再利用から守るための identity。
 */
data class TtyIdentity(
    val device: ULong,
    val inode: ULong,
    val displayPath: String,
)

/**
 * attachment client が agent へ接続する endpoint。
 */
sealed interface IpcEndpoint {
    /**
     * Unix domain socket endpoint。
     */
    data class UnixSocket(
        val path: String,
    ) : IpcEndpoint
}

/**
 * remote に保存する attachment の利用権。
 */
data class AttachmentLease(
    val formatVersion: UShort,
    val id: AttachmentId,
    val endpoint: IpcEndpoint,
    val nonce: Secret16,
    val capabilities: Set<ClipboardCapability>,
    val scope: TtyIdentity,
    val createdAt: EpochSeconds,
)

/**
 * remote TTY binding file の内容。
 */
data class AttachmentBinding(
    val attachmentId: AttachmentId,
    val ttyIdentity: TtyIdentity,
)

/**
 * attachment lease と TTY binding の binary format codec。
 */
object AttachmentStateCodec {
    private const val ATTACHMENT_ID_BYTES = 16
    private const val BINDING_BYTES = 38
    private const val BINDING_FORMAT_VERSION = 1u
    private const val BINDING_MAGIC = "KCLB"
    private const val CAPABILITY_COPY = 1u
    private const val CAPABILITY_PASTE = 2u
    private const val CHECKSUM_BYTES = 32
    private const val ENDPOINT_UNIX_SOCKET = 1
    private const val LEASE_FORMAT_VERSION = 1u
    private const val LEASE_MAGIC = "KCLL"
    private const val MIN_LEASE_BYTES = 102
    private const val SECRET_BYTES = 16

    fun encodeLease(lease: AttachmentLease): Outcome<ByteArray> {
        val endpoint = lease.endpoint
        if (endpoint !is IpcEndpoint.UnixSocket) {
            return Outcome.Err(
                KclipError.InvalidInput(
                    message = "unsupported endpoint type",
                ),
            )
        }

        val endpointBytes = endpoint.path.encodeToByteArray()
        if (endpointBytes.size > UShort.MAX_VALUE.toInt()) {
            return Outcome.Err(
                KclipError.InvalidInput(
                    message = "endpoint path is too long",
                ),
            )
        }
        val attachmentIdBytes = when (val outcome = lease.id.fullBytes()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        val writer = BinaryWriter()
        writer.writeAscii(LEASE_MAGIC)
        writer.writeUShort(LEASE_FORMAT_VERSION)
        writer.writeUShort(0u)
        writer.writeBytes(attachmentIdBytes)
        writer.writeBytes(lease.nonce.copyBytes())
        writer.writeUShort(capabilityBits(lease.capabilities))
        writer.writeByte(ENDPOINT_UNIX_SOCKET)
        writer.writeByte(0)
        writer.writeULong(lease.scope.device)
        writer.writeULong(lease.scope.inode)
        writer.writeLong(lease.createdAt.value)
        writer.writeUShort(endpointBytes.size.toUInt())
        writer.writeBytes(endpointBytes)

        val body = writer.toByteArray()
        val checksum = Sha256.digest(body)

        return Outcome.Ok(body + checksum)
    }

    fun decodeLease(bytes: ByteArray): Outcome<AttachmentLease> {
        if (bytes.size < MIN_LEASE_BYTES) {
            return corruptedState("lease is too short")
        }
        val body = bytes.copyOfRange(fromIndex = 0, toIndex = bytes.size - CHECKSUM_BYTES)
        val expectedChecksum = bytes.copyOfRange(fromIndex = bytes.size - CHECKSUM_BYTES, toIndex = bytes.size)
        if (!Sha256.digest(body).contentEquals(expectedChecksum)) {
            return corruptedState("lease checksum mismatch")
        }

        val reader = BinaryReader(body)
        if (reader.readAscii(LEASE_MAGIC.length) != LEASE_MAGIC) {
            return corruptedState("invalid lease magic")
        }
        val formatVersion = reader.readUShort()
        if (formatVersion != LEASE_FORMAT_VERSION) {
            return unsupportedStateVersion()
        }
        if (reader.readUShort() != 0u) {
            return corruptedState("lease flags must be zero")
        }

        val attachmentId = when (val outcome = AttachmentId.fromBytes(reader.readBytes(ATTACHMENT_ID_BYTES))) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val nonce = when (val outcome = Secret16.fromBytes(reader.readBytes(SECRET_BYTES))) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val capabilities = when (val outcome = capabilitiesFromBits(reader.readUShort())) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val endpointType = reader.readByte()
        if (reader.readByte() != 0) {
            return corruptedState("lease reserved byte must be zero")
        }
        val ttyIdentity = TtyIdentity(
            device = reader.readULong(),
            inode = reader.readULong(),
            displayPath = "",
        )
        val createdAt = EpochSeconds(reader.readLong())
        val endpointLength = reader.readUShort().toInt()
        if (endpointLength > reader.remainingBytes()) {
            return corruptedState("lease endpoint length exceeds body")
        }
        val endpointBytes = reader.readBytes(endpointLength)
        if (!reader.isAtEnd()) {
            return corruptedState("lease has trailing bytes")
        }
        if (endpointType != ENDPOINT_UNIX_SOCKET) {
            return corruptedState("unsupported endpoint type")
        }

        return Outcome.Ok(
            AttachmentLease(
                formatVersion = formatVersion.toUShort(),
                id = attachmentId,
                endpoint = IpcEndpoint.UnixSocket(endpointBytes.decodeToString()),
                nonce = nonce,
                capabilities = capabilities,
                scope = ttyIdentity,
                createdAt = createdAt,
            ),
        )
    }

    fun encodeBinding(binding: AttachmentBinding): Outcome<ByteArray> {
        val attachmentIdBytes = when (val outcome = binding.attachmentId.fullBytes()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val writer = BinaryWriter()
        writer.writeAscii(BINDING_MAGIC)
        writer.writeUShort(BINDING_FORMAT_VERSION)
        writer.writeBytes(attachmentIdBytes)
        writer.writeULong(binding.ttyIdentity.device)
        writer.writeULong(binding.ttyIdentity.inode)

        return Outcome.Ok(writer.toByteArray())
    }

    fun decodeBinding(bytes: ByteArray): Outcome<AttachmentBinding> {
        if (bytes.size != BINDING_BYTES) {
            return corruptedState("binding has invalid length")
        }

        val reader = BinaryReader(bytes)
        if (reader.readAscii(BINDING_MAGIC.length) != BINDING_MAGIC) {
            return corruptedState("invalid binding magic")
        }
        if (reader.readUShort() != BINDING_FORMAT_VERSION) {
            return unsupportedStateVersion()
        }

        val attachmentId = when (val outcome = AttachmentId.fromBytes(reader.readBytes(ATTACHMENT_ID_BYTES))) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return Outcome.Ok(
            AttachmentBinding(
                attachmentId = attachmentId,
                ttyIdentity = TtyIdentity(
                    device = reader.readULong(),
                    inode = reader.readULong(),
                    displayPath = "",
                ),
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
            return corruptedState("lease capability bits are unknown")
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

    private fun corruptedState(message: String): Outcome.Err {
        return Outcome.Err(
            KclipError.ProtocolFailure(
                message = message,
            ),
        )
    }

    private fun unsupportedStateVersion(): Outcome.Err {
        return Outcome.Err(
            KclipError.ProtocolFailure(
                message = "unsupported attachment state format version",
            ),
        )
    }
}

/**
 * big-endian binary writer。
 */
private class BinaryWriter {
    private val bytes = mutableListOf<Byte>()

    fun writeAscii(value: String) {
        writeBytes(value.encodeToByteArray())
    }

    fun writeByte(value: Int) {
        bytes.add(value.toByte())
    }

    fun writeBytes(value: ByteArray) {
        value.forEach { byteValue -> bytes.add(byteValue) }
    }

    fun writeLong(value: Long) {
        for (byteIndex in 0 until LONG_BYTES) {
            val shift = (LONG_BYTES - 1 - byteIndex) * Byte.SIZE_BITS
            writeByte((value ushr shift).toInt())
        }
    }

    fun writeUShort(value: UInt) {
        writeByte((value shr Byte.SIZE_BITS).toInt())
        writeByte(value.toInt())
    }

    fun writeULong(value: ULong) {
        for (byteIndex in 0 until LONG_BYTES) {
            val shift = (LONG_BYTES - 1 - byteIndex) * Byte.SIZE_BITS
            writeByte((value shr shift).toInt())
        }
    }

    fun toByteArray(): ByteArray {
        return bytes.toByteArray()
    }

    private companion object {
        const val LONG_BYTES = 8
    }
}

/**
 * big-endian binary reader。
 */
private class BinaryReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun isAtEnd(): Boolean {
        return offset == bytes.size
    }

    fun remainingBytes(): Int {
        return bytes.size - offset
    }

    fun readAscii(length: Int): String {
        return readBytes(length).decodeToString()
    }

    fun readByte(): Int {
        val value = bytes[offset].toInt() and BYTE_MASK
        offset += 1

        return value
    }

    fun readBytes(length: Int): ByteArray {
        val value = bytes.copyOfRange(fromIndex = offset, toIndex = offset + length)
        offset += length

        return value
    }

    fun readLong(): Long {
        var value = 0L
        repeat(LONG_BYTES) {
            value = (value shl Byte.SIZE_BITS) or readByte().toLong()
        }

        return value
    }

    fun readUShort(): UInt {
        return ((readByte() shl Byte.SIZE_BITS) or readByte()).toUInt()
    }

    fun readULong(): ULong {
        var value = 0uL
        repeat(LONG_BYTES) {
            value = (value shl Byte.SIZE_BITS) or readByte().toULong()
        }

        return value
    }

    private companion object {
        const val BYTE_MASK = 0xff
        const val LONG_BYTES = 8
    }
}
