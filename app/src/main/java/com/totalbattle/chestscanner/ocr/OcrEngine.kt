package com.totalbattle.chestscanner.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.resume

data class OcrResult(
    val isValid: Boolean,
    val rawText: String,
    val chestType: String,
    val playerName: String,
    val boundingBox: Rect?,
    val confidence: Float
)

class OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    private val chestRegex = Regex("(?i)(.*?)Chest")
    private val playerRegex = Regex("(?i)From:\\s*(.*)")
    
    // OCR Debounce layer (Map of region normalizedY string -> timestamp)
    private val lastOcrTimes = mutableMapOf<String, Long>()

    suspend fun process(bitmap: Bitmap, normalizedY: Float): OcrResult? {
        val regionKey = String.format("%.2f", normalizedY)
        val now = System.currentTimeMillis()
        val lastTime = lastOcrTimes[regionKey] ?: 0L
        
        // OCR Debounce Window = 500ms per region
        if (now - lastTime < 500) {
            return null // Skip OCR
        }
        lastOcrTimes[regionKey] = now

        // Stage 1: OpenCV Preprocessing
        val preprocessed = preprocessImage(bitmap)
        
        // Stage 2: ML Kit OCR
        val textBlocks = runMlKitOcr(preprocessed)
        
        var chestType = ""
        var playerName = ""
        var rawTextCombined = ""
        var boundingBox: Rect? = null
        var totalConfidence = 1.0f // ML Kit doesn't provide confidence via standard text blocks, defaulting for contract
        
        for (block in textBlocks) {
            val text = block.text
            rawTextCombined += "$text "
            
            // If boundingBox missing -> discard
            if (block.boundingBox != null) {
                if (boundingBox == null) {
                    boundingBox = block.boundingBox
                } else {
                    boundingBox.union(block.boundingBox!!)
                }
            }
            
            val chestMatch = chestRegex.find(text)
            if (chestMatch != null) {
                chestType = chestMatch.groupValues[1].trim() + " Chest"
            }
            
            val playerMatch = playerRegex.find(text)
            if (playerMatch != null) {
                playerName = playerMatch.groupValues[1].trim()
            }
        }
        
        preprocessed.recycle()

        // Missing Bounding Box check
        if (boundingBox == null) {
            return OcrResult(false, rawTextCombined, chestType, playerName, null, 0.0f)
        }

        // Stage 4: Strict validation & Sanity Filter
        val isValid = isValidChestName(chestType) && isValidPlayerName(playerName)
        
        return OcrResult(
            isValid = isValid,
            rawText = rawTextCombined.trim(),
            chestType = chestType,
            playerName = playerName,
            boundingBox = boundingBox,
            confidence = totalConfidence // Mock confidence since ML Kit Latin base doesn't expose it
        )
    }

    private fun isValidChestName(name: String): Boolean {
        if (name.length <= 3) return false
        if (!name.matches(Regex(".*[a-zA-Z].*"))) return false
        return true
    }

    private fun isValidPlayerName(name: String): Boolean {
        if (name.isEmpty() || name.length > 20) return false
        // Must not contain symbols only (must have at least one alphanumeric)
        if (!name.matches(Regex(".*[a-zA-Z0-9].*"))) return false
        return true
    }

    private fun preprocessImage(src: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(src, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, result)
        mat.release()
        return result
    }

    private suspend fun runMlKitOcr(bitmap: Bitmap): List<com.google.mlkit.vision.text.Text.TextBlock> = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    continuation.resume(visionText.textBlocks)
                }
                .addOnFailureListener { e ->
                    Log.e("OcrEngine", "ML Kit Error", e)
                    continuation.resume(emptyList())
                }
        } catch (e: Exception) {
            Log.e("OcrEngine", "Exception", e)
            continuation.resume(emptyList())
        }
    }
}
