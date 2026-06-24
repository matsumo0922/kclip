package io.github.kclip.core.diagnostics

/**
 * doctor command が表示する診断結果。
 */
data class DoctorReport(
    val lines: List<String>,
)

/**
 * Phase 0 の最小 doctor report を生成する component。
 */
class Doctor {
    fun createLocalReport(): DoctorReport {
        return DoctorReport(
            lines = listOf(
                "kclip",
                "  status: bootstrap",
            ),
        )
    }
}
