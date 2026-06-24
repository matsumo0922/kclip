package io.github.kclip.core.domain

/**
 * protocol codec が必要とする最小 byte channel。
 */
interface ProtocolByteChannel {
    fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
        deadline: Deadline,
    ): Outcome<Int>

    fun writeAll(source: ByteArray, deadline: Deadline): Outcome<Unit>

    fun close(): Outcome<Unit> {
        return Outcome.Ok(Unit)
    }
}
