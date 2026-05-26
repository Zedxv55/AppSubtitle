package com.example.api

import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface GroqApi {
    @Multipart
    @POST("audio/transcriptions")
    suspend fun transcribeAudio(
        @Header("Authorization") authHeader: String,
        @Part parts: List<MultipartBody.Part>
    ): GroqTranscriptionResponse
}

@JsonClass(generateAdapter = true)
data class GroqTranscriptionResponse(
    val text: String,
    val segments: List<TranscriptionSegment>? = null,
    val words: List<TranscriptionWord>? = null
)

@JsonClass(generateAdapter = true)
data class TranscriptionSegment(
    val start: Double,
    val end: Double,
    val text: String,
    val words: List<TranscriptionWord>? = null
)

@JsonClass(generateAdapter = true)
data class TranscriptionWord(
    val start: Double,
    val end: Double,
    val word: String
)
