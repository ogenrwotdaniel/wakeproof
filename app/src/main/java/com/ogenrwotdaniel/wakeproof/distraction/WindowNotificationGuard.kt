package com.ogenrwotdaniel.wakeproof.distraction

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDateTime
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A walled app shows nothing at all — its notifications are removed as they
 * arrive, and whatever is still in the shade when a wall goes up is swept.
 *
 * Since 2026-11-01 it asks [AppBlockService.walledNow] and follows EVERY wall —
 * a focus session (on any of the user's devices), a spent cap, the Monday–Thursday
 * open window and Friday–Sunday shutdown, bedtime, earn-the-feed, and the night
 * sleep wall — because a notification is a way to read an app the wall keeps
 * shut: the shade, the Edge panel, Edge lighting and an icon's long-press preview
 * all show it. With the blocker not running it falls back to [windowShutActive]
 * directly for gated apps.
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
    private var essentials: Set<String> = emptySet()
    /**
     * Never stripped, though a focus session walls it: the default SMS app is
     * where sign-in codes arrive, and a code that cannot be read mid-session
     * locks the user out of something real. Opening the app stays walled.
     */
    private var smsApp: String? = null
    /** The default SMS app as read, spared or not; [AppGate.isGated] needs it. */
    private var defaultSms: String? = null
    private val windowedCache = mutableMapOf<String, Boolean>()

    override fun onCreate() {
        super.onCreate()
        // Off the main thread. Nothing is stripped until it lands: without the SMS
        // app a sign-in code could go, and a removed notification is lost for good.
        // The minute's sweep catches anything kept meanwhile.
        scope.launch { refreshDefaults() }
        scope.launch {
            wallRulesRepository.rules.collect { next ->
                if (next.gateBrowsers != rules.gateBrowsers) windowedCache.clear()
                rules = next
            }
        }
    }

    override fun onListenerConnected() {
        sweeper?.cancel()
        // Once a minute is enough: it is what catches a wall going up (the
        // window closing, a session starting) on a shade that already holds
        // notifications posted before it.
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

    private suspend fun sweep() {
        refreshDefaults()
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.filter(::shouldRemove).forEach(::remove)
    }

    /** Defaults can change, and with them what is essential or gated. Lookups off the main thread. */
    private suspend fun refreshDefaults() {
        // The block service's reading when it runs, so the two never disagree;
        // this own reading is only the fallback for when it does not.
        val apps = AppBlockService.defaultApps()
            ?: withContext(Dispatchers.Default) { readDefaultApps(this@WindowNotificationGuard) }
        if (apps.essential != essentials || apps.sms != defaultSms) windowedCache.clear()
        essentials = apps.essential
        defaultSms = apps.sms
        smsApp = apps.sparedSms
        defaultsLoaded = true
    }

    private var defaultsLoaded = false

    private fun shouldRemove(sbn: StatusBarNotification): Boolean {
        if (sbn.isOngoing || !sbn.isClearable || !defaultsLoaded) return false
        if (sbn.packageName == smsApp) return false
        AppBlockService.walledNow(sbn.packageName)?.let { return it }
        val now = LocalDateTime.now()
        val minuteOfDay = now.hour * 60 + now.minute
        if (!windowShutActive(
                dayOfWeek = now.dayOfWeek,
                minuteOfDay = minuteOfDay,
                weekendShut = rules.weekendShut,
                startMinutes = rules.openWindowStartMinutes,
                endMinutes = rules.openWindowEndMinutes
            )
        ) {
            return false
        }
        val pkg = sbn.packageName
        return windowedCache.getOrPut(pkg) {
            val gated = AppGate.isGated(this, pkg, essentials, browsers, rules.gateBrowsers, defaultSms)
            pkg !in essentials && gated
        }
    }

    private fun remove(sbn: StatusBarNotification) {
        runCatching { cancelNotification(sbn.key) }
            .onSuccess { Log.i(TAG, "${sbn.packageName} notification removed (walled)") }
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
