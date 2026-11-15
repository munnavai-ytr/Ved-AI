package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class GeminiModelsResponse(
  @param:Json(name = "models") val models: List<GeminiModelDto> = emptyList(),
  @param:Json(name = "nextPageToken") val nextPageToken: String? = null,
)

@JsonClass(generateAdapter = true)
data class GeminiModelDto(
  @param:Json(name = "name") val name: String,
  @param:Json(name = "displayName") val displayName: String? = null,
  @param:Json(name = "description") val description: String? = null,
  @param:Json(name = "inputTokenLimit") val inputTokenLimit: Int? = null,
  @param:Json(name = "outputTokenLimit") val outputTokenLimit: Int? = null,
  @param:Json(name = "supportedGenerationMethods")
  val supportedGenerationMethods: List<String> = emptyList(),
)

/**
 * Retrofit service for discovering live Gemini models from Google AI Studio / Generative Language API.
 */
interface GeminiApiService {
  @GET("v1beta/models")
  suspend fun getModels(
    @Query("key") apiKey: String,
    @Query("pageSize") pageSize: Int = 100,
    @Query("pageToken") pageToken: String? = null,
  ): GeminiModelsResponse
}
