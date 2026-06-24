package io.github.kclip.core.protocol

import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.Secret16
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * protocol object が secret と payload を表示しないことのテスト。
 */
class AgentProtocolRedactionTest {
    @Test
    fun requestToStringRedactsCredentialAndPayload() {
        val secret = assertIs<Outcome.Ok<Secret16>>(Secret16.fromBytes(ByteArray(size = 16) { 7 })).value
        val request = AgentRequest(
            version = AgentProtocolVersion(1u),
            operation = AgentOperation.COPY,
            credential = secret,
            payload = "secret-body".encodeToByteArray(),
        )

        val rendered = request.toString()

        assertFalse(rendered.contains("secret-body"))
        assertFalse(rendered.contains("7, 7"))
    }
}
