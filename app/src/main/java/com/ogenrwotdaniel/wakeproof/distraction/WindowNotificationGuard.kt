package com.ogenrwotdaniel.wakeproof.distraction

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Outside the open window (2026-10-29, the user's choice: every windowed app),
 * a windowed app shows nothing at all — its notifications are removed as they
 * arrive, and whatever is still in the shade when the window closes is swept.
 *
 * Windowed = the waiting apps plus [AppGate.WINDOWED_PACKAGES], the same set
 * [AppBlockService] walls with [GateVerdict.WINDOW_WALL]. Inside the window
 * nothing is touched.
 *
 * LIMIT, accepted: the system plays a notification's sound and vibration before
 * any listener hears of it, so a single buzz can still get through. What this
 * stops is the notification sitting in the shade or on the lock screen as a
 * reason to pick the phone up. Ongoing notifications cannot be removed by a
 * listener and are left alone by the system's own rule.
 *
 * Needs notification access, a special grant (row on the Usage screen under
 * the open window). Nothing is read beyond the posting package.
 */
@AndroidEntryPoint
class WindowNotificationGuard : NotificationListenerService() {

    @Inject lateinit var wallRulesRepository: WallRulesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sweeper: Job? = null

    @Volatile private var rules: WallRules = WallRules()
    private val browsers: Set<String> by lazy { AppGate.browserPackages(this) }
    private val essentials: Set<String> by lazy { essentialPackages(this) }
    private val windowedCache = mutableMapOf<String, Boolean>()

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            wallRulesRepository.rules.collect { next ->
                if (next.gateBrowsers != rules.gateBrowsers) windowedCache.clear()
                rules = next
            }
        }
    }

    override fun onListenerConnected() {
        sweeper?.cancel()
        // Once a minute is enough: it is what catches the window closing on a
        // shade that still holds notifications posted inside it.
        sweeper = scope.launch {
            while (true) {
                sweep()
                delay(SWEEP_EVERY_MILLIS)
            }
        }
    }

    override fun onListenerDisconnected() {
        sweeper?.cancel()
        sweeper = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (shouldRemove(sbn)) remove(sbn)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sweep() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.filter(::shouldRemove).forEach(::remove)
    }

    private fun shouldRemove(sbn: StatusBarNotification): Boolean {
        if (sbn.isOngoing || !sbn.isClearable) return false
        val minuteOfDay = LocalTime.now().let { it.hour * 60 + it.minute }
        if (!outsideOpenWindow(minuteOfDay, rules.openWindowStartMinutes, rules.openWindowEndMinutes)) {
            return false
        }
        val pkg = sbn.packageName
        return windowedCache.getOrPut(pkg) {
            val gated = AppGate.isGated(this, pkg, essentials, browsers, rules.gateBrowsers)
            pkg !in essentials && AppGate.keepsOpenWindow(pkg, gated)
        }
    }

    private fun remove(sbn: StatusBarNotification) {
        runCatching { cancelNotification(sbn.key) }
            .onSuccess { Log.i(TAG, "${sbn.packageName} notification removed (outside open window)") }
            .onFailure { Log.w(TAG, "could not remove ${sbn.packageName} notification", it) }
    }

    companion object {
        private const val TAG = "WindowNotificationGuard"
        private const val SWEEP_EVERY_MILLIS = 60_000L

        /** Whether the user has given WakeProof notification access. */
        fun hasAccess(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context): ComponentName =
            ComponentName(context, WindowNotificationGuard::class.java)
    }
}
