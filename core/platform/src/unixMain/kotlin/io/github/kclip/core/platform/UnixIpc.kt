@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kclip.core.platform

import io.github.kclip.core.domain.Deadline
import io.github.kclip.core.domain.IpcEndpoint
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.ProtocolByteChannel
import io.github.kclip.core.domain.TtyIdentity
import io.github.kclip.core.platform.spawn.kclip_current_uid
import io.github.kclip.core.platform.spawn.kclip_ensure_private_dir
import io.github.kclip.core.platform.spawn.kclip_spawn_background_with_config_fd
import io.github.kclip.core.platform.spawn.kclip_stat_identity
import io.github.kclip.core.platform.spawn.kclip_unix_accept
import io.github.kclip.core.platform.spawn.kclip_unix_bind_listener
import io.github.kclip.core.platform.spawn.kclip_unix_connect
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.errno
import platform.posix.lseek
import platform.posix.mkstemp
import platform.posix.read
import platform.posix.strerror
import platform.posix.unlink
import platform.posix.write

/**
 * Unix domain socket へ接続する IPC adapter。
 */
class UnixIpcConnector : IpcConnector {
    override fun connect(endpoint: IpcEndpoint, deadline: Deadline): Outcome<ProtocolByteChannel> {
        if (endpoint !is IpcEndpoint.UnixSocket) {
            return Outcome.Err(
                KclipError.ProtocolFailure(
                    message = "unsupported IPC endpoint",
                ),
            )
        }

        val fileDescriptor = kclip_unix_connect(endpoint.path)
        if (fileDescriptor < 0) {
            return lastErrno("failed to connect IPC endpoint")
        }

        return Outcome.Ok(UnixProtocolByteChannel(fileDescriptor))
    }
}

/**
 * Unix domain socket listener を作成する IPC adapter。
 */
class UnixIpcServer : IpcServer {
    override fun listen(endpoint: IpcEndpoint): Outcome<IpcListener> {
        if (endpoint !is IpcEndpoint.UnixSocket) {
            return Outcome.Err(
                KclipError.ProtocolFailure(
                    message = "unsupported IPC endpoint",
                ),
            )
        }

        val listenerDescriptor = kclip_unix_bind_listener(endpoint.path, LISTEN_BACKLOG)
        if (listenerDescriptor < 0) {
            return lastErrno("failed to bind IPC listener")
        }

        return Outcome.Ok(UnixIpcListener(listenerDescriptor, endpoint.path))
    }

    private companion object {
        const val LISTEN_BACKLOG = 8
    }
}

/**
 * Unix domain socket listener。
 */
class UnixIpcListener(
    private val fileDescriptor: Int,
    private val socketPath: String,
) : IpcListener {
    override fun accept(deadline: Deadline): Outcome<ProtocolByteChannel> {
        val acceptedDescriptor = kclip_unix_accept(fileDescriptor)
        if (acceptedDescriptor < 0) {
            return lastErrno("failed to accept IPC connection")
        }

        return Outcome.Ok(UnixProtocolByteChannel(acceptedDescriptor))
    }

    override fun close(): Outcome<Unit> {
        close(fileDescriptor)
        unlink(socketPath)

        return Outcome.Ok(Unit)
    }
}

/**
 * fd backed protocol byte channel。
 */
class UnixProtocolByteChannel(
    private val fileDescriptor: Int,
) : ProtocolByteChannel {
    override fun read(destination: ByteArray, offset: Int, length: Int, deadline: Deadline): Outcome<Int> {
        val readBytes = destination.usePinned { pinned ->
            read(
                fileDescriptor,
                pinned.addressOf(offset),
                length.convert(),
            )
        }
        if (readBytes < 0) {
            return lastErrno("failed to read IPC bytes")
        }

        return Outcome.Ok(readBytes.toInt())
    }

    override fun writeAll(source: ByteArray, deadline: Deadline): Outcome<Unit> {
        var writtenBytes = 0

        while (writtenBytes < source.size) {
            val writeBytes = source.usePinned { pinned ->
                write(
                    fileDescriptor,
                    pinned.addressOf(writtenBytes),
                    (source.size - writtenBytes).convert(),
                )
            }
            if (writeBytes < 0) {
                return lastErrno("failed to write IPC bytes")
            }

            writtenBytes += writeBytes.toInt()
        }

        return Outcome.Ok(Unit)
    }

    override fun close(): Outcome<Unit> {
        close(fileDescriptor)

        return Outcome.Ok(Unit)
    }
}

/**
 * secret config fd を渡して background kclip process を起動する launcher。
 */
class UnixBackgroundProcessLauncher : BackgroundProcessLauncher {
    override fun launch(spec: BackgroundProcessSpec, configBytes: ByteArray): Outcome<BackgroundProcess> {
        val configFile = createConfigFile(configBytes)
        if (configFile is Outcome.Err) {
            return configFile
        }

        val file = (configFile as Outcome.Ok<OpenTemporaryFile>).value
        return try {
            spawnProcess(spec, file.fileDescriptor)
        } finally {
            close(file.fileDescriptor)
            unlink(file.path)
        }
    }

