package com.ogenrwotdaniel.wakeproof.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.reportDataStore: DataStore<Preferences> by preferencesDataStore("weekly_report")

/** The last AI weekly report plus the day it was written. */
data class CachedReport(val text: String, val epochDay: Long)

/** Caches the AI-written weekly report so it costs one API call per refresh. */
@Singleton
class WeeklyReportRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore = context.reportDataStore

    val report: Flow<CachedReport?> = dataStore.data.map { prefs ->
        val text = prefs[TEXT] ?: return@map null
        CachedReport(text, prefs[DAY] ?: 0L)
    }

    suspend fun save(text: String) {
        dataStore.edit {
            it[TEXT] = text
            it[DAY] = LocalDate.now().toEpochDay()
        }
    }

    private companion object {
        val TEXT = stringPreferencesKey("text")
        val DAY = longPreferencesKey("epoch_day")
    }
}
