package com.ogenrwotdaniel.wakeproof.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class PaneModeTest {

    @Test
    fun `a phone in portrait stays compact`() {
        // S25 Edge is around 411 dp wide.
        assertEquals(PaneMode.COMPACT, paneModeForWidthDp(411))
    }

    @Test
    fun `a tablet in portrait gets the permanent navigation`() {
        // Tab S9 FE+ portrait is around 800 dp — the case the breakpoint exists
        // for, and the reason it sits at 600 rather than 840.
        assertEquals(PaneMode.WIDE, paneModeForWidthDp(800))
    }

    @Test
    fun `a tablet in landscape gets it too`() {
        assertEquals(PaneMode.WIDE, paneModeForWidthDp(1280))
    }

    @Test
    fun `the boundary itself is wide`() {
        assertEquals(PaneMode.COMPACT, paneModeForWidthDp(WIDE_MIN_DP - 1))
        assertEquals(PaneMode.WIDE, paneModeForWidthDp(WIDE_MIN_DP))
    }

    @Test
    fun `a narrow multi-window split falls back to compact`() {
        // The whole reason width comes from the composable's own constraints
        // and not the screen: half of a tablet is a phone-shaped window, and
        // drawing a rail plus content into 500 dp would leave neither usable.
        assertEquals(PaneMode.COMPACT, paneModeForWidthDp(500))
    }

    @Test
    fun `a degenerate width does not crash or read as wide`() {
        assertEquals(PaneMode.COMPACT, paneModeForWidthDp(0))
        assertEquals(PaneMode.COMPACT, paneModeForWidthDp(-1))
    }
}
