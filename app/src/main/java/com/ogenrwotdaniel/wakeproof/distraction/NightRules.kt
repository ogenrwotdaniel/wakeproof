package com.ogenrwotdaniel.wakeproof.distraction

/**
 * The night, as the 03:00 locked alarm needs it (2026-11-04, user's choice):
 *
 * - **Wind-down** at [WIND_DOWN_MINUTES] (19:15): one reminder that the sleep
 *   wall is half an hour away.
 * - **Sleep wall** from [SLEEP_START_MINUTES] (19:45, "everything else off
 *   totally" — user's choice, 2026-11-04) to [SLEEP_END_MINUTES] (03:00),
 *   with bed at [BEDTIME_MINUTES] (20:00, a "lights out" reminder): every app
 *   but the essentials (calls, home screen, Settings,
 *   WakeProof itself — the alarm runs in it), the clock and the calendar is
 *   walled. Seven hours in bed; without a bedtime the alarm turns into sleep
 *   loss rather than discipline. A focus session running into the night keeps
 *   its focus-list apps.
 * - **Charging** from [CHARGE_FROM_MINUTES] (19:45) until the alarm: a phone
 *   below [CHARGE_BELOW_PERCENT] % and not charging is nagged every
 *   [CHARGE_NAG_EVERY_MILLIS]. A flat phone is the one way out of a locked
 *   alarm that no rule can close.
 *
 * Fixed rather than settings: a bedtime that can be moved at 19:50 is not one.
 */
const val WIND_DOWN_MINUTES = 19 * 60 + 15
const val SLEEP_START_MINUTES = 19 * 60 + 45
const val BEDTIME_MINUTES = 20 * 60
const val SLEEP_END_MINUTES = 3 * 60
const val CHARGE_FROM_MINUTES = 19 * 60 + 45
const val CHARGE_BELOW_PERCENT = 40
const val CHARGE_NAG_EVERY_MILLIS = 30 * 60_000L

/** Pure: inside the sleep wall's hours (it spans midnight). */
fun sleepWallActive(minuteOfDay: Int): Boolean =
    minuteOfDay >= SLEEP_START_MINUTES || minuteOfDay < SLEEP_END_MINUTES

/** Pure: the wind-down reminder is due — inside its half hour, not yet sent today. */
fun windDownDue(minuteOfDay: Int, sentOnDay: Long, today: Long): Boolean =
    minuteOfDay in WIND_DOWN_MINUTES until SLEEP_START_MINUTES && sentOnDay != today

/** Pure: the "lights out" reminder is due — in the half hour from bedtime, not yet sent today. */
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
 * Pure: the camera warning lands silently from the sleep wall until the last
 * locked alarm ([lastSlotMinutes]): an hourly heads-up would only wake the user
 * it is meant to get up in the morning. It is still in the shade when they do.
 */
fun cameraNagSilent(minuteOfDay: Int, lastSlotMinutes: Int): Boolean =
    sleepWallActive(minuteOfDay) || minuteOfDay < lastSlotMinutes

/** Pure: how often the lost-grant alert returns, on the sideloaded build and on Play. */
fun wallsDownAlertEvery(sideloaded: Boolean): Long =
    if (sideloaded) 15 * 60_000L else 24 * 60 * 60_000L

/** Pure: a stamp in the future (a clock wound back) never silences a repeating warning. */
fun nagDue(lastAt: Long, now: Long, everyMillis: Long): Boolean =
    lastAt > now || now - lastAt >= everyMillis

/**
 * Pure: nag to charge — night hours up to the alarm, low and not charging,
 * and not nagged in the last [CHARGE_NAG_EVERY_MILLIS]. An unreadable level
 * (negative) never nags.
 */
fun chargeNagDue(
    minuteOfDay: Int,
    levelPercent: Int,
    charging: Boolean,
    lastNagAt: Long,
    now: Long
): Boolean =
    (minuteOfDay >= CHARGE_FROM_MINUTES || minuteOfDay < SLEEP_END_MINUTES) &&
        !charging && levelPercent in 0 until CHARGE_BELOW_PERCENT &&
        now - lastNagAt >= CHARGE_NAG_EVERY_MILLIS
