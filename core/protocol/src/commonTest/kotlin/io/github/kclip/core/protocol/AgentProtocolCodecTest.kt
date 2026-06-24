package io.github.kclip.core.protocol

import io.github.kclip.core.domain.Deadline
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.Secret16
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * agent protocol codec の golden / malformed frame テスト。
 */
class AgentProtocolCodecTest {
    private val codec = DefaultAgentProtocolCodec()
    private val deadline = Deadline(epochMillis = 0)

    @Test
    fun writeRequestMatchesGoldenBytes() {
        val channel = MemoryProtocolByteChannel()
        val request = AgentRequest(
            version = AgentProtocolVersion.CURRENT,
            operation = AgentOperation.COPY,
            credential = secret(),
            payload = "hi".encodeToByteArray(),
        )

        assertIs<Outcome.Ok<Unit>>(codec.writeRequest(channel, request, deadline))

        assertContentEquals(
            hexBytes("4B434C5001030000000102030405060708090A0B0C0D0E0F00000002000000006869"),
            channel.writtenBytes(),
        )
    }

    @Test
    fun readRequestParsesPartialReads() {
        val channel = MemoryProtocolByteChannel(
            initialBytes = hexBytes("4B434C5001030000000102030405060708090A0B0C0D0E0F00000002000000006869"),
            maxReadBytes = 3,
        )

        val request = assertIs<Outcome.Ok<AgentRequest>>(codec.readRequest(channel, deadline)).value

        assertEquals(AgentOperation.COPY, request.operation)
        assertContentEquals("hi".encodeToByteArray(), request.payload)
    }

    @Test
    fun readRequestRejectsBadMagic() {
        val channel = MemoryProtocolByteChannel(
            initialBytes = hexBytes("5858585801030000000102030405060708090A0B0C0D0E0F0000000000000000"),
        )

        val error = assertIs<Outcome.Err>(codec.readRequest(channel, deadline)).error

        assertIs<KclipError.ProtocolFailure>(error)
    }

    @Test
    fun readRequestRejectsOversizedCopyBeforeReadingPayload() {
        val channel = MemoryProtocolByteChannel(
            initialBytes = hexBytes("4B434C5001030000000102030405060708090A0B0C0D0E0F0000000500000000FFFF"),
        )
        val limits = ProtocolLimits(maxCopyBytes = 4)

        val error = assertIs<Outcome.Err>(codec.readRequest(channel, limits, deadline)).error

        assertIs<KclipError.TooLarge>(error)
        assertEquals(32, channel.readOffset)
    }

    @Test
    fun writeAndReadResponseRoundTrips() {
        val writeChannel = MemoryProtocolByteChannel()
        val response = AgentResponse(
            version = AgentProtocolVersion.CURRENT,
            status = AgentStatus.OK,
            errorCode = 0u,
            payload = "pong".encodeToByteArray(),
        )

        assertIs<Outcome.Ok<Unit>>(codec.writeResponse(writeChannel, response, deadline))
        val readChannel = MemoryProtocolByteChannel(writeChannel.writtenBytes())
        val decoded = assertIs<Outcome.Ok<AgentResponse>>(codec.readResponse(readChannel, deadline)).value

        assertEquals(AgentStatus.OK, decoded.status)
        assertContentEquals("pong".encodeToByteArray(), decoded.payload)
    }

    private fun secret(): Secret16 {
        val bytes = ByteArray(size = 16) { index -> index.toByte() }

        return assertIs<Outcome.Ok<Secret16>>(Secret16.fromBytes(bytes)).value
    }

    private fun hexBytes(value: String): ByteArray {
        require(value.length % 2 == 0)

        return ByteArray(size = value.length / 2) { index ->
            value.substring(startIndex = index * 2, endIndex = index * 2 + 2).toInt(radix = 16).toByte()
        }
    }
}

/**
 * test 用の protocol byte channel。
 */
private class MemoryProtocolByteChannel(
    private val initialBytes: ByteArray = ByteArray(size = 0),
    private val maxReadBytes: Int = Int.MAX_VALUE,
) : ProtocolByteChannel {
    var readOffset = 0
        private set

    private val output = mutableListOf<Byte>()

    override fun read(destination: ByteArray, offset: Int, length: Int, deadline: Deadline): Outcome<Int> {
        if (readOffset >= initialBytes.size) {
            return Outcome.Ok(0)
        }

        val readableBytes = minOf(length, maxReadBytes, initialBytes.size - readOffset)
        initialBytes.copyInto(
            destination = destination,
            destinationOffset = offset,
            startIndex = readOffset,
            endIndex = readOffset + readableBytes,
        )
        readOffset += readableBytes

        return Outcome.Ok(readableBytes)
    }

    override fun writeAll(source: ByteArray, deadline: Deadline): Outcome<Unit> {
        source.forEach { byteValue -> output.add(byteValue) }

        return Outcome.Ok(Unit)
    }

    fun writtenBytes(): ByteArray {
        return output.toByteArray()
    }
}
