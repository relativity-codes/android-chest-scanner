package com.totalbattle.chestscanner.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class WhitelistResponse(
    val players: List<String>,
    val fixes: Map<String, String>
)

data class ChestRequest(
    val chestName: String,
    val fromPlayer: String,
    val source: String,
    val time: String, // ISO 8601 String
    val gameDay: String,
    val originalTimer: String
)

data class UnknownPlayerRequest(
    val ocrName: String
)

interface ApiService {

    @GET("api/whitelist")
    suspend fun getWhitelist(): WhitelistResponse

    @POST("api/chests")
    suspend fun uploadChest(@Body request: ChestRequest)

    @POST("api/chests/batch")
    suspend fun uploadChestsBatch(@Body requests: List<ChestRequest>)

    @POST("api/unknown-players")
    suspend fun reportUnknownPlayer(@Body request: UnknownPlayerRequest)

    companion object {
        private var BASE_URL = "https://elf-clan.vercel.app/"

        fun setBaseUrl(url: String) {
            BASE_URL = if (url.endsWith("/")) url else "$url/"
        }

        fun create(): ApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}
