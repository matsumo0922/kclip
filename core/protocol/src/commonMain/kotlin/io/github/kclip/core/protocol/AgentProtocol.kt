package io.github.kclip.core.protocol

import io.github.kclip.core.domain.Secret16

/**
 * agent wire protocol の version。
 */
data class AgentProtocolVersion(
    val value: UByte,
) {
    companion object {
        val CURRENT = AgentProtocolVersion(1u)
    }
}

/**
 * agent wire protocol の operation。
 */
enum class AgentOperation(
    val wireValue: UByte,
) {
    PAIR(1u),
    PAIR_CONFIRM(2u),
    COPY(3u),
    PASTE(4u),
    PING(5u),
    SHUTDOWN(6u),
    ;

    companion object {
        fun fromWireValue(value: UByte): AgentOperation? {
            return entries.firstOrNull { operation -> operation.wireValue == value }
        }
    }
}

/**
 * agent response の status。
 */
enum class AgentStatus(
    val wireValue: UByte,
) {
    OK(0u),
    BAD_REQUEST(1u),
    UNAUTHORIZED(2u),
    CAPABILITY_DENIED(3u),
    TOO_LARGE(4u),
    BACKEND_UNAVAILABLE(5u),
    BACKEND_FAILURE(6u),
    VERSION_UNSUPPORTED(7u),
    TIMEOUT(8u),
    PAIRING_EXPIRED(9u),
    PAIRING_CONSUMED(10u),
    ATTACHMENT_NOT_ACTIVE(11u),
    INTERNAL(12u),
    ;

    companion object {
        fun fromWireValue(value: UByte): AgentStatus? {
            return entries.firstOrNull { status -> status.wireValue == value }
        }
    }
}

/**
 * agent へ送る request frame。
 */
data class AgentRequest(
    val version: AgentProtocolVersion,
    val operation: AgentOperation,
    val credential: Secret16,
    val payload: ByteArray,
) {
    override fun toString(): String {
        return "AgentRequest(version=$version, operation=$operation, credential=REDACTED, payload=REDACTED)"
    }
}

/**
 * agent から返る response frame。
 */
data class AgentResponse(
    val version: AgentProtocolVersion,
    val status: AgentStatus,
    val errorCode: UInt,
    val payload: ByteArray,
) {
    override fun toString(): String {
        return "AgentResponse(version=$version, status=$status, errorCode=$errorCode, payload=REDACTED)"
    }
}
