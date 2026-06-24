package io.github.kclip.core.diagnostics

/**
 * structured log の重要度。
 */
enum class LogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE,
}

/**
 * log field として安全に扱う値。
 */
sealed interface LogValue {
    /**
     * 公開してよい短い文字列。
     */
    data class Public(
        val value: String,
    ) : LogValue

    /**
     * 件数や byte 数などの数値。
     */
    data class Count(
        val value: Long,
    ) : LogValue

    /**
     * 明示的に伏せた値。
     */
    data object Redacted : LogValue
}

/**
 * kclip の structured logger。
 */
interface Logger {
    fun log(
        level: LogLevel,
        event: String,
        fields: Map<String, LogValue> = emptyMap(),
    )
}
