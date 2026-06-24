@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kclip.core.platform

import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.platform.spawn.kclip_current_uid
import io.github.kclip.core.platform.spawn.kclip_lstat_metadata
import io.github.kclip.core.platform.spawn.kclip_open_private_write
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.ENOENT
import platform.posix.F_OK
import platform.posix.O_RDONLY
import platform.posix.access
import platform.posix.close
import platform.posix.errno
import platform.posix.gethostname
import platform.posix.open
import platform.posix.read
import platform.posix.strerror
import platform.posix.unlink
import platform.posix.usleep

/**
 * Unix の短時間 sleep adapter。
 */
class UnixSleeper : Sleeper {
    override fun sleepMillis(durationMillis: Long): Outcome<Unit> {
        val microseconds = (durationMillis * MICROSECONDS_PER_MILLISECOND).coerceAtLeast(0)
        usleep(microseconds.convert())

        return Outcome.Ok(Unit)
    }

    private companion object {
        const val MICROSECONDS_PER_MILLISECOND = 1_000L
    }
}

/**
 * `/dev/urandom` から byte 列を読む secure random source。
 */
class UnixSecureRandom : SecureRandom {
    override fun readBytes(size: Int): Outcome<ByteArray> {
        if (size < 0) {
            return Outcome.Err(
                KclipError.InvalidInput(
                    message = "random byte size must be non-negative",
                ),
            )
        }

        val fileDescriptor = open(URANDOM_PATH, O_RDONLY)
        if (fileDescriptor < 0) {
            return lastErrno("failed to open secure random source")
        }

        return try {
            readExactRandomBytes(fileDescriptor, size)
        } finally {
            close(fileDescriptor)
        }
    }

    private fun readExactRandomBytes(fileDescriptor: Int, size: Int): Outcome<ByteArray> {
        val output = ByteArray(size)
        var offset = 0

        while (offset < size) {
            val readBytes = output.usePinned { pinned ->
                read(
                    fileDescriptor,
                    pinned.addressOf(offset),
                    (size - offset).convert(),
                )
            }
            if (readBytes < 0) {
                return lastErrno("failed to read secure random bytes")
            }
            if (readBytes == 0.convert<platform.posix.ssize_t>()) {
                return Outcome.Err(
                    KclipError.Internal(
                        message = "secure random source ended unexpectedly",
                    ),
                )
            }

            offset += readBytes.toInt()
        }

        return Outcome.Ok(output)
    }

    private companion object {
        const val URANDOM_PATH = "/dev/urandom"
    }
}

/**
 * Unix の private file store。
 */
class UnixFileStore : FileStore {
    override fun exists(path: String): Outcome<Boolean> {
        val result = access(path, F_OK)
        if (result == 0) {
            return Outcome.Ok(true)
        }
        if (errno == ENOENT) {
            return Outcome.Ok(false)
        }

        return lastErrno("failed to inspect state file")
    }

    override fun lstat(path: String): Outcome<FileMetadata> {
        return memScoped {
            val ownerUid = allocArray<ULongVar>(1)
            val permissionMode = allocArray<UIntVar>(1)
            val fileType = allocArray<IntVar>(1)
            val result = kclip_lstat_metadata(path, ownerUid, permissionMode, fileType)
            if (result != 0) {
                return@memScoped lastErrno("failed to inspect file metadata")
            }

            Outcome.Ok(
                FileMetadata(
                    ownerUid = ownerUid[0],
                    permissionMode = permissionMode[0],
                    type = fileType[0].toFileType(),
                ),
            )
        }
    }

    override fun readBytes(path: String, maxBytes: Int): Outcome<ByteArray> {
        val fileDescriptor = open(path, O_RDONLY)
        if (fileDescriptor < 0) {
            return lastErrno("failed to open state file")
        }

        return try {
            UnixByteInput(fileDescriptor).readAll(maxBytes)
        } finally {
            close(fileDescriptor)
        }
    }

    override fun writePrivateBytes(path: String, bytes: ByteArray): Outcome<Unit> {
        val fileDescriptor = kclip_open_private_write(path)
        if (fileDescriptor < 0) {
            return lastErrno("failed to open state file for writing")
        }

        return try {
            UnixByteOutput(fileDescriptor).writeAll(bytes)
        } finally {
            close(fileDescriptor)
        }
    }

    override fun delete(path: String): Outcome<Unit> {
        val result = unlink(path)
        if (result != 0 && errno != ENOENT) {
            return lastErrno("failed to delete state file")
        }

        return Outcome.Ok(Unit)
    }
}

private fun Int.toFileType(): FileType {
    return when (this) {
        1 -> FileType.REGULAR
        2 -> FileType.DIRECTORY
        3 -> FileType.SOCKET
        else -> FileType.OTHER
    }
}

/**
 * Unix process の user/host metadata resolver。
 */
class UnixProcessIdentityResolver(
    private val environment: Environment,
) : ProcessIdentityResolver {
    override fun current(): Outcome<CurrentProcessIdentity> {
        val hostname = resolveHostname()
        if (hostname is Outcome.Err) {
            return hostname
        }

        return Outcome.Ok(
            CurrentProcessIdentity(
                uid = kclip_current_uid().toULong(),
                username = resolveUsername(),
                hostname = (hostname as Outcome.Ok<String>).value,
            ),
        )
    }

    private fun resolveUsername(): String {
        return environment.get("USER")
            ?: environment.get("LOGNAME")
            ?: "unknown"
    }

    private fun resolveHostname(): Outcome<String> {
        return memScoped {
            val buffer = allocArray<ByteVar>(HOSTNAME_BUFFER_BYTES)
            val result = gethostname(buffer, HOSTNAME_BUFFER_BYTES.convert())
            if (result != 0) {
                return@memScoped lastErrno("failed to resolve hostname")
            }
            buffer[HOSTNAME_BUFFER_BYTES - 1] = 0

            Outcome.Ok(buffer.toKString())
        }
    }

    private companion object {
        const val HOSTNAME_BUFFER_BYTES = 256
    }
}

private fun lastErrno(message: String): Outcome.Err {
    return Outcome.Err(
        KclipError.SubprocessFailure(
            command = "unix",
            status = errno,
            message = message,
            detail = strerror(errno)?.toKString(),
        ),
    )
}
