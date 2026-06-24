package io.github.kclip.cli

import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.ClipboardOperation
import io.github.kclip.core.domain.ExitCodes
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.platform.Environment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CLI helper のテスト。
 */
class CliSupportTest {
    @Test
    fun parseBackendPreferenceReturnsKnownBackend() {
        val outcome = assertIs<Outcome.Ok<BackendPreference>>(parseBackendPreference("system"))

        assertEquals(BackendPreference.SYSTEM, outcome.value)
    }

    @Test
    fun parseBackendPreferenceRejectsUnknownBackend() {
        val outcome = assertIs<Outcome.Err>(parseBackendPreference("systme"))
        val error = assertIs<KclipError.InvalidInput>(outcome.error)

        assertEquals(ExitCodes.USAGE, error.exitCode)
    }

    @Test
    fun autoPasteRequiresAttachmentInsideSshSession() {
        val requiresAttachment = requiresAttachmentForMissingBinding(
            operation = ClipboardOperation.PASTE,
            preference = BackendPreference.AUTO,
            environment = MapEnvironment(
                values = mapOf("SSH_CONNECTION" to "local remote"),
            ),
        )

        assertTrue(requiresAttachment)
    }

    @Test
    fun systemPasteDoesNotRequireAttachmentInsideSshSession() {
        val requiresAttachment = requiresAttachmentForMissingBinding(
            operation = ClipboardOperation.PASTE,
            preference = BackendPreference.SYSTEM,
            environment = MapEnvironment(
                values = mapOf("SSH_CONNECTION" to "local remote"),
            ),
        )

        assertFalse(requiresAttachment)
    }
}

/**
 * test 用 environment。
 */
private class MapEnvironment(
    private val values: Map<String, String>,
) : Environment {
    override fun get(name: String): String? {
        return values[name]
    }

    override fun snapshot(): Map<String, String> {
        return values
    }
}
