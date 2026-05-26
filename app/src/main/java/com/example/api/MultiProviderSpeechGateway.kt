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

    private val moshi = Moshi.Builder().build()
    private val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, String::class.java)
    private val listAdapter = moshi.adapter<List<String>>(listType)

    // Simple thread-safe LRU cache for translations to avoid duplicate API calls & excessive memory usage
    private val translationCache = android.util.LruCache<String, String>(1000)

    private fun isKeyValid(key: String, placeholderDefault: String): Boolean {
        if (key.isEmpty()) return false
        val upper = key.uppercase().trim()
        val upperPlaceholder = placeholderDefault.uppercase().trim()
        return !upper.startsWith("YOUR_") && !upper.startsWith("MY_") && !upper.startsWith("PLACEHOLDER") && upper != upperPlaceholder
    }

    /**
     * Translates multiple segments in bulk batches of 25 for extremely fast throughput,
     * rate-limit prevention, and coherent conversational translation context.
     */
    suspend fun translateSegmentsBulk(
        segments: List<String>,
        targetLanguage: String,
        tone: String,
        preferredProvider: String = "DEEPSEEK"
    ): List<String> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()

        // Batch segments into chunks of 25 to optimize prompt length & LLM accuracy
        val chunkSize = 25
        val resultList = mutableListOf<String>()

        for (i in segments.indices step chunkSize) {
            val chunk = segments.subList(i, (i + chunkSize).coerceAtMost(segments.size))
            val chunkTranslated = translateBatchWithFailover(chunk, targetLanguage, tone, preferredProvider)
            resultList.addAll(chunkTranslated)
        }

        return@withContext resultList
    }

    private suspend fun translateBatchWithFailover(
        batch: List<String>,
        targetLanguage: String,
        tone: String,
        preferredProvider: String
    ): List<String> {
        val jsonInput = listAdapter.toJson(batch)
        val prompt = ("You are an expert subtitle translator. Translate the following sequential list of subtitle lines to $targetLanguage.\n" +
                "The translation tone must be '$tone'.\n\n" +
                "IMPORTANT COMBINED RULES:\n" +
                "1. Translate each line accurately and naturally based on the surrounding conversation context.\n" +
                "2. Return the translation as a JSON array of strings: [\"translation1\", \"translation2\", ...]\n" +
                "3. The number of elements in the output JSON array MUST be EXACTLY the same as the input (${batch.size} items).\n" +
                "4. Do not change, merge, split, or omit any lines. Keep the indices 1:1 mapped.\n" +
                "5. Return ONLY the valid JSON array starting with '[' and ending with ']'. Do not include markdown block markers (like ```json), notes, or descriptions.\n\n" +
                "Input lines:\n$jsonInput")

        val providersToTry = when (preferredProvider.uppercase()) {
            "DEEPSEEK" -> listOf("DEEPSEEK", "MISTRAL", "GEMINI")
            "MISTRAL" -> listOf("MISTRAL", "DEEPSEEK", "GEMINI")
            else -> listOf("GEMINI", "DEEPSEEK", "MISTRAL")
        }

        var lastError: Throwable? = null

        for (provider in providersToTry) {
            try {
                Log.d(TAG, "Attempting batch translation of ${batch.size} items with: $provider")
                val responseText = when (provider) {
                    "DEEPSEEK" -> callDeepSeek(prompt)
                    "MISTRAL" -> callMistral(prompt)
                    "GEMINI" -> callGemini(prompt)
                    else -> null
                }

                if (!responseText.isNullOrEmpty()) {
                    val cleaned = cleanJsonResponse(responseText)
                    val list = listAdapter.fromJson(cleaned)
                    if (list != null && list.size == batch.size) {
                        return list
                    } else {
                        Log.e(TAG, "Size mismatch or invalid JSON. Expected: ${batch.size}, Got: ${list?.size ?: 0}")
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Batch provider $provider failed: ${e.message}")
                lastError = e
                delay(200)
            }
        }

        Log.e(TAG, "All batch translation providers failed! Falling back to elements translation.")
        // Graceful element-by-element fallback
        return batch.map { individual ->
            try {
                translateWithFailover(individual, targetLanguage, tone, preferredProvider)
            } catch (e: Exception) {
                individual + " (Translate Error)"
            }
        }
    }

    private fun cleanJsonResponse(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```json")) {
            text = text.substringAfter("```json").substringBeforeLast("```").trim()
        } else if (text.startsWith("```")) {
            text = text.substringAfter("```").substringBeforeLast("```").trim()
        }
        val firstBracket = text.indexOf('[')
        val lastBracket = text.lastIndexOf(']')
        if (firstBracket != -1 && lastBracket != -1 && lastBracket > firstBracket) {
            return text.substring(firstBracket, lastBracket + 1)
        }
        return text
    }

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
        translationCache.get(cacheKey)?.let {
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
                    translationCache.put(cacheKey, resultText)
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
        if (!isKeyValid(key, "YOUR_DEEPSEEK_API_KEY")) {
            Log.w(TAG, "DeepSeek Key is empty or placeholder; skipping.")
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
        if (!isKeyValid(key, "YOUR_MISTRAL_API_KEY")) {
            Log.w(TAG, "Mistral Key is empty or placeholder; skipping.")
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
        if (!isKeyValid(key, "YOUR_GEMINI_API_KEY")) {
            Log.w(TAG, "Gemini Key is empty or placeholder; skipping.")
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
        translationCache.evictAll()
    }
}
