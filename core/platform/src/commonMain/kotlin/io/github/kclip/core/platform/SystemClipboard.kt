package io.github.kclip.core.platform

import io.github.kclip.core.domain.BackendPreference
import io.github.kclip.core.domain.ClipboardBackend
import io.github.kclip.core.domain.ClipboardBackendResolver
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.ClipboardPayload
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.flatMap

/**
 * system clipboard backend の共通実装。
 */
class CommandClipboardBackend(
    override val id: String,
    private val commandRunner: CommandRunner,
    private val copySpec: CommandSpec,
    private val pasteSpec: CommandSpec,
) : ClipboardBackend {
    override val capabilities: Set<ClipboardCapability> =
        setOf(
            ClipboardCapability.COPY,
            ClipboardCapability.PASTE,
        )

    override fun copy(payload: ClipboardPayload): Outcome<Unit> {
        return commandRunner.run(copySpec, payload.copyBytes()).flatMap { output ->
            if (output.exitStatus == 0) {
                Outcome.Ok(Unit)
            } else {
                Outcome.Err(output.toBackendError("clipboard copy command failed"))
            }
        }
    }

    override fun paste(maxBytes: Int): Outcome<ClipboardPayload> {
        val spec = pasteSpec.copy(maxStdoutBytes = maxBytes)

        return commandRunner.run(spec, null).flatMap { output ->
            if (output.exitStatus != 0) {
                return@flatMap Outcome.Err(output.toBackendError("clipboard paste command failed"))
            }

            ClipboardPayload.fromUtf8Bytes(output.stdout, maxBytes)
        }
    }

    private fun CommandOutput.toBackendError(message: String): KclipError {
        val stderrText = stderr.decodeToString()
        val detail = if (stderrText.isBlank()) {
            "exitStatus=$exitStatus"
        } else {
            "exitStatus=$exitStatus stderr=$stderrText"
        }

        return KclipError.BackendUnavailable(
            message = message,
            detail = detail,
        )
    }
}

/**
 * macOS の pbcopy/pbpaste backend を解決する resolver。
 */
class MacOsClipboardBackendResolver(
    private val environment: Environment,
    commandRunner: CommandRunner,
) : ClipboardBackendResolver {
    private val backend = CommandClipboardBackend(
        id = "macos-pbcopy",
        commandRunner = commandRunner,
        copySpec = CommandSpec(
            executable = "/usr/bin/pbcopy",
            environment = macOsClipboardEnvironment(),
        ),
        pasteSpec = CommandSpec(
            executable = "/usr/bin/pbpaste",
            environment = macOsClipboardEnvironment(),
        ),
    )

    override fun resolve(preference: BackendPreference): Outcome<ClipboardBackend> {
        return when (preference) {
            BackendPreference.AUTO,
            BackendPreference.SYSTEM,
            -> Outcome.Ok(backend)

            BackendPreference.ATTACHMENT,
            BackendPreference.OSC52,
            -> Outcome.Err(
                KclipError.BackendUnavailable(
                    message = "requested clipboard backend is not available yet",
                ),
            )
        }
    }

    private fun macOsClipboardEnvironment(): Map<String, String> {
        val names = listOf(
            "__CF_USER_TEXT_ENCODING",
            "HOME",
            "USER",
            "LOGNAME",
            "TMPDIR",
            "PATH",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
        )

        return names
            .mapNotNull { name -> environment.get(name)?.let { value -> name to value } }
            .toMap()
    }
}

/**
 * Linux の Wayland/X11 clipboard backend を環境から解決する resolver。
 */
class LinuxClipboardBackendResolver(
    private val environment: Environment,
    private val executableLookup: ExecutableLookup,
    private val commandRunner: CommandRunner,
) : ClipboardBackendResolver {
    override fun resolve(preference: BackendPreference): Outcome<ClipboardBackend> {
        if (preference != BackendPreference.AUTO && preference != BackendPreference.SYSTEM) {
            return Outcome.Err(
                KclipError.BackendUnavailable(
                    message = "requested clipboard backend is not available yet",
                ),
            )
        }

        val waylandBackend = createWaylandBackend()
        if (waylandBackend != null) {
            return Outcome.Ok(waylandBackend)
        }

        val xclipBackend = createXclipBackend()
        if (xclipBackend != null) {
            return Outcome.Ok(xclipBackend)
        }

        val xselBackend = createXselBackend()
        if (xselBackend != null) {
            return Outcome.Ok(xselBackend)
        }

        return Outcome.Err(
            KclipError.BackendUnavailable(
                message = "no local clipboard backend is available",
                detail = "Install wl-clipboard, xclip, or xsel.",
            ),
        )
    }

    private fun createWaylandBackend(): ClipboardBackend? {
        val hasWaylandDisplay = environment.get("WAYLAND_DISPLAY")?.isNotBlank() == true
        if (!hasWaylandDisplay) return null

        val copyExecutable = executableLookup.findExecutable("wl-copy") ?: return null
        val pasteExecutable = executableLookup.findExecutable("wl-paste") ?: return null
        val environmentValues = clipboardEnvironment()

        return CommandClipboardBackend(
            id = "linux-wl-clipboard",
            commandRunner = commandRunner,
            copySpec = CommandSpec(
                executable = copyExecutable,
                environment = environmentValues,
            ),
            pasteSpec = CommandSpec(
                executable = pasteExecutable,
                arguments = listOf("--no-newline"),
                environment = environmentValues,
            ),
        )
    }

    private fun createXclipBackend(): ClipboardBackend? {
        val hasDisplay = environment.get("DISPLAY")?.isNotBlank() == true
        if (!hasDisplay) return null

        val executable = executableLookup.findExecutable("xclip") ?: return null
        val environmentValues = clipboardEnvironment()

        return CommandClipboardBackend(
            id = "linux-xclip",
            commandRunner = commandRunner,
            copySpec = CommandSpec(
                executable = executable,
                arguments = listOf("-selection", "clipboard", "-in"),
                environment = environmentValues,
            ),
            pasteSpec = CommandSpec(
                executable = executable,
                arguments = listOf("-selection", "clipboard", "-out"),
                environment = environmentValues,
            ),
        )
    }

    private fun createXselBackend(): ClipboardBackend? {
        val hasDisplay = environment.get("DISPLAY")?.isNotBlank() == true
        if (!hasDisplay) return null

        val executable = executableLookup.findExecutable("xsel") ?: return null
        val environmentValues = clipboardEnvironment()

        return CommandClipboardBackend(
            id = "linux-xsel",
            commandRunner = commandRunner,
            copySpec = CommandSpec(
                executable = executable,
                arguments = listOf("--clipboard", "--input"),
                environment = environmentValues,
            ),
            pasteSpec = CommandSpec(
                executable = executable,
                arguments = listOf("--clipboard", "--output"),
                environment = environmentValues,
            ),
        )
    }

    private fun clipboardEnvironment(): Map<String, String> {
        val names = listOf(
            "DISPLAY",
            "WAYLAND_DISPLAY",
            "XAUTHORITY",
            "XDG_RUNTIME_DIR",
            "DBUS_SESSION_BUS_ADDRESS",
            "HOME",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
        )

        return names
            .mapNotNull { name -> environment.get(name)?.let { value -> name to value } }
            .toMap()
    }
}
