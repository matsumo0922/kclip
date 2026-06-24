package io.github.kclip.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 設計書で定義した kclip 固有 exit code のテスト。
 */
class ExitCodesTest {
    @Test
    fun matchesDocumentedExitCodes() {
        assertEquals(0, ExitCodes.SUCCESS)
        assertEquals(2, ExitCodes.USAGE)
        assertEquals(3, ExitCodes.BACKEND_UNAVAILABLE)
        assertEquals(4, ExitCodes.PERMISSION_DENIED)
        assertEquals(5, ExitCodes.ATTACHMENT)
        assertEquals(6, ExitCodes.PROTOCOL)
        assertEquals(7, ExitCodes.FORWARDING)
        assertEquals(8, ExitCodes.TOO_LARGE)
        assertEquals(9, ExitCodes.TIMEOUT)
        assertEquals(10, ExitCodes.SUBPROCESS)
        assertEquals(70, ExitCodes.INTERNAL)
    }

    @Test
    fun errorTypesReturnMatchingExitCodes() {
        assertEquals(ExitCodes.ATTACHMENT, KclipError.AttachmentUnavailable(null, "missing").exitCode)
        assertEquals(ExitCodes.FORWARDING, KclipError.ForwardingRejected("rejected").exitCode)
    }
}
