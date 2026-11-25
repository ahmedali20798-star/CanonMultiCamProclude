package com.canonmulticam.pro

import com.canonmulticam.pro.engine.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun backoffDoublesThenCaps() {
        assertEquals(500L, ReconnectPolicy.backoffMs(1))
        assertEquals(1_000L, ReconnectPolicy.backoffMs(2))
        assertEquals(2_000L, ReconnectPolicy.backoffMs(3))
        assertEquals(4_000L, ReconnectPolicy.backoffMs(4))
        assertEquals(5_000L, ReconnectPolicy.backoffMs(5))
        assertEquals(5_000L, ReconnectPolicy.backoffMs(500))
    }

    @Test
    fun jitterIsBoundedToTwentyFivePercent() {
        assertEquals(5_000L + 1_250L, ReconnectPolicy.backoffMs(5, 10.0))
        assertEquals(500L, ReconnectPolicy.backoffMs(1, -1.0))
    }

    @Test
    fun shortFastFailuresDoNotTriggerReconnectImmediately() {
        // The old logic dropped the camera after 40 fast errors (~1.3 s). Time must also elapse.
        assertFalse(ReconnectPolicy.shouldReconnect(downMs = 1_300L, consecutiveErrors = 40))
        assertTrue(ReconnectPolicy.shouldReconnect(downMs = 4_000L, consecutiveErrors = 2))
        assertFalse(ReconnectPolicy.shouldReconnect(downMs = 9_000L, consecutiveErrors = 1))
    }

    @Test
    fun errorDelayGrowsAndCaps() {
        assertEquals(100L, ReconnectPolicy.errorDelayMs(1))
        assertEquals(300L, ReconnectPolicy.errorDelayMs(3))
        assertEquals(500L, ReconnectPolicy.errorDelayMs(99))
    }

    @Test
    fun giveUpRules() {
        assertFalse(ReconnectPolicy.shouldGiveUp(false, false, 4, 0L))
        assertTrue(ReconnectPolicy.shouldGiveUp(false, false, 5, 0L))
        assertFalse(ReconnectPolicy.shouldGiveUp(false, true, 50, 119_999L))
        assertTrue(ReconnectPolicy.shouldGiveUp(false, true, 1, 120_000L))
        assertTrue(ReconnectPolicy.shouldGiveUp(true, true, 2, 0L))
    }
}
