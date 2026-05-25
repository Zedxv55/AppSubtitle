package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subtitle_history")
data class SubtitleHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val timestamp: Long,
    val srtContent: String,
    val segmentsJson: String,
    val targetLanguage: String,
    val engineUsed: String
)
