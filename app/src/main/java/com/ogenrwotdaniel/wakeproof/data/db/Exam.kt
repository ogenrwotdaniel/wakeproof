package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** An upcoming exam; [epochDay] is [java.time.LocalDate.toEpochDay]. */
@Entity(tableName = "exams")
data class Exam(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val epochDay: Long
)

/** Room access to [Exam]. */
@Dao
interface ExamDao {
    @Insert
    suspend fun insert(exam: Exam)

    @Delete
    suspend fun delete(exam: Exam)

    @Query("SELECT * FROM exams WHERE epochDay >= :today ORDER BY epochDay")
    fun observeUpcoming(today: Long): Flow<List<Exam>>
}
