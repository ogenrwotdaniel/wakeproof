package com.ogenrwotdaniel.wakeproof.alarm

import android.content.Context
import android.net.Uri
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType

/**
 * The LOCKED alarms (2026-11-04, the user's request: "my first alarm at 3am,
 * no way around, can't be turned off on the phone, surviving restart too like
 * focus timing"; a second at 05:30 the same night, and both at 25 pushups).
 * Now 03:00 and 05:40 at 10 pushups, and a fixed day-plan alarm at 08:20
 * ([SLOTS], [REPS]; the 08:20 slot was water until 2026-11-12).
 *
 * Each of [SLOTS] pins one alarm on the ringing device to its time and kind,
 * every day, enabled. Nothing on the phone can change it — the Settings rows
 * refuse — and anything that does reach the row (an edit or delete synced from
 * the tablet, a restore) is written back by [strictAlarmFixed] at the next
 * reconcile, stamped newer so the correction syncs back out too.
 *
 * While a pushup one ([Slot.hard]) rings there is no snooze and no emergency
 * dismiss: [REPS] pushups is the only way to silence it. The day-plan alarm is
 * fixed in the schedule only and rings like any other. Should the camera be unusable (broken hardware
 * or a revoked permission), the way out is [MATH_FALLBACK_PROBLEMS] sums in a row,
 * never a free dismiss.
 * It rings with [toneUri] when a tone is bundled.
 *
 * A ring that has not been verified is recorded ([markRinging]) in
 * device-protected storage and resumes after a reboot, a power-off or the
 * process dying ([pendingRing]), like a focus session does.
 */
object StrictAlarm {
    /**
     * One locked alarm's time and kind. [hard]: no snooze, no emergency dismiss,
     * held volume and resumed after a restart. The day-plan alarm is not: a ring
     * with no way out at the minute the user leaves home would be a trap.
     */
    data class Slot(
        val hour: Int,
        val minute: Int,
        val type: AlarmType = AlarmType.PUSHUP,
        val hard: Boolean = true
    ) {
        val minuteOfDay: Int get() = hour * 60 + minute
    }

    /**
     * In order; slot 0 keeps the storage keys from when there was only one.
     * Slot 2 was the water alarm until 2026-11-12 and is the day plan on
     * purpose: the row a device adopted for it is rewritten into the plan alarm
     * by [strictAlarmFixed] instead of being left behind. 03:00 and 05:40 since
     * 2026-11-11 (the user wakes at 03:00 and leaves at 08:20; 05:40 is halfway),
     * with the night wall moved to 20:00–03:00 to match, and the day plan at 08:20.
     * Adopted rows follow by themselves: [strictAlarmFixed] rewrites their time
     * at the next reconcile.
     */
    val SLOTS = listOf(
        Slot(3, 0), Slot(5, 40), Slot(8, 20, AlarmType.PLAN, hard = false),
        // The evening review, half an hour before the night wall (2026-11-12).
        Slot(19, 30, AlarmType.SHUTDOWN, hard = false)
    )

    /**
     * Former pushup [SLOTS]. Copies of an old locked pair that sync back (from
     * before a reinstall) sit on these minutes, and only [strictAlarmDuplicates]
     * kept them from ringing; without this they would start ringing with snooze
     * and emergency dismiss the day the slots moved.
     */
    val RETIRED_SLOTS = listOf(Slot(5, 30), Slot(4, 0), Slot(6, 5))

    /** Minutes of the day an unlocked alarm of [type] is kept silent on: current and retired slots. */
    fun silentMinutes(type: AlarmType): Set<Int> =
        (SLOTS + RETIRED_SLOTS).filter { it.type == type }.map { it.minuteOfDay }.toSet()

    /** Minutes of the day a locked alarm of [type] rings at. */
    fun slotMinutes(type: AlarmType): Set<Int> =
        SLOTS.filter { it.type == type }.map { it.minuteOfDay }.toSet()

    /** The [Slot.hard] alarms' minutes, in order: what the watch backs up. */
    val hardMinutes: List<Int> get() = SLOTS.filter { it.hard }.map { it.minuteOfDay }

    /** The last hard alarm's minute of the day: night quiet and the charge nag run until it. */
    val lastSlotMinute: Int get() = hardMinutes.max()

    const val REPS = com.ogenrwotdaniel.wakeproof.data.MAX_PUSHUP_TARGET
    const val MATH_FALLBACK_PROBLEMS = 10
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

    /** [alarm] is pinned to a slot: its row cannot be edited, hard or not. */
    fun isStrict(context: Context, alarm: Alarm?): Boolean =
        alarm != null && alarm.syncId in syncIds(context)

    /** The slot [alarm] is pinned to on this device, if any. */
    fun slotOf(context: Context, alarm: Alarm): Slot? =
        SLOTS.withIndex().firstOrNull { (index, _) -> syncId(context, index) == alarm.syncId }?.value

