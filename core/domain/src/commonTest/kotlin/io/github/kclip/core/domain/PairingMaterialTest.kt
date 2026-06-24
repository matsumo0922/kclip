package io.github.kclip.core.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * PairingMaterial の生成と parse のテスト。
 */
class PairingMaterialTest {
    @Test
    fun fromEntropyDerivesDisplayCodeCredentialAndSocketId() {
        val entropy = ByteArray(size = 10) { index -> index.toByte() }
        val material = assertIs<Outcome.Ok<PairingMaterial>>(PairingMaterial.fromEntropy(entropy)).value

        assertEquals("KC1-000G-40R4-0M30-E209", material.code.displayValue)
        assertEquals("A4DA2B983F9C299ED3B2BD68", material.socketId.value)
        assertContentEquals(hexBytes("B74BE73D136C832EE802F404AB43A2F6"), material.credential.secret.copyBytes())
    }

    @Test
    fun parseNormalizesAmbiguousCrockfordCharacters() {
        val code = assertIs<Outcome.Ok<PairingCode>>(PairingCode.parse("kc1-ooog-4or4-om3o-e2o9")).value

        assertEquals("KC1-000G-40R4-0M30-E209", code.displayValue)
    }

    @Test
    fun fromCodeRoundTripsMaterial() {
        val code = assertIs<Outcome.Ok<PairingCode>>(PairingCode.parse("KC1-000G-40R4-0M30-E209")).value
        val material = assertIs<Outcome.Ok<PairingMaterial>>(PairingMaterial.fromCode(code)).value

        assertEquals(code, material.code)
        assertEquals("A4DA2B983F9C299ED3B2BD68", material.socketId.value)
    }

    private fun hexBytes(value: String): ByteArray {
        return assertIs<Outcome.Ok<ByteArray>>(HexEncoding.decode(value)).value
    }
}
