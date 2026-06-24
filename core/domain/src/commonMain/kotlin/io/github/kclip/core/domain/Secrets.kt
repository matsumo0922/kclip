package io.github.kclip.core.domain

/**
 * diagnostic や log で本文を表示してはならない 16 byte の secret。
 */
class Secret16 private constructor(
    private val bytes: ByteArray,
) {
    init {
        require(bytes.size == BYTE_COUNT)
    }

    fun copyBytes(): ByteArray {
        return bytes.copyOf()
    }

    fun constantTimeEquals(candidate: ByteArray): Boolean {
        if (candidate.size != BYTE_COUNT) return false

        var difference = 0
        for (index in bytes.indices) {
            difference = difference or (bytes[index].toInt() xor candidate[index].toInt())
        }

        return difference == 0
    }

    fun destroy() {
        bytes.fill(0)
    }

    override fun toString(): String {
        return "Secret16(REDACTED)"
    }

    companion object {
        /** secret の byte 数。 */
        private const val BYTE_COUNT = 16

        fun fromBytes(bytes: ByteArray): Outcome<Secret16> {
            if (bytes.size != BYTE_COUNT) {
                return Outcome.Err(
                    KclipError.InvalidInput(
                        message = "secret must be 16 bytes",
                    ),
                )
            }

            return Outcome.Ok(Secret16(bytes.copyOf()))
        }
    }
}

/**
 * clipboard 本文や protocol credential などを隠すための表示値。
 */
data class RedactedString(
    val label: String,
) {
    override fun toString(): String {
        return "$label(REDACTED)"
    }
}
