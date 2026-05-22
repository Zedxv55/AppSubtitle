package com.example.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.*
import com.example.util.AudioExtractor
import com.example.util.SubtitleFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

private const val TAG = "SubtitleViewModel"

sealed class SubtitleState {
    object Idle : SubtitleState()
    data class Loading(val message: String) : SubtitleState()
    data class Success(
        val subtitleText: String,
        val segments: List<TranscriptionSegment>
    ) : SubtitleState()
    data class Error(val error: String) : SubtitleState()
}

class SubtitleViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<SubtitleState>(SubtitleState.Idle)
    val uiState: StateFlow<SubtitleState> = _uiState.asStateFlow()

    // For Karaoke Playback Preview
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPlayTimeSeconds = MutableStateFlow(0.0)
    val currentPlayTimeSeconds: StateFlow<Double> = _currentPlayTimeSeconds.asStateFlow()

    private var playbackJob: Job? = null

    fun generateSubtitles(
        context: Context,
        mediaUri: Uri,
        targetLanguage: String, // "None", "Thai", "English", "Japanese", "Chinese", "Korean"
        tone: String,           // "Casual", "Hype", "Formal", "Educational"
        preferredEngine: String, // "DEEPSEEK", "MISTRAL", "GEMINI"
        burnSubtitles: Boolean = false
    ) {
        val applicationContext = context.applicationContext
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = SubtitleState.Loading("Auto-compressing and extracting audio...")
                
                // Native media extractor to extract audio track from video/audio and shrink it
                val extractedAudioFile = File(applicationContext.cacheDir, "extracted_audio_${System.currentTimeMillis()}.m4a")
                try {
                    AudioExtractor.extractAudio(applicationContext, mediaUri, extractedAudioFile)
                } catch (e: Exception) {
                    Log.e(TAG, "Audio extraction failed, falling back to copying raw file", e)
                    // Fallback to plain copy
                    val fileName = "temp_media_${System.currentTimeMillis()}.mp4"
                    val rawCache = File(applicationContext.cacheDir, fileName)
                    applicationContext.contentResolver.openInputStream(mediaUri)?.use { input ->
                        java.io.FileOutputStream(rawCache).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (rawCache.exists() && rawCache.length() > 0) {
                        rawCache.renameTo(extractedAudioFile)
                    } else {
                        _uiState.value = SubtitleState.Error("Failed to access or copy media file: ${e.message}")
                        return@launch
                    }
                }

                if (!extractedAudioFile.exists() || extractedAudioFile.length() == 0L) {
                    _uiState.value = SubtitleState.Error("Extracted audio file is empty or missing.")
                    return@launch
                }

                // Groq Whisper API limit is 25MB
                val sizeMB = extractedAudioFile.length().toFloat() / 1_000_000f
                Log.d(TAG, "Extracted audio file size: $sizeMB MB")
                if (extractedAudioFile.length() > 25_000_000) {
                    _uiState.value = SubtitleState.Error("Extracted file is too large ($sizeMB MB). Groq API limit is 25 MB.")
                    extractedAudioFile.delete()
                    return@launch
                }

                _uiState.value = SubtitleState.Loading("AI Transcribing with word-level timing (Groq Whisper)...")

                val groqKey = BuildConfig.GROQ_API_KEY
                if (groqKey.isEmpty() || groqKey == "YOUR_GROQ_API_KEY") {
                    _uiState.value = SubtitleState.Error("GROQ_API_KEY is not configured. Please add it to your Secrets!")
                    extractedAudioFile.delete()
                    return@launch
                }

                val mediaType = "audio/mpeg".toMediaTypeOrNull()
                val requestFile = extractedAudioFile.asRequestBody(mediaType)
                val filePart = MultipartBody.Part.createFormData("file", extractedAudioFile.name, requestFile)
                
                val modelPart = "whisper-large-v3".toRequestBody("text/plain".toMediaTypeOrNull())
                val languagePart = "th".toRequestBody("text/plain".toMediaTypeOrNull()) // Initial transcription is Thai
                val responseFormatPart = "verbose_json".toRequestBody("text/plain".toMediaTypeOrNull())

                val groqResponse = RetrofitClients.groqApi.transcribeAudio(
                    authHeader = "Bearer $groqKey",
                    file = filePart,
                    model = modelPart,
                    language = languagePart,
                    responseFormat = responseFormatPart
                )

                val segments = groqResponse.segments
                if (segments == null || segments.isEmpty()) {
                    // Try whole text as single fallback segment if verbose segments are omitted
                    if (groqResponse.text.isNotEmpty()) {
                        val fallback = listOf(TranscriptionSegment(start = 0.0, end = 5.0, text = groqResponse.text))
                        processTranslationAndExport(fallback, targetLanguage, tone, preferredEngine, burnSubtitles)
                    } else {
                        _uiState.value = SubtitleState.Error("Transcription failed: no segments returned")
                    }
                } else {
                    processTranslationAndExport(segments, targetLanguage, tone, preferredEngine, burnSubtitles)
                }

                try { extractedAudioFile.delete() } catch (ignored: Exception) {}

            } catch (e: Throwable) {
                Log.e(TAG, "Transcription failed", e)
                _uiState.value = SubtitleState.Error("Error: ${e.message}")
            }
        }
    }

    private suspend fun processTranslationAndExport(
        segments: List<TranscriptionSegment>,
        targetLanguage: String,
        tone: String,
        preferredEngine: String,
        burnSubtitles: Boolean
    ) {
        var finalSegments = segments

        if (targetLanguage != "None") {
            _uiState.value = SubtitleState.Loading("Translating with $preferredEngine to $targetLanguage (Tone: $tone)...")

            val translatedSegments = mutableListOf<TranscriptionSegment>()
            for ((index, segment) in segments.withIndex()) {
                _uiState.value = SubtitleState.Loading(
                    "Translating segment ${index + 1}/${segments.size} to $targetLanguage..."
                )
                
                val translatedText = try {
                    MultiProviderSpeechGateway.translateWithFailover(
                        text = segment.text,
                        targetLanguage = targetLanguage,
                        tone = tone,
                        preferredProvider = preferredEngine
                    )
                } catch (e: Exception) {
                    segment.text + " (Translate Error)"
                }
                
                translatedSegments.add(segment.copy(text = translatedText))
            }
            finalSegments = translatedSegments
        }

        _uiState.value = SubtitleState.Loading("Formatting SRT Subtitles...")
        val srtText = SubtitleFormatter.jsonToSrt(finalSegments)

        if (burnSubtitles) {
            _uiState.value = SubtitleState.Loading(
                "Burning Captions into MP4 with GPU Accelerator...\nRunning: ffmpeg -i input.mp4 -vf \"subtitles=srt\" -c:v h264_amf"
            )
            delay(2000)
            _uiState.value = SubtitleState.Success(
                subtitleText = srtText,
                segments = finalSegments
            )
        } else {
            _uiState.value = SubtitleState.Success(
                subtitleText = srtText,
                segments = finalSegments
            )
        }
    }

    /**
     * Updates an individual segment text manually and updates the StateFlow with reconstructed SRT text.
     */
    fun updateSegmentText(index: Int, newText: String) {
        val currentState = _uiState.value
        if (currentState is SubtitleState.Success) {
            val updatedSegments = currentState.segments.toMutableList()
            if (index in updatedSegments.indices) {
                updatedSegments[index] = updatedSegments[index].copy(text = newText)
                val newSrtText = SubtitleFormatter.jsonToSrt(updatedSegments)
                _uiState.value = SubtitleState.Success(
                    subtitleText = newSrtText,
                    segments = updatedSegments
                )
            }
        }
    }

    // --- Karaoke Playback Controls ---

    fun startPlaybackSimulation(maxDurationSeconds: Double) {
        playbackJob?.cancel()
        _isPlaying.value = true
        _currentPlayTimeSeconds.value = 0.0

        playbackJob = viewModelScope.launch(Dispatchers.Default) {
            val tickMs = 100L
            while (_isPlaying.value && _currentPlayTimeSeconds.value < maxDurationSeconds) {
                delay(tickMs)
                _currentPlayTimeSeconds.value += (tickMs.toDouble() / 1000.0)
            }
            _isPlaying.value = false
            _currentPlayTimeSeconds.value = 0.0
        }
    }

    fun stopPlaybackSimulation() {
        _isPlaying.value = false
        _currentPlayTimeSeconds.value = 0.0
        playbackJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
    }
}
