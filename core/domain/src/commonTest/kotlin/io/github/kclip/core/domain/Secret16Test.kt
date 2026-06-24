package io.github.kclip.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Secret16 の redaction と比較処理のテスト。
 */
class Secret16Test {
    @Test
    fun toStringDoesNotExposeSecretBytes() {
        val bytes = ByteArray(size = 16) { index -> index.toByte() }
        val secret = Secret16.fromBytes(bytes)

        val ok = assertIs<Outcome.Ok<Secret16>>(secret)

        assertEquals("Secret16(REDACTED)", ok.value.toString())
        assertFalse(ok.value.toString().contains("15"))
    }

    @Test
    fun constantTimeEqualsChecksAllBytes() {
        val bytes = ByteArray(size = 16) { index -> index.toByte() }
        val secret = assertIs<Outcome.Ok<Secret16>>(Secret16.fromBytes(bytes)).value
        val same = bytes.copyOf()
        val different = bytes.copyOf().also { copiedBytes ->
            copiedBytes[15] = 99
        }

        assertTrue(secret.constantTimeEquals(same))
        assertFalse(secret.constantTimeEquals(different))
    }

    @Test
    fun destroyZeroizesSecretBytes() {
        val bytes = ByteArray(size = 16) { index -> (index + 1).toByte() }
        val secret = assertIs<Outcome.Ok<Secret16>>(Secret16.fromBytes(bytes)).value

        secret.destroy()

        assertTrue(secret.copyBytes().all { byteValue -> byteValue == 0.toByte() })
    }
}
