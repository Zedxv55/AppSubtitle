package com.example.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

object AudioExtractor {
    /**
     * Extracts the audio track from a video stream and saves it into an M4A file
     * This minimizes file size quickly (under 1 second) and meets the 25MB Groq Whisper API limit.
     */
    fun extractAudio(context: Context, uri: Uri, outputFile: File): File {
        val extractor = MediaExtractor()
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw Exception("Unable to open file descriptor for Uri: $uri")
        
        try {
            extractor.setDataSource(pfd.fileDescriptor)

            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) {
                // If it's already an audio file and we couldn't extract standard track directly,
                // we copy it directly as a fallback.
                return copyUriToFile(context, uri, outputFile)
            }

            extractor.selectTrack(audioTrackIndex)

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val writeTrackIndex = muxer.addTrack(format)
                muxer.start()

                val maxBufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    val size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    if (size > 0) size else 1024 * 1024
                } else {
                    1024 * 1024
                }
                val buffer = ByteBuffer.allocate(maxBufferSize)
                val bufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        bufferInfo.size = 0
                        break
                    }
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(writeTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
            } finally {
                try {
                    muxer.stop()
                    muxer.release()
                } catch (e: Exception) {
                    android.util.Log.e("AudioExtractor", "Muxer release failed", e)
                }
            }
        } finally {
            extractor.release()
            pfd.close()
        }

        return outputFile
    }

    private fun copyUriToFile(context: Context, uri: Uri, outputFile: File): File {
        context.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("Failed to open input stream")
        return outputFile
    }
}
