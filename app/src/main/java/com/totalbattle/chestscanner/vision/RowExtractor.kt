package com.totalbattle.chestscanner.vision

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Anchor representation in normalized coordinates.
 */
data class NormalizedAnchor(
    val normalizedX: Float,
    val normalizedY: Float,
    val confidence: Float
)

data class ExtractedRow(
    val bitmap: Bitmap,
    val normalizedY: Float
)

/**
 * Extracts rows from a scrolling list using anchor-based segmentation.
 */
class RowExtractor {
    
    private var anchorLockWindowCount = 0
    private var isRecoveryMode = false
    private var framesWithoutAnchor = 0

    // Caches for temporal stability
    private val lockedAnchors = mutableListOf<NormalizedAnchor>()

    /**
     * Splits the full screen bitmap into individual list item bitmaps based on anchor locations.
     */
    fun extract(fullScreen: Bitmap): List<ExtractedRow> {
        val rows = mutableListOf<ExtractedRow>()
        
        // Mocking anchor detection for scaffolding:
        val detectedAnchors = detectAnchors(fullScreen)
        
        if (detectedAnchors.isEmpty()) {
            framesWithoutAnchor++
            if (framesWithoutAnchor >= 2 /* frames in ~2 seconds at 1fps */) {
                isRecoveryMode = true
            }
            return emptyList()
        } else {
            framesWithoutAnchor = 0
            
            // Recovery Exit Condition: anchors stable for 5 frames
            if (isRecoveryMode) {
                anchorLockWindowCount++
                if (anchorLockWindowCount >= 5) {
                    isRecoveryMode = false
                    anchorLockWindowCount = 0
                } else {
                    return emptyList() // Skip processing while recovering stability
                }
            } else {
                // Determine if we should lock these anchors (mock logic)
                lockedAnchors.clear()
                lockedAnchors.addAll(detectedAnchors)
            }
        }

        // Process locked anchors
        for (anchor in lockedAnchors) {
            // Anchor Confidence Score (< 0.75 discard)
            if (anchor.confidence < 0.75f) continue
            
            // Map normalized coordinates back to absolute
            val absoluteY = (anchor.normalizedY * fullScreen.height).toInt()
            
            val rowHeight = (fullScreen.height * 0.12f).toInt()
            
            // Visibility Threshold Rule:
            // Ignore row if visible_height < 60% OR entire icon isn't visible.
            if (absoluteY < 0 || absoluteY + rowHeight > fullScreen.height) {
                // If it's clipped, check if at least 60% is visible
                val visibleTop = maxOf(0, absoluteY)
                val visibleBottom = minOf(fullScreen.height, absoluteY + rowHeight)
                val visibleHeight = visibleBottom - visibleTop
                
                if (visibleHeight.toFloat() / rowHeight < 0.60f) {
                    continue // discard
                }
            }
            
            val rowRect = Rect(0, maxOf(0, absoluteY), fullScreen.width, minOf(fullScreen.height, absoluteY + rowHeight))
            val rowBitmap = cropBitmap(fullScreen, rowRect)
            rows.add(ExtractedRow(rowBitmap, anchor.normalizedY))
        }
        
        return rows
    }
    
    private fun detectAnchors(fullScreen: Bitmap): List<NormalizedAnchor> {
        // In a real OpenCV app, we use matchTemplate.
        // For scaffold, we mock generating anchors in the safe area.
        val safeTopNorm = 0.15f
        val safeBottomNorm = 0.85f
        val listHeightNorm = safeBottomNorm - safeTopNorm
        
        val estimatedRowHeightNorm = 0.12f
        val anchors = mutableListOf<NormalizedAnchor>()
        
        var currentYNorm = safeTopNorm
        while (currentYNorm + estimatedRowHeightNorm <= safeBottomNorm) {
            // Mocking a perfect confidence anchor
            anchors.add(NormalizedAnchor(0.05f, currentYNorm, 0.90f))
            currentYNorm += estimatedRowHeightNorm
        }
        
        return anchors
    }
    
    private fun cropBitmap(src: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, src.width - 1)
        val top = rect.top.coerceIn(0, src.height - 1)
        val width = rect.width().coerceIn(1, src.width - left)
        val height = rect.height().coerceIn(1, src.height - top)
        return Bitmap.createBitmap(src, left, top, width, height)
    }
    
    fun reset() {
        lockedAnchors.clear()
        framesWithoutAnchor = 0
        anchorLockWindowCount = 0
        isRecoveryMode = false
    }
}
