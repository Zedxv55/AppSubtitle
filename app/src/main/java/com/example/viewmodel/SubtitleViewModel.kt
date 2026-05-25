package com.example.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.*
import com.example.db.*
import com.example.util.AudioExtractor
import com.example.util.SubtitleFormatter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
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

    // For Subtitle History Database
    private val _historyState = MutableStateFlow<List<SubtitleHistory>>(emptyList())
    val historyState: StateFlow<List<SubtitleHistory>> = _historyState.asStateFlow()

    private var historyRepository: SubtitleHistoryRepository? = null

    private fun getHistoryRepository(context: Context): SubtitleHistoryRepository {
        return historyRepository ?: synchronized(this) {
            val r = historyRepository ?: SubtitleHistoryRepository(
                AppDatabase.getDatabase(context.applicationContext).subtitleHistoryDao()
            )
            historyRepository = r
            r
        }
    }

    fun loadHistory(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getHistoryRepository(context).allHistory.collect { list ->
                    _historyState.value = list
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load history from database", e)
            }
        }
    }

    fun deleteHistoryItem(context: Context, item: SubtitleHistory) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getHistoryRepository(context).delete(item)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete history item", e)
            }
        }
    }

    fun clearAllHistory(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getHistoryRepository(context).clearAll()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear history", e)
            }
        }
    }

    fun loadHistoryItem(item: SubtitleHistory) {
        try {
            val types = Types.newParameterizedType(List::class.java, TranscriptionSegment::class.java)
            val adapter = Moshi.Builder().build().adapter<List<TranscriptionSegment>>(types)
            val loadedSegments = adapter.fromJson(item.segmentsJson) ?: emptyList()
            _uiState.value = SubtitleState.Success(
                subtitleText = item.srtContent,
                segments = loadedSegments
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse loaded history item segments", e)
            _uiState.value = SubtitleState.Success(
                subtitleText = item.srtContent,
                segments = emptyList()
            )
        }
    }

    // For Karaoke Playback Preview
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPlayTimeSeconds = MutableStateFlow(0.0)
    val currentPlayTimeSeconds: StateFlow<Double> = _currentPlayTimeSeconds.asStateFlow()

    private var playbackJob: Job? = null

    fun generateSubtitles(
        context: Context,
        mediaUri: Uri,
        fileName: String,
        targetLanguage: String, // "None", "Thai", "English", "Japanese", "Chinese", "Korean"
        tone: String,           // "Casual", "Hype", "Formal", "Educational"
        preferredEngine: String, // "DEEPSEEK", "MISTRAL", "GEMINI"
        burnSubtitles: Boolean = false,
        sourceLanguage: String = "Auto"
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
                    val fallbackName = "temp_media_${System.currentTimeMillis()}.mp4"
                    val rawCache = File(applicationContext.cacheDir, fallbackName)
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
                val languagePart = if (sourceLanguage != "Auto") {
                    sourceLanguage.toRequestBody("text/plain".toMediaTypeOrNull())
                } else {
                    null
                }
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
                        processTranslationAndExport(applicationContext, fileName, fallback, targetLanguage, tone, preferredEngine, burnSubtitles)
                    } else {
                        _uiState.value = SubtitleState.Error("Transcription failed: no segments returned")
                    }
                } else {
                    processTranslationAndExport(applicationContext, fileName, segments, targetLanguage, tone, preferredEngine, burnSubtitles)
                }

                try { extractedAudioFile.delete() } catch (ignored: Exception) {}

            } catch (e: Throwable) {
                Log.e(TAG, "Transcription failed", e)
                _uiState.value = SubtitleState.Error("Error: ${e.message}")
            }
        }
    }

    private suspend fun processTranslationAndExport(
        context: Context,
        fileName: String,
        segments: List<TranscriptionSegment>,
        targetLanguage: String,
        tone: String,
        preferredEngine: String,
        burnSubtitles: Boolean
    ) {
        var finalSegments = segments

        if (targetLanguage != "None") {
            _uiState.value = SubtitleState.Loading("Translating with $preferredEngine to $targetLanguage (Tone: $tone) in fast bulk mode...")

            val sourceTexts = segments.map { it.text }
            val translatedTexts = MultiProviderSpeechGateway.translateSegmentsBulk(
                segments = sourceTexts,
                targetLanguage = targetLanguage,
                tone = tone,
                preferredProvider = preferredEngine
            )

            val translatedSegments = segments.mapIndexed { index, segment ->
                val translatedText = if (index in translatedTexts.indices) {
                    translatedTexts[index]
                } else {
                    segment.text + " (Translate Error)"
                }
                segment.copy(text = translatedText)
            }
            finalSegments = translatedSegments
        }

        _uiState.value = SubtitleState.Loading("Formatting SRT Subtitles...")
        val srtText = SubtitleFormatter.jsonToSrt(finalSegments)

        // Save to Local Room Database History!
        try {
            val types = Types.newParameterizedType(List::class.java, TranscriptionSegment::class.java)
            val adapter = Moshi.Builder().build().adapter<List<TranscriptionSegment>>(types)
            val jsonStr = adapter.toJson(finalSegments)

            val historyItem = SubtitleHistory(
                fileName = fileName,
                timestamp = System.currentTimeMillis(),
                srtContent = srtText,
                segmentsJson = jsonStr,
                targetLanguage = targetLanguage,
                engineUsed = preferredEngine
            )
            getHistoryRepository(context).insert(historyItem)
        } catch (dbEx: Exception) {
            Log.e(TAG, "Failed to save generated subtitles to local history database", dbEx)
        }

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

    fun importSubtitleFile(context: Context, uri: Uri, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = SubtitleState.Loading("กำลังนำเข้าและประมวลผลไฟล์ซับไตเติ้ล...")
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    _uiState.value = SubtitleState.Error("ไม่สามารถเปิดไฟล์ซับไตเติ้ลได้")
                    return@launch
                }
                
                val parsedSegments = com.example.util.SubtitleParser.parseSrtOrVtt(inputStream)
                inputStream.close()
                
                if (parsedSegments.isEmpty()) {
                    _uiState.value = SubtitleState.Error("ไฟล์ซับไตเติ้ลไม่มีข้อมูล หรือฟอร์แมตไม่ถูกต้อง")
                    return@launch
                }
                
                val srtText = SubtitleFormatter.jsonToSrt(parsedSegments)
                
                // Save to local history as imported
                try {
                    val types = Types.newParameterizedType(List::class.java, TranscriptionSegment::class.java)
                    val adapter = Moshi.Builder().build().adapter<List<TranscriptionSegment>>(types)
                    val jsonStr = adapter.toJson(parsedSegments)

                    val historyItem = SubtitleHistory(
                        fileName = "$fileName (Imported)",
                        timestamp = System.currentTimeMillis(),
                        srtContent = srtText,
                        segmentsJson = jsonStr,
                        targetLanguage = "None",
                        engineUsed = "IMPORTED"
                    )
                    getHistoryRepository(context).insert(historyItem)
                } catch (dbEx: Exception) {
                    Log.e(TAG, "Failed to save imported subtitles to database", dbEx)
                }
                
                _uiState.value = SubtitleState.Success(
                    subtitleText = srtText,
                    segments = parsedSegments
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import subtitle file", e)
                _uiState.value = SubtitleState.Error("เกิดข้อผิดพลาดในการโหลดไฟล์: ${e.message}")
            }
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

    private fun smartSplitSpaceless(text: String, approxChars: Int): List<String> {
        if (text.length <= approxChars) return listOf(text)
        
        val results = mutableListOf<String>()
        var start = 0
        val nonStartingChars = setOf(
            '\u0e30', '\u0e31', '\u0e34', '\u0e35', '\u0e36', '\u0e37', '\u0e38', '\u0e39',
            '\u0e47', '\u0e48', '\u0e49', '\u0e4a', '\u0e4b', '\u0e4c', '\u0e4d', '\u0e33', 
            'ะ', 'ั', 'ิ', 'ี', 'ึ', 'ื', 'ุ', 'ู', '็', '่', '้', '๊', '๋', '์', 'า', 'ำ', 'ๆ'
        )
        
        while (start < text.length) {
            var end = start + approxChars
            if (end >= text.length) {
                results.add(text.substring(start))
                break
            }
            
            // Scan backwards a bit to see if we can find a space or a naturally good split point
            var bestSplitIdx = -1
            for (i in end downTo (start + approxChars / 2)) {
                if (i < text.length && (text[i] == ' ' || text[i] == ',' || text[i] == '.' || text[i] == '-' || text[i] == '_')) {
                    bestSplitIdx = i
                    break
                }
                // Transition from English to Thai or Thai to English / Numbers
                if (i > start && i < text.length) {
                    val prev = text[i - 1]
                    val curr = text[i]
                    val isPrevEnglish = prev in 'a'..'z' || prev in 'A'..'Z'
                    val isCurrEnglish = curr in 'a'..'z' || curr in 'A'..'Z'
                    val isPrevThai = prev.code in 0x0E00..0x0E7F
                    val isCurrThai = curr.code in 0x0E00..0x0E7F
                    val isPrevDigit = prev.isDigit()
                    val isCurrDigit = curr.isDigit()
                    
                    if ((isPrevEnglish && isCurrThai) || (isPrevThai && isCurrEnglish) || 
                        (isPrevDigit != isCurrDigit)) {
                        bestSplitIdx = i
                        break
                    }
                }
            }
            
            if (bestSplitIdx != -1) {
                end = bestSplitIdx
            } else {
                // Adjust end index forward or backward so we don't start the next chunk with a combining character
                while (end < text.length && nonStartingChars.contains(text[end])) {
                    end++
                }
            }
            
            // Guard against infinite loops: ensure end has advanced
            if (end <= start) {
                end = start + 1
            }

            // Avoid adding empty strings/spaces
            val chunk = text.substring(start, end).trim()
            if (chunk.isNotEmpty()) {
                results.add(chunk)
            }
            start = end
            // Skip leading spaces in the next chunk
            while (start < text.length && text[start] == ' ') {
                start++
            }
        }
        
        return if (results.isEmpty()) listOf(text) else results
    }

    /**
     * Re-chunks the subtitle segments so they don't exceed a specific maximum count of words
     * or characters. This splits longer clauses proportionally across intermediate timestamps.
     */
    fun rechunkSegments(maxWords: Int) {
        val currentState = _uiState.value
        if (currentState is SubtitleState.Success) {
            val originalSegments = currentState.segments
            val newSegments = mutableListOf<TranscriptionSegment>()
            
            for (segment in originalSegments) {
                val text = segment.text.trim()
                if (text.isEmpty()) continue
                
                val hasSpaces = text.contains(" ")
                
                if (hasSpaces) {
                    val words = text.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    if (words.size <= maxWords || maxWords <= 0) {
                        newSegments.add(segment)
                    } else {
                        val totalWords = words.size
                        val chunked = words.chunked(maxWords)
                        val duration = segment.end - segment.start
                        var wordsProcessed = 0
                        
                        for (chunk in chunked) {
                            val chunkWordCount = chunk.size
                            val chunkStart = segment.start + (duration * wordsProcessed.toDouble() / totalWords.toDouble())
                            wordsProcessed += chunkWordCount
                            val chunkEnd = segment.start + (duration * wordsProcessed.toDouble() / totalWords.toDouble())
                            
                            newSegments.add(
                                TranscriptionSegment(
                                    start = chunkStart,
                                    end = chunkEnd,
                                    text = chunk.joinToString(" ")
                                )
                            )
                        }
                    }
                } else {
                    // Spaceless continuous text (Thai, Chinese, etc.)
                    // Map "maxWords" roughly to character counts: 1 word ~ 4-5 Thai characters
                    val approxChars = if (maxWords <= 0) 15 else maxWords * 4
                    if (text.length <= approxChars) {
                        newSegments.add(segment)
                    } else {
                        val chunks = smartSplitSpaceless(text, approxChars)
                        val totalChars = text.length
                        val duration = segment.end - segment.start
                        var charsProcessed = 0
                        
                        for (chunk in chunks) {
                            val chunkSize = chunk.length
                            val chunkStart = segment.start + (duration * charsProcessed.toDouble() / totalChars.toDouble())
                            charsProcessed += chunkSize
                            val chunkEnd = segment.start + (duration * charsProcessed.toDouble() / totalChars.toDouble())
                            
                            newSegments.add(
                                TranscriptionSegment(
                                    start = chunkStart,
                                    end = chunkEnd,
                                    text = chunk
                                )
                            )
                        }
                    }
                }
            }
            
            // Deduplicate/sanitize timestamps slightly
            val sanitized = newSegments.mapIndexed { i, s ->
                val nextStart = newSegments.getOrNull(i + 1)?.start ?: (s.end + 1.0)
                if (s.end > nextStart) s.copy(end = nextStart) else s
            }

            val newSrtText = SubtitleFormatter.jsonToSrt(sanitized)
            _uiState.value = SubtitleState.Success(
                subtitleText = newSrtText,
                segments = sanitized
            )
        }
    }

    // --- Karaoke Playback Controls ---

    fun seekToPosition(seconds: Double) {
        _currentPlayTimeSeconds.value = seconds
    }

    fun startPlaybackSimulation(maxDurationSeconds: Double, isVideoAttached: Boolean = false) {
        playbackJob?.cancel()
        _isPlaying.value = true
        if (isVideoAttached) {
            // Let the attached media player drive playback progress
            return
        }
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
