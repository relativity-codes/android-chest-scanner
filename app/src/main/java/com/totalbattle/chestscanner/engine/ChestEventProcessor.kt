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
            
            scope.launch {
                dao.insert(
                    ChestEventEntity(
                        chestName = ocrResult.chestType,
                        fromPlayer = ocrResult.playerName,
                        source = "Overlay Auto-Scan"
                    )
                )
            }
            
            onUniqueChest(ocrResult)
        }
    }
}
