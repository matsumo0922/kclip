package io.github.kclip.core.agent

import io.github.kclip.core.domain.AttachmentId
import io.github.kclip.core.domain.Outcome

/**
 * attachment agent の lifecycle state。
 */
enum class AgentLifecycleState {
    PAIRING,
    PENDING_CONFIRM,
    ACTIVE,
    DEGRADED,
    STOPPING,
    CLOSED,
}

/**
 * Phase 0 の attachment agent placeholder。
 */
class AttachmentAgent {
    fun status(): Outcome<AgentStatusSnapshot> {
        return Outcome.Ok(
            AgentStatusSnapshot(
                id = AttachmentId("KC-BOOTSTRAP"),
                state = AgentLifecycleState.PAIRING,
            ),
        )
    }
}

/**
 * agent の状態表示用 snapshot。
 */
data class AgentStatusSnapshot(
    val id: AttachmentId,
    val state: AgentLifecycleState,
)
