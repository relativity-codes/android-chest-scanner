package com.totalbattle.chestscanner

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.totalbattle.chestscanner.util.ErrorLogger
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
    private var txtError: android.widget.TextView? = null
    
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
        
        if (!org.opencv.android.OpenCVLoader.initDebug()) {
            ErrorLogger.logError(TAG, "OpenCV initialization failed!", Exception("OpenCV Init Failed"))
            showError("OpenCV Init Failed")
        }

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

    private var currentTab = "Gifts"

    private fun startOcrConsumer() {
        scope.launch(Dispatchers.Default) {
            for (bitmap in ocrQueue) {
                try {
                    // Parallelize row extraction and OCR for max speed
                    val rows = rowExtractor.extract(bitmap)
                    coroutineScope {
                        rows.forEach { row ->
                            launch {
                                try {
                                    val ocrResult = ocrEngine.process(row.bitmap, row.normalizedY)
                                    if (ocrResult != null && ocrResult.isValid) {
                                        eventProcessor.process(ocrResult, row.normalizedY, frameIndex, currentTab)
                                    }
                                } catch (e: Throwable) {
                                    ErrorLogger.logError(TAG, "Row processing error", e)
                                    showError("Row Err: ${e.message ?: e.javaClass.simpleName}")
                                } finally {
                                    row.bitmap.recycle()
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    ErrorLogger.logError(TAG, "OCR Consumer loop error", e)
                    showError("OCR Loop Err: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("data")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Clan Chest Tracker")
                .setContentText("Overlay active - Ready to scan")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            
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

    private var indicatorView: android.view.View? = null
    private var isIndicatorVisible = true

    private fun showFloatingControl() {
        if (floatingControlView != null) return 

        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 10
            y = 300
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#33000000")) // 20% alpha black
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            minimumWidth = (140 * density).toInt()
        }

        // Dedicated drag handle at the top
        val dragHandle = android.widget.TextView(this).apply {
            text = ":::: DRAG HERE ::::"
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 6f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#11FFFFFF"))
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        }

        val actionRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val recLabel = android.widget.TextView(this).apply {
            text = "REC"
            setTextColor(Color.RED)
            textSize = 8f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, (4 * density).toInt(), 0)
        }

        indicatorView = android.view.View(this).apply {
            val size = (10 * density).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (8 * density).toInt()
            }
            val shape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.RED)
            }
            background = shape
        }

        actionRow.addView(recLabel)
        actionRow.addView(indicatorView)

        btnScan = Button(this).apply {
            text = "START SCAN"
            textSize = 8f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (80 * density).toInt(),
                (32 * density).toInt()
            )
            setBackgroundColor(Color.parseColor("#4DDFB239"))
        }
        actionRow.addView(btnScan)

        txtStatus = android.widget.TextView(this).apply {
            text = "READY TO SCAN"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 7f
            setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            maxWidth = (180 * density).toInt()
        }

        txtError = android.widget.TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#AAFF5252"))
            textSize = 6f
            visibility = View.GONE
        }
        
        container.addView(dragHandle)
        container.addView(actionRow)
        container.addView(txtStatus)
        container.addView(txtError)
        floatingControlView = container

        // ONLY the handle moves the overlay
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (initialX + (event.rawX - initialTouchX)).toInt()
                        params.y = (initialY + (event.rawY - initialTouchY)).toInt()
                        try { windowManager.updateViewLayout(floatingControlView, params) } catch (e: Exception) {}
                        return true
                    }
                }
                return false
            }
        })
        
        try {
            windowManager.addView(floatingControlView, params)
        } catch (e: Exception) {
            ErrorLogger.logError(TAG, "Failed to add overlay", e)
            floatingControlView = null
        }

        btnScan?.setOnClickListener {
            toggleScanning()
        }
    }

    private fun showError(message: String) {
        scope.launch(Dispatchers.Main) {
            txtError?.text = "ERR: $message"
            txtError?.visibility = View.VISIBLE
            // Log for logcat as well
            Log.e(TAG, "Overlay Error: $message")
        }
    }

    private fun toggleScanning() {
        try {
            if (currentState == ScannerState.STOPPED) {
                startScanning()
            } else {
                stopScanning()
            }
        } catch (e: Exception) {
            ErrorLogger.logError(TAG, "Toggle Scanning Failed", e)
            showError("Start Failed: ${e.message}")
        }
    }

    private var blinkJob: Job? = null

    private var heartbeatJob: Job? = null
    private var heartbeatFrame = 0

    private fun startScanning() {
        currentState = ScannerState.INITIALIZING
        scannedCount = 0
        deduplicationEngine.clearSession()
        updateButtonUI()
        
        // Start Pulsing Indicator (REC + Red Dot)
        blinkJob = scope.launch {
            indicatorView?.visibility = View.VISIBLE
            while (isActive) {
                val alpha = if (isIndicatorVisible) 1.0f else 0.1f
                indicatorView?.alpha = alpha
                // Pulse the REC label as well
                (indicatorView?.parent as? android.view.ViewGroup)?.getChildAt(0)?.alpha = alpha
                isIndicatorVisible = !isIndicatorVisible
                delay(600)
            }
        }

        // Heartbeat for status text to show it's "working"
        heartbeatJob = scope.launch {
            while (isActive) {
                if (currentState != ScannerState.STOPPED) {
                    val dots = ".".repeat(heartbeatFrame % 4).padEnd(3, ' ')
                    withContext(Dispatchers.Main) {
                        if (scannedCount == 0) {
                            txtStatus?.text = "SEARCHING [$currentTab]$dots"
                        } else {
                            txtStatus?.text = "FOUND [$currentTab]: $scannedCount $dots"
                        }
                    }
                    heartbeatFrame++
                }
                delay(400)
            }
        }

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
        blinkJob?.cancel()
        blinkJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        indicatorView?.visibility = View.GONE
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
        
        val apiService = com.totalbattle.chestscanner.network.ApiService.create(this@ScannerService)
        
        val success = com.totalbattle.chestscanner.network.SyncManager.syncEvents(this@ScannerService, apiService)
        
        withContext(Dispatchers.Main) {
            if (success) {
                // We don't want to spam success toasts every minute, maybe just a log or a small indicator
                Log.d(TAG, "Periodic sync successful")
                scope.launch(Dispatchers.Main) { txtError?.visibility = View.GONE }
            } else {
                showError("Sync failed. Check API.")
                Toast.makeText(this@ScannerService, "Sync failed. Saved locally.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateButtonUI() {
        scope.launch(Dispatchers.Main) {
            if (currentState == ScannerState.STOPPED) {
                btnScan?.text = "START SCAN"
                btnScan?.setBackgroundColor(Color.parseColor("#4DDFB239"))
                txtStatus?.text = "IDLE - READY"
            } else {
                btnScan?.text = "STOP"
                btnScan?.setBackgroundColor(Color.parseColor("#80FF5252"))
                txtStatus?.text = "LIVE: $scannedCount CHESTS"
            }
            
            // Force layout update to ensure visibility
            try {
                floatingControlView?.let { 
                    windowManager.updateViewLayout(it, it.layoutParams as WindowManager.LayoutParams)
                }
            } catch (e: Exception) {}
        }
    }

    fun onUniqueChestDetected(result: com.totalbattle.chestscanner.ocr.OcrResult) {
        scannedCount++
        Log.d(TAG, "Unique Chest: ${result.chestType} from ${result.playerName}")
        scope.launch(Dispatchers.Main) {
            updateButtonUI()
            // Pulse the status text on find
            txtStatus?.setTextColor(Color.YELLOW)
            delay(300)
            txtStatus?.setTextColor(Color.WHITE)
        }
    }

    private var lastOcrRequestTime = 0L

    private suspend fun runAdaptiveCaptureLoop() {
        var fpsDelay = 150L
        var initFrames = 0
        
        while (currentState != ScannerState.STOPPED) {
            val startTime = System.currentTimeMillis()
            frameIndex++
            
            // Frame Integrity Guard
            if (lastFrameTimestamp > 0) {
                val gap = startTime - lastFrameTimestamp
                if (gap > 300) {
                    frameStabilizer.reset()
                    rowExtractor.reset()
                }
            }
            lastFrameTimestamp = startTime

            if (currentState == ScannerState.INITIALIZING) {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image, downscale = 4)
                    frameStabilizer.analyzeFrame(bitmap)
                    bitmap.recycle()
                    image.close()
                    initFrames++
                    if (initFrames >= 3) {
                        currentState = ScannerState.RUNNING
                    }
                }
                delay(200)
                continue
            }
            
            if (currentState == ScannerState.RUNNING || currentState == ScannerState.RECOVERING) {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    try {
                        // Optimization: Check stability with a downscaled bitmap first
                        val smallBitmap = imageToBitmap(image, downscale = 4)
                        val stability = frameStabilizer.analyzeFrame(smallBitmap)
                        smallBitmap.recycle()
                        
                        // While scrolling, we scan every 200ms to catch moving chests
                        // While stable, we scan every 150ms for high precision
                        val now = System.currentTimeMillis()
                        val timeSinceLastOcr = now - lastOcrRequestTime
                        
                        val shouldScan = when {
                            stability.isStable -> timeSinceLastOcr >= 150
                            stability.isScrolling -> timeSinceLastOcr >= 200 
                            else -> false
                        }

                        if (shouldScan && currentPowerMode != PowerMode.LOW_POWER_MODE) {
                            // Only create full-res bitmap when we actually need to scan
                            val fullBitmap = imageToBitmap(image, downscale = 1)
                            
                            // Periodically detect which tab we are in
                            if (frameIndex % 5 == 0L) {
                                val detected = ocrEngine.detectTab(fullBitmap)
                                if (detected != "Unknown") {
                                    currentTab = detected
                                }
                            }

                            // Pass ownership of fullBitmap to the queue
                            if (ocrQueue.trySend(fullBitmap).isSuccess) {
                                lastOcrRequestTime = now
                                withContext(Dispatchers.Main) {
                                    txtStatus?.text = "LIVE [$currentTab]: $scannedCount"
                                }
                            } else {
                                // If queue is full, we must recycle it ourselves
                                fullBitmap.recycle()
                            }
                        } else if (stability.isScrolling) {
                            withContext(Dispatchers.Main) {
                                if (txtStatus?.text?.contains("✅") != true) {
                                    txtStatus?.text = "SCROLLING... [Ready]"
                                }
                            }
                        }
                        
                        fpsDelay = if (stability.isScrolling) 60L else 100L
                    } catch (e: Throwable) {
                        ErrorLogger.logError(TAG, "Frame processing error", e)
                        showError("Cap Err: ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        image.close()
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            val sleepTime = (fpsDelay - elapsed).coerceAtLeast(5L)
            delay(sleepTime)
        }
    }

    private var reusableBuffer: java.nio.ByteBuffer? = null

    private fun imageToBitmap(image: android.media.Image, downscale: Int = 1): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val fullWidth = image.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(
            fullWidth,
            image.height, Bitmap.Config.ARGB_8888
        )
        
        buffer.position(0)
        val expectedSize = bitmap.byteCount
        if (buffer.remaining() < expectedSize) {
            // The buffer is missing the rowPadding at the end of the very last row.
            // We pad it using a reusable buffer to avoid allocating memory on every frame.
            var localBuffer = reusableBuffer
            if (localBuffer == null || localBuffer.capacity() < expectedSize) {
                localBuffer = java.nio.ByteBuffer.allocateDirect(expectedSize)
                reusableBuffer = localBuffer
            }
            val nonNullBuffer = localBuffer!!
            nonNullBuffer.clear()
            nonNullBuffer.put(buffer)
            // Fill the rest with zeros to satisfy Bitmap.copyPixelsFromBuffer
            val paddingNeeded = expectedSize - nonNullBuffer.position()
            for (i in 0 until paddingNeeded) {
                nonNullBuffer.put(0.toByte())
            }
            nonNullBuffer.flip()
            bitmap.copyPixelsFromBuffer(nonNullBuffer)
        } else {
            bitmap.copyPixelsFromBuffer(buffer)
        }

        // 1. Always crop the padding first to get a clean image
        val cleanBitmap = if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            cropped
        } else {
            bitmap
        }

        // 2. Then scale if downscale > 1
        return if (downscale > 1) {
            val targetWidth = image.width / downscale
            val targetHeight = image.height / downscale
            val scaled = Bitmap.createScaledBitmap(cleanBitmap, targetWidth, targetHeight, true)
            if (scaled != cleanBitmap) {
                cleanBitmap.recycle()
            }
            scaled
        } else {
            cleanBitmap
        }
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
