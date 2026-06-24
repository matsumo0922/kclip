package io.github.kclip.core.domain

/**
 * 利用者が remote から local へ転記する one-time pairing code。
 */
data class PairingCode(
    val displayValue: String,
) {
    override fun toString(): String = "PairingCode(REDACTED)"
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
}
