package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import kotlin.system.exitProcess

/**
 * CLI の backend option を domain value へ変換する helper。
 */
fun parseBackendPreference(value: String): Outcome<BackendPreference> {
    return when (value) {
        "auto" -> Outcome.Ok(BackendPreference.AUTO)
        "system" -> Outcome.Ok(BackendPreference.SYSTEM)
        "attachment" -> Outcome.Ok(BackendPreference.ATTACHMENT)
        "osc52" -> Outcome.Ok(BackendPreference.OSC52)
        else -> Outcome.Err(
            KclipError.InvalidInput(
                message = "invalid backend: $value",
                detail = "expected one of: auto, system, attachment, osc52",
            ),
        )
    }
}

/**
 * typed error を表示して process を終了する。
 */
fun CliktCommand.exitWith(error: KclipError): Nothing {
    echo("kclip: ${error.message}", err = true)
    error.detail
        ?.takeIf { detail -> detail.isNotBlank() }
        ?.let { detail ->
            echo("detail: $detail", err = true)
        }

    exitProcess(error.exitCode)
}
