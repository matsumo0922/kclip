package io.github.kclip.core.platform

import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
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
import platform.posix.F_OK
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SIGKILL
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.WNOHANG
import platform.posix.X_OK
import platform.posix._exit
import platform.posix.access
import platform.posix.close
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execve
import platform.posix.fork
import platform.posix.kill
import platform.posix.mkstemp
import platform.posix.open
import platform.posix.read
import platform.posix.strerror
import platform.posix.unlink
import platform.posix.usleep
import platform.posix.waitpid
import platform.posix.write

/**
 * Unix の PATH から実行ファイルを探す lookup。
 */
class UnixExecutableLookup(
    private val environment: Environment,
) : ExecutableLookup {
    override fun findExecutable(name: String): String? {
        if (name.contains('/')) {
            return name.takeIf { path -> isExecutable(path) }
        }

        return searchPath()
            .firstNotNullOfOrNull { directory ->
                val candidate = "$directory/$name"

                candidate.takeIf { path -> isExecutable(path) }
            }
    }

    private fun searchPath(): List<String> {
        val path = environment.get("PATH") ?: DEFAULT_PATH

        return path
            .split(":")
            .filter { directory -> directory.isNotBlank() }
    }

    private fun isExecutable(path: String): Boolean {
        return access(path, F_OK) == 0 && access(path, X_OK) == 0
    }

    private companion object {
        const val DEFAULT_PATH = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
    }
}

/**
 * Unix の標準入力を byte 列として読む adapter。
 */
class UnixByteInput(
    private val fileDescriptor: Int = STDIN_FILENO,
) : ByteInput {
    override fun readAll(maxBytes: Int): Outcome<ByteArray> {
        return readFileDescriptor(fileDescriptor, maxBytes)
    }
}

/**
 * Unix の標準出力へ byte 列を書き込む adapter。
 */
class UnixByteOutput(
    private val fileDescriptor: Int = STDOUT_FILENO,
) : ByteOutput {
    override fun writeAll(bytes: ByteArray): Outcome<Unit> {
        return writeFileDescriptor(fileDescriptor, bytes)
    }
}

/**
 * posix_spawn と一時ファイルで external command を実行する runner。
 */
class UnixCommandRunner : CommandRunner {
    override fun run(spec: CommandSpec, stdin: ByteArray?): Outcome<CommandOutput> {
        val stdinFile = createInputFile(stdin) ?: return lastErrno("failed to create stdin file")
        val stdoutFile = createTemporaryFile() ?: return lastErrno("failed to create stdout file")
        val stderrFile = createTemporaryFile() ?: return lastErrno("failed to create stderr file")

        return try {
            runWithFiles(spec, stdinFile, stdoutFile, stderrFile)
        } finally {
            stdinFile.unlink()
            stdoutFile.unlink()
            stderrFile.unlink()
        }
    }

