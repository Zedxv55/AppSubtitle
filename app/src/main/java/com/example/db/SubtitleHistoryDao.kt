package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SubtitleHistoryDao {
    @Query("SELECT * FROM subtitle_history ORDER BY timestamp DESC")
    fun getAllHistoryFlow(): Flow<List<SubtitleHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: SubtitleHistory)

    @Delete
    suspend fun deleteHistory(history: SubtitleHistory)

    @Query("DELETE FROM subtitle_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Int)

    @Query("DELETE FROM subtitle_history")
    suspend fun clearAllHistory()
}
