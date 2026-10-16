package com.example.bluetoothcodecpriority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecSelectionTest {
    private val codecs = listOf(
        CodecCapability("aac", "AAC"),
        CodecCapability("sbc", "SBC"),
        CodecCapability("aptx_hd", "aptX HD")
    )

    @Test fun selectsFirstSupportedCodecInUserOrder() {
        assertEquals("aptx_hd", selectHighestPriority(listOf("aptx_hd", "aac", "sbc"), codecs)?.id)
    }

    @Test fun fallsBackToNextSupportedCodec() {
        assertEquals("aac", selectHighestPriority(listOf("aptx_hd", "aac", "sbc"), codecs.filterNot { it.id == "aptx_hd" })?.id)
    }

    @Test fun returnsAllCompatibleCandidatesInPriorityOrder() {
        assertEquals(
            listOf("aptx_hd", "aac", "sbc"),
            orderedCandidates(listOf("aptx_hd", "missing", "aac", "sbc"), codecs).map { it.id }
        )
    }

    @Test fun duplicatePriorityEntriesDoNotCauseRepeatedAttempts() {
        assertEquals(listOf("aac"), orderedCandidates(listOf("aac", "aac"), codecs).map { it.id })
    }

    @Test fun unknownCodecIsNeverInvented() {
        assertNull(selectHighestPriority(listOf("ldac"), codecs))
    }

    @Test fun movesCodecByStableIdInsteadOfStalePosition() {
        assertEquals(listOf("sbc", "aptx_hd", "aac"), moveCodec(listOf("aptx_hd", "aac", "sbc"), "sbc", 0))
    }

    @Test fun movingUnknownCodecDoesNotChangePriority() {
        val priority = listOf("aptx_hd", "aac")
        assertEquals(priority, moveCodec(priority, "ldac", 0))
    }

    @Test fun staleSuccessIsNotReportedWhenActiveCodecDiffers() {
        val state = DeviceApplyState("aptx_hd", "aptX HD", "SUCCESS", "Verified active codec: aptX HD", 1L)
        assertEquals("MISMATCH", reconcileApplyState(state, "aptx" )?.status)
    }

    @Test fun successRemainsValidWhenActiveCodecMatches() {
        val state = DeviceApplyState("aptx_hd", "aptX HD", "SUCCESS", "Verified active codec: aptX HD", 1L)
        assertEquals("SUCCESS", reconcileApplyState(state, "aptx_hd")?.status)
    }

    @Test fun systemDefaultIsUnmanaged() {
        assertNull(resolvePriority(true, listOf("aptx_hd"), DeviceMode.SYSTEM_DEFAULT, emptyList()))
        assertNull(resolvePriority(false, listOf("aptx_hd"), DeviceMode.GLOBAL, emptyList()))
    }

    @Test fun customRemainsManagedWhenGlobalDisabled() {
        assertEquals(listOf("aac"), resolvePriority(false, listOf("aptx_hd"), DeviceMode.CUSTOM, listOf("aac")))
    }
}
