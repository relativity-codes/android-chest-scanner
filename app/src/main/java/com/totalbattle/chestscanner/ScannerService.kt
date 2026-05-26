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
    private var txtStatus: android.widget.TextView? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var syncJob: Job? = null
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
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 10
            y = 250 // Positioned for visibility below status bar
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2000000")) // Almost solid black for contrast
            setPadding(20, 20, 20, 20)
        }

        btnScan = Button(this).apply {
            text = "▶ START SCAN"
            setBackgroundColor(Color.parseColor("#DFB239")) // Gold
            setTextColor(Color.BLACK)
            setPadding(40, 25, 40, 25)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }

        txtStatus = android.widget.TextView(this).apply {
            text = "Ready to Scan"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(10, 20, 10, 10)
            visibility = View.GONE
            setLineSpacing(0f, 1.3f)
        }
        
        container.addView(btnScan)
        container.addView(txtStatus)
        floatingControlView = container
        
        try {
            windowManager.addView(floatingControlView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
        }

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
        deduplicationEngine.clearSession()
        updateButtonUI()
        
        // Start Periodic Sync (every 60 seconds)
        syncJob = scope.launch {
            while (isActive) {
                delay(60_000) // Wait 1 minute
                if (currentState == ScannerState.RUNNING || currentState == ScannerState.RECOVERING) {
                    performCloudSync()
                }
            }
        }

        scope.launch(Dispatchers.Default) {
            runAdaptiveCaptureLoop()
        }
    }

    private fun stopScanning() {
        currentState = ScannerState.STOPPED
        syncJob?.cancel()
        syncJob = null
        updateButtonUI()
        
        // Final Sync
        scope.launch {
            performCloudSync()
        }
    }

    private suspend fun performCloudSync() {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@ScannerService, "Auto-syncing to cloud...", Toast.LENGTH_SHORT).show()
        }
        
        val db = com.totalbattle.chestscanner.data.AppDatabase.getDatabase(this@ScannerService)
        val apiService = com.totalbattle.chestscanner.network.RetrofitClient.instance
        
        val success = com.totalbattle.chestscanner.network.SyncManager.syncEvents(this@ScannerService, apiService)
        
        withContext(Dispatchers.Main) {
            if (success) {
                // We don't want to spam success toasts every minute, maybe just a log or a small indicator
                Log.d(TAG, "Periodic sync successful")
            } else {
                Toast.makeText(this@ScannerService, "Sync failed. Saved locally.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateButtonUI() {
        scope.launch(Dispatchers.Main) {
            if (currentState == ScannerState.STOPPED) {
                btnScan?.text = "▶ START SCAN"
                btnScan?.setBackgroundColor(Color.parseColor("#DFB239"))
                txtStatus?.visibility = View.GONE
            } else {
                btnScan?.text = "⏹ STOP ($scannedCount)"
                btnScan?.setBackgroundColor(Color.parseColor("#E53935")) // Bright Red
                txtStatus?.visibility = View.VISIBLE
            }
        }
    }

    fun onUniqueChestDetected(result: com.totalbattle.chestscanner.ocr.OcrResult) {
        scannedCount++
        scope.launch(Dispatchers.Main) {
            updateButtonUI()
            txtStatus?.text = "✅ Found: ${result.chestType}\n👤 By: ${result.playerName}"
        }
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
                            if (sent) {
                                withContext(Dispatchers.Main) {
                                    // Update indicator if we haven't found a chest in this session yet
                                    if (scannedCount == 0) txtStatus?.text = "Scanning... [Searching]"
                                }
                            } else {
                                Log.w(TAG, "OCR Queue Full - Dropped Frame")
                            }
                        } else if (!stability.isStable) {
                            withContext(Dispatchers.Main) {
                                // If the status hasn't been updated with a name yet, show scrolling status
                                if (txtStatus?.text?.contains("✅") != true) {
                                    txtStatus?.text = "Scanning... [Scrolling]"
                                }
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
