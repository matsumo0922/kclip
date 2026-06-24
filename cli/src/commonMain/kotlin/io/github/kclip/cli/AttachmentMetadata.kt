package io.github.kclip.cli

import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.ClipboardCapability
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.Secret16
import io.github.kclip.core.platform.PlatformServices

/**
 * local attachment process の管理用 metadata。
 */
data class LocalAttachmentMetadata(
    val attachmentId: AttachmentId,
    val agentProcessId: Int,
    val destination: String,
    val localSocketPath: String,
    val controlPath: String,
    val controlSecret: Secret16,
    val allowsPaste: Boolean,
)

/**
 * local attachment metadata の text codec。
 */
object LocalAttachmentMetadataCodec {
    fun encode(metadata: LocalAttachmentMetadata): ByteArray {
        val lines = listOf(
            "version=1",
            "attachmentId=${metadata.attachmentId.value}",
            "agentProcessId=${metadata.agentProcessId}",
            "destination=${metadata.destination}",
            "localSocketPath=${metadata.localSocketPath}",
            "controlPath=${metadata.controlPath}",
            "controlSecret=${MetadataHexCodec.encode(metadata.controlSecret.copyBytes())}",
            "allowsPaste=${metadata.allowsPaste}",
        )

        return (lines.joinToString(separator = "\n") + "\n").encodeToByteArray()
    }

    fun decode(bytes: ByteArray): Outcome<LocalAttachmentMetadata> {
        val values = bytes
            .decodeToString()
            .lineSequence()
            .filter { line -> line.isNotBlank() }
            .associate { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex < 0) {
                    return invalidMetadata("metadata line must contain '='")
                }

                line.take(separatorIndex) to line.drop(separatorIndex + 1)
            }
        if (values["version"] != "1") {
            return invalidMetadata("unsupported attachment metadata version")
        }

        return decodeValues(values)
    }

    private fun decodeValues(values: Map<String, String>): Outcome<LocalAttachmentMetadata> {
        val attachmentId = when (val outcome = AttachmentId.parse(required(values, "attachmentId"))) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return Outcome.Ok(
            LocalAttachmentMetadata(
                attachmentId = attachmentId,
                agentProcessId = required(values, "agentProcessId").toIntOrNull()
                    ?: return invalidMetadata("agentProcessId must be an integer"),
                destination = required(values, "destination"),
                localSocketPath = required(values, "localSocketPath"),
                controlPath = required(values, "controlPath"),
                controlSecret = when (val outcome = decodeSecret(values, "controlSecret")) {
                    is Outcome.Ok -> outcome.value
                    is Outcome.Err -> return outcome
                },
                allowsPaste = required(values, "allowsPaste").toBooleanStrictOrNull() ?: false,
            ),
        )
    }

    private fun decodeSecret(values: Map<String, String>, key: String): Outcome<Secret16> {
        val bytes = when (val outcome = MetadataHexCodec.decode(required(values, key))) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return Secret16.fromBytes(bytes)
    }

    private fun required(values: Map<String, String>, key: String): String {
        return values[key] ?: ""
    }

    private fun invalidMetadata(message: String): Outcome.Err {
        return Outcome.Err(
            KclipError.ProtocolFailure(
                message = message,
            ),
        )
    }
}

/**
 * local attachment metadata file path を扱う helper。
 */
