package com.ogenrwotdaniel.wakeproof.alarm

import android.content.Context
import android.net.Uri
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType

/**
 * The LOCKED alarms (2026-11-04, the user's request: "my first alarm at 3am,
 * no way around, can't be turned off on the phone, surviving restart too like
 * focus timing"; a second at 05:30 the same night, and both at 25 pushups).
 *
 * Each of [SLOTS] pins one alarm on the ringing device to its time, pushups,
 * every day, enabled. Nothing on the phone can change it — the Settings rows
 * refuse — and anything that does reach the row (an edit or delete synced from
 * the tablet, a restore) is written back by [strictAlarmFixed] at the next
 * reconcile, stamped newer so the correction syncs back out too.
 *
 * While one rings there is no snooze and no emergency dismiss: [REPS] pushups
 * is the only way to silence it. Should the camera be unusable (broken hardware
 * or a revoked permission), the way out is [MATH_FALLBACK_PROBLEMS] sums in a row,
 * never a free dismiss.
 * It rings with [toneUri] when a tone is bundled.
 *
 * A ring that has not been verified is recorded ([markRinging]) in
 * device-protected storage and resumes after a reboot, a power-off or the
 * process dying ([pendingRing]), like a focus session does.
 */
object StrictAlarm {
    /** One locked alarm's time. */
    data class Slot(val hour: Int, val minute: Int)

    /** In order; slot 0 keeps the storage keys from when there was only one. */
    val SLOTS = listOf(Slot(3, 0), Slot(5, 30))

    const val REPS = com.ogenrwotdaniel.wakeproof.data.MAX_PUSHUP_TARGET
    const val MATH_FALLBACK_PROBLEMS = 10
    val TYPE = AlarmType.PUSHUP
    const val DAYS_MASK = 127

    /** `res/raw/locked_alarm.*`, looked up by name so the build works without one. */
    private const val TONE_NAME = "locked_alarm"

    /** A ring older than this is not resumed: a whole day has gone by. */
    const val RESUME_WITHIN_MILLIS = 20 * 60 * 60 * 1000L

    private const val PREFS = "strict_alarm"
    private const val KEY_RINGING_ID = "ringing_id"
    private const val KEY_RINGING_AT = "ringing_at"

    private fun syncKey(slot: Int) = if (slot == 0) "sync_id" else "sync_id_$slot"
    private fun idKey(slot: Int) = if (slot == 0) "alarm_id" else "alarm_id_$slot"

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Slot [slot]'s sync id on this device; null = none adopted yet. */
    fun syncId(context: Context, slot: Int): String? = prefs(context).getString(syncKey(slot), null)

    /** Every adopted locked alarm's sync id. Empty = this device holds none. */
    fun syncIds(context: Context): Set<String> =
        SLOTS.indices.mapNotNull { syncId(context, it) }.toSet()

    /**
     * Records [alarm] as slot [slot]'s. The row id is kept too, because a ring
     * before the first unlock has only the id and cannot read Room.
     */
    fun adopt(context: Context, slot: Int, alarm: Alarm) {
        prefs(context).edit()
            .putString(syncKey(slot), alarm.syncId)
            .putLong(idKey(slot), alarm.id)
            .commit()
    }

    fun isStrict(context: Context, alarm: Alarm?): Boolean =
        alarm != null && alarm.syncId in syncIds(context)

    /** By row id, for the ringing path, which may run before the first unlock. */
    fun isStrictId(context: Context, alarmId: Long): Boolean {
        if (alarmId == -1L) return false
        val prefs = prefs(context)
        return SLOTS.indices.any { prefs.getLong(idKey(it), -1L) == alarmId }
    }

    /** The bundled alarm song (every alarm uses it), or null for the phone's alarm sound. */
    fun toneUri(context: Context): Uri? {
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier(TONE_NAME, "raw", context.packageName)
        return if (id == 0) null else Uri.parse("android.resource://${context.packageName}/$id")
    }

