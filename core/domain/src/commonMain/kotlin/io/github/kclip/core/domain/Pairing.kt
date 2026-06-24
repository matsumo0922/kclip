package io.github.kclip.core.domain

/**
 * 利用者が remote から local へ転記する one-time pairing code。
 */
data class PairingCode(
    val displayValue: String,
) {
    override fun toString(): String = "PairingCode(REDACTED)"

    companion object {
        private const val PAIRING_ENTROPY_BYTES = 10
        private const val PAIRING_GROUP_LENGTH = 4
        private const val PAIRING_PREFIX = "KC1"

        fun fromEntropy(entropy: ByteArray): Outcome<PairingCode> {
            if (entropy.size != PAIRING_ENTROPY_BYTES) {
                return Outcome.Err(
                    KclipError.InvalidInput(
                        message = "pairing entropy must be 10 bytes",
                    ),
                )
            }

            return Outcome.Ok(PairingCode(formatDisplayValue(CrockfordBase32.encode(entropy))))
        }

        fun parse(value: String): Outcome<PairingCode> {
            val compactValue = value
                .filterNot { character -> character == '-' || character.isWhitespace() }
                .uppercase()
            if (!compactValue.startsWith(PAIRING_PREFIX)) {
                return invalidPairingCode()
            }

            val encodedEntropy = compactValue.drop(PAIRING_PREFIX.length)
            val normalizedEntropy = normalizeCrockfordText(encodedEntropy)
            val entropy = CrockfordBase32.decode(normalizedEntropy)
            if (entropy is Outcome.Err) {
                return invalidPairingCode()
            }

            return fromEntropy((entropy as Outcome.Ok<ByteArray>).value)
        }

        fun decodeEntropy(code: PairingCode): Outcome<ByteArray> {
            val compactValue = code.displayValue
                .filterNot { character -> character == '-' || character.isWhitespace() }
                .uppercase()
            val encodedEntropy = compactValue.removePrefix(PAIRING_PREFIX)

            return CrockfordBase32.decode(encodedEntropy)
        }

        private fun normalizeCrockfordText(value: String): String {
            return value.map { character ->
                when (character) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> character
                }
            }.joinToString(separator = "")
        }

        private fun formatDisplayValue(encodedEntropy: String): String {
            val groups = encodedEntropy.chunked(size = PAIRING_GROUP_LENGTH)

            return "$PAIRING_PREFIX-${groups.joinToString(separator = "-")}"
        }

        private fun invalidPairingCode(): Outcome.Err {
            return Outcome.Err(
                KclipError.InvalidInput(
                    message = "invalid pairing code",
                ),
            )
        }
    }
}

/**
 * pairing code から導出する remote socket path 用の識別子。
 */
data class SocketId(
    val value: String,
) {
    override fun toString(): String = "SocketId(REDACTED)"
}

/**
 * pairing code から導出する protocol credential。
 */
data class PairCredential(
    val secret: Secret16,
) {
    override fun toString(): String = "PairCredential(REDACTED)"
}

/**
 * local attach に必要な pairing material。
 */
data class PairingMaterial(
    val code: PairingCode,
    val socketId: SocketId,
    val credential: PairCredential,
) {
    override fun toString(): String = "PairingMaterial(REDACTED)"

    companion object {
        private const val PAIR_CREDENTIAL_DOMAIN = "kclip/pair-credential/v1"
        private const val SECRET_BYTES = 16
        private const val SOCKET_ID_BYTES = 12
        private const val SOCKET_ID_DOMAIN = "kclip/remote-socket/v1"

        fun fromEntropy(entropy: ByteArray): Outcome<PairingMaterial> {
            val code = PairingCode.fromEntropy(entropy)
            if (code is Outcome.Err) {
                return code
            }

            val credential = deriveSecret(PAIR_CREDENTIAL_DOMAIN, entropy)
            val socketId = deriveSocketId(entropy)

            return Outcome.Ok(
                PairingMaterial(
                    code = (code as Outcome.Ok<PairingCode>).value,
                    socketId = socketId,
                    credential = PairCredential(credential),
                ),
            )
        }

        fun fromCode(code: PairingCode): Outcome<PairingMaterial> {
            val entropy = PairingCode.decodeEntropy(code)
            if (entropy is Outcome.Err) {
                return entropy
            }

            return fromEntropy((entropy as Outcome.Ok<ByteArray>).value)
        }

        private fun deriveSecret(domain: String, entropy: ByteArray): Secret16 {
            val digest = Sha256.digest(domain.encodeToByteArray() + entropy)
            val secret = Secret16.fromBytes(digest.copyOfRange(fromIndex = 0, toIndex = SECRET_BYTES))

            return (secret as Outcome.Ok<Secret16>).value
        }

        private fun deriveSocketId(entropy: ByteArray): SocketId {
            val digest = Sha256.digest(SOCKET_ID_DOMAIN.encodeToByteArray() + entropy)
            val socketBytes = digest.copyOfRange(fromIndex = 0, toIndex = SOCKET_ID_BYTES)

            return SocketId(HexEncoding.encodeUpper(socketBytes))
        }
    }
}

