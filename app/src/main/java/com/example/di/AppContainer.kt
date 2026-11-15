package com.example.di

import android.content.Context
import com.example.data.local.VedDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.GeminiApiService
import com.example.data.remote.OpenRouterApiService
import com.example.data.remote.StreamingAiClient
import com.example.data.repository.ChatRepository
import com.example.data.security.ApiKeyStore
import com.example.service.AiGenerationCoordinator
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Lightweight manual dependency container for fast cold start and zero DI reflection overhead.
 */
class AppContainer(context: Context) {
  private val appContext = context.applicationContext

  val okHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .writeTimeout(20, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }

  private val moshi: Moshi by lazy {
    Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  }

  private val geminiApi: GeminiApiService by lazy {
    Retrofit.Builder()
      .baseUrl("https://generativelanguage.googleapis.com/")
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(GeminiApiService::class.java)
  }

  private val openRouterApi: OpenRouterApiService by lazy {
    Retrofit.Builder()
      .baseUrl("https://openrouter.ai/")
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(OpenRouterApiService::class.java)
  }

  val database: VedDatabase by lazy { VedDatabase.create(appContext) }

  val preferencesRepository: UserPreferencesRepository by lazy {
    UserPreferencesRepository(appContext)
  }

  val apiKeyStore: ApiKeyStore by lazy { ApiKeyStore(appContext) }

  private val streamingAiClient: StreamingAiClient by lazy { StreamingAiClient(okHttpClient) }

  val chatRepository: ChatRepository by lazy {
    ChatRepository(
      appContext = appContext,
      chatDao = database.chatDao(),
      memoryDao = database.memoryDao(),
      skillDao = database.skillDao(),
      streamingClient = streamingAiClient,
      geminiApi = geminiApi,
      openRouterApi = openRouterApi,
      apiKeyStore = apiKeyStore,
      preferencesRepository = preferencesRepository,
    )
  }

  val aiGenerationCoordinator: AiGenerationCoordinator by lazy {
    AiGenerationCoordinator(
      appContext = appContext,
      repository = chatRepository,
    )
  }
}