    /** A ring of [alarmId] started at [now] and is not yet verified. */
    fun markRinging(context: Context, alarmId: Long, now: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_RINGING_ID, alarmId).putLong(KEY_RINGING_AT, now).commit()
    }

    /** Verified: nothing to resume. */
    fun clearRinging(context: Context) {
        prefs(context).edit().remove(KEY_RINGING_ID).remove(KEY_RINGING_AT).commit()
    }

    /** The alarm id to ring again, if a ring was cut off before it was verified. */
    fun pendingRing(context: Context, now: Long = System.currentTimeMillis()): Long? {
        val prefs = prefs(context)
        val id = prefs.getLong(KEY_RINGING_ID, -1L)
        val at = prefs.getLong(KEY_RINGING_AT, 0L)
        return id.takeIf { shouldResumeRing(id, at, now) }
    }

    /** Re-starts a cut-off ring. Safe to call anywhere, any number of times. */
    fun resumeIfPending(context: Context) {
        if (AlarmRingingService.ringing) return
        pendingRing(context)?.let { AlarmRingingService.start(context, it) }
    }
}

/**
 * Pure: whether a recorded ring is resumed. A stamp in the future (a clock
 * wound back) is resumed too — the alarm clock that is unsure still rings.
 */
fun shouldResumeRing(alarmId: Long, ringingAt: Long, now: Long): Boolean =
    alarmId != -1L && ringingAt > 0L &&
        (ringingAt > now || now - ringingAt < StrictAlarm.RESUME_WITHIN_MILLIS)

/**
 * Pure: the locked alarm as it must be, or null when [alarm] already is.
 * Label and history are kept; only what decides when and how it rings is
 * pinned. [now] restamps it so the correction wins the sync merge.
 */
fun strictAlarmFixed(alarm: Alarm, slot: StrictAlarm.Slot, now: Long): Alarm? {
    val fixed = alarm.copy(
        hour = slot.hour,
        minute = slot.minute,
        type = StrictAlarm.TYPE,
        enabled = true,
        daysMask = StrictAlarm.DAYS_MASK,
        deleted = false
    )
    return if (fixed == alarm) null else fixed.copy(updatedAt = now, pending = true, seeded = false)
}

/**
 * Pure: which alarm a slot adopts when it has none yet — the earliest live
 * PUSHUP alarm not already locked ([taken] sync ids). Never another kind: that
 * would quietly turn the user's math or water alarm into pushups. Null = create.
 */
fun strictAlarmCandidate(alarms: List<Alarm>, taken: Set<String> = emptySet()): Alarm? =
    alarms.filter { it.enabled && !it.deleted && it.type == StrictAlarm.TYPE && it.syncId !in taken }
        .minWithOrNull(compareBy<Alarm>({ it.hour }, { it.minute }))

/** Pure: unlocked pushup alarms standing on a locked slot's minute. None before a slot is locked. */
fun strictAlarmDuplicates(alarms: List<Alarm>, locked: Set<String>): List<Alarm> =
    if (locked.isEmpty()) emptyList() else alarms.filter { alarm ->
        !alarm.deleted && alarm.type == StrictAlarm.TYPE && alarm.syncId !in locked &&
            StrictAlarm.SLOTS.any { it.hour == alarm.hour && it.minute == alarm.minute }
    }

/** The share of the alarm stream's maximum a locked alarm rings at; see [strictStreamIndex]. */
const val STRICT_STREAM_FRACTION = 0.7f

/** Pure: the alarm-stream index a locked alarm holds, at least 1 so it is never silent. */
fun strictStreamIndex(maxIndex: Int): Int =
    kotlin.math.ceil(maxIndex * STRICT_STREAM_FRACTION).toInt().coerceIn(1, maxOf(1, maxIndex))

/**
 * Pure: the ringtone's volume (0..1, of the alarm stream) while pushups are
 * counted (2026-11-04, user's request: "once I start doing pushups the volume
 * goes down"). Full until the first rep; then it drops to [PUSHUP_FIRST_REP_VOLUME]
 * and falls with each rep to [PUSHUP_MIN_VOLUME] — never silent, so stopping
 * halfway is never comfortable. A counter reset (reps back to 0) is full again.
 */
fun pushupRingVolume(reps: Int, target: Int): Float {
    if (reps <= 0) return 1f
    if (target <= 1) return PUSHUP_MIN_VOLUME
    val left = 1f - (reps - 1).toFloat() / (target - 1)
    return (PUSHUP_FIRST_REP_VOLUME * left).coerceIn(PUSHUP_MIN_VOLUME, 1f)
}

const val PUSHUP_FIRST_REP_VOLUME = 0.6f
const val PUSHUP_MIN_VOLUME = 0.15f
