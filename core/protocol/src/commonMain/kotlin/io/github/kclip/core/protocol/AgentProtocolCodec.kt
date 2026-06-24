package io.github.kclip.core.protocol

import io.github.kclip.core.domain.Deadline
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.ProtocolByteChannel
import io.github.kclip.core.domain.Secret16

/**
 * agent protocol の byte 入出力を隠蔽する codec。
 */
interface AgentProtocolCodec {
    fun readRequest(channel: ProtocolByteChannel, deadline: Deadline): Outcome<AgentRequest> {
        return readRequest(channel, ProtocolLimits(), deadline)
    }

    fun readRequest(
        channel: ProtocolByteChannel,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<AgentRequest>

    fun writeRequest(
        channel: ProtocolByteChannel,
        request: AgentRequest,
        deadline: Deadline,
    ): Outcome<Unit> {
        return writeRequest(channel, request, ProtocolLimits(), deadline)
    }

    fun writeRequest(
        channel: ProtocolByteChannel,
        request: AgentRequest,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<Unit>

    fun readResponse(channel: ProtocolByteChannel, deadline: Deadline): Outcome<AgentResponse> {
        return readResponse(channel, ProtocolLimits(), deadline)
    }

    fun readResponse(
        channel: ProtocolByteChannel,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<AgentResponse>

    fun writeResponse(
        channel: ProtocolByteChannel,
        response: AgentResponse,
        deadline: Deadline,
    ): Outcome<Unit> {
        return writeResponse(channel, response, ProtocolLimits(), deadline)
    }

    fun writeResponse(
        channel: ProtocolByteChannel,
        response: AgentResponse,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<Unit>
}

/**
 * protocol parser が allocation 前に確認する上限値。
 */
data class ProtocolLimits(
    val maxPairBodyBytes: Int = 4 * 1024,
    val maxCopyBytes: Int = 1 * 1024 * 1024,
    val maxPasteBytes: Int = 1 * 1024 * 1024,
    val maxPingBytes: Int = 1024,
    val maxErrorBodyBytes: Int = 4 * 1024,
) {
    val maxResponseBodyBytes: Int
        get() = maxOf(maxPasteBytes, maxPingBytes, maxErrorBodyBytes)
}

/**
 * agent wire protocol v1 の binary codec。
 */
class DefaultAgentProtocolCodec : AgentProtocolCodec {
    override fun readRequest(
        channel: ProtocolByteChannel,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<AgentRequest> {
        val header = readExact(channel, REQUEST_HEADER_BYTES, deadline)
        if (header is Outcome.Err) {
            return header
        }

        return parseRequestHeader(channel, (header as Outcome.Ok<ByteArray>).value, limits, deadline)
    }

    override fun writeRequest(
        channel: ProtocolByteChannel,
        request: AgentRequest,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<Unit> {
        val validation = validateRequestPayload(request.operation, request.payload, limits)
        if (validation is Outcome.Err) {
            return validation
        }

        val frame = ByteArray(REQUEST_HEADER_BYTES + request.payload.size)
        writeAscii(frame, offset = 0, value = REQUEST_MAGIC)
        frame[4] = request.version.value.toByte()
        frame[5] = request.operation.wireValue.toByte()
        writeUShort(frame, offset = 6, value = 0u)
        request.credential.copyBytes().copyInto(frame, destinationOffset = 8)
        writeUInt(frame, offset = 24, value = request.payload.size.toUInt())
        writeUInt(frame, offset = 28, value = 0u)
        request.payload.copyInto(frame, destinationOffset = REQUEST_HEADER_BYTES)

        return channel.writeAll(frame, deadline)
    }

    override fun readResponse(
        channel: ProtocolByteChannel,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<AgentResponse> {
        val header = readExact(channel, RESPONSE_HEADER_BYTES, deadline)
        if (header is Outcome.Err) {
            return header
        }

        return parseResponseHeader(channel, (header as Outcome.Ok<ByteArray>).value, limits, deadline)
    }

    override fun writeResponse(
        channel: ProtocolByteChannel,
        response: AgentResponse,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<Unit> {
        val validation = validateResponsePayload(response, limits)
        if (validation is Outcome.Err) {
            return validation
        }

        val frame = ByteArray(RESPONSE_HEADER_BYTES + response.payload.size)
        writeAscii(frame, offset = 0, value = RESPONSE_MAGIC)
        frame[4] = response.version.value.toByte()
        frame[5] = response.status.wireValue.toByte()
        writeUShort(frame, offset = 6, value = 0u)
        writeUInt(frame, offset = 8, value = response.payload.size.toUInt())
        writeUInt(frame, offset = 12, value = response.errorCode)
        response.payload.copyInto(frame, destinationOffset = RESPONSE_HEADER_BYTES)

        return channel.writeAll(frame, deadline)
    }

    private fun parseRequestHeader(
        channel: ProtocolByteChannel,
        header: ByteArray,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<AgentRequest> {
        if (readAscii(header, offset = 0, length = REQUEST_MAGIC.length) != REQUEST_MAGIC) {
            return protocolFailure("invalid request magic")
        }
        val version = header[4].toUByte()
        if (version != AgentProtocolVersion.CURRENT.value) {
            return protocolFailure("unsupported protocol version")
        }
        val operation = AgentOperation.fromWireValue(header[5].toUByte())
            ?: return protocolFailure("unknown request operation")
        if (readUShort(header, offset = 6) != 0u) {
            return protocolFailure("request flags must be zero")
        }
        if (readUInt(header, offset = 28) != 0u) {
            return protocolFailure("request reserved field must be zero")
        }

        val payloadLength = readUInt(header, offset = 24)
        val lengthValidation = validateRequestPayloadLength(operation, payloadLength, limits)
        if (lengthValidation is Outcome.Err) {
            return lengthValidation
        }
        val payload = readExact(channel, payloadLength.toInt(), deadline)
        if (payload is Outcome.Err) {
            return payload
        }

        val credential = Secret16.fromBytes(header.copyOfRange(fromIndex = 8, toIndex = 24))
        if (credential is Outcome.Err) {
            return credential
        }
        val request = AgentRequest(
            version = AgentProtocolVersion(version),
            operation = operation,
            credential = (credential as Outcome.Ok<Secret16>).value,
            payload = (payload as Outcome.Ok<ByteArray>).value,
        )
        val validation = validateRequestPayload(request.operation, request.payload, limits)
        if (validation is Outcome.Err) {
            return validation
        }

        return Outcome.Ok(request)
    }

    private fun parseResponseHeader(
        channel: ProtocolByteChannel,
        header: ByteArray,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<AgentResponse> {
        if (readAscii(header, offset = 0, length = RESPONSE_MAGIC.length) != RESPONSE_MAGIC) {
            return protocolFailure("invalid response magic")
        }
        val version = header[4].toUByte()
        if (version != AgentProtocolVersion.CURRENT.value) {
            return protocolFailure("unsupported protocol version")
        }
        val status = AgentStatus.fromWireValue(header[5].toUByte())
            ?: return protocolFailure("unknown response status")
        if (readUShort(header, offset = 6) != 0u) {
            return protocolFailure("response flags must be zero")
        }

        val payloadLength = readUInt(header, offset = 8)
        if (payloadLength > limits.maxResponseBodyBytes.toUInt()) {
            return tooLarge(payloadLength, limits.maxResponseBodyBytes)
        }
        val isErrorBody = status != AgentStatus.OK
        if (isErrorBody && payloadLength > limits.maxErrorBodyBytes.toUInt()) {
            return tooLarge(payloadLength, limits.maxErrorBodyBytes)
        }
        val payload = readExact(channel, payloadLength.toInt(), deadline)
        if (payload is Outcome.Err) {
            return payload
        }

        return Outcome.Ok(
            AgentResponse(
                version = AgentProtocolVersion(version),
                status = status,
                errorCode = readUInt(header, offset = 12),
                payload = (payload as Outcome.Ok<ByteArray>).value,
            ),
        )
    }

    private fun validateRequestPayloadLength(
        operation: AgentOperation,
        payloadLength: UInt,
        limits: ProtocolLimits,
    ): Outcome<Unit> {
        val maxBytes = when (operation) {
            AgentOperation.PAIR -> limits.maxPairBodyBytes
            AgentOperation.PAIR_CONFIRM -> SECRET_BYTES
            AgentOperation.COPY -> limits.maxCopyBytes
            AgentOperation.PASTE -> 0
            AgentOperation.PING -> 0
            AgentOperation.SHUTDOWN -> 0
        }
        if (payloadLength > maxBytes.toUInt()) {
            return tooLarge(payloadLength, maxBytes)
        }

        return Outcome.Ok(Unit)
    }

    private fun validateRequestPayload(
        operation: AgentOperation,
        payload: ByteArray,
        limits: ProtocolLimits,
    ): Outcome<Unit> {
        val lengthValidation = validateRequestPayloadLength(operation, payload.size.toUInt(), limits)
        if (lengthValidation is Outcome.Err) {
            return lengthValidation
        }
        if (operation == AgentOperation.PAIR_CONFIRM && payload.size != SECRET_BYTES) {
            return protocolFailure("PAIR_CONFIRM body must be 16 bytes")
        }
        val emptyBodyOperations = setOf(AgentOperation.PASTE, AgentOperation.PING, AgentOperation.SHUTDOWN)
        val expectsEmptyBody = operation in emptyBodyOperations
        if (expectsEmptyBody && payload.isNotEmpty()) {
            return protocolFailure("${operation.name} request body must be empty")
        }
        if (operation == AgentOperation.COPY) {
            return validateUtf8(payload)
        }

        return Outcome.Ok(Unit)
    }

    private fun validateResponsePayload(response: AgentResponse, limits: ProtocolLimits): Outcome<Unit> {
        if (response.payload.size > limits.maxResponseBodyBytes) {
            return tooLarge(response.payload.size.toUInt(), limits.maxResponseBodyBytes)
        }
        val isErrorBody = response.status != AgentStatus.OK
        if (isErrorBody && response.payload.size > limits.maxErrorBodyBytes) {
            return tooLarge(response.payload.size.toUInt(), limits.maxErrorBodyBytes)
        }

        return Outcome.Ok(Unit)
    }

    private fun validateUtf8(bytes: ByteArray): Outcome<Unit> {
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)

            Outcome.Ok(Unit)
        } catch (_: Throwable) {
            protocolFailure("payload must be valid UTF-8")
        }
    }

    private fun readExact(
        channel: ProtocolByteChannel,
        length: Int,
        deadline: Deadline,
    ): Outcome<ByteArray> {
        val output = ByteArray(length)
        var offset = 0

        while (offset < length) {
            val read = channel.read(
                destination = output,
                offset = offset,
                length = length - offset,
                deadline = deadline,
            )
            if (read is Outcome.Err) {
                return read
            }
            val readBytes = (read as Outcome.Ok<Int>).value
            if (readBytes <= 0) {
                return protocolFailure("unexpected end of protocol stream")
            }

            offset += readBytes
        }

        return Outcome.Ok(output)
    }

    private fun protocolFailure(message: String): Outcome.Err {
        return Outcome.Err(
            KclipError.ProtocolFailure(
                message = message,
            ),
        )
    }

    private fun tooLarge(actualBytes: UInt, maxBytes: Int): Outcome.Err {
        return Outcome.Err(
            KclipError.TooLarge(
                actualBytes = actualBytes.toLong(),
                maxBytes = maxBytes,
            ),
        )
    }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String {
        return bytes.copyOfRange(fromIndex = offset, toIndex = offset + length).decodeToString()
    }

    private fun readUInt(bytes: ByteArray, offset: Int): UInt {
        return ((bytes[offset].toUInt() and BYTE_MASK) shl 24) or
            ((bytes[offset + 1].toUInt() and BYTE_MASK) shl 16) or
            ((bytes[offset + 2].toUInt() and BYTE_MASK) shl 8) or
            (bytes[offset + 3].toUInt() and BYTE_MASK)
    }

    private fun readUShort(bytes: ByteArray, offset: Int): UInt {
        return ((bytes[offset].toUInt() and BYTE_MASK) shl 8) or
            (bytes[offset + 1].toUInt() and BYTE_MASK)
    }

    private fun writeAscii(bytes: ByteArray, offset: Int, value: String) {
        value.encodeToByteArray().copyInto(bytes, destinationOffset = offset)
    }

    private fun writeUInt(bytes: ByteArray, offset: Int, value: UInt) {
        bytes[offset] = (value shr 24).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun writeUShort(bytes: ByteArray, offset: Int, value: UInt) {
        bytes[offset] = (value shr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private companion object {
        const val REQUEST_HEADER_BYTES = 32
        const val REQUEST_MAGIC = "KCLP"
        const val RESPONSE_HEADER_BYTES = 16
        const val RESPONSE_MAGIC = "KCLR"
        const val SECRET_BYTES = 16
        const val BYTE_MASK = 0xffu
    }
}