    private fun runWithFiles(
        spec: CommandSpec,
        stdinFile: TemporaryFile,
        stdoutFile: TemporaryFile,
        stderrFile: TemporaryFile,
    ): Outcome<CommandOutput> {
        val processId = spawnProcess(spec, stdinFile, stdoutFile, stderrFile)
        if (processId is Outcome.Err) {
            return processId
        }

        val waitStatus = waitForProcess((processId as Outcome.Ok<platform.posix.pid_t>).value, spec.timeoutMillis)
        if (waitStatus is Outcome.Err) {
            return waitStatus
        }

        val stdout = stdoutFile.readAll(spec.maxStdoutBytes)
        if (stdout is Outcome.Err) {
            return stdout
        }

        val stderr = stderrFile.readAll(spec.maxStderrBytes)
        if (stderr is Outcome.Err) {
            return stderr
        }

        return Outcome.Ok(
            CommandOutput(
                exitStatus = (waitStatus as Outcome.Ok<Int>).value,
                stdout = (stdout as Outcome.Ok<ByteArray>).value,
                stderr = (stderr as Outcome.Ok<ByteArray>).value,
            ),
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun spawnProcess(
        spec: CommandSpec,
        stdinFile: TemporaryFile,
        stdoutFile: TemporaryFile,
        stderrFile: TemporaryFile,
    ): Outcome<platform.posix.pid_t> {
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

            val processId = fork()
            if (processId < 0) {
                return@memScoped lastErrno("failed to fork command")
            }
            if (processId == 0) {
                redirectOrExit(STDIN_FILENO, stdinFile.path, O_RDONLY)
                redirectOrExit(STDOUT_FILENO, stdoutFile.path, O_WRONLY or O_TRUNC)
                redirectOrExit(STDERR_FILENO, stderrFile.path, O_WRONLY or O_TRUNC)
                execve(spec.executable, argv, environmentPointer)
                _exit(127)
            }

            Outcome.Ok(processId)
        }
    }

    private fun redirectOrExit(targetFileDescriptor: Int, path: String, flags: Int) {
        val fileDescriptor = open(path, flags)
        if (fileDescriptor < 0) {
            _exit(126)
        }

        if (dup2(fileDescriptor, targetFileDescriptor) < 0) {
            _exit(126)
        }

        close(fileDescriptor)
    }

    private fun createInputFile(stdin: ByteArray?): TemporaryFile? {
        val temporaryFile = createTemporaryFile() ?: return null
        if (stdin == null) return temporaryFile

        val writeOutcome = temporaryFile.writeAll(stdin)
        if (writeOutcome is Outcome.Err) {
            temporaryFile.unlink()

            return null
        }

        return temporaryFile
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun waitForProcess(processId: platform.posix.pid_t, timeoutMillis: Long): Outcome<Int> {
        val startedAt = kotlin.time.TimeSource.Monotonic.markNow()

        return memScoped {
            val status = allocArray<IntVar>(1)
            var result: Outcome<Int>? = null

            while (result == null) {
                val waitResult = waitpid(processId, status, WNOHANG)
                if (waitResult == processId) {
                    result = Outcome.Ok(status[0].toExitStatus())
                } else if (waitResult < 0) {
                    result = lastErrno("failed to wait for command")
                } else {
                    result = waitOrTimeout(processId, timeoutMillis, startedAt, status)
                }
            }

            result
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun waitOrTimeout(
        processId: platform.posix.pid_t,
        timeoutMillis: Long,
        startedAt: kotlin.time.TimeMark,
        status: CPointer<IntVar>,
    ): Outcome<Int>? {
        val timedOut = startedAt.elapsedNow().inWholeMilliseconds > timeoutMillis
        if (!timedOut) {
            usleep(10_000.convert())

            return null
        }

        kill(processId, SIGKILL)
        waitpid(processId, status, 0)

        return Outcome.Err(
            KclipError.TimedOut(
                message = "command timed out",
            ),
        )
    }

    private fun Int.toExitStatus(): Int {
        val signal = this and 0x7f
        if (signal == 0) {
            return (this shr 8) and 0xff
        }
        if (signal != 0x7f) {
            return 128 + signal
        }

        return 70
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createTemporaryFile(): TemporaryFile? {
    val template = "/tmp/kclip-command.XXXXXX"
    val pathBytes = template.encodeToByteArray() + byteArrayOf(0)

    pathBytes.usePinned { pinned ->
        val fileDescriptor = mkstemp(pinned.addressOf(0))
        if (fileDescriptor < 0) return null

        close(fileDescriptor)

        return TemporaryFile(pinned.addressOf(0).toKString())
    }
}

/**
 * external command 用の一時ファイル。
 */
private data class TemporaryFile(
    val path: String,
) {
    fun writeAll(bytes: ByteArray): Outcome<Unit> {
        val fileDescriptor = open(path, O_WRONLY or O_TRUNC)
        if (fileDescriptor < 0) {
            return lastErrno("failed to open temporary file")
        }

        return try {
            writeFileDescriptor(fileDescriptor, bytes)
        } finally {
            close(fileDescriptor)
        }
    }

    fun readAll(maxBytes: Int): Outcome<ByteArray> {
        val fileDescriptor = open(path, O_RDONLY)
        if (fileDescriptor < 0) {
            return lastErrno("failed to open temporary file")
        }

        return try {
            readFileDescriptor(fileDescriptor, maxBytes)
        } finally {
            close(fileDescriptor)
        }
    }

    fun unlink() {
        unlink(path)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readFileDescriptor(fileDescriptor: Int, maxBytes: Int): Outcome<ByteArray> {
    val buffer = ByteArray(size = 8 * 1024)
    var output = ByteArray(size = 0)

    while (true) {
        val readBytes = buffer.usePinned { pinned ->
            read(
                fileDescriptor,
                pinned.addressOf(0),
                buffer.size.convert(),
            )
        }
        if (readBytes < 0) {
            return lastErrno("failed to read bytes")
        }
        if (readBytes == 0.convert<platform.posix.ssize_t>()) {
            return Outcome.Ok(output)
        }

        val nextSize = output.size + readBytes.toInt()
        if (nextSize > maxBytes) {
            return Outcome.Err(
                KclipError.TooLarge(
                    actualBytes = nextSize.toLong(),
                    maxBytes = maxBytes,
                ),
            )
        }

        val previousSize = output.size
        output = output.copyOf(nextSize)

        for (byteIndex in 0 until readBytes.toInt()) {
            output[previousSize + byteIndex] = buffer[byteIndex]
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeFileDescriptor(fileDescriptor: Int, bytes: ByteArray): Outcome<Unit> {
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
            return lastErrno("failed to write bytes")
        }

        writtenBytes += writeBytes.toInt()
    }

    return Outcome.Ok(Unit)
}

@OptIn(ExperimentalForeignApi::class)
private fun errnoResult(message: String, errorNumber: Int): Outcome.Err {
    return Outcome.Err(
        KclipError.SubprocessFailure(
            command = "posix_spawn",
            status = errorNumber,
            message = message,
            detail = strerror(errorNumber)?.toKString(),
        ),
    )
}

private fun lastErrno(message: String): Outcome.Err {
    return errnoResult(message, errno)
}
