package com.ogenrwotdaniel.wakeproof.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipScreenshotRowTest {

    private fun row(
        stored: Boolean = false,
        clipEnabled: Boolean = true,
        photos: Boolean = false,
        canAsk: Boolean = true,
        refused: Boolean = false,
        inSession: Boolean = false
    ) = clipScreenshotRow(stored, clipEnabled, photos, canAsk, refused, inSession)

    @Test
    fun `only the sending state says screenshots are sent`() {
        assertTrue("goes straight" in row(stored = true, photos = true).subtitle)
        assertFalse("goes straight" in row(stored = false, photos = true).subtitle)
        assertFalse("goes straight" in row(stored = true, photos = true, clipEnabled = false).subtitle)
        assertFalse("goes straight" in row(stored = true, photos = false).subtitle)
    }

    @Test
    fun `the switch reads off without the grant, whatever is stored`() {
        assertFalse(row(stored = true, photos = false).checked)
        assertTrue(row(stored = true, photos = true).checked)
    }

    @Test
    fun `the release build can neither ask nor switch it on`() {
        val release = row(canAsk = false, refused = true)
        assertFalse(release.switchEnabled)
        assertFalse(release.settingsLink)
    }

    @Test
    fun `with the clipboard off the row points there first`() {
        val off = row(clipEnabled = false, refused = true)
        assertFalse(off.switchEnabled)
        assertFalse(off.settingsLink)
        assertTrue("Share the clipboard" in off.subtitle)
    }

    @Test
    fun `a refusal offers Android Settings, which a session walls`() {
        assertTrue(row(refused = true).settingsLink)
        assertTrue(row(refused = true).settingsLinkEnabled)
        assertFalse(row(refused = true, inSession = true).settingsLinkEnabled)
        assertFalse(row(refused = true, photos = true).settingsLink)
    }
}
