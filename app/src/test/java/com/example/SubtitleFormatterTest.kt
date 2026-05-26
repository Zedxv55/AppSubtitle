package com.example

import com.example.api.TranscriptionSegment
import com.example.util.SubtitleFormatter
import org.junit.Assert.*
import org.junit.Test

class SubtitleFormatterTest {

    private val sampleSegments = listOf(
        TranscriptionSegment(start = 0.5, end = 2.3, text = "First segment text"),
        TranscriptionSegment(start = 124.15, end = 127.8, text = "Second segment text")
    )

    @Test
    fun testJsonToSrt() {
        val srt = SubtitleFormatter.jsonToSrt(sampleSegments)
        val expected = """
            1
            00:00:00,500 --> 00:00:02,300
            First segment text

            2
            00:02:04,150 --> 00:02:07,800
            Second segment text


        """.trimIndent()
        assertEquals(expected, srt.trimIndent())
    }

    @Test
    fun testJsonToVtt() {
        val vtt = SubtitleFormatter.jsonToVtt(sampleSegments)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:00.500 --> 00:00:02.300"))
        assertTrue(vtt.contains("First segment text"))
    }

    @Test
    fun testJsonToTxt() {
        val txt = SubtitleFormatter.jsonToTxt(sampleSegments)
        val expected = """
            First segment text
            Second segment text
        """.trimIndent()
        assertEquals(expected, txt)
    }
}
