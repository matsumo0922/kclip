package io.github.kclip.core.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * ClipboardPayload の UTF-8 と size validation のテスト。
 */
class ClipboardPayloadTest {
    @Test
    fun acceptsUtf8WithoutChangingBytes() {
        val bytes = "hello\n日本語".encodeToByteArray()
        val payload = ClipboardPayload.fromUtf8Bytes(bytes, maxBytes = 1024)

        val ok = assertIs<Outcome.Ok<ClipboardPayload>>(payload)

        assertEquals(bytes.size, ok.value.size)
        assertContentEquals(bytes, ok.value.copyBytes())
    }

    @Test
    fun rejectsOversizedPayload() {
        val payload = ClipboardPayload.fromUtf8Bytes("hello".encodeToByteArray(), maxBytes = 4)

        assertIs<Outcome.Err>(payload)
    }

    @Test
    fun rejectsInvalidUtf8() {
        val payload = ClipboardPayload.fromUtf8Bytes(byteArrayOf(0xC3.toByte(), 0x28), maxBytes = 1024)

        assertIs<Outcome.Err>(payload)
    }
}
