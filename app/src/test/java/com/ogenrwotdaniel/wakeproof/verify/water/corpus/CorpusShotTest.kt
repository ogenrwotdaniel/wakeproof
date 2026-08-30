package com.ogenrwotdaniel.wakeproof.verify.water.corpus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CSV is a cross-language contract: `tools/water/sequences.py` reads it.
 * A column that drifts here silently mislabels the corpus rather than failing,
 * so the shape is pinned by test the same way the detector port is.
 */
class CorpusShotTest {

    private val shot = CorpusShot(
        sequence = "s1",
        bottle = "clear-750",
        capacityMl = 750,
        lighting = "daylight",
        filename = "s1_000.jpg",
        pouredTotalMl = 0
    )

    @Test
    fun `the header matches the python template exactly`() {
        assertEquals(
            "sequence,bottle,capacity_ml,lighting,filename,poured_total_ml,crop,unreadable",
            CORPUS_CSV_HEADER
        )
    }

    @Test
    fun `a row has one field per header column`() {
        assertEquals(
            CORPUS_CSV_HEADER.split(",").size,
            shot.toCsvRow().split(",").size
        )
    }

    @Test
    fun `a plain row matches the documented shape`() {
        assertEquals("s1,clear-750,750,daylight,s1_000.jpg,0,,", shot.toCsvRow())
    }

    @Test
    fun `unreadable is written as the 1 the importer looks for`() {
        assertTrue(shot.copy(unreadable = true).toCsvRow().endsWith(",1"))
    }

    @Test
    fun `a comma in a bottle name cannot shift the later columns`() {
        // The dangerous failure: without quoting this parses fine and silently
        // reads the capacity as a lighting condition.
        val row = shot.copy(bottle = "clear, 750ml").toCsvRow()
        assertTrue(row.contains("\"clear, 750ml\""))
    }

    @Test
    fun `a quote in a field is doubled rather than escaping the row`() {
        val row = shot.copy(bottle = "the \"good\" one").toCsvRow()
        assertTrue(row.contains("\"the \"\"good\"\" one\""))
    }

    @Test
    fun `filenames sort into shooting order`() {
        // Zero-padded so the directory listing matches the manifest order,
        // which is what makes a suspect label checkable by eye.
        assertEquals("s1_000.jpg", corpusFilename("s1", 0))
        assertEquals("s1_009.jpg", corpusFilename("s1", 9))
        assertEquals("s1_010.jpg", corpusFilename("s1", 10))
        assertTrue(corpusFilename("s1", 9) < corpusFilename("s1", 10))
    }
}
