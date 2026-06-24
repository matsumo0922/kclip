package io.github.kclip.core.platform

import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.ClipboardBackend
import io.github.kclip.core.domain.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Linux clipboard backend discovery のテスト。
 */
class LinuxClipboardBackendResolverTest {
    @Test
    fun prefersWaylandWhenWaylandToolsExist() {
        val resolver = createResolver(
            environment = mapOf(
                "WAYLAND_DISPLAY" to "wayland-0",
                "DISPLAY" to ":0",
            ),
            executables = mapOf(
                "wl-copy" to "/usr/bin/wl-copy",
                "wl-paste" to "/usr/bin/wl-paste",
                "xclip" to "/usr/bin/xclip",
            ),
        )

        val backend = assertIs<Outcome.Ok<ClipboardBackend>>(resolver.resolve(BackendPreference.AUTO)).value

        assertEquals("linux-wl-clipboard", backend.id)
    }

    @Test
    fun fallsBackToXclipWhenWaylandToolsAreMissing() {
        val resolver = createResolver(
            environment = mapOf("DISPLAY" to ":0"),
            executables = mapOf("xclip" to "/usr/bin/xclip"),
        )

        val backend = assertIs<Outcome.Ok<ClipboardBackend>>(resolver.resolve(BackendPreference.AUTO)).value

        assertEquals("linux-xclip", backend.id)
    }

    @Test
    fun returnsUnavailableWhenNoBackendExists() {
        val resolver = createResolver(
            environment = emptyMap(),
            executables = emptyMap(),
        )

        assertIs<Outcome.Err>(resolver.resolve(BackendPreference.AUTO))
    }

    private fun createResolver(
        environment: Map<String, String>,
        executables: Map<String, String>,
    ): LinuxClipboardBackendResolver {
        return LinuxClipboardBackendResolver(
            environment = MapEnvironment(environment),
            executableLookup = MapExecutableLookup(executables),
            commandRunner = NoopCommandRunner(),
        )
    }
}

/**
 * test 用 Environment。
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

/**
 * test 用 executable lookup。
 */
private class MapExecutableLookup(
    private val values: Map<String, String>,
) : ExecutableLookup {
    override fun findExecutable(name: String): String? {
        return values[name]
    }
}

/**
 * discovery test では呼ばれない command runner。
 */
private class NoopCommandRunner : CommandRunner {
    override fun run(spec: CommandSpec, stdin: ByteArray?): Outcome<CommandOutput> {
        return Outcome.Err(
            io.github.kclip.core.domain.KclipError.Internal(
                message = "not used",
            ),
        )
    }
}
