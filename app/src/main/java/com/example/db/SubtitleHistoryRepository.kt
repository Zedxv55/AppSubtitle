package com.example.db

import kotlinx.coroutines.flow.Flow

class SubtitleHistoryRepository(private val dao: SubtitleHistoryDao) {
    val allHistory: Flow<List<SubtitleHistory>> = dao.getAllHistoryFlow()

    suspend fun insert(history: SubtitleHistory) {
        dao.insertHistory(history)
    }

    suspend fun delete(history: SubtitleHistory) {
        dao.deleteHistory(history)
    }

    suspend fun deleteById(id: Int) {
        dao.deleteHistoryById(id)
    }

    suspend fun clearAll() {
        dao.clearAllHistory()
    }
}
