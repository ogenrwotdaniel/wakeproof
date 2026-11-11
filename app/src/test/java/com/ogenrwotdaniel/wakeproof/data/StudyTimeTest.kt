package com.ogenrwotdaniel.wakeproof.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyTimeTest {

    private val today = 20_000L
    private val notes = "com.samsung.android.app.notes"
    private val wakeproof = "com.ogenrwotdaniel.wakeproof"

    private fun summary(
        local: Map<String, Long> = emptyMap(),
        peers: List<DeviceStudyUsage> = emptyList(),
        focus: Long = 0,
        credit: String? = notes
    ) = studyTimeSummary(
        today = today,
        local = local,
        localLabels = mapOf(notes to "Samsung Notes"),
        peers = peers,
        focusMinutes = focus,
        creditPackage = credit
    )

    private fun tablet(minutes: Map<String, Long>, day: Long = today) =
        DeviceStudyUsage("Tab S9 FE+", day, minutes, mapOf(notes to "Samsung Notes"))

    @Test
    fun `a watch block with no screen time is credited in full`() {
        val s = summary(focus = 50)
        assertEquals(50L, s.creditedMinutes)
        assertEquals(50L, s.totalMinutes)
        assertEquals(notes, s.creditedPackage)
    }

    @Test
    fun `a block spent in Notes on this device is not counted twice`() {
        val s = summary(local = mapOf(notes to 50L), focus = 50)
        assertEquals(0L, s.creditedMinutes)
        assertEquals(50L, s.totalMinutes)
    }

    @Test
    fun `only the shortfall is credited`() {
        val s = summary(local = mapOf(notes to 20L), focus = 50)
        assertEquals(30L, s.creditedMinutes)
        assertEquals(50L, s.totalMinutes)
    }

    @Test
    fun `measured time beyond focus still counts and credits nothing`() {
        val s = summary(local = mapOf(notes to 90L), focus = 50)
        assertEquals(0L, s.creditedMinutes)
        assertEquals(90L, s.totalMinutes)
    }

    @Test
    fun `the tablet's Notes time is merged into the same row`() {
        val s = summary(local = mapOf(notes to 10L, wakeproof to 5L), peers = listOf(tablet(mapOf(notes to 40L))))
        assertEquals(StudyAppTotal(notes, "Samsung Notes", 50L), s.measured.first())
        assertEquals(55L, s.measuredMinutes)
        assertEquals(1, s.peers.size)
    }

    @Test
    fun `tablet time counts against the shortfall so a tablet block is not credited again`() {
        val s = summary(peers = listOf(tablet(mapOf(notes to 50L))), focus = 50)
        assertEquals(0L, s.creditedMinutes)
        assertEquals(50L, s.totalMinutes)
    }

    @Test
    fun `a peer report from yesterday is ignored`() {
        val s = summary(peers = listOf(tablet(mapOf(notes to 40L), day = today - 1)), focus = 50)
        assertEquals(0L, s.measuredMinutes)
        assertEquals(50L, s.creditedMinutes)
        assertTrue(s.peers.isEmpty())
    }

    @Test
    fun `no credit app means measured time only`() {
        val s = summary(focus = 50, credit = "")
        assertEquals(null, s.creditedPackage)
        assertEquals(0L, s.creditedMinutes)
    }

    @Test
    fun `zero-minute apps are left off the card`() {
        val s = summary(local = mapOf(notes to 0L, wakeproof to 3L))
        assertEquals(listOf(wakeproof), s.measured.map { it.packageName })
    }

    @Test
    fun `WakeProof's own screens are not study, whichever device reported them`() {
        val own = "com.ogenrwotdaniel.wakeproof"
        val notes = "com.samsung.android.app.notes"
        val summary = studyTimeSummary(
            today = 10L,
            local = mapOf(notes to 20L, own to 90L),
            localLabels = mapOf(notes to "Samsung Notes"),
            // A tablet on an older build still reports its home screen and timer.
            peers = listOf(DeviceStudyUsage("Tab", 10L, mapOf(own to 60L))),
            focusMinutes = 50L,
            creditPackage = notes,
            ownPackage = own
        )
        assertEquals(listOf(notes), summary.measured.map { it.packageName })
        // A session timed on the watch with the phone showing the countdown counts once.
        assertEquals(50L, summary.totalMinutes)
        assertEquals(30L, summary.creditedMinutes)
        assertEquals(emptyList<DeviceStudyUsage>(), summary.peers)
    }
}
