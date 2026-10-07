package com.ogenrwotdaniel.wakeproof.distraction

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ogenrwotdaniel.wakeproof.MainActivity
import com.ogenrwotdaniel.wakeproof.R
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Periodic check of per-app screen time against the user's distraction
 * limits; posts one nudge per app per day when a cap is crossed.
 *
 * Instantiated by WorkManager's default factory, so dependencies are built by
 * hand from [Context] rather than injected.
 */
class DistractionLimitWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val limitsRepository = DistractionLimitRepository(applicationContext)
        val usageRepository = UsageStatsRepository(applicationContext)
        // Land any loosening whose 24 h cooling-off has run out.
        limitsRepository.promoteDuePending()

        // Blocked apps get a wall instead, so a nudge for them would only be a
        // notification about something the user has already been shown.
        val limits = limitsRepository.currentLimits().filterValues { it.mode == LimitMode.NUDGE }
        // Enforcement is separate from nudging and has to survive reboots, so every
        // periodic run also makes sure the guard service is in the right state.
        AppBlockService.sync(applicationContext)
        if (limits.isEmpty() || !usageRepository.hasAccess()) return Result.success()

        val usedToday = usageRepository.todayMinutesFor(limits.keys)
        val today = LocalDate.now().toEpochDay()
        limits.forEach { (pkg, limit) ->
            val used = usedToday[pkg] ?: 0
            if (used >= limit.minutes && limitsRepository.tryMarkNotified(pkg, today)) {
                notifyOverLimit(usageRepository.appLabel(pkg), pkg, used, limit.minutes)
            }
        }
        return Result.success()
    }

    private fun notifyOverLimit(label: String, pkg: String, used: Long, cap: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Distraction nudges", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Fires when an app crosses its daily limit" }
        )
        val openApp = PendingIntent.getActivity(
            applicationContext, pkg.hashCode(),
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            pkg.hashCode(),
            Notification.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("$label limit reached")
                .setContentText("${used} min today (limit $cap min). Back to the books? 📚")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val CHANNEL_ID = "wakeproof_distraction"
        private const val WORK_NAME = "distraction_limit_check"

        /** Idempotent; call on every app start. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DistractionLimitWorker>(15, TimeUnit.MINUTES).build()
            )
        }
    }
}