    /**
     * A [Slot.hard] alarm, by row id: decides how the ring behaves. By id
     * because the ringing path may run before the first unlock.
     */
    fun isStrictId(context: Context, alarmId: Long): Boolean {
        if (alarmId == -1L) return false
        val prefs = prefs(context)
        return SLOTS.indices.any { SLOTS[it].hard && prefs.getLong(idKey(it), -1L) == alarmId }
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
 * Label and history are kept, except a "Locked H:MM" label, which follows
 * the slot's time; only what decides when and how it rings is
 * pinned. [now] restamps it so the correction wins the sync merge.
 */
fun strictAlarmFixed(alarm: Alarm, slot: StrictAlarm.Slot, now: Long): Alarm? {
    val fixed = alarm.copy(
        // The created label names its time; a moved slot must not keep the old one.
        // A row changing kind (the 08:20 water alarm becoming the day plan) drops
        // its label too: "Morning water" on a plan alarm would be wrong.
        label = if (alarm.label in STRICT_LABELS || alarm.type != slot.type) strictLabel(slot) else alarm.label,
        hour = slot.hour,
        minute = slot.minute,
        type = slot.type,
        enabled = true,
        daysMask = StrictAlarm.DAYS_MASK,
        deleted = false
    )
    return if (fixed == alarm) null else fixed.copy(updatedAt = now, pending = true, seeded = false)
}

/**
 * Pure: which alarm [slot] adopts when it has none yet. A hard slot takes the
 * earliest live alarm of its kind not already locked ([taken] sync ids); the
 * day-plan slot only one already on its minute, so the user's other plan alarms
 * are not moved. Never another kind: that would quietly turn a math alarm into
 * pushups. Null = create.
 */
fun strictAlarmCandidate(
    alarms: List<Alarm>,
    slot: StrictAlarm.Slot,
    taken: Set<String> = emptySet()
): Alarm? =
    alarms.filter { it.enabled && !it.deleted && it.type == slot.type && it.syncId !in taken }
        .filter { slot.hard || it.hour * 60 + it.minute == slot.minuteOfDay }
        .minWithOrNull(compareBy<Alarm>({ it.hour }, { it.minute }))


/** The label a created locked alarm carries, e.g. "Locked 3:00". */
// Locale.ROOT: the label is compared with [STRICT_LABELS], so it must not take local digits.
fun strictLabel(slot: StrictAlarm.Slot): String =
    String.format(java.util.Locale.ROOT, "Locked %d:%02d", slot.hour, slot.minute)

/**
 * Labels WakeProof wrote on a locked alarm, for any slot past or present.
 * Exact strings rather than a pattern, so a "Locked 5:15" the user typed is
 * kept. An old label written with local digits is not recognised and stays.
 */
private val STRICT_LABELS: Set<String> =
    (StrictAlarm.SLOTS + StrictAlarm.RETIRED_SLOTS).map(::strictLabel).toSet()

/**
 * Whether [label] is one WakeProof wrote. The locked row shows it as plain
 * "Locked", because its stored "H:MM" ignores the phone's 12/24-hour setting
 * and the row already shows the time through formatAlarmTime. Only there: an
 * old copy synced back as an ordinary row must not claim to be locked.
 */
fun isStrictLabel(label: String): Boolean = label in STRICT_LABELS

/**
 * Pure: unlocked alarms standing on the minute of a locked slot of their own
 * kind, current or retired ([StrictAlarm.RETIRED_SLOTS]). None before a slot is
 * locked.
 */
fun strictAlarmDuplicates(alarms: List<Alarm>, locked: Set<String>): List<Alarm> =
    if (locked.isEmpty()) emptyList() else alarms.filter { alarm ->
        !alarm.deleted && alarm.syncId !in locked &&
            (alarm.hour * 60 + alarm.minute) in StrictAlarm.silentMinutes(alarm.type)
    }

/**
 * Pure: live alarms of a retired kind ([AlarmType.retired]), which the
 * reconcile deletes. [locked] rows are left out: the ringing device rewrites
 * its own into the current slot instead. So is any row on a fixed (not hard)
 * slot's minute, on every device: it may be another ringing device's locked
 * 08:20 row that this one has not yet seen rewritten, and deleting it would
 * have the two devices delete and restore it in turn (the reason
 * [strictAlarmDuplicates] are not deleted either). Such a row never rings and
 * is not listed; it stays until its owner rewrites it.
 */
fun retiredAlarms(alarms: List<Alarm>, locked: Set<String> = emptySet()): List<Alarm> {
    val fixedMinutes = StrictAlarm.SLOTS.filter { !it.hard }.map { it.minuteOfDay }.toSet()
    return alarms.filter {
        it.type.retired && !it.deleted && it.syncId !in locked &&
            (it.hour * 60 + it.minute) !in fixedMinutes
    }
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
