package io.github.kclip.core.agent

import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.IpcEndpoint
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.PairCredential
import io.github.kclip.core.domain.Secret16
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AttachmentAgentConfigCodec の test。
 */
class AttachmentAgentConfigCodecTest {
    @Test
    fun encodeDecodeRoundTripsConfig() {
        val config = AttachmentAgentConfig(
            endpoint = IpcEndpoint.UnixSocket("/tmp/kclip-test.sock"),
            expectedPairCredential = PairCredential(secret(value = 1)),
            attachmentId = attachmentId(),
            attachmentNonce = secret(value = 2),
            controlSecret = secret(value = 3),
            allowPaste = true,
            maxCopyBytes = 123,
            maxPasteBytes = 456,
        )

        val decoded = assertIs<Outcome.Ok<AttachmentAgentConfig>>(
            AttachmentAgentConfigCodec.decode(AttachmentAgentConfigCodec.encode(config)),
        ).value

        assertEquals(config.endpoint, decoded.endpoint)
        assertEquals(config.attachmentId, decoded.attachmentId)
        assertEquals(config.allowPaste, decoded.allowPaste)
        assertEquals(config.maxCopyBytes, decoded.maxCopyBytes)
        assertEquals(config.maxPasteBytes, decoded.maxPasteBytes)
        assertTrue(config.expectedPairCredential.secret.constantTimeEquals(decoded.expectedPairCredential.secret.copyBytes()))
        assertTrue(config.attachmentNonce.constantTimeEquals(decoded.attachmentNonce.copyBytes()))
        assertTrue(config.controlSecret.constantTimeEquals(decoded.controlSecret.copyBytes()))
    }

    @Test
    fun decodeRejectsMissingSecret() {
        val outcome = AttachmentAgentConfigCodec.decode(
            """
            version=1
            socketPath=/tmp/kclip-test.sock
            attachmentId=000102030405060708090A0B0C0D0E0F
            attachmentNonce=000102030405060708090A0B0C0D0E0F
            controlSecret=000102030405060708090A0B0C0D0E0F
            allowPaste=true
            maxCopyBytes=123
            maxPasteBytes=456
            """.trimIndent().encodeToByteArray(),
        )

        assertIs<Outcome.Err>(outcome)
    }

    private fun attachmentId(): AttachmentId {
        return assertIs<Outcome.Ok<AttachmentId>>(
            AttachmentId.fromBytes(ByteArray(size = 16) { index -> index.toByte() }),
        ).value
    }

    private fun secret(value: Int): Secret16 {
        return assertIs<Outcome.Ok<Secret16>>(
            Secret16.fromBytes(ByteArray(size = 16) { value.toByte() }),
        ).value
    }
}
