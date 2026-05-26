package com.totalbattle.chestscanner.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

data class FrameStabilityResult(
    val frameDiff: Double,
    val verticalPixelShift: Double,
    val isScrolling: Boolean,
    val isStable: Boolean
)

class FrameStabilizer {
    private var previousFrameGray: Mat? = null
    
    // To calculate vertical pixel shift, we'd ideally use optical flow or phase correlation.
    // For this lightweight pass, we will mock it based on perceptual diff, but in a full 
    // OpenCV implementation, `phaseCorrelate` is perfect for exact pixel shift.
    // We'll keep it simple: if diff is high, we assume shift.

    /**
     * Calculates the perceptual frame difference ratio (0.0 to 1.0)
     * using downscaled grayscale pixel diff.
     */
    fun analyzeFrame(currentBitmap: Bitmap): FrameStabilityResult {
        val currentMat = Mat()
        Utils.bitmapToMat(currentBitmap, currentMat)

        val currentGray = Mat()
        Imgproc.cvtColor(currentMat, currentGray, Imgproc.COLOR_RGBA2GRAY)
        
        // Downscale for speed and to smooth out noise
        val smallGray = Mat()
        Imgproc.resize(currentGray, smallGray, Size(currentGray.width() / 4.0, currentGray.height() / 4.0))
        currentGray.release()

        var frameDiff = 1.0
        var verticalPixelShift = 50.0 // Default to high shift

        if (previousFrameGray != null && previousFrameGray!!.size() == smallGray.size()) {
            val diff = Mat()
            Core.absdiff(smallGray, previousFrameGray, diff)
            
            // Calculate ratio of changed pixels (pixels with difference > threshold)
            val thresholded = Mat()
            Imgproc.threshold(diff, thresholded, 30.0, 255.0, Imgproc.THRESH_BINARY)
            
            val changedPixels = Core.countNonZero(thresholded)
            frameDiff = changedPixels.toDouble() / thresholded.total()
            
            // In a real scenario, phaseCorrelate provides exact verticalPixelShift.
            // For now, we estimate: if frameDiff is small, shift is small.
            verticalPixelShift = if (frameDiff < 0.02) 0.0 else (frameDiff * 100)

            diff.release()
            thresholded.release()
        }

        previousFrameGray?.release()
        previousFrameGray = smallGray
        
        currentMat.release()

        val isStable = verticalPixelShift < 2.0 && frameDiff < 0.02
        val isScrolling = !isStable

        return FrameStabilityResult(
            frameDiff = frameDiff,
            verticalPixelShift = verticalPixelShift,
            isScrolling = isScrolling,
            isStable = isStable
        )
    }
    
    fun reset() {
        previousFrameGray?.release()
        previousFrameGray = null
    }

    fun release() {
        reset()
    }
}
