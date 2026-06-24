package io.github.kclip.core.domain

/**
 * kclip の通常失敗を例外ではなく値として返すための結果型。
 */
sealed interface Outcome<out T> {
    /**
     * 成功値を保持する結果。
     */
    data class Ok<T>(
        val value: T,
    ) : Outcome<T>

    /**
     * kclip の利用者向け失敗を保持する結果。
     */
    data class Err(
        val error: KclipError,
    ) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> {
    return when (this) {
        is Outcome.Ok -> Outcome.Ok(transform(value))
        is Outcome.Err -> this
    }
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> {
    return when (this) {
        is Outcome.Ok -> transform(value)
        is Outcome.Err -> this
    }
}
