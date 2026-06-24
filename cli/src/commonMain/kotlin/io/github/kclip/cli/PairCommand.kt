package io.github.kclip.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.kclip.core.agent.DefaultPairAgentClient
import io.github.kclip.core.application.PairAgentClient
import io.github.kclip.core.application.PairOptions
import io.github.kclip.core.application.RemotePairContext
import io.github.kclip.core.application.RemotePairUseCase
import io.github.kclip.core.domain.EpochSeconds
import io.github.kclip.core.domain.KclipError
import io.github.kclip.core.domain.Outcome
import io.github.kclip.core.domain.PairCredential
import io.github.kclip.core.domain.PairingMaterial
import io.github.kclip.core.platform.PlatformServices
import io.github.kclip.core.protocol.PairAcceptedFrame
import io.github.kclip.core.protocol.PairFrame

/**
 * remote terminal から pairing request を開始する command。
 */
class PairCommand(
    private val platformServices: PlatformServices,
) : CliktCommand(
    name = "pair",
) {
    private val paste by option("--paste").flag(default = false)
    private val replace by option("--replace").flag(default = false)

    override fun run() {
        val material = createPairingMaterial()
        val endpoint = remotePairEndpoint(material)
        echoPairingInstructions(material)

        val context = createRemotePairContext(material)
        val agentClient = RetryingPairAgentClient(
            delegate = DefaultPairAgentClient(
                endpoint = endpoint,
                connector = platformServices.ipcConnector,
                clock = platformServices.clock,
            ),
            platformServices = platformServices,
        )
        val useCase = RemotePairUseCase(
            agentClient = agentClient,
            stateRepository = attachmentStateRepository(platformServices),
        )
        val result = useCase.execute(
            options = PairOptions(
                requestPaste = paste,
                replaceExisting = replace,
            ),
            context = context,
        )
        if (result is Outcome.Err) {
            exitWith(result.error)
        }

        echo("Attachment ${(result as Outcome.Ok).value.lease.id.displayValue()} is active.")
    }

    private fun createPairingMaterial(): PairingMaterial {
        val entropy = when (val outcome = platformServices.secureRandom.readBytes(PAIRING_ENTROPY_BYTES)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }

        return when (val outcome = PairingMaterial.fromEntropy(entropy)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
    }

    private fun createRemotePairContext(material: PairingMaterial): RemotePairContext {
        val processIdentity = when (val outcome = platformServices.processIdentityResolver.current()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }
        val ttyIdentity = when (val outcome = platformServices.ttyIdentityResolver.current()) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> exitWith(outcome.error)
        }

        return RemotePairContext(
            material = material,
            endpoint = remotePairEndpoint(material),
            remoteUid = processIdentity.uid,
            username = processIdentity.username,
            hostname = processIdentity.hostname,
            ttyIdentity = ttyIdentity,
            createdAt = EpochSeconds(platformServices.clock.nowMillis() / MILLIS_PER_SECOND),
        )
    }

    private fun echoPairingInstructions(material: PairingMaterial) {
        echo("kclip pairing")
        echo("code: ${material.code.displayValue}")
        echo("")
        echo("On your local machine:")
        echo("  kclip attach --pairing-code-stdin --paste=${if (paste) "allow" else "deny"} <ssh-destination>")
        echo("")
        echo("Waiting for local attachment...")
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val PAIRING_ENTROPY_BYTES = 10
    }
}

/**
 * local attach が forwarding を張るまで PAIR を retry する client。
 */
private class RetryingPairAgentClient(
    private val delegate: PairAgentClient,
    private val platformServices: PlatformServices,
) : PairAgentClient {
    override fun pair(credential: PairCredential, frame: PairFrame): Outcome<PairAcceptedFrame> {
        val deadlineMillis = platformServices.clock.nowMillis() + PAIR_TIMEOUT_MILLIS
        var lastError: KclipError? = null

        while (platformServices.clock.nowMillis() <= deadlineMillis) {
            val outcome = delegate.pair(credential, frame)
            if (outcome is Outcome.Ok) {
                return outcome
            }
            lastError = (outcome as Outcome.Err).error

            val sleep = platformServices.sleeper.sleepMillis(RETRY_DELAY_MILLIS)
            if (sleep is Outcome.Err) {
                return sleep
            }
        }

        return Outcome.Err(
            KclipError.TimedOut(
                message = "no matching local attachment completed before the deadline",
                detail = lastError?.message,
            ),
        )
    }

    override fun confirm(acceptedFrame: PairAcceptedFrame): Outcome<Unit> {
        return delegate.confirm(acceptedFrame)
    }

    private companion object {
        const val PAIR_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        const val RETRY_DELAY_MILLIS = 200L
    }
}
