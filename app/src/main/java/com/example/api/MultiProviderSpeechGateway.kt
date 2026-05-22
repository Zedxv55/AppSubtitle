package com.example.api

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonClass
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import retrofit2.http.POST
import retrofit2.http.Body
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

private const val TAG = "MultiSpeechGateway"

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
    val text: String
)

@JsonClass(generateAdapter = true)
data class GeminiConfig(
    val temperature: Double? = null
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    val content: GeminiContent? = null
)

interface GeminiRestService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

object GeminiClient {
    private val moshi = Moshi.Builder().build()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val service: GeminiRestService by lazy {
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiRestService::class.java)
    }
}

object MultiProviderSpeechGateway {

    // Simple cache for translations to avoid duplicate API calls
    private val translationCache = mutableMapOf<String, String>()

    /**
     * Translates text with robust failover and self-healing.
     * Tries vendors in order: DeepSeek -> Mistral -> Gemini.
     */
    suspend fun translateWithFailover(
        text: String,
        targetLanguage: String,
        tone: String,
        preferredProvider: String = "DEEPSEEK"
    ): String = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext ""

        val cacheKey = "$targetLanguage-$tone-${trimmed.hashCode()}"
        translationCache[cacheKey]?.let {
            Log.d(TAG, "Translation Cache hit for: $trimmed")
            return@withContext it
        }

        val prompt = "Translate the following audio subtitle text to $targetLanguage. " +
                "The translation tone must be '$tone'. " +
                "Return ONLY the direct translation, preserving the sentiment and timing structure. " +
                "Do not include explanations, notes, or prefixes.\n\n$trimmed"

        val providersToTry = when (preferredProvider.uppercase()) {
            "DEEPSEEK" -> listOf("DEEPSEEK", "MISTRAL", "GEMINI")
            "MISTRAL" -> listOf("MISTRAL", "DEEPSEEK", "GEMINI")
            else -> listOf("GEMINI", "DEEPSEEK", "MISTRAL")
        }

        var lastError: Throwable? = null

        for (provider in providersToTry) {
            try {
                Log.d(TAG, "Attempting translation with: $provider")
                val resultText = when (provider) {
                    "DEEPSEEK" -> callDeepSeek(prompt)
                    "MISTRAL" -> callMistral(prompt)
                    "GEMINI" -> callGemini(prompt)
                    else -> throw IllegalStateException("Unknown provider")
                }

                if (resultText != null && resultText.isNotEmpty()) {
                    translationCache[cacheKey] = resultText
                    return@withContext resultText
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Provider $provider failed: ${e.message}", e)
                lastError = e
                // Pause briefly before switching/retrying
                delay(300)
            }
        }

        Log.e(TAG, "All translation providers failed!")
        throw lastError ?: Exception("Translation failed across all failover providers")
    }

    private suspend fun callDeepSeek(prompt: String): String? {
        val key = BuildConfig.DEEPSEEK_API_KEY
        if (key.isEmpty() || key.startsWith("YOUR_")) {
            Log.w(TAG, "DeepSeek Key is empty; skipping.")
            return null
        }

        val chatRequest = ChatRequest(
            model = "deepseek-chat",
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            temperature = 0.3
        )
        val response = RetrofitClients.deepSeekApi.createChatCompletion("Bearer $key", chatRequest)
        return response.choices.firstOrNull()?.message?.content?.trim()
    }

    private suspend fun callMistral(prompt: String): String? {
        val key = BuildConfig.MISTRAL_API_KEY
        if (key.isEmpty() || key.startsWith("YOUR_")) {
            Log.w(TAG, "Mistral Key is empty; skipping.")
            return null
        }

        val chatRequest = ChatRequest(
            model = "mistral-large-latest",
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            temperature = 0.3
        )
        val response = RetrofitClients.mistralApi.createChatCompletion("Bearer $key", chatRequest)
        return response.choices.firstOrNull()?.message?.content?.trim()
    }

    private suspend fun callGemini(prompt: String): String? {
        val key = BuildConfig.GEMINI_API_KEY
        if (key.isEmpty() || key.startsWith("MY_")) {
            Log.w(TAG, "Gemini Key is empty; skipping.")
            return null
        }

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(GeminiPart(text = prompt))
                )
            ),
            generationConfig = GeminiConfig(temperature = 0.3)
        )
        val response = GeminiClient.service.generateContent(key, request)
        return response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
    }

    /**
     * Clears local translation caches.
     */
    fun clearCache() {
        translationCache.clear()
    }
}
