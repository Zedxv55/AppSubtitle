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
import okhttp3.RequestBody
import okio.source
import okio.BufferedSink

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
        target: TranslationTarget
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = SubtitleState.Loading("Reading file...")

                val groqKey = BuildConfig.GROQ_API_KEY
                
                if (groqKey.isEmpty() || groqKey == "YOUR_GROQ_API_KEY") {
                    _uiState.value = SubtitleState.Error("GROQ_API_KEY is not configured in .env")
                    return@launch
                }

                _uiState.value = SubtitleState.Loading("Transcribing with Groq (Whisper)...")

                val requestFile = object : RequestBody() {
                    override fun contentType() = "audio/*".toMediaTypeOrNull()
                    override fun contentLength(): Long {
                        var size: Long = -1
                        context.contentResolver.query(audioUri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                if (sizeIndex != -1) {
                                    size = cursor.getLong(sizeIndex)
                                }
                            }
                        }
                        return size
                    }
                    override fun writeTo(sink: okio.BufferedSink) {
                        context.contentResolver.openInputStream(audioUri)?.use { inputStream ->
                            sink.writeAll(inputStream.source())
                        }
                    }
                }
                
                val filePart = MultipartBody.Part.createFormData("file", "audio_file.wav", requestFile)
                
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

                _uiState.value = SubtitleState.Success(srtText)

            } catch (e: Exception) {
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
        } catch (e: Exception) {
            text + " (Translate Error)"
        }
    }
}
