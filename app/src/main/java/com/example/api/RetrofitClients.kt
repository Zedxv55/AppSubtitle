package com.example.api

import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClients {
    private val moshi = Moshi.Builder()
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        })
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val groqApi: GroqApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/openai/v1/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GroqApi::class.java)
    }

    val deepSeekApi: DeepSeekApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DeepSeekApi::class.java)
    }

    val mistralApi: MistralApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.mistral.ai/v1/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MistralApi::class.java)
    }
}
