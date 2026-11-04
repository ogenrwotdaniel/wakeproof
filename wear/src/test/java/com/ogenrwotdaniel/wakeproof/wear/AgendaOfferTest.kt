package com.ogenrwotdaniel.wakeproof.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgendaOfferTest {

    private val min = 60_000L
    private val now = 10_000 * min
    private val idle = WatchTimerState()
    private val lecture = WatchAgendaEvent("k", "Lecture", now + 10 * min, now + 95 * min, done = false)

    private fun phone(running: Boolean = true, finishAt: Long = now + 12 * min, active: Boolean = true) =
        TimerSnapshot(active = active, running = running, phase = "Focus", remainingSeconds = 0, finishAtMillis = finishAt)

    @Test
    fun `nothing running offers what is left of the event, ending with it`() {
        assertEquals(AgendaOffer.Offer(85, now + 95 * min), AgendaOffers.offer(lecture, idle, null, now))
    }

    @Test
    fun `an event already under way counts from now`() {
        val started = lecture.copy(beginMillis = now - 30 * min)
        assertEquals(AgendaOffer.Offer(95, now + 95 * min), AgendaOffers.offer(started, idle, null, now))
    }

    @Test
    fun `a phone session refuses, naming the end of its current block`() {
        assertEquals(
            AgendaOffer.Refused(AgendaRefusal.PhoneSession(now + 12 * min)),
            AgendaOffers.offer(lecture, idle, phone(), now)
        )
    }

    @Test
    fun `a paused phone run still counts, with no known end`() {
        assertEquals(
            AgendaOffer.Refused(AgendaRefusal.PhoneSession(null)),
            AgendaOffers.offer(lecture, idle, phone(running = false), now)
        )
    }

    @Test
    fun `an inactive phone mirror is no session`() {
        assertEquals(AgendaOffer.Offer(85, now + 95 * min), AgendaOffers.offer(lecture, idle, phone(active = false), now))
    }

    @Test
    fun `a phone block that ended moments ago is still a session, its end unknown`() {
        // The phone's next push may simply not have landed yet.
        assertEquals(
            AgendaOffer.Refused(AgendaRefusal.PhoneSession(null)),
            AgendaOffers.offer(lecture, idle, phone(finishAt = now - 2 * min), now)
        )
    }

    @Test
    fun `a running phone mirror more than two minutes past its finish is a dead session`() {
        // The phone's process was killed mid-session and never pushed active=false.
        val dead = phone(finishAt = now - TimerSnapshot.RUNNING_STALE_MILLIS - 1)
        assertEquals(AgendaOffer.Offer(85, now + 95 * min), AgendaOffers.offer(lecture, idle, dead, now))
        assertNull(AgendaOffers.runningSession(idle, dead, now))
        // Exactly at the grace it still counts.
        val edge = phone(finishAt = now - TimerSnapshot.RUNNING_STALE_MILLIS)
        assertEquals(RunningSession.Phone(null), AgendaOffers.runningSession(idle, edge, now))
    }

    @Test
    fun `a paused phone mirror pushed more than twelve hours ago is a dead session`() {
        val old = phone(running = false).copy(updatedAtMillis = now - TimerSnapshot.PAUSED_STALE_MILLIS - 1)
        assertNull(AgendaOffers.runningSession(idle, old, now))
        val recent = phone(running = false).copy(updatedAtMillis = now - 60 * min)
        assertEquals(RunningSession.Phone(null), AgendaOffers.runningSession(idle, recent, now))
    }

    @Test
    fun `the mirror's liveness drives the main screen too`() {
        assertEquals(true, phone().liveAt(now))
        assertEquals(false, phone(active = false).liveAt(now))
        assertEquals(false, phone(finishAt = now - 3 * min).liveAt(now))
        // No push time from an older phone: a paused mirror is believed.
        assertEquals(true, phone(running = false).liveAt(now + 100 * 60 * min))
    }

    @Test
    fun `a watch session refuses with the session's end, not the block's`() {
        val watch = WatchTimer.startExact(60, null, now - 5 * min)
        val expected = WatchSessionBeacon.sessionEndsAt(watch, now)
        assertEquals(now + 55 * min, expected)
        assertEquals(
            AgendaOffer.Refused(AgendaRefusal.WatchSession(expected)),
            AgendaOffers.offer(lecture, watch, phone(), now)
        )
    }

    @Test
    fun `too little left, over and ticked are refused in that order of basics`() {
        val short = lecture.copy(beginMillis = now - 60 * min, endMillis = now + 9 * min)
        assertEquals(AgendaOffer.Refused(AgendaRefusal.TooLittleLeft), AgendaOffers.offer(short, idle, null, now))

        val over = lecture.copy(beginMillis = now - 60 * min, endMillis = now)
        // Over beats a running session: the session is not why it can't be focused for.
        assertEquals(AgendaOffer.Refused(AgendaRefusal.Over), AgendaOffers.offer(over, idle, phone(), now))

        assertEquals(
            AgendaOffer.Refused(AgendaRefusal.Done),
            AgendaOffers.offer(over.copy(done = true), idle, phone(), now)
        )
    }

    @Test
    fun `refusals read plainly`() {
        val t: (Long) -> String = { "16:07" }
        assertEquals("Your phone is in a focus session. This block ends at 16:07.",
            AgendaOffers.refusalText(AgendaRefusal.PhoneSession(1L), t))
        assertEquals("Your phone is in a focus session.", AgendaOffers.refusalText(AgendaRefusal.PhoneSession(null), t))
        assertEquals("A session is running on this watch until 16:07.",
            AgendaOffers.refusalText(AgendaRefusal.WatchSession(1L), t))
        assertEquals("Less than 10 minutes left.", AgendaOffers.refusalText(AgendaRefusal.TooLittleLeft, t))
        assertEquals("This event is over.", AgendaOffers.refusalText(AgendaRefusal.Over, t))
        assertEquals("Already ticked off.", AgendaOffers.refusalText(AgendaRefusal.Done, t))
    }

    @Test
    fun `no session when neither timer runs`() {
        assertNull(AgendaOffers.runningSession(idle, null, now))
    }

    // --- The tile -----------------------------------------------------------------

    private fun ev(key: String, begin: Long, end: Long, done: Boolean = false) =
        WatchAgendaEvent(key, key, now + begin * min, now + end * min, done)

    @Test
    fun `the tile lists ongoing and upcoming undone events, earliest first, at most four`() {
        val events = listOf(
            ev("later", 300, 360),
            ev("ended", -90, -30),
            ev("ongoing", -10, 20),
            ev("ticked", 30, 60, done = true),
            ev("next", 60, 90),
            ev("third", 120, 150),
            ev("fourth", 200, 230),
            ev("fifth", 400, 430)
        )
        assertEquals(
            listOf("ongoing", "next", "third", "fourth"),
            AgendaOffers.tileEvents(events, now).map { it.key }
        )
    }

    @Test
    fun `an event ending exactly now has dropped off`() {
        assertEquals(emptyList<WatchAgendaEvent>(), AgendaOffers.tileEvents(listOf(ev("a", -30, 0)), now))
    }

    @Test
    fun `the tile stays fresh until the next start or end, between one and fifteen minutes`() {
        assertEquals(7 * min, AgendaOffers.tileFreshnessMillis(now, listOf(now - min, now + 7 * min, now + 40 * min)))
        assertEquals(15 * min, AgendaOffers.tileFreshnessMillis(now, listOf(now + 2 * 60 * min)))
        assertEquals(15 * min, AgendaOffers.tileFreshnessMillis(now, emptyList()))
        assertEquals(min, AgendaOffers.tileFreshnessMillis(now, listOf(now + 5_000L)))
    }
}
