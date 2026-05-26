package com.example

import com.example.util.SubtitleParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class SubtitleParserTest {

    @Test
    fun testParseStandardSrt() {
        val srtData = """
            1
            00:01:20,000 --> 00:01:23,500
            Hello, World!
            This is a subtitle.

            2
            00:01:24,100 --> 00:01:26,000
            Second segment
        """.trimIndent()

        val stream = ByteArrayInputStream(srtData.toByteArray())
        val segments = SubtitleParser.parseSrtOrVtt(stream)

        assertEquals(2, segments.size)
        
        // Segment 1
        assertEquals(80.0, segments[0].start, 0.001)
        assertEquals(83.5, segments[0].end, 0.001)
        assertEquals("Hello, World!\nThis is a subtitle.", segments[0].text)

        // Segment 2
        assertEquals(84.1, segments[1].start, 0.001)
        assertEquals(86.0, segments[1].end, 0.001)
        assertEquals("Second segment", segments[1].text)
    }

    @Test
    fun testParseStandardVttWithStyles() {
        val vttData = """
            WEBVTT

            1
            00:01.200 --> 00:01.230 line:0% align:center
            Short timestamp style format

            2
            00:01:24.100 --> 00:01:26.000
            Longer format
        """.trimIndent()

        val stream = ByteArrayInputStream(vttData.toByteArray())
        val segments = SubtitleParser.parseSrtOrVtt(stream)

        assertEquals(2, segments.size)
        assertEquals(1.2, segments[0].start, 0.001)
        assertEquals(1.23, segments[0].end, 0.001)
        assertEquals("Short timestamp style format", segments[0].text)
    }

    @Test
    fun testParseEmptyStream() {
        val stream = ByteArrayInputStream("".toByteArray())
        val segments = SubtitleParser.parseSrtOrVtt(stream)
        assertTrue(segments.isEmpty())
    }
}
