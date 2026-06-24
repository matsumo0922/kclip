package io.github.kclip.core.diagnostics

/**
 * doctor command が表示する診断結果。
 */
data class DoctorReport(
    val lines: List<String>,
)

/**
 * local diagnostics report を生成する component。
 */
class Doctor {
    fun createLocalReport(clipboardStatus: String): DoctorReport {
        return DoctorReport(
            lines = listOf(
                "kclip",
                "  status: local",
                "  clipboard: $clipboardStatus",
            ),
        )
    }
}
