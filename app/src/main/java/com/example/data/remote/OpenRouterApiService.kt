package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET

@JsonClass(generateAdapter = true)
data class OpenRouterModelsResponse(
  @param:Json(name = "data") val data: List<OpenRouterModelDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OpenRouterModelDto(
  @param:Json(name = "id") val id: String,
  @param:Json(name = "name") val name: String? = null,
  @param:Json(name = "context_length") val contextLength: Int? = null,
  @param:Json(name = "pricing") val pricing: OpenRouterPricingDto? = null,
)

@JsonClass(generateAdapter = true)
data class OpenRouterPricingDto(
  @param:Json(name = "prompt") val prompt: String? = null,
  @param:Json(name = "completion") val completion: String? = null,
)

/**
 * Retrofit service for OpenRouter catalog metadata.
 */
interface OpenRouterApiService {
  @GET("api/v1/models")
  suspend fun getModels(): OpenRouterModelsResponse
}
