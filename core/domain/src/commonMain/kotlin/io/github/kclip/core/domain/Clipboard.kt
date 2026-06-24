package io.github.kclip.core.domain

/**
 * clipboard 操作の種類。
 */
enum class ClipboardOperation {
    COPY,
    PASTE,
}

/**
 * clipboard backend の選択方針。
 */
enum class BackendPreference {
    AUTO,
    ATTACHMENT,
    SYSTEM,
    OSC52,
}

/**
 * clipboard backend が提供する能力。
 */
enum class ClipboardCapability {
    COPY,
    PASTE,
}

/**
 * UTF-8 text/plain として扱う clipboard payload。
 */
class ClipboardPayload private constructor(
    private val content: ByteArray,
) {
    val size: Int get() = content.size

    fun copyBytes(): ByteArray {
        return content.copyOf()
    }

    override fun toString(): String {
        return "ClipboardPayload(size=$size, content=REDACTED)"
    }

    companion object {
        fun fromUtf8Bytes(bytes: ByteArray, maxBytes: Int): Outcome<ClipboardPayload> {
            if (bytes.size > maxBytes) {
                return Outcome.Err(
                    KclipError.TooLarge(
                        actualBytes = bytes.size.toLong(),
                        maxBytes = maxBytes,
                    ),
                )
            }

            val decodedText = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: Throwable) {
                return Outcome.Err(
                    KclipError.InvalidInput(
                        message = "clipboard payload must be valid UTF-8",
                    ),
                )
            }
            val normalizedBytes = decodedText.encodeToByteArray()
            val preservesBytes = normalizedBytes.contentEquals(bytes)
            if (!preservesBytes) {
                return Outcome.Err(
                    KclipError.InvalidInput(
                        message = "clipboard payload must be valid UTF-8",
                    ),
                )
            }

            return Outcome.Ok(ClipboardPayload(bytes.copyOf()))
        }
    }
}
