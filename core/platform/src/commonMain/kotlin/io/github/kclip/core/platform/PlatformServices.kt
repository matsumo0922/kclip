package io.github.kclip.core.platform

import io.github.kclip.core.domain.Deadline
import io.github.kclip.core.domain.Outcome

/**
 * platform 固有 service を束ねる composition root 用の値。
 */
data class PlatformServices(
    val environment: Environment,
    val clock: MonotonicClock,
)

/**
 * process environment を読むための interface。
 */
interface Environment {
    fun get(name: String): String?

    fun snapshot(): Map<String, String>
}

/**
 * monotonic time を扱う clock。
 */
interface MonotonicClock {
    fun nowMillis(): Long

    fun deadlineAfterMillis(durationMillis: Long): Deadline {
        return Deadline(nowMillis() + durationMillis)
    }
}

/**
 * byte stream の入力。
 */
interface ByteInput {
    fun readAll(maxBytes: Int): Outcome<ByteArray>
}

/**
 * byte stream の出力。
 */
interface ByteOutput {
    fun writeAll(bytes: ByteArray): Outcome<Unit>
}
