package io.github.kclip.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Pairing に由来する secret 表示抑止のテスト。
 */
class PairingRedactionTest {
    @Test
    fun pairingCodeToStringDoesNotExposeDisplayValue() {
        val code = PairingCode("KC1-6X4P-9Q2K-H7MT-W3DN")

        assertEquals("PairingCode(REDACTED)", code.toString())
        assertFalse(code.toString().contains(code.displayValue))
    }

    @Test
    fun pairingMaterialToStringDoesNotExposeDerivedSecrets() {
        val secretBytes = ByteArray(size = 16) { index -> index.toByte() }
        val secret = assertIs<Outcome.Ok<Secret16>>(Secret16.fromBytes(secretBytes)).value
        val material = PairingMaterial(
            code = PairingCode("KC1-6X4P-9Q2K-H7MT-W3DN"),
            socketId = SocketId("kclip-pairing-socket-id"),
            credential = PairCredential(secret),
        )
        val text = material.toString()

        assertEquals("PairingMaterial(REDACTED)", text)
        assertFalse(text.contains(material.code.displayValue))
        assertFalse(text.contains(material.socketId.value))
        assertFalse(text.contains("15"))
    }
}
