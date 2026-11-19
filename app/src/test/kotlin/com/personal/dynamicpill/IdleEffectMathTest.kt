package com.personal.dynamicpill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleEffectMathTest {

    @Test
    fun `random is stable for the same seed`() {
        assertEquals(IdleEffect.random(42), IdleEffect.random(42), 0f)
    }

    @Test
    fun `random stays within zero and one`() {
        (-1_000..1_000).forEach { seed ->
            val value = IdleEffect.random(seed)
            assertTrue("seed $seed gave $value", value in 0f..1f)
        }
    }

    @Test
    fun `random spreads neighbouring seeds apart`() {
        assertNotEquals(IdleEffect.random(1), IdleEffect.random(2), 0.01f)
    }

    @Test
    fun `smooth eases between the clamped ends`() {
        assertEquals(0f, IdleEffect.smooth(-1f), 0f)
        assertEquals(0.5f, IdleEffect.smooth(0.5f), 1e-6f)
        assertEquals(1f, IdleEffect.smooth(2f), 0f)
    }

    @Test
    fun `every idle widget can be stored in the settings slider range`() {
        assertEquals(IdleWidget.entries.size - 1, IslandSetting.IDLE_WIDGET.max)
    }
}
