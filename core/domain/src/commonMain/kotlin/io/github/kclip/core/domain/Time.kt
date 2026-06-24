package io.github.kclip.core.domain

/**
 * monotonic clock から算出される deadline。
 */
data class Deadline(
    val epochMillis: Long,
)

/**
 * wall clock から得た epoch 秒。
 */
data class EpochSeconds(
    val value: Long,
)
