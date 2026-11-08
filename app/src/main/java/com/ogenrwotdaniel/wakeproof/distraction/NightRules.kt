package com.ogenrwotdaniel.wakeproof.distraction

/**
 * The night, as the first locked alarm needs it (2026-11-04, user's choice;
 * 21:00–04:00 since 2026-11-08, sleeping 21:00 to the 04:00 alarm):
 *
 * - **Wind-down** at [WIND_DOWN_MINUTES] (20:30): one reminder that the sleep
 *   wall is half an hour away.
 * - **Sleep wall** from [SLEEP_START_MINUTES] (21:00, "everything else off
 *   totally" — user's choice, 2026-11-04) to [SLEEP_END_MINUTES] (04:00, the
 *   first locked alarm), with bed at [BEDTIME_MINUTES] (the same 21:00, a
 *   "lights out" reminder): every app
 *   but the essentials (calls, home screen, Settings,
 *   WakeProof itself — the alarm runs in it), the clock and the calendar is
 *   walled. Seven hours in bed; without a bedtime the alarm turns into sleep
 *   loss rather than discipline. A focus session running into the night keeps
 *   its focus-list apps.
 * - **Charging** from [CHARGE_FROM_MINUTES] (21:30) until the last locked alarm: a phone
 *   below [CHARGE_BELOW_PERCENT] % and not charging is nagged every
 *   [CHARGE_NAG_EVERY_MILLIS]. A flat phone is the one way out of a locked
 *   alarm that no rule can close.
 *
 * Fixed rather than settings: a bedtime that can be moved at 21:05 is not one.
 */
const val SLEEP_START_MINUTES = 21 * 60
const val WIND_DOWN_MINUTES = SLEEP_START_MINUTES - 30
/** Bed is when the wall goes up; the wind-down copy says so. */
const val BEDTIME_MINUTES = SLEEP_START_MINUTES
const val SLEEP_END_MINUTES = 4 * 60
// Half an hour after lights-out, which already says "phone on charge": both at 21:00 was two alerts at once.
const val CHARGE_FROM_MINUTES = SLEEP_START_MINUTES + 30
const val CHARGE_BELOW_PERCENT = 40
const val CHARGE_NAG_EVERY_MILLIS = 30 * 60_000L

/** Pure: inside the sleep wall's hours (it spans midnight). */
fun sleepWallActive(minuteOfDay: Int): Boolean =
    minuteOfDay >= SLEEP_START_MINUTES || minuteOfDay < SLEEP_END_MINUTES

/** Pure: the wind-down reminder is due — inside its half hour, not yet sent today. */
fun windDownDue(minuteOfDay: Int, sentOnDay: Long, today: Long): Boolean =
    minuteOfDay in WIND_DOWN_MINUTES until SLEEP_START_MINUTES && sentOnDay != today

/**
 * Pure: the "lights out" reminder is due — in the half hour from bedtime, not
 * yet sent today. Bedtime is the wall's start, so this is the notice that the
 * wall is up (with the alarm time); it replaces the wind-down one on purpose.
 */
fun lightsOutDue(minuteOfDay: Int, sentOnDay: Long, today: Long): Boolean =
    minuteOfDay in BEDTIME_MINUTES until BEDTIME_MINUTES + 30 && sentOnDay != today

/**
 * Pure: warn that a locked alarm's pushups have lost the camera, hourly while it
 * lasts. Without it the ring falls back to sums, which can be done lying down.
 */
fun cameraNagDue(strictHeld: Boolean, cameraGranted: Boolean, lastNagAt: Long, now: Long): Boolean =
    strictHeld && !cameraGranted && nagDue(lastNagAt, now, CAMERA_NAG_EVERY_MILLIS)

const val CAMERA_NAG_EVERY_MILLIS = 60 * 60_000L

/**
 * Pure: repeating warnings land silently from the sleep wall until the last
 * locked alarm ([lastSlotMinutes]): a heads-up would only wake the user it is
 * meant to get up in the morning. They are still in the shade when they do.
 */
fun nightQuiet(minuteOfDay: Int, lastSlotMinutes: Int): Boolean =
    sleepWallActive(minuteOfDay) || minuteOfDay < lastSlotMinutes

/**
 * Pure: a walls-down repeat holds off ringing until morning. The first alert
 * always rings, whatever the hour, and a lost tree is always news.
 */
fun wallsDownRepeatHeld(treeLost: Boolean, lastAlertAt: Long, quiet: Boolean): Boolean =
    !treeLost && lastAlertAt != 0L && quiet

/** Pure: how often the lost-grant alert returns, on the sideloaded build and on Play. */
fun wallsDownAlertEvery(sideloaded: Boolean): Long =
    if (sideloaded) 15 * 60_000L else 24 * 60 * 60_000L

/** Pure: a stamp in the future (a clock wound back) never silences a repeating warning. */
fun nagDue(lastAt: Long, now: Long, everyMillis: Long): Boolean =
    lastAt > now || now - lastAt >= everyMillis

/**
 * Pure: nag to charge — from the wall up to the last locked alarm
 * ([lastSlotMinutes]; a phone unplugged after the first ring must still be on
 * for the second), low and not charging,
 * and not nagged in the last [CHARGE_NAG_EVERY_MILLIS]. An unreadable level
 * (negative) never nags.
 */
fun chargeNagDue(
    minuteOfDay: Int,
    levelPercent: Int,
    charging: Boolean,
    lastNagAt: Long,
    now: Long,
    lastSlotMinutes: Int = SLEEP_END_MINUTES
): Boolean =
    (minuteOfDay >= CHARGE_FROM_MINUTES || minuteOfDay < maxOf(SLEEP_END_MINUTES, lastSlotMinutes)) &&
        !charging && levelPercent in 0 until CHARGE_BELOW_PERCENT &&
        now - lastNagAt >= CHARGE_NAG_EVERY_MILLIS
