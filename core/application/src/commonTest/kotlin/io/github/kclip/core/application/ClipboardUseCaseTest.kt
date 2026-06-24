package io.github.kclip.core.application

import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.ClipboardBackend
import io.github.kclip.core.domain.ClipboardBackendResolver
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.ClipboardPayload
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * local clipboard use case のテスト。
 */
class ClipboardUseCaseTest {
    @Test
    fun copyWritesPayloadToBackend() {
        val backend = FakeClipboardBackend()
        val resolver = FixedClipboardBackendResolver(Outcome.Ok(backend))
        val payload = assertPayload("hello\n")

        val result = CopyUseCase(resolver).execute(CopyOptions(), payload)

        assertIs<Outcome.Ok<Unit>>(result)
        assertContentEquals("hello\n".encodeToByteArray(), backend.copiedBytes)
    }

    @Test
    fun pasteReadsPayloadFromBackendWithoutChangingBytes() {
        val backend = FakeClipboardBackend("hello\n日本語".encodeToByteArray())
        val resolver = FixedClipboardBackendResolver(Outcome.Ok(backend))

        val result = PasteUseCase(resolver).execute(PasteOptions())

        val payload = assertIs<Outcome.Ok<ClipboardPayload>>(result).value
        assertContentEquals("hello\n日本語".encodeToByteArray(), payload.copyBytes())
    }

    @Test
    fun copyRejectsPayloadOverLimitBeforeBackendCall() {
        val backend = FakeClipboardBackend()
        val resolver = FixedClipboardBackendResolver(Outcome.Ok(backend))
        val payload = assertPayload("hello")

        val result = CopyUseCase(resolver).execute(CopyOptions(maxBytes = 4), payload)

        assertIs<Outcome.Err>(result)
        assertEquals(0, backend.copyCallCount)
    }

    @Test
    fun pasteReturnsBackendUnavailable() {
        val resolver = FixedClipboardBackendResolver(
            Outcome.Err(
                KclipError.BackendUnavailable(
                    message = "missing",
                ),
            ),
        )

        val result = PasteUseCase(resolver).execute(PasteOptions())

        assertIs<Outcome.Err>(result)
    }

    private fun assertPayload(text: String): ClipboardPayload {
        return assertIs<Outcome.Ok<ClipboardPayload>>(
            ClipboardPayload.fromUtf8Bytes(text.encodeToByteArray(), DefaultClipboardLimits.MAX_BYTES),
        ).value
    }
}

/**
 * test 用の clipboard backend resolver。
 */
private class FixedClipboardBackendResolver(
    private val outcome: Outcome<ClipboardBackend>,
) : ClipboardBackendResolver {
    override fun resolve(preference: BackendPreference): Outcome<ClipboardBackend> {
        return outcome
    }
}

/**
 * test 用の clipboard backend。
 */
private class FakeClipboardBackend(
    private val pasteBytes: ByteArray = ByteArray(size = 0),
) : ClipboardBackend {
    override val id: String = "fake"
    override val capabilities: Set<ClipboardCapability> =
        setOf(
            ClipboardCapability.COPY,
            ClipboardCapability.PASTE,
        )

    var copiedBytes: ByteArray = ByteArray(size = 0)
        private set

    var copyCallCount: Int = 0
        private set

    override fun copy(payload: ClipboardPayload): Outcome<Unit> {
        copyCallCount += 1
        copiedBytes = payload.copyBytes()

        return Outcome.Ok(Unit)
    }

    override fun paste(maxBytes: Int): Outcome<ClipboardPayload> {
        return ClipboardPayload.fromUtf8Bytes(pasteBytes, maxBytes)
    }
}
