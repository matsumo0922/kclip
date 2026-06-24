package io.github.kclip.core.diagnostics

/**
 * secret を含む可能性のある文字列を既定表示から伏せる helper。
 */
object Redaction {
    fun secret(label: String): String {
        return "$label(REDACTED)"
    }
}
