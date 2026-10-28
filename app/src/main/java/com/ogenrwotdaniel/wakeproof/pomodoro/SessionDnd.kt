package com.ogenrwotdaniel.wakeproof.pomodoro

import android.app.NotificationManager
import android.content.Context

/**
 * Do Not Disturb for the length of a focus session ([com.ogenrwotdaniel.wakeproof.distraction.WallRule.SESSION_DND]).
 *
 * The session already walls every app off the focus list, but their
 * notifications still arrived and pulled the eye — and the hand — back to the
 * phone. Priority mode lets through whatever the user's own DND settings allow
 * (starred contacts, repeat callers, alarms), so a real emergency still rings.
 *
 * Needs the "Do Not Disturb access" special grant; without it this does
 * nothing. Only undoes what it did: the filter in place before is remembered,
 * and nothing is restored if the user changed DND themselves in the meantime.
 */
object SessionDnd {
    private const val PREFS = "session_dnd"
    private const val KEY_PREVIOUS = "previous_filter"

    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun enter(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Already ours (a recovered session): keep the filter saved the first time.
        if (prefs.contains(KEY_PREVIOUS)) return
        val current = manager.currentInterruptionFilter
        if (current != NotificationManager.INTERRUPTION_FILTER_ALL) return // already quieter
        prefs.edit().putInt(KEY_PREVIOUS, current).commit()
        runCatching { manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
    }

    fun exit(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PREVIOUS)) return
        val previous = prefs.getInt(KEY_PREVIOUS, NotificationManager.INTERRUPTION_FILTER_ALL)
        prefs.edit().remove(KEY_PREVIOUS).commit()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) return
        if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
            runCatching { manager.setInterruptionFilter(previous) }
        }
    }
}
