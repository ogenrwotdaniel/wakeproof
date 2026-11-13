package com.ogenrwotdaniel.wakeproof.alarm

import android.content.Context
import android.net.Uri
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType

/**
 * The LOCKED alarms (2026-11-04, the user's request: "my first alarm at 3am,
 * no way around, can't be turned off on the phone, surviving restart too like
 * focus timing"; a second at 05:30 the same night, and both at 25 pushups).
 * Now one alone: 03:00 at 10 pushups ([SLOTS], [REPS]). The 05:40 pushups,
 * the fixed 08:20 day-plan alarm (water until 2026-11-12) and the 19:30
 * evening review were removed on 2026-11-13 at the user's request: a summary
 * is written after every focus session now ([DROPPED_KEYS]).
 *
 * Each of [SLOTS] pins one alarm on the ringing device to its time and kind,
 * every day, enabled. Nothing on the phone can change it — the Settings rows
 * refuse — and anything that does reach the row (an edit or delete synced from
 * the tablet, a restore) is written back by [strictAlarmFixed] at the next
 * reconcile, stamped newer so the correction syncs back out too.
 *
 * While one rings there is no snooze and no emergency
 * dismiss: [REPS] pushups is the only way to silence it. Should the camera be unusable (broken hardware
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
     * One locked alarm's time and kind: no snooze, no emergency dismiss, held
     * volume, resumed after a restart.
     */
    data class Slot(
        val hour: Int,
        val minute: Int,
        val type: AlarmType = AlarmType.PUSHUP,
        /**
         * False for a slot that is fixed but not hard: its row is as uneditable
         * as any locked one, but the ring keeps the ordinary snooze and
         * emergency dismiss, is not resumed after a restart and is not backed
         * up by the watch. The evening's top three is one: an alarm to write
         * three lines must not be able to keep the user from bed.
         */
        val hard: Boolean = true
    ) {
        val minuteOfDay: Int get() = hour * 60 + minute
    }

    /**
     * The locked alarms, each under the storage key it has always had: key 0
     * from when there was only one. Keys are never renumbered or reused, or a
     * device would take the row it still holds under an old key for a new slot's.
     */
    val PINNED: List<IndexedValue<Slot>> = listOf(
        IndexedValue(0, Slot(3, 0)),
        // Tomorrow's top three, ten minutes before bed (2026-11-13, the user:
        // "make it alarm then for top three"). Key 4: 3 was the 19:30 evening
        // review, and a device still holding that row must delete it, not move it.
        IndexedValue(
            4,
            Slot(
                com.ogenrwotdaniel.wakeproof.plan.TOP_THREE_MINUTES / 60,
                com.ogenrwotdaniel.wakeproof.plan.TOP_THREE_MINUTES % 60,
                AlarmType.SHUTDOWN, hard = false
            )
        )
    )

    /** The locked alarms' times and kinds, in order. */
    val SLOTS: List<Slot> = PINNED.map { it.value }

    /**
     * Storage keys of slots that no longer exist: 1 was the 05:40 pushups, 2
     * the 08:20 day plan (water before that), 3 the 19:30 evening review (all
     * removed 2026-11-13). The row a device still holds under one is deleted
     * at the next reconcile, not left behind as an ordinary alarm that would
     * go on ringing at the old time.
     */
    val DROPPED_KEYS = listOf(1, 2, 3)

    /**
     * Former pushup [SLOTS]. Copies of an old locked pair that sync back (from
     * before a reinstall) sit on these minutes, and only [strictAlarmDuplicates]
     * kept them from ringing; without this they would start ringing with snooze
     * and emergency dismiss the day the slots moved.
     */
    val RETIRED_SLOTS = listOf(Slot(5, 30), Slot(4, 0), Slot(6, 5), Slot(5, 40))

    /** Minutes of the day an unlocked alarm of [type] is kept silent on: current and retired slots. */
    fun silentMinutes(type: AlarmType): Set<Int> =
        (SLOTS + RETIRED_SLOTS).filter { it.type == type }.map { it.minuteOfDay }.toSet()

    /** Minutes of the day a locked alarm of [type] rings at. */
    fun slotMinutes(type: AlarmType): Set<Int> =
        SLOTS.filter { it.type == type }.map { it.minuteOfDay }.toSet()

    /** The [Slot.hard] alarms' minutes, in order: what the watch backs up. */
    val hardMinutes: List<Int> get() = SLOTS.filter { it.hard }.map { it.minuteOfDay }

    /** The last locked alarm's minute of the day: night quiet and the charge nag run until it. */
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

    /** The sync ids of rows still held under a dropped key ([DROPPED_KEYS]). */
    fun droppedSyncIds(context: Context): Set<String> =
        DROPPED_KEYS.mapNotNull { syncId(context, it) }.toSet()

    /** Lets go of every dropped key, once its row is deleted. */
    fun forgetDropped(context: Context) {
        val edit = prefs(context).edit()
        DROPPED_KEYS.forEach { edit.remove(syncKey(it)).remove(idKey(it)) }
        edit.commit()
    }

    /**
     * Lets go of every pinned slot without touching the rows: for a tablet
     * leaving the ringing role, whose rows are the phone's own locked alarms.
     */
    fun release(context: Context) {
        val edit = prefs(context).edit()
        PINNED.forEach { edit.remove(syncKey(it.index)).remove(idKey(it.index)) }
        edit.remove(KEY_RINGING_ID).remove(KEY_RINGING_AT).commit()
    }

    /** Slot key [slot]'s sync id on this device; null = none adopted yet. */
    fun syncId(context: Context, slot: Int): String? = prefs(context).getString(syncKey(slot), null)

    /** Every adopted locked alarm's sync id. Empty = this device holds none. */
    fun syncIds(context: Context): Set<String> =
        PINNED.mapNotNull { syncId(context, it.index) }.toSet()

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

    /** [alarm] is pinned to a slot: its row cannot be edited. */
    fun isStrict(context: Context, alarm: Alarm?): Boolean =
        alarm != null && alarm.syncId in syncIds(context)

    /**
     * A locked alarm, by row id: decides how the ring behaves. By id
     * because the ringing path may run before the first unlock.
     */
    fun isStrictId(context: Context, alarmId: Long): Boolean {
        if (alarmId == -1L) return false
        val prefs = prefs(context)
        return PINNED.any { it.value.hard && prefs.getLong(idKey(it.index), -1L) == alarmId }
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
        // A row changing kind drops its label too: the old kind's name on it
        // would be wrong.
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
 * Pure: which alarm [slot] adopts when it has none yet: the earliest live
 * alarm of its kind not already locked ([taken] sync ids). Never another kind:
 * that would quietly turn a math alarm into pushups. Null = create.
 */
fun strictAlarmCandidate(
    alarms: List<Alarm>,
    slot: StrictAlarm.Slot,
    taken: Set<String> = emptySet()
): Alarm? =
    alarms.filter { it.enabled && !it.deleted && it.type == slot.type && it.syncId !in taken }
        // A fixed slot takes only a row already on its minute, and of several the
        // one every device agrees on ([fixedSlotRow]).
        .filter { slot.hard || it.hour * 60 + it.minute == slot.minuteOfDay }
        .minWithOrNull(
            if (slot.hard) compareBy<Alarm>({ it.hour }, { it.minute }) else compareBy { it.syncId }
        )


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
 * Pure: live alarms the reconcile deletes on every device: those of a retired
 * kind ([AlarmType.retired]: water, the day plan) and strays of a kind that
 * exists only as a fixed slot ([locked] is no longer consulted: the kept row is
 * chosen the same way everywhere).
 */
fun retiredAlarms(alarms: List<Alarm>, locked: Set<String> = emptySet()): List<Alarm> {
    // A kind that exists only as a fixed slot (the top three): every other live
    // row of it is a leftover, such as the old 19:30 evening review. Rows on the
    // slot's minute are spared everywhere, because on a device that rings nothing
    // the ringing device's locked row is unlocked and would be deleted for it.
    // One row a slot, the same on every device ([fixedSlotRow]): two devices that
    // each made one before they had synced showed the alarm twice.
    val fixed = StrictAlarm.SLOTS.filter { !it.hard }
    val kept = fixed.mapNotNull { fixedSlotRow(alarms, it)?.syncId }.toSet()
    return alarms.filter { alarm ->
        !alarm.deleted && (
            alarm.type.retired || (fixed.any { it.type == alarm.type } && alarm.syncId !in kept)
            )
    }
}

/**
 * Pure: the one row a fixed (not hard) [slot] keeps: of the live rows of its
 * kind on its minute, the smallest sync id, so every device picks the same one
 * whichever it made itself. Null when there is none.
 */
fun fixedSlotRow(alarms: List<Alarm>, slot: StrictAlarm.Slot): Alarm? =
    alarms.filter {
        !it.deleted && it.type == slot.type && it.hour * 60 + it.minute == slot.minuteOfDay
    }.minByOrNull { it.syncId }

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
