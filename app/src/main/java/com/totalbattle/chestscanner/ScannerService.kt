package com.totalbattle.chestscanner

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

enum class ScannerState {
    STOPPED, INITIALIZING, RUNNING, PAUSED, RECOVERING
}

enum class PowerMode {
    HIGH_ACCURACY_MODE, BALANCED_MODE, LOW_POWER_MODE
}

class ScannerService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var floatingControlView: View? = null
    private var btnScan: Button? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var currentState = ScannerState.STOPPED
    private var currentPowerMode = PowerMode.HIGH_ACCURACY_MODE
    private var scannedCount = 0

    // Dependencies
    private val frameStabilizer = com.totalbattle.chestscanner.vision.FrameStabilizer()
    private val rowExtractor = com.totalbattle.chestscanner.vision.RowExtractor()
    private val ocrEngine = com.totalbattle.chestscanner.ocr.OcrEngine()
    private val deduplicationEngine = com.totalbattle.chestscanner.engine.DeduplicationEngine()
    private lateinit var eventProcessor: com.totalbattle.chestscanner.engine.ChestEventProcessor

    // Processing Queue Guard (Backpressure Control)
    // max OCR queue size = 5. if queue full, drop oldest frame.
    private val ocrQueue = Channel<Bitmap>(capacity = 5, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var frameIndex = 0L
    private var lastFrameTimestamp = 0L

    companion object {
        const val CHANNEL_ID = "ScannerServiceChannel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "ScannerService"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        
        val db = com.totalbattle.chestscanner.data.AppDatabase.getDatabase(this)
        eventProcessor = com.totalbattle.chestscanner.engine.ChestEventProcessor(
            deduplicationEngine,
            db.chestEventDao(),
            scope
        ) { result ->
            onUniqueChestDetected(result)
        }

        // Start the OCR processing consumer
        startOcrConsumer()
    }

    private fun startOcrConsumer() {
        scope.launch(Dispatchers.Default) {
            for (bitmap in ocrQueue) {
                // Perform extraction and OCR
                val rows = rowExtractor.extract(bitmap)
                for (row in rows) {
                    val ocrResult = ocrEngine.process(row.bitmap, row.normalizedY)
                    if (ocrResult != null && ocrResult.isValid) {
                        eventProcessor.process(ocrResult, row.normalizedY, frameIndex)
                    }
                    row.bitmap.recycle()
                }
                bitmap.recycle()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Clan Chest Tracker")
                .setContentText("Overlay active - Ready to scan")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
            
            startForeground(NOTIFICATION_ID, notification)
            
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            setupScreenCapture()
            showFloatingControl()
        }

        return START_NOT_STICKY
    }

    @SuppressLint("WrongConstant")
    private fun setupScreenCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScannerCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun showFloatingControl() {
        // Safe Overlay Zone Rule: edge-anchored, must not overlap detection regions
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 100 // Edge anchored, avoids the main list rect
        }

        val controlLayout = FrameLayout(this)
        btnScan = Button(this).apply {
            text = "▶ START SCAN"
            setBackgroundColor(Color.parseColor("#DFB239"))
            setTextColor(Color.BLACK)
            setPadding(30, 20, 30, 20)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        
        controlLayout.addView(btnScan)
        floatingControlView = controlLayout
        windowManager.addView(floatingControlView, params)

        btnScan?.setOnClickListener {
            toggleScanning()
        }
    }

    private fun toggleScanning() {
        if (currentState == ScannerState.STOPPED) {
            startScanning()
        } else {
            stopScanning()
        }
    }

    private fun startScanning() {
        currentState = ScannerState.INITIALIZING
        scannedCount = 0
        updateButtonUI()
        
        scope.launch(Dispatchers.Default) {
            runAdaptiveCaptureLoop()
        }
    }

    private fun stopScanning() {
        currentState = ScannerState.STOPPED
        updateButtonUI()
        Toast.makeText(this, "Batch Syncing $scannedCount chests...", Toast.LENGTH_SHORT).show()
        // TODO: SyncManager call
    }

    private fun updateButtonUI() {
        scope.launch(Dispatchers.Main) {
            if (currentState == ScannerState.STOPPED) {
                btnScan?.text = "▶ START SCAN"
                btnScan?.setBackgroundColor(Color.parseColor("#DFB239")) // Gold
            } else {
                btnScan?.text = "⏹ STOP ($scannedCount)"
                btnScan?.setBackgroundColor(Color.parseColor("#E53935")) // Red
            }
        }
    }

    fun onUniqueChestDetected(result: com.totalbattle.chestscanner.ocr.OcrResult) {
        scannedCount++
        updateButtonUI()
    }

    private suspend fun runAdaptiveCaptureLoop() {
        var fpsDelay = 1000L
        var initFrames = 0
        
        while (currentState != ScannerState.STOPPED) {
            val startTime = System.currentTimeMillis()
            frameIndex++
            
            // Frame Integrity Guard
            if (lastFrameTimestamp > 0) {
                val gap = startTime - lastFrameTimestamp
                if (gap > 300) {
                    Log.w(TAG, "Frame drop assumed! Gap = ${gap}ms. Resetting stabilizers.")
                    frameStabilizer.reset()
                    rowExtractor.reset()
                }
            }
            lastFrameTimestamp = startTime

            // Initialization Phase: run 3-frame baseline scan on start
            if (currentState == ScannerState.INITIALIZING) {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    frameStabilizer.analyzeFrame(bitmap)
                    bitmap.recycle()
                    image.close()
                    initFrames++
                    if (initFrames >= 3) {
                        currentState = ScannerState.RUNNING
                    }
                }
                delay(300)
                continue
            }
            
            if (currentState == ScannerState.RUNNING || currentState == ScannerState.RECOVERING) {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    try {
                        val bitmap = imageToBitmap(image)
                        
                        val stability = frameStabilizer.analyzeFrame(bitmap)
                        
                        fpsDelay = if (stability.isScrolling) 100L else 333L
                        
                        // Only OCR if in stable condition and power mode permits
                        if (stability.isStable && currentPowerMode != PowerMode.LOW_POWER_MODE) {
                            // Queue Guard: Try to send to the OCR channel
                            val sent = ocrQueue.trySend(Bitmap.createBitmap(bitmap)).isSuccess
                            if (!sent) {
                                Log.w(TAG, "OCR Queue Full - Dropped Frame")
                            }
                        }
                        
                        bitmap.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "Capture analysis failed", e)
                    } finally {
                        image.close()
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            
            // CPU Hard Limit Enforcer: downgrade if processing takes > 120ms
            if (elapsed > 120 && currentPowerMode == PowerMode.HIGH_ACCURACY_MODE) {
                currentPowerMode = PowerMode.BALANCED_MODE
                Log.w(TAG, "CPU Stressed: Downgrading to BALANCED_MODE")
            }
            
            val sleepTime = (fpsDelay - elapsed).coerceAtLeast(10L)
            delay(sleepTime)
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Scanner Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        currentState = ScannerState.STOPPED
        scope.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (floatingControlView != null) windowManager.removeView(floatingControlView)
    }
}
