package io.github.kclip.core.agent

import io.github.kclip.core.domain.AttachmentBinding
import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.AttachmentLease
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.EpochSeconds
import io.github.kclip.core.domain.IpcEndpoint
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.Secret16
import io.github.kclip.core.domain.TtyIdentity
import io.github.kclip.core.platform.FileStore
import io.github.kclip.core.platform.RuntimePaths
import io.github.kclip.core.platform.TtyIdentityResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * FileAttachmentStateRepository の test。
 */
class FileAttachmentStateRepositoryTest {
    @Test
    fun commitPersistsLeaseAndCurrentBinding() {
        val fileStore = MemoryFileStore()
        val ttyIdentity = TtyIdentity(
            device = 11u,
            inode = 22u,
            displayPath = "/dev/ttys001",
        )
        val repository = FileAttachmentStateRepository(
            fileStore = fileStore,
            runtimePaths = FixedRuntimePaths(),
            ttyIdentityResolver = FixedTtyIdentityResolver(ttyIdentity),
        )
        val lease = AttachmentLease(
            formatVersion = 1u,
            id = attachmentId(),
            endpoint = IpcEndpoint.UnixSocket("/tmp/kclip-remote.sock"),
            nonce = secret(value = 7),
            capabilities = setOf(
                ClipboardCapability.COPY,
                ClipboardCapability.PASTE,
            ),
            scope = ttyIdentity,
            createdAt = EpochSeconds(123),
        )
        val binding = AttachmentBinding(
            attachmentId = lease.id,
            ttyIdentity = ttyIdentity,
        )

        assertIs<Outcome.Ok<Unit>>(repository.commit(lease, binding))
        val restored = assertIs<Outcome.Ok<AttachmentLease>>(repository.readCurrentLease()).value

        assertEquals(lease.id, restored.id)
        assertEquals(lease.endpoint, restored.endpoint)
        assertEquals(lease.capabilities, restored.capabilities)
        assertEquals(lease.scope.device, restored.scope.device)
        assertEquals(lease.scope.inode, restored.scope.inode)
        assertEquals(lease.createdAt, restored.createdAt)
    }

    private fun attachmentId(): AttachmentId {
        return assertIs<Outcome.Ok<AttachmentId>>(
            AttachmentId.fromBytes(ByteArray(size = 16) { index -> (index + 1).toByte() }),
        ).value
    }

    private fun secret(value: Int): Secret16 {
        return assertIs<Outcome.Ok<Secret16>>(
            Secret16.fromBytes(ByteArray(size = 16) { value.toByte() }),
        ).value
    }
}

/**
 * test 用 in-memory file store。
 */
private class MemoryFileStore : FileStore {
    private val files = mutableMapOf<String, ByteArray>()

    override fun exists(path: String): Outcome<Boolean> {
        return Outcome.Ok(files.containsKey(path))
    }

    override fun readBytes(path: String, maxBytes: Int): Outcome<ByteArray> {
        val bytes = files[path]
            ?: return Outcome.Err(
                KclipError.AttachmentUnavailable(
                    attachmentId = null,
                    message = "missing test file",
                ),
            )

        return Outcome.Ok(bytes.copyOf())
    }

    override fun writePrivateBytes(path: String, bytes: ByteArray): Outcome<Unit> {
        files[path] = bytes.copyOf()

        return Outcome.Ok(Unit)
    }

    override fun delete(path: String): Outcome<Unit> {
        files.remove(path)

        return Outcome.Ok(Unit)
    }
}

/**
 * test 用 runtime path。
 */
private class FixedRuntimePaths : RuntimePaths {
    override fun runtimeRoot(): Outcome<String> = Outcome.Ok("/runtime")

    override fun attachmentsDirectory(): Outcome<String> = Outcome.Ok("/runtime/attachments")

    override fun bindingsDirectory(): Outcome<String> = Outcome.Ok("/runtime/bindings")

    override fun agentsDirectory(): Outcome<String> = Outcome.Ok("/runtime/agents")

    override fun sshDirectory(): Outcome<String> = Outcome.Ok("/runtime/ssh")
}

/**
 * test 用 TTY identity resolver。
 */
private class FixedTtyIdentityResolver(
    private val ttyIdentity: TtyIdentity,
) : TtyIdentityResolver {
    override fun current(): Outcome<TtyIdentity> {
        return Outcome.Ok(ttyIdentity)
    }
}
