package com.example.util

import com.example.api.TranscriptionSegment
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

object SubtitleParser {
    fun parseSrtOrVtt(inputStream: InputStream): List<TranscriptionSegment> {
        val segments = mutableListOf<TranscriptionSegment>()
        val reader = BufferedReader(InputStreamReader(inputStream))
        var line: String?
        
        var currentText = StringBuilder()
        var currentStart = -1.0
        var currentEnd = -1.0
        
        while (reader.readLine().also { line = it } != null) {
            val trimmed = line!!.trim()
            if (trimmed.isEmpty()) {
                if (currentStart >= 0.0 && currentEnd >= 0.0 && currentText.isNotEmpty()) {
                    segments.add(
                        TranscriptionSegment(
                            start = currentStart,
                            end = currentEnd,
                            text = currentText.toString().trim()
                        )
                    )
                    currentText = StringBuilder()
                    currentStart = -1.0
                    currentEnd = -1.0
                }
                continue
            }
            
            if (trimmed.contains("-->")) {
                val parts = trimmed.split("-->")
                if (parts.size >= 2) {
                    val startStr = parts[0].trim()
                    val endSettingsStr = parts[1].trim()
                    // Strip extra VTT styling settings if present
                    val endStr = if (endSettingsStr.contains(" ")) {
                        endSettingsStr.split(" ")[0].trim()
                    } else {
                        endSettingsStr
                    }
                    currentStart = parseTimeToSeconds(startStr)
                    currentEnd = parseTimeToSeconds(endStr)
                }
            } else if (trimmed.all { it.isDigit() }) {
                // Probably line number, ignore
            } else if (trimmed.equals("WEBVTT", ignoreCase = true)) {
                // Ignore WEBVTT header
            } else {
                if (currentText.isNotEmpty()) {
                    currentText.append(" ")
                }
                currentText.append(trimmed)
            }
        }
        
        // Add final block if left
        if (currentStart >= 0.0 && currentEnd >= 0.0 && currentText.isNotEmpty()) {
            segments.add(
                TranscriptionSegment(
                    start = currentStart,
                    end = currentEnd,
                    text = currentText.toString().trim()
                )
            )
        }
        
        return segments
    }
    
    private fun parseTimeToSeconds(timeStr: String): Double {
        try {
            val cleaned = timeStr.trim().replace(',', '.')
            val parts = cleaned.split(":")
            if (parts.size == 3) {
                val hours = parts[0].toIntOrNull() ?: 0
                val minutes = parts[1].toIntOrNull() ?: 0
                val secs = parts[2].toDoubleOrNull() ?: 0.0
                return hours * 3600.0 + minutes * 60.0 + secs
            } else if (parts.size == 2) {
                val minutes = parts[0].toIntOrNull() ?: 0
                val secs = parts[1].toDoubleOrNull() ?: 0.0
                return minutes * 60.0 + secs
            } else if (parts.size == 1) {
                return parts[0].toDoubleOrNull() ?: 0.0
            }
        } catch (e: Exception) {
            android.util.Log.e("SubtitleParser", "Error parsing time: $timeStr", e)
        }
        return 0.0
    }
}
