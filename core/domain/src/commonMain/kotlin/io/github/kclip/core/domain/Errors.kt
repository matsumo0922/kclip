package io.github.kclip.core.domain

/**
 * kclip の process exit code を集約する定数群。
 */
object ExitCodes {
    /** 正常終了を表す exit code。 */
    const val SUCCESS = 0

    /** CLI usage または入力不正を表す exit code。 */
    const val USAGE = 2

    /** clipboard backend が利用不能な場合の exit code。 */
    const val BACKEND_UNAVAILABLE = 3

    /** permission または capability 拒否を表す exit code。 */
    const val PERMISSION_DENIED = 4

    /** attachment が利用不能または stale な場合の exit code。 */
    const val ATTACHMENT = 5

    /** agent protocol failure を表す exit code。 */
    const val PROTOCOL = 6

    /** SSH forwarding または transport failure を表す exit code。 */
    const val FORWARDING = 7

    /** payload が大きすぎる場合の exit code。 */
    const val TOO_LARGE = 8

    /** timeout を表す exit code。 */
    const val TIMEOUT = 9

    /** subprocess failure を表す exit code。 */
    const val SUBPROCESS = 10

    /** internal software error を表す exit code。 */
    const val INTERNAL = 70
}

/**
 * 利用者へ安全に表示できる kclip の失敗情報。
 */
sealed interface KclipError {
    val message: String
    val detail: String?
    val exitCode: Int

    /**
     * CLI option や入力値が不正な場合の失敗。
     */
    data class InvalidInput(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.USAGE
    }

    /**
     * 要求された clipboard backend を利用できない場合の失敗。
     */
    data class BackendUnavailable(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.BACKEND_UNAVAILABLE
    }

    /**
     * attachment capability や filesystem permission により拒否された失敗。
     */
    data class PermissionDenied(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.PERMISSION_DENIED
    }

    /**
     * attachment が存在しない、または stale な場合の失敗。
     */
    data class AttachmentUnavailable(
        val attachmentId: AttachmentId?,
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.ATTACHMENT
    }

    /**
     * agent protocol または attachment state が壊れている場合の失敗。
     */
    data class ProtocolFailure(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.PROTOCOL
    }

    /**
     * SSH forwarding または transport が拒否された場合の失敗。
     */
    data class ForwardingRejected(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.FORWARDING
    }

    /**
     * clipboard payload が許可された上限を超えた場合の失敗。
     */
    data class TooLarge(
        val actualBytes: Long?,
        val maxBytes: Int,
        override val message: String = "clipboard payload is too large",
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.TOO_LARGE
    }

    /**
     * deadline までに操作が完了しなかった場合の失敗。
     */
    data class TimedOut(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.TIMEOUT
    }

    /**
     * child process の起動または終了状態が失敗を示した場合の失敗。
     */
    data class SubprocessFailure(
        val command: String,
        val status: Int?,
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.SUBPROCESS
    }

    /**
     * kclip 自身の不変条件違反を示す失敗。
     */
    data class Internal(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.INTERNAL
    }
}