    private fun spawnProcess(spec: BackgroundProcessSpec, configFileDescriptor: Int): Outcome<BackgroundProcess> {
        return memScoped {
            val argvValues = listOf(spec.executable) + spec.arguments
            val argv = allocArray<CPointerVar<ByteVar>>(argvValues.size + 1)
            argvValues.forEachIndexed { index, value ->
                argv[index] = value.cstr.ptr
            }
            argv[argvValues.size] = null

            val environmentValues = spec.environment.map { entry -> "${entry.key}=${entry.value}" }
            val environmentPointer = allocArray<CPointerVar<ByteVar>>(environmentValues.size + 1)
            environmentValues.forEachIndexed { index, value ->
                environmentPointer[index] = value.cstr.ptr
            }
            environmentPointer[environmentValues.size] = null

            val processId = allocArray<platform.posix.pid_tVar>(1)
            val spawnResult = kclip_spawn_background_with_config_fd(
                processId,
                spec.executable,
                argv,
                environmentPointer,
                configFileDescriptor,
                CHILD_CONFIG_FD,
                spec.stdoutPath,
                spec.stderrPath,
            )
            if (spawnResult != 0) {
                return@memScoped errnoResult(
                    message = "failed to spawn background process",
                    errorNumber = spawnResult,
                )
            }

            Outcome.Ok(BackgroundProcess(processId[0]))
        }
    }

    private companion object {
        const val CHILD_CONFIG_FD = 3
    }
}

/**
 * Unix runtime path helper。
 */
class UnixRuntimePaths(
    private val environment: Environment,
) : RuntimePaths {
    override fun runtimeRoot(): Outcome<String> {
        val runtimeDirectory = environment.get("XDG_RUNTIME_DIR")
        val root = if (runtimeDirectory.isNullOrBlank()) {
            "/tmp/kclip-${kclip_current_uid()}"
        } else {
            "$runtimeDirectory/kclip"
        }

        return ensureDirectory(root)
    }

    override fun attachmentsDirectory(): Outcome<String> {
        return childDirectory("attachments")
    }

    override fun bindingsDirectory(): Outcome<String> {
        return childDirectory("bindings")
    }

    override fun agentsDirectory(): Outcome<String> {
        return childDirectory("agents")
    }

    override fun sshDirectory(): Outcome<String> {
        return childDirectory("ssh")
    }

    private fun childDirectory(name: String): Outcome<String> {
        val root = runtimeRoot()
        if (root is Outcome.Err) {
            return root
        }

        return ensureDirectory("${(root as Outcome.Ok<String>).value}/$name")
    }

    private fun ensureDirectory(path: String): Outcome<String> {
        val result = kclip_ensure_private_dir(path)
        if (result != 0) {
            return lastErrno("failed to create runtime directory")
        }

        return Outcome.Ok(path)
    }
}

/**
 * `/dev/tty` の device/inode を取得する resolver。
 */
class UnixTtyIdentityResolver : TtyIdentityResolver {
    override fun current(): Outcome<TtyIdentity> {
        return memScoped {
            val device = allocArray<ULongVar>(1)
            val inode = allocArray<ULongVar>(1)
            val result = kclip_stat_identity("/dev/tty", device, inode)
            if (result != 0) {
                return@memScoped lastErrno("failed to resolve current TTY")
            }

            Outcome.Ok(
                TtyIdentity(
                    device = device[0],
                    inode = inode[0],
                    displayPath = "/dev/tty",
                ),
            )
        }
    }
}

private data class OpenTemporaryFile(
    val path: String,
    val fileDescriptor: Int,
)

private fun createConfigFile(configBytes: ByteArray): Outcome<OpenTemporaryFile> {
    val template = "/tmp/kclip-agent-config.XXXXXX"
    val pathBytes = template.encodeToByteArray() + byteArrayOf(0)

    return pathBytes.usePinned { pinned ->
        val fileDescriptor = mkstemp(pinned.addressOf(0))
        if (fileDescriptor < 0) {
            return@usePinned lastErrno("failed to create config file")
        }

        val path = pinned.addressOf(0).toKString()
        val writeOutcome = writeFile(fileDescriptor, configBytes)
        if (writeOutcome is Outcome.Err) {
            close(fileDescriptor)
            unlink(path)

            return@usePinned writeOutcome
        }
        lseek(fileDescriptor, 0, SEEK_SET)

        Outcome.Ok(
            OpenTemporaryFile(
                path = path,
                fileDescriptor = fileDescriptor,
            ),
        )
    }
}

private fun writeFile(fileDescriptor: Int, bytes: ByteArray): Outcome<Unit> {
    var writtenBytes = 0

    while (writtenBytes < bytes.size) {
        val writeBytes = bytes.usePinned { pinned ->
            write(
                fileDescriptor,
                pinned.addressOf(writtenBytes),
                (bytes.size - writtenBytes).convert(),
            )
        }
        if (writeBytes < 0) {
            return lastErrno("failed to write config file")
        }

        writtenBytes += writeBytes.toInt()
    }

    return Outcome.Ok(Unit)
}

private fun errnoResult(message: String, errorNumber: Int): Outcome.Err {
    return Outcome.Err(
        KclipError.SubprocessFailure(
            command = "unix",
            status = errorNumber,
            message = message,
            detail = strerror(errorNumber)?.toKString(),
        ),
    )
}

private fun lastErrno(message: String): Outcome.Err {
    return errnoResult(message, errno)
}
