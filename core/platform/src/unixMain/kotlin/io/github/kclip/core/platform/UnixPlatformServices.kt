package io.github.kclip.core.platform

import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Unix 環境変数を読む Environment 実装。
 */
class UnixEnvironment : Environment {
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    override fun get(name: String): String? {
        return getenv(name)?.toKString()
    }

    override fun snapshot(): Map<String, String> {
        return emptyMap()
    }
}

/**
 * Unix 系 target で使う monotonic clock の最小実装。
 */
class UnixMonotonicClock : MonotonicClock {
    private val startedAt = kotlin.time.TimeSource.Monotonic.markNow()

    override fun nowMillis(): Long {
        return startedAt.elapsedNow().inWholeMilliseconds
    }
}
