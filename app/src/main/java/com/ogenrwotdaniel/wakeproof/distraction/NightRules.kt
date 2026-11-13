package com.ogenrwotdaniel.wakeproof.distraction

/**
 * The night, as the first locked alarm needs it (2026-11-04, user's choice;
 * 20:00–03:00 since 2026-11-11, sleeping 20:00 to the 03:00 alarm):
 *
 * - **Sleep wall** from [SLEEP_START_MINUTES] (20:00, "everything else off
 *   totally" — user's choice, 2026-11-04) to [SLEEP_END_MINUTES] (03:00, the
 *   first locked alarm), with bed at [BEDTIME_MINUTES] (the same 20:00, a
 *   "lights out" reminder): every app
 *   but the essentials (calls, home screen, Settings,
 *   WakeProof itself — the alarm runs in it), the clock and the calendar is
 *   walled. Seven hours in bed; without a bedtime the alarm turns into sleep
 *   loss rather than discipline. A focus session running into the night keeps
 *   its focus-list apps.
 * - **Charging** from [CHARGE_FROM_MINUTES] (20:30) until the last locked alarm: a phone
 *   below [CHARGE_BELOW_PERCENT] % and not charging is nagged every
 *   [CHARGE_NAG_EVERY_MILLIS]. A flat phone is the one way out of a locked
 *   alarm that no rule can close.
 *
 * Fixed rather than settings: a bedtime that can be moved at 20:05 is not one.
 */
const val SLEEP_START_MINUTES = 20 * 60
/** Bed is when the wall goes up. */
const val BEDTIME_MINUTES = SLEEP_START_MINUTES
const val SLEEP_END_MINUTES = 3 * 60
// Half an hour after lights-out, which already says "phone on charge": both at once was two alerts together.
const val CHARGE_FROM_MINUTES = SLEEP_START_MINUTES + 30
const val CHARGE_BELOW_PERCENT = 40
const val CHARGE_NAG_EVERY_MILLIS = 30 * 60_000L

/** Pure: inside the sleep wall's hours (it spans midnight). */
fun sleepWallActive(minuteOfDay: Int): Boolean =
    minuteOfDay >= SLEEP_START_MINUTES || minuteOfDay < SLEEP_END_MINUTES

/**
 * The phone's longer night (2026-11-13, the user: "gating almost all apps
 * except phone, camera, calendar, wakeproof right from 6pm to 8am ... only in
 * the phone not tablet"). The same wall as the sleep wall, with the same
 * verdict, from [PHONE_NIGHT_START_MINUTES] to [PHONE_NIGHT_END_MINUTES]; the
 * tablet keeps the sleep wall's own hours. Bed, lights out, the charge nag,
 * the quiet hours and the sleep score stay on 20:00–03:00 everywhere: this is
 * about the phone being shut, not about when the user sleeps.
 */
const val PHONE_NIGHT_START_MINUTES = 18 * 60
const val PHONE_NIGHT_END_MINUTES = 8 * 60

/** Pure: the minute the night wall goes up on this kind of device. */
fun nightWallStart(phone: Boolean): Int = if (phone) PHONE_NIGHT_START_MINUTES else SLEEP_START_MINUTES

/** Pure: the minute the night wall comes down on this kind of device. */
fun nightWallEnd(phone: Boolean): Int = if (phone) PHONE_NIGHT_END_MINUTES else SLEEP_END_MINUTES

/** Pure: inside the night wall's hours for this kind of device (they span midnight). */
fun nightWallActive(minuteOfDay: Int, phone: Boolean): Boolean =
    minuteOfDay >= nightWallStart(phone) || minuteOfDay < nightWallEnd(phone)

/**
 * Pure: the night wall takes a walled app's notifications only in the sleep
 * wall's own hours. In the phone's extra hours the app stays shut but its
 * notification stays in the shade: a removed one is lost for good, and
 * fourteen hours a day of lost bank and mail alerts is more than the wall is for.
 */
fun nightWallStrips(minuteOfDay: Int): Boolean = sleepWallActive(minuteOfDay)

/**
 * Pure: the night wall's headline and body. In bed hours it sends the user to
 * bed; in the phone's extra hours it only says what is open and until when.
 */
// [phone] false is every device on the shorter night.
fun nightWallCopy(minuteOfDay: Int, phone: Boolean, formatClock: (Int) -> String): Pair<String, String> =
    when {
        !phone -> "Time to sleep" to
            "Everything but calls, the clock and your alarms is shut until " +
            "${formatClock(SLEEP_END_MINUTES)}. Put it on charge and go to bed."
        sleepWallActive(minuteOfDay) -> "Time to sleep" to
            "Everything but calls, the camera, the clock, the calendar and WakeProof is shut until " +
            "${formatClock(PHONE_NIGHT_END_MINUTES)}. Put the phone on charge and go to bed."
        else -> "Phone shut for the night" to
            "Everything but calls, the camera, the clock, the calendar and WakeProof is shut from " +
            "${formatClock(PHONE_NIGHT_START_MINUTES)} to ${formatClock(PHONE_NIGHT_END_MINUTES)}."
    }

/**
 * Pure: the "lights out" reminder is due — in the half hour from bedtime, not
 * yet sent today. Bedtime is the wall's start, so this is the notice that the
 * wall is up (with the alarm time). The only night notice: the 19:30
 * wind-down reminder before it was removed on 2026-11-13 at the user's request.
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

/**
 * Whether this device has the phone's longer night ([nightWallActive]): any
 * phone, on the Play build as on the sideloaded one (the user's choice,
 * 2026-11-13: the source is public for anyone who wants other hours).
 */
fun longNightDevice(context: android.content.Context): Boolean =
    com.ogenrwotdaniel.wakeproof.alarm.isPhoneDevice(context)

/**
 * Pure: bed hours take no work (2026-11-13, the user: "no exception or no work
 * whether on tablet, watch or phone between 8pm to 3am"). No focus session
 * starts or is joined then on any device, and one that runs into them loses
 * its focus-list apps to the sleep wall like everything else.
 */
fun bedHoursShutWork(minuteOfDay: Int): Boolean = sleepWallActive(minuteOfDay)

/**
 * Pure: a session of [minutes] started now is in bed hours or would run into
 * them. A calendar event's or a joined session's length is not checked beyond
 * its start when [minutes] is 0.
 */
fun sessionReachesBed(minuteOfDay: Int, minutes: Int): Boolean =
    bedHoursShutWork(minuteOfDay) || minuteOfDay + minutes > SLEEP_START_MINUTES

/** Said when a session start is refused for bed hours. */
const val BED_HOURS_TEXT = "No focus between 20:00 and 03:00: this session would not end by 20:00."

/**
 * A call is ringing or under way (the audio mode says so without any
 * permission): nothing WakeProof opens by itself may land on it.
 */
fun callOrRing(context: android.content.Context): Boolean = runCatching {
    context.getSystemService(android.media.AudioManager::class.java).mode != android.media.AudioManager.MODE_NORMAL
}.getOrDefault(false)
