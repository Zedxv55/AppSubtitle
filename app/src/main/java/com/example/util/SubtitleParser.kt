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
        
        // Match timestamps like 00:00:00,000 --> 00:00:00,000 or 00:00.000
        val timestampRegex = """(\d+:\d+:\d+[\.,]\d+)[\s-]*-->[\s-]*(\d+:\d+:\d+[\.,]\d+)""".toRegex()
        val shortTimestampRegex = """(\d+:\d+[\.,]\d+)[\s-]*-->[\s-]*(\d+:\d+[\.,]\d+)""".toRegex()
        
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
            
            val matchResult = timestampRegex.find(trimmed) ?: shortTimestampRegex.find(trimmed)
            if (matchResult != null) {
                // Parse start and end times
                currentStart = parseTimeToSeconds(matchResult.groupValues[1])
                currentEnd = parseTimeToSeconds(matchResult.groupValues[2])
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
            val parts = timeStr.replace(',', '.').split(":")
            if (parts.size == 3) {
                val hours = parts[0].toInt()
                val minutes = parts[1].toInt()
                val secs = parts[2].toDouble()
                return hours * 3600.0 + minutes * 60.0 + secs
            } else if (parts.size == 2) {
                val minutes = parts[0].toInt()
                val secs = parts[1].toDouble()
                return minutes * 60.0 + secs
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return 0.0
    }
}
