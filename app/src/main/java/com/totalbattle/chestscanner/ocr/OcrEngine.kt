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
    val timerText: String,
    val sourceText: String,
    val boundingBox: Rect?,
    val confidence: Float
)

class OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // Requires 2–30 letters/spaces before "Chest" as a whole word.
    // Prevents matching lines like " Chest" (empty prefix) or symbol garbage.
    private val chestRegex = Regex("(?i)([A-Za-z][A-Za-z\\s]{1,29})\\bChest\\b")
    // Handles: "From: Kazimi", "From:Kazimi", "From : Kazimi"
    private val playerRegex = Regex("(?i)From\\s*:\\s*([\\w\\s'-]{2,20})")
    private val sourceRegex = Regex("(?i)S(?:ource|ource)?\\s*:\\s*(.+)")
    private val timerRegex = Regex("(?i)(\\d{1,2})h\\s*(\\d{1,2})m")
    
    // Tab Detection Regex
    private val giftsTabRegex = Regex("(?i)Gifts")
    private val triumphalTabRegex = Regex("(?i)Triumphal")

    private val lastOcrTimes = mutableMapOf<String, Long>()

    suspend fun detectTab(bitmap: Bitmap): String {
        // Crop the header area where tabs are (roughly 18% to 25% of screen height)
        val headerHeight = (bitmap.height * 0.08).toInt()
        val headerTop = (bitmap.height * 0.17).toInt()
        val headerRect = Rect(0, headerTop, bitmap.width, headerTop + headerHeight)
        
        val headerBitmap = Bitmap.createBitmap(bitmap, headerRect.left, headerRect.top, headerRect.width(), headerRect.height())
        val textBlocks = runMlKitOcr(headerBitmap)

        var hasGifts = false
        var hasTriumphal = false

        for (block in textBlocks) {
            val text = block.text
            val bbox = block.boundingBox
            if (bbox != null) {
                if (giftsTabRegex.containsMatchIn(text) && isRegionRed(headerBitmap, bbox)) {
                    hasGifts = true
                }
                if (triumphalTabRegex.containsMatchIn(text) && isRegionRed(headerBitmap, bbox)) {
                    hasTriumphal = true
                }
            }
        }
        
        headerBitmap.recycle()

        return when {
            hasTriumphal -> "Triumphal Gifts"
            hasGifts -> "Gifts"
            else -> "Unknown"
        }
    }

    suspend fun process(bitmap: Bitmap, normalizedY: Float): OcrResult? {
        val regionKey = String.format("%.2f", normalizedY)
        val now = System.currentTimeMillis()
        val lastTime = lastOcrTimes[regionKey] ?: 0L
        
        // OCR Debounce Window = 200ms per region (Faster than human reaction)
        if (now - lastTime < 200) {
            return null // Skip OCR
        }
        lastOcrTimes[regionKey] = now

        // Stage 1: OpenCV Preprocessing
        val preprocessed = preprocessImage(bitmap)
        
        // Stage 2: ML Kit OCR
        val textBlocks = runMlKitOcr(preprocessed)
        
        var chestType = ""
        var playerName = ""
        var timerText = ""
        var sourceText = ""
        var rawTextCombined = ""
        var boundingBox: Rect? = null
        val totalConfidence = 1.0f // ML Kit doesn't provide confidence via standard text blocks, defaulting for contract
        
        for (block in textBlocks) {
            // If boundingBox missing -> discard
            if (block.boundingBox != null) {
                if (boundingBox == null) {
                    boundingBox = block.boundingBox
                } else {
                    boundingBox.union(block.boundingBox!!)
                }
            }
            
            for (line in block.lines) {
                val text = line.text.trim()
                if (text.isEmpty()) continue
                
                rawTextCombined += "$text "
                
                // 1. Timer check
                val timerMatch = timerRegex.find(text)
                if (timerMatch != null) {
                    timerText = timerMatch.groupValues[0].trim()
                    continue
                }
                
                // 2. Player name check
                val playerMatch = playerRegex.find(text)
                if (playerMatch != null) {
                    playerName = playerMatch.groupValues[1].trim()
                    continue
                }
                
                // 3. Chest type check — only accept if a non-empty meaningful prefix was captured
                val chestMatch = chestRegex.find(text)
                if (chestMatch != null) {
                    val prefix = chestMatch.groupValues[1].trim()
                    if (prefix.isNotEmpty()) {
                        chestType = "$prefix Chest"
                    }
                    continue
                }
                
                // 4. Source line check ("Source: Level 10 Crypt", "Source: Seasonal store", etc.)
                val sourceMatch = sourceRegex.find(text)
                if (sourceMatch != null) {
                    sourceText = sourceMatch.groupValues[1].trim()
                    continue
                }

                // 5. Other leftover lines — ignore to avoid noise contaminating sourceText
            }
        }
        
        preprocessed.recycle()

        // Missing Bounding Box check
        if (boundingBox == null) {
            return OcrResult(false, rawTextCombined, chestType, playerName, timerText, sourceText, null, 0.0f)
        }

        // Stage 4: Strict validation & Sanity Filter
        val isValid = isValidChestName(chestType) && isValidPlayerName(playerName)
        
        return OcrResult(
            isValid = isValid,
            rawText = rawTextCombined.trim(),
            chestType = chestType,
            playerName = playerName,
            timerText = timerText,
            sourceText = sourceText.trim(),
            boundingBox = boundingBox,
            confidence = totalConfidence // Mock confidence since ML Kit Latin base doesn't expose it
        )
    }

    private fun isValidChestName(name: String): Boolean {
        // Must be longer than just "Chest" alone — needs a meaningful type prefix
        if (name.length <= 6) return false          // "X Chest" minimum is 7 chars
        if (!name.matches(Regex(".*[a-zA-Z].*"))) return false
        // Confirm "Chest" is present and preceded by at least 2 non-space chars
        val chestIdx = name.indexOf("Chest", ignoreCase = true)
        if (chestIdx < 2) return false
        return true
    }

    private fun isValidPlayerName(name: String): Boolean {
        if (name.length < 2 || name.length > 20) return false
        // Must contain at least one letter (not symbols/digits only)
        if (!name.matches(Regex(".*[a-zA-Z].*"))) return false
        return true
    }

    private fun isRegionRed(bitmap: Bitmap, rect: Rect): Boolean {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(0, bitmap.width)
        val bottom = rect.bottom.coerceIn(0, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return false

        var redPixelCount = 0
        var totalPixels = 0

        for (x in 0 until width step 2) {
            for (y in 0 until height step 2) {
                val pixel = bitmap.getPixel(left + x, top + y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Red criteria: R is high, G and B are low, and R is significantly greater than G and B
                if (r > 120 && r > g * 1.5 && r > b * 1.5) {
                    redPixelCount++
                }
                totalPixels++
            }
        }

        if (totalPixels == 0) return false
        val redRatio = redPixelCount.toFloat() / totalPixels
        return redRatio > 0.06f // Threshold for text outline/strokes
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
