package com.totalbattle.chestscanner.network

import android.content.Context
import android.util.Log
import com.totalbattle.chestscanner.data.AppDatabase
import com.totalbattle.chestscanner.util.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

object SyncManager {
    private const val TAG = "SyncManager"

    suspend fun syncEvents(context: Context, apiService: ApiService): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Event Sync to Cloud API...")
            
            val db = AppDatabase.getDatabase(context)
            val events = db.chestEventDao().getUnsyncedEvents(false)
            
            if (events.isEmpty()) {
                Log.d(TAG, "No events to sync.")
                com.totalbattle.chestscanner.util.ErrorLogger.logError(TAG, "ℹ️ Sync Skipped: No new unsynced chests.", null)
                return@withContext true
            }

            // In a real app we might batch these, but for this scaffold we'll loop or use a batch API.
            // Using the existing uploadChest API for each event for simplicity.
            val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            isoFormatter.timeZone = TimeZone.getTimeZone("UTC")

            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT+10")
            }
            val norm = com.totalbattle.chestscanner.ocr.Normalizer()

            val batchRequests = events.map { event ->
                // If we calculated an actual timestamp from the timer text ("5m ago"), use that.
                // Otherwise fallback to the time the chest was scanned.
                val claimTime = if (event.actualTimestamp > 0) event.actualTimestamp else event.timestamp
                val gameDay = "chests_${sdf.format(Date(claimTime))}"
                val timeIso = isoFormatter.format(Date(claimTime))
                
                val normalizedChest = event.chestName.trim()
                val normalizedPlayer = norm.normalizePlayer(event.fromPlayer)
                val normalizedSource = norm.normalizeSource(event.source ?: "Overlay Auto-Scan")

                ChestRequest(
                    chestName = normalizedChest,
                    fromPlayer = normalizedPlayer,
                    source = normalizedSource,
                    time = timeIso,
                    gameDay = gameDay,
                    originalTimer = event.originalTimer
                )
            }

            val response = apiService.uploadChestsBatch(batchRequests)
            
            if (response.success) {
                // Mark as synced instead of deleting. Chunk to avoid SQLite 999 variables limit.
                events.map { it.id }.chunked(900).forEach { chunk ->
                    db.chestEventDao().markAsSynced(chunk)
                }

                // Automatic clean up: delete synced events older than 7 days
                val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                db.chestEventDao().deleteOldSyncedEvents(cutoff)

                Log.d(TAG, "✅ Synced ${events.size} events successfully!")
                ErrorLogger.logError(TAG, "✅ API Request Successful. Uploaded ${events.size} chests.", null)
                true
            } else {
                Log.e(TAG, "❌ Server returned success=false during sync. Response: $response")
                ErrorLogger.logError(TAG, "Server returned success=false. Response: $response", null)
                false
            }
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "❌ Server rejected sync (HTTP ${e.code()}): $errorBody")
            ErrorLogger.logError(TAG, "Server rejected sync (HTTP ${e.code()}): $errorBody", e)
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Event sync failed.", e)
            ErrorLogger.logError(TAG, "Event sync failed", e)
            false
        }
    }
}
