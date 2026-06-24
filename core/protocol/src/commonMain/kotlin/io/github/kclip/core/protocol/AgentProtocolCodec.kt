package io.github.kclip.core.protocol

import io.github.kclip.core.domain.Deadline
import io.github.kclip.core.domain.Outcome

/**
 * agent protocol の byte 入出力を隠蔽する codec。
 */
interface AgentProtocolCodec {
    fun readRequest(channel: ProtocolByteChannel, deadline: Deadline): Outcome<AgentRequest>

    fun writeRequest(
        channel: ProtocolByteChannel,
        request: AgentRequest,
        deadline: Deadline,
    ): Outcome<Unit>

    fun readResponse(channel: ProtocolByteChannel, deadline: Deadline): Outcome<AgentResponse>

    fun writeResponse(
        channel: ProtocolByteChannel,
        response: AgentResponse,
        deadline: Deadline,
    ): Outcome<Unit>
}

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
}
