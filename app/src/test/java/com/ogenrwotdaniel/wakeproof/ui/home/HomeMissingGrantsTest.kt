package com.ogenrwotdaniel.wakeproof.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMissingGrantsTest {

    @Test
    fun `missingGrantsFrom flags usageAccess and overlay regardless of sessionDnd`() {
        val missing = missingGrantsFrom(
            usageAccess = false,
            overlay = false,
            dnd = false,
            notifications = true,
            sessionDnd = false
        )
        assertTrue(missing.usageAccess)
        assertTrue(missing.overlay)
        assertFalse(missing.dnd)
        assertFalse(missing.notifications)
        assertTrue(missing.any)
    }

    @Test
    fun `missingGrantsFrom clears any when all required grants are present`() {
        val ok = missingGrantsFrom(
            usageAccess = true,
            overlay = true,
            dnd = true,
            notifications = true,
            sessionDnd = true
        )
        assertFalse(ok.usageAccess)
        assertFalse(ok.overlay)
        assertFalse(ok.dnd)
        assertFalse(ok.notifications)
        assertFalse(ok.any)
    }
}
