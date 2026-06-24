package io.github.kclip.cli

import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.ExitCodes
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
}
