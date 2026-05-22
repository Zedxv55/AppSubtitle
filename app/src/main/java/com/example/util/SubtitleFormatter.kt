package com.example.util

import com.example.api.TranscriptionSegment
import java.util.Locale

object SubtitleFormatter {
    fun jsonToSrt(segments: List<TranscriptionSegment>): String {
        val srtOutput = StringBuilder()
        for ((index, segment) in segments.withIndex()) {
            val startTime = formatTimestamp(segment.start)
            val endTime = formatTimestamp(segment.end)
            val text = segment.text.trim()
            
            srtOutput.append("${index + 1}\n")
            srtOutput.append("$startTime --> $endTime\n")
            srtOutput.append("$text\n\n")
        }
        return srtOutput.toString()
    }

    private fun formatTimestamp(seconds: Double): String {
        val totalSeconds = seconds.toLong()
        val hours = totalSeconds / 3600
        val remainingSeconds = totalSeconds % 3600
        val minutes = remainingSeconds / 60
        val secs = remainingSeconds % 60
        val milliseconds = ((seconds - totalSeconds) * 1000).toInt()

        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, secs, milliseconds)
    }
}