class LocalAttachmentMetadataStore(
    private val platformServices: PlatformServices,
) {
    fun write(metadata: LocalAttachmentMetadata): Outcome<Unit> {
        val path = when (val outcome = path(metadata.attachmentId)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return platformServices.fileStore.writePrivateBytes(path, LocalAttachmentMetadataCodec.encode(metadata))
    }

    fun read(attachmentId: AttachmentId): Outcome<LocalAttachmentMetadata> {
        val path = when (val outcome = path(attachmentId)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val bytes = when (val outcome = platformServices.fileStore.readBytes(path, MAX_METADATA_BYTES)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return LocalAttachmentMetadataCodec.decode(bytes)
    }

    fun delete(attachmentId: AttachmentId): Outcome<Unit> {
        val path = when (val outcome = path(attachmentId)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return platformServices.fileStore.delete(path)
    }

    fun list(): Outcome<List<LocalAttachmentMetadata>> {
        val directory = when (val outcome = platformServices.runtimePaths.agentsDirectory()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }
        val output = platformServices.commandRunner.run(
            spec = io.github.kclip.core.platform.CommandSpec(
                executable = "/bin/ls",
                arguments = listOf("-1", directory),
                environment = platformServices.environment.snapshot(),
            ),
            stdin = null,
        )
        if (output is Outcome.Err) {
            return output
        }

        return readListedMetadata(directory, (output as Outcome.Ok).value.stdout.decodeToString())
    }

    private fun readListedMetadata(directory: String, listing: String): Outcome<List<LocalAttachmentMetadata>> {
        val metadata = mutableListOf<LocalAttachmentMetadata>()
        val names = listing
            .lineSequence()
            .filter { name -> name.endsWith(METADATA_SUFFIX) }
            .toList()

        for (name in names) {
            val bytes = when (val outcome = platformServices.fileStore.readBytes("$directory/$name", MAX_METADATA_BYTES)) {
                is Outcome.Ok -> outcome.value
                is Outcome.Err -> return outcome
            }
            val decoded = when (val outcome = LocalAttachmentMetadataCodec.decode(bytes)) {
                is Outcome.Ok -> outcome.value
                is Outcome.Err -> return outcome
            }
            metadata.add(decoded)
        }

        return Outcome.Ok(metadata)
    }

    private fun path(attachmentId: AttachmentId): Outcome<String> {
        val directory = when (val outcome = platformServices.runtimePaths.agentsDirectory()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return outcome
        }

        return Outcome.Ok("$directory/${attachmentId.value.lowercase()}$METADATA_SUFFIX")
    }

    private companion object {
        const val MAX_METADATA_BYTES = 8 * 1024
        const val METADATA_SUFFIX = ".meta"
    }
}

/**
 * attachment capabilities から paste 表示用の値を返す helper。
 */
fun Set<ClipboardCapability>.allowsPaste(): Boolean {
    return ClipboardCapability.PASTE in this
}

/**
 * metadata 用 hex codec。
 */
private object MetadataHexCodec {
    private const val BYTE_MASK = 0xff
    private const val HEX_BITS = 4
    private const val HEX_ALPHABET = "0123456789ABCDEF"
    private const val HEX_CHARS_PER_BYTE = 2

    fun encode(bytes: ByteArray): String {
        val output = StringBuilder(bytes.size * HEX_CHARS_PER_BYTE)
        for (byteValue in bytes) {
            val unsignedValue = byteValue.toInt() and BYTE_MASK
            output.append(HEX_ALPHABET[unsignedValue ushr HEX_BITS])
            output.append(HEX_ALPHABET[unsignedValue and 0x0f])
        }

        return output.toString()
    }

    fun decode(value: String): Outcome<ByteArray> {
        val normalizedValue = value.uppercase()
        if (normalizedValue.length % HEX_CHARS_PER_BYTE != 0) {
            return invalidHex()
        }
        val output = ByteArray(normalizedValue.length / HEX_CHARS_PER_BYTE)
        for (byteIndex in output.indices) {
            val high = HEX_ALPHABET.indexOf(normalizedValue[byteIndex * HEX_CHARS_PER_BYTE])
            val low = HEX_ALPHABET.indexOf(normalizedValue[byteIndex * HEX_CHARS_PER_BYTE + 1])
            if (high < 0 || low < 0) {
                return invalidHex()
            }

            output[byteIndex] = ((high shl HEX_BITS) or low).toByte()
        }

        return Outcome.Ok(output)
    }

    private fun invalidHex(): Outcome.Err {
        return Outcome.Err(
            KclipError.InvalidInput(
                message = "invalid hex text",
            ),
        )
    }
}
