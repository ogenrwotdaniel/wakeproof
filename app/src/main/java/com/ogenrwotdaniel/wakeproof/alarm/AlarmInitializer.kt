package com.ogenrwotdaniel.wakeproof.alarm

import com.ogenrwotdaniel.wakeproof.data.AlarmRepository
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings AlarmManager in sync with the database: seeds the default alarms on
 * first launch, (re)schedules every live alarm, and drops the schedule of every
 * alarm that is no longer live. Safe to call repeatedly — rescheduling replaces
 * the existing PendingIntent. Called from app startup, from [BootReceiver], and
 * whenever the device's alarm role is switched.
 */
@Singleton
class AlarmInitializer @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val directBootStore: DirectBootAlarmStore,
    private val devicePolicy: AlarmDevicePolicy
) {
    suspend fun initialize() {
        repository.seedDefaultAlarmsIfEmpty()
        val plan = reconcilePlan(
            alarms = repository.getAllAlarmsIncludingDeleted(),
            mirroredIds = directBootStore.ids(),
            ringsAlarms = devicePolicy.ringsAlarms
        )
        // Arm the live alarms before dropping anything: if the process is killed
        // part-way through, the failure mode is a stale alarm rather than a
        // missed one.
        plan.toSchedule.forEach(scheduler::schedule)
        plan.toCancel.forEach(scheduler::cancel)
        plan.orphanIds.forEach(scheduler::cancelById)
    }
}

/**
 * @param toSchedule alarms that should be armed.
 * @param toCancel alarms whose row still exists but which must not ring.
 * @param orphanIds mirrored alarm ids with no row left at all.
 */
internal data class AlarmReconcilePlan(
    val toSchedule: List<Alarm>,
    val toCancel: List<Alarm>,
    val orphanIds: List<Long>
)

/**
 * Decides what AlarmManager should hold, given the whole alarms table
 * (tombstones included) and the ids in the direct-boot mirror.
 *
 * [orphanIds] is the repair path for ghost alarms. Most ways of removing an
 * alarm leave a tombstone, which keeps it visible here long enough to cancel —
 * but retiring the seeded starter alarms is a hard delete, so those rows vanish
 * while their pending intents stay armed. Nothing in the table can point at them
 * afterwards; the mirror is the only remaining record that they were ever
 * scheduled, and an id in the mirror with no row behind it is exactly that ghost.
 */
internal fun reconcilePlan(
    alarms: List<Alarm>,
    mirroredIds: Set<Long>,
    ringsAlarms: Boolean = true
): AlarmReconcilePlan {
    val (live, dead) = alarms.partition { it.enabled && !it.deleted }
    return AlarmReconcilePlan(
        // A companion device arms nothing and disarms everything, so switching
        // the role off is a real disarm rather than a promise not to ring: the
        // pending intents actually go, and so does the direct-boot mirror that
        // would otherwise re-register them on the next reboot.
        toSchedule = if (ringsAlarms) live else emptyList(),
        toCancel = if (ringsAlarms) dead else alarms,
        orphanIds = (mirroredIds - alarms.map { it.id }.toSet()).sorted()
    )
}
