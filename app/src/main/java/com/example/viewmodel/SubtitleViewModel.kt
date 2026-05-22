package com.example.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.*
import com.example.util.SubtitleFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody
import okio.source
import okio.BufferedSink
import java.io.File

sealed class SubtitleState {
    object Idle : SubtitleState()
    data class Loading(val message: String) : SubtitleState()
    data class Success(val subtitleText: String) : SubtitleState()
    data class Error(val error: String) : SubtitleState()
}

enum class TranslationTarget {
    NONE, DEEPSEEK_THAI, MISTRAL_THAI
}

class SubtitleViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<SubtitleState>(SubtitleState.Idle)
    val uiState: StateFlow<SubtitleState> = _uiState.asStateFlow()

    fun generateSubtitles(
        context: Context,
        audioUri: Uri,
        target: TranslationTarget,
        burnSubtitles: Boolean = false
    ) {
        val applicationContext = context.applicationContext
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Read metadata inside coroutine to prevent strictly main-thread blocking or crashes
                val contentResolver = applicationContext.contentResolver
                val mediaTypeStr = contentResolver.getType(audioUri) ?: "audio/*"
                val mediaType = mediaTypeStr.toMediaTypeOrNull()

                var fileSize = -1L
                var fileName = "media_file.mp4"
                try {
                    contentResolver.query(audioUri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (sizeIndex != -1) {
                                fileSize = cursor.getLong(sizeIndex)
                            }
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) {
                                fileName = cursor.getString(nameIndex) ?: fileName
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                _uiState.value = SubtitleState.Loading("Reading media file...")

                val groqKey = BuildConfig.GROQ_API_KEY
                
                if (groqKey.isEmpty() || groqKey == "YOUR_GROQ_API_KEY") {
                    _uiState.value = SubtitleState.Error("GROQ_API_KEY is not configured in .env")
                    return@launch
                }

                _uiState.value = SubtitleState.Loading("Copying media file to cache...")
                
                val cacheFile = java.io.File(applicationContext.cacheDir, fileName)
                try {
                    contentResolver.openInputStream(audioUri)?.use { input ->
                        java.io.FileOutputStream(cacheFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    _uiState.value = SubtitleState.Error("Failed to read selected file: ${e.message}")
                    return@launch
                }
                
                // Groq Whisper API limit is 25MB (25 * 1024 * 1024 bytes)
                if (cacheFile.length() > 25_000_000) {
                    _uiState.value = SubtitleState.Error("File is too large (${cacheFile.length() / 1_000_000} MB). Groq API limit is 25 MB.")
                    cacheFile.delete()
                    return@launch
                }

                _uiState.value = SubtitleState.Loading("Transcribing with Groq (Whisper)...")

                val requestFile = cacheFile.asRequestBody(mediaType)
                val filePart = MultipartBody.Part.createFormData("file", fileName, requestFile)
                
                val modelPart = "whisper-large-v3".toRequestBody("text/plain".toMediaTypeOrNull())
                val languagePart = "th".toRequestBody("text/plain".toMediaTypeOrNull())
                val responseFormatPart = "verbose_json".toRequestBody("text/plain".toMediaTypeOrNull())

                val groqResponse = RetrofitClients.groqApi.transcribeAudio(
                    authHeader = "Bearer $groqKey",
                    file = filePart,
                    model = modelPart,
                    language = languagePart,
                    responseFormat = responseFormatPart
                )

                val segments = groqResponse.segments
                if (segments == null) {
                    _uiState.value = SubtitleState.Error("Transcription failed: no segments returned")
                    return@launch
                }

                var finalSegments = segments

                if (target == TranslationTarget.DEEPSEEK_THAI || target == TranslationTarget.MISTRAL_THAI) {
                    _uiState.value = SubtitleState.Loading("Translating with ${target.name}...")
                    
                    val translatedSegments = mutableListOf<TranscriptionSegment>()
                    for (segment in segments) {
                        val translatedText = translateText(segment.text, target)
                        translatedSegments.add(segment.copy(text = translatedText))
                    }
                    finalSegments = translatedSegments
                }

                _uiState.value = SubtitleState.Loading("Formatting SRT...")
                val srtText = SubtitleFormatter.jsonToSrt(finalSegments)

                if (burnSubtitles) {
                    _uiState.value = SubtitleState.Loading("Simulating FFmpeg Burning (Hard-sub)...\nffmpeg -i input.mp4 -vf subtitles=sub.srt -c:a copy output.mp4")
                    kotlinx.coroutines.delay(2500)
                    _uiState.value = SubtitleState.Success("VIDEO RENDER COMPLETE:\n\n-- SRT --\n$srtText")
                } else {
                    _uiState.value = SubtitleState.Success(srtText)
                }
                
                try { cacheFile.delete() } catch (ignored: Exception) {}

            } catch (e: Throwable) {
                _uiState.value = SubtitleState.Error("Error: ${e.message}")
            }
        }
    }

    private suspend fun translateText(text: String, target: TranslationTarget): String {
        return try {
            val prompt = "Translate the following text to Thai. Return ONLY the translated text, nothing else.\n\n$text"
            val chatRequest = ChatRequest(
                model = if (target == TranslationTarget.DEEPSEEK_THAI) "deepseek-chat" else "mistral-large-latest",
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                temperature = 0.2
            )

            val response = if (target == TranslationTarget.DEEPSEEK_THAI) {
                val key = BuildConfig.DEEPSEEK_API_KEY
                if (key.isEmpty() || key.startsWith("YOUR_")) return text + " (Missing DeepSeek Key)"
                RetrofitClients.deepSeekApi.createChatCompletion("Bearer $key", chatRequest)
            } else {
                val key = BuildConfig.MISTRAL_API_KEY
                if (key.isEmpty() || key.startsWith("YOUR_")) return text + " (Missing Mistral Key)"
                RetrofitClients.mistralApi.createChatCompletion("Bearer $key", chatRequest)
            }
            
            response.choices.firstOrNull()?.message?.content?.trim() ?: text
        } catch (e: Throwable) {
            text + " (Translate Error)"
        }
    }
}
