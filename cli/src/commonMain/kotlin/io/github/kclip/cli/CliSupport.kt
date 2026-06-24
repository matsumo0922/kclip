package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.KclipError
import kotlin.system.exitProcess

/**
 * CLI の backend option を domain value へ変換する helper。
 */
fun parseBackendPreference(value: String): BackendPreference {
    return when (value) {
        "auto" -> BackendPreference.AUTO
        "system" -> BackendPreference.SYSTEM
        "attachment" -> BackendPreference.ATTACHMENT
        "osc52" -> BackendPreference.OSC52
        else -> BackendPreference.AUTO
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