/**
 * Crockford Base32 の pairing code 用 encoder/decoder。
 */
private object CrockfordBase32 {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val BYTE_MASK = 0xff
    private const val ENCODED_BITS = 5
    private const val ENCODED_MASK = 0x1f
    private const val ENCODED_PAIRING_LENGTH = 16
    private const val PAIRING_ENTROPY_BYTES = 10

    fun encode(bytes: ByteArray): String {
        val output = StringBuilder()
        var bitBuffer = 0
        var bufferedBitCount = 0

        for (byteValue in bytes) {
            bitBuffer = (bitBuffer shl Byte.SIZE_BITS) or (byteValue.toInt() and BYTE_MASK)
            bufferedBitCount += Byte.SIZE_BITS

            while (bufferedBitCount >= ENCODED_BITS) {
                val shift = bufferedBitCount - ENCODED_BITS
                val alphabetIndex = (bitBuffer ushr shift) and ENCODED_MASK
                output.append(ALPHABET[alphabetIndex])
                bufferedBitCount -= ENCODED_BITS
                bitBuffer = bitBuffer and ((1 shl bufferedBitCount) - 1)
            }
        }

        if (bufferedBitCount > 0) {
            val alphabetIndex = (bitBuffer shl (ENCODED_BITS - bufferedBitCount)) and ENCODED_MASK
            output.append(ALPHABET[alphabetIndex])
        }

        return output.toString()
    }

    fun decode(value: String): Outcome<ByteArray> {
        if (value.length != ENCODED_PAIRING_LENGTH) {
            return invalidEncoding()
        }

        val output = ByteArray(size = PAIRING_ENTROPY_BYTES)
        var outputIndex = 0
        var bitBuffer = 0
        var bufferedBitCount = 0

        for (character in value) {
            val alphabetIndex = ALPHABET.indexOf(character)
            if (alphabetIndex < 0) {
                return invalidEncoding()
            }

            bitBuffer = (bitBuffer shl ENCODED_BITS) or alphabetIndex
            bufferedBitCount += ENCODED_BITS

            while (bufferedBitCount >= Byte.SIZE_BITS) {
                val shift = bufferedBitCount - Byte.SIZE_BITS
                output[outputIndex] = ((bitBuffer ushr shift) and BYTE_MASK).toByte()
                outputIndex += 1
                bufferedBitCount -= Byte.SIZE_BITS
                bitBuffer = bitBuffer and ((1 shl bufferedBitCount) - 1)
            }
        }

        return Outcome.Ok(output)
    }

    private fun invalidEncoding(): Outcome.Err {
        return Outcome.Err(
            KclipError.InvalidInput(
                message = "invalid Crockford Base32 text",
            ),
        )
    }
}

/**
 * binary state と path fragment 用の hex encoder。
 */
internal object HexEncoding {
    private const val BYTE_MASK = 0xff
    private const val HEX_ALPHABET = "0123456789ABCDEF"
    private const val HEX_BITS = 4
    private const val HEX_CHARS_PER_BYTE = 2
    private const val HEX_MASK = 0x0f
    private const val INVALID_NIBBLE = -1

    fun encodeUpper(bytes: ByteArray): String {
        val output = StringBuilder(capacity = bytes.size * HEX_CHARS_PER_BYTE)

        for (byteValue in bytes) {
            val unsignedValue = byteValue.toInt() and BYTE_MASK
            output.append(HEX_ALPHABET[unsignedValue ushr HEX_BITS])
            output.append(HEX_ALPHABET[unsignedValue and HEX_MASK])
        }

        return output.toString()
    }

    fun decode(value: String): Outcome<ByteArray> {
        val normalizedValue = value.uppercase()
        if (normalizedValue.length % HEX_CHARS_PER_BYTE != 0) {
            return invalidHex()
        }

        val output = ByteArray(size = normalizedValue.length / HEX_CHARS_PER_BYTE)
        for (byteIndex in output.indices) {
            val firstNibble = decodeNibble(normalizedValue[byteIndex * HEX_CHARS_PER_BYTE])
            val secondNibble = decodeNibble(normalizedValue[byteIndex * HEX_CHARS_PER_BYTE + 1])
            if (firstNibble == INVALID_NIBBLE || secondNibble == INVALID_NIBBLE) {
                return invalidHex()
            }

            output[byteIndex] = ((firstNibble shl HEX_BITS) or secondNibble).toByte()
        }

        return Outcome.Ok(output)
    }

    private fun decodeNibble(character: Char): Int {
        return HEX_ALPHABET.indexOf(character)
    }

    private fun invalidHex(): Outcome.Err {
        return Outcome.Err(
            KclipError.InvalidInput(
                message = "invalid hex text",
            ),
        )
    }
}
