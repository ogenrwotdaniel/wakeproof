package com.ogenrwotdaniel.wakeproof.pomodoro

import android.content.Context

/**
 * The calendar event a session was started for (2026-11-01): its agenda key,
 * so the end-of-event check-in can tick it off, and its title for the words.
 */
data class SessionEvent(val key: String, val title: String)

/**
 * [SessionEvent] beside [SessionStore], kept apart from the session record so
 * that record's format did not have to change for it. Survives a reboot like
 * the session; cleared when the session ends.
 */
object SessionEventStore {
    private const val PREFS = "pomodoro_session_event"
    private const val KEY = "key"
    private const val TITLE = "title"

    fun save(context: Context, event: SessionEvent?) {
        val edit = prefs(context).edit()
        if (event == null) edit.clear() else edit.putString(KEY, event.key).putString(TITLE, event.title)
        edit.commit()
    }

    fun load(context: Context): SessionEvent? {
        val p = prefs(context)
        val key = p.getString(KEY, null) ?: return null
        return SessionEvent(key, p.getString(TITLE, null).orEmpty())
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
