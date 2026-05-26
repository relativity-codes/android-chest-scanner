package com.totalbattle.chestscanner.engine

import android.util.Log
import com.totalbattle.chestscanner.data.ChestEventDao
import com.totalbattle.chestscanner.data.ChestEventEntity
import com.totalbattle.chestscanner.ocr.OcrResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ChestEventProcessor(
    private val deduplicationEngine: DeduplicationEngine,
    private val dao: ChestEventDao,
    private val scope: CoroutineScope,
    private val onUniqueChest: (OcrResult) -> Unit
) {
    
    fun process(ocrResult: OcrResult, normalizedY: Float, frameIndex: Long) {
        // System Principle Guarantee: Prioritize correctness over completeness
        // If uncertain (invalid), do NOT log event, do NOT retry aggressively, do NOT infer missing data.
        if (!ocrResult.isValid || ocrResult.boundingBox == null) return
        
        val isDuplicate = deduplicationEngine.isDuplicate(
            chestType = ocrResult.chestType,
            playerName = ocrResult.playerName,
            normalizedY = normalizedY
        )
        
        if (!isDuplicate) {
            // Event Ordering Guarantee
            val eventSequenceId = "${frameIndex}_${normalizedY}"
            Log.i("EventProcessor", "Unique Chest Detected: ${ocrResult.chestType} from ${ocrResult.playerName} (Seq: $eventSequenceId)")
            
            val actualTime = calculateActualTimestamp(ocrResult.timerText)

            scope.launch {
                dao.insert(
                    ChestEventEntity(
                        chestName = ocrResult.chestType,
                        fromPlayer = ocrResult.playerName,
                        source = "Overlay Auto-Scan",
                        originalTimer = ocrResult.timerText,
                        actualTimestamp = actualTime
                    )
                )
            }
            
            onUniqueChest(ocrResult)
        }
    }

    private fun calculateActualTimestamp(timerText: String): Long {
        val now = Calendar.getInstance()
        if (timerText.isEmpty()) return now.timeInMillis

        return try {
            // Regex for "19h 43m"
            val regex = Regex("(?i)(\\d{1,2})h\\s*(\\d{1,2})m")
            val match = regex.find(timerText) ?: return now.timeInMillis
            
            val hours = match.groupValues[1].toInt()
            val minutes = match.groupValues[2].toInt()

            val chestTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hours)
                set(Calendar.MINUTE, minutes)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // If the calculated time is in the future compared to "now", 
            // it likely happened yesterday (e.g., it's 1 AM and chest says 23:50).
            if (chestTime.after(now)) {
                chestTime.add(Calendar.DAY_OF_YEAR, -1)
            }

            chestTime.timeInMillis
        } catch (e: Exception) {
            now.timeInMillis
        }
    }
}
