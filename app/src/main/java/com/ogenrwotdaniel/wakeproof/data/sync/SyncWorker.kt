package com.ogenrwotdaniel.wakeproof.data.sync

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ogenrwotdaniel.wakeproof.wear.WearStatsSync
import com.ogenrwotdaniel.wakeproof.widget.WakeProofWidget
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Periodic background sync, so the tablet is already current when it is picked up
 * rather than only after the app has been open long enough to sync in the
 * foreground.
 *
 * Dependencies come through a Hilt entry point, matching
 * [com.ogenrwotdaniel.wakeproof.distraction.DistractionLimitWorker] and the Glance
 * widget: WorkManager builds workers outside the DI graph.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncWorkerEntryPoint {
        fun syncEngine(): SyncEngine
        fun wearStatsSync(): WearStatsSync
    }

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, SyncWorkerEntryPoint::class.java
        )
        return when (val result = entryPoint.syncEngine().sync()) {
            is SyncResult.Skipped -> Result.success()
            is SyncResult.Failure -> Result.retry()
            is SyncResult.Success -> {
                // A download that changed nothing should not spend a Bluetooth push
                // and a widget redraw; one that did must, or the watch tile and the
                // home screen keep showing the pre-sync numbers until the app is opened.
                if (result.changed > 0) {
                    runCatching { WakeProofWidget().updateAll(applicationContext) }
                    runCatching { entryPoint.wearStatsSync().push() }
                }
                Result.success()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "wakeproof-sync"
        private const val ONE_OFF_NAME = "wakeproof-sync-now"

        /**
         * Schedules the recurring pass. KEEP, so re-scheduling on every app start
         * does not reset the interval and starve the job on a device that is opened
         * often.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * Pushes whatever just changed, without waiting for the next hourly pass.
         *
         * Queued as work rather than run inline because the caller is usually an
         * Activity going away: WorkManager will still deliver it once there is a
         * network, whereas a coroutine tied to the activity would be cancelled.
         */
        fun requestOneOff(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_OFF_NAME,
                // REPLACE: only the newest request matters, and leaving a queue of
                // them would sync the same state several times over.
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(WORK_NAME)
                cancelUniqueWork(ONE_OFF_NAME)
            }
        }
    }
}
