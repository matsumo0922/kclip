package io.github.kclip.core.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * SHA-256 実装の known-answer test。
 */
class Sha256Test {
    @Test
    fun digestMatchesKnownAnswerForAbc() {
        val digest = Sha256.digest("abc".encodeToByteArray())

        assertContentEquals(
            assertIs<Outcome.Ok<ByteArray>>(
                HexEncoding.decode("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"),
            ).value,
            digest,
        )
    }
}
