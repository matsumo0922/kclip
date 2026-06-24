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
        return SNAPSHOT_NAMES
            .mapNotNull { name -> get(name)?.let { value -> name to value } }
            .toMap()
    }

    private companion object {
        val SNAPSHOT_NAMES = listOf(
            "__CF_USER_TEXT_ENCODING",
            "DBUS_SESSION_BUS_ADDRESS",
            "DISPLAY",
            "HOME",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "LOGNAME",
            "PATH",
            "SSH_AUTH_SOCK",
            "TMPDIR",
            "USER",
            "WAYLAND_DISPLAY",
            "XAUTHORITY",
            "XDG_RUNTIME_DIR",
        )
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
