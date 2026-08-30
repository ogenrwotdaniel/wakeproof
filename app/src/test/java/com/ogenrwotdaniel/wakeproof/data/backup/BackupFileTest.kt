package com.ogenrwotdaniel.wakeproof.data.backup

import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup is the only copy of this data that survives a lost phone, so the
 * format is pinned by test rather than trusted to the serializer.
 */
class BackupFileTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val backup = BackupFile(
        createdAtMillis = 1_700_000_000_000L,
        alarms = listOf(
            Alarm(id = 1, type = AlarmType.PUSHUP, hour = 5, minute = 0, daysMask = 31),
            Alarm(id = 2, type = AlarmType.WATER, hour = 6, minute = 30, enabled = false)
        ),
        completionLogs = listOf(
            CompletionLog(epochDay = 20_000, kind = GoalKind.WATER, status = CompletionStatus.VERIFIED, amount = 250)
        )
    )

    @Test
    fun `a backup round-trips unchanged`() {
        val text = json.encodeToString(BackupFile.serializer(), backup)
        assertEquals(backup, json.decodeFromString(BackupFile.serializer(), text))
    }

    @Test
    fun `the weekly repeat mask survives the round trip`() {
        // daysMask is the difference between a weekdays-only alarm and one that
        // rings on Saturday. Losing it in a restore would be silent.
        val text = json.encodeToString(BackupFile.serializer(), backup)
        val restored = json.decodeFromString(BackupFile.serializer(), text)
        assertEquals(31, restored.alarms.first { it.id == 1L }.daysMask)
    }

    @Test
    fun `a disabled alarm does not come back enabled`() {
        val text = json.encodeToString(BackupFile.serializer(), backup)
        val restored = json.decodeFromString(BackupFile.serializer(), text)
        assertEquals(false, restored.alarms.first { it.id == 2L }.enabled)
    }

    @Test
    fun `a file from a newer build still restores what it can`() {
        // Refusing an unknown field would lose the whole backup rather than one
        // field of it — the wrong trade when this is the only copy.
        val text = """{"version":99,"createdAtMillis":1,"somethingNew":true,"alarms":[]}"""
        val restored = json.decodeFromString(BackupFile.serializer(), text)
        assertEquals(99, restored.version)
        assertTrue(restored.alarms.isEmpty())
    }

    @Test
    fun `an empty backup is valid rather than an error`() {
        val restored = json.decodeFromString(BackupFile.serializer(), """{}""")
        assertEquals(0, restored.totalRows)
        assertEquals(CURRENT_BACKUP_VERSION, restored.version)
    }

    @Test
    fun `totalRows counts every table`() {
        assertEquals(3, backup.totalRows)
    }

    @Test
    fun `backup filenames sort by date`() {
        assertTrue(backupFilename("2026-08-30") > backupFilename("2026-08-29"))
        assertTrue(backupFilename("2026-09-01") > backupFilename("2026-08-30"))
    }
}
