package com.totalbattle.chestscanner.network

import android.content.Context
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

data class SyncResponse(
    val success: Boolean,
    val count: Int
)

interface ApiService {

    @GET("api/whitelist")
    suspend fun getWhitelist(): WhitelistResponse

    @POST("api/chests")
    suspend fun uploadChest(@Body request: ChestRequest): SyncResponse

    @POST("api/chests/batch")
    suspend fun uploadChestsBatch(@Body requests: List<ChestRequest>): SyncResponse

    @POST("api/unknown-players")
    suspend fun reportUnknownPlayer(@Body request: UnknownPlayerRequest)

    companion object {
        private const val DEFAULT_URL = "https://elf-clan.vercel.app/"
        private const val PREF_NAME = "ChestScannerPrefs"
        private const val KEY_BASE_URL = "api_base_url"

        private var BASE_URL = DEFAULT_URL

        fun getBaseUrl(context: Context): String {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            BASE_URL = prefs.getString(KEY_BASE_URL, DEFAULT_URL) ?: DEFAULT_URL
            return BASE_URL
        }

        fun setBaseUrl(context: Context, url: String) {
            val finalUrl = if (url.endsWith("/")) url else "$url/"
            BASE_URL = finalUrl
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_BASE_URL, finalUrl).apply()
        }

        fun create(context: Context): ApiService {
            return Retrofit.Builder()
                .baseUrl(getBaseUrl(context))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}

