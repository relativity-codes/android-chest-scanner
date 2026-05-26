package com.totalbattle.chestscanner

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.totalbattle.chestscanner.data.AppDatabase
import com.totalbattle.chestscanner.network.ApiService
import com.totalbattle.chestscanner.network.ChestRequest
import com.totalbattle.chestscanner.network.SyncManager
import com.totalbattle.chestscanner.ocr.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScannerService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Toast.makeText(this, "Media Projection Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF030307),
                    surface = Color(0xFF0E0F19),
                    primary = Color(0xFFDFB239),
                    onBackground = Color(0xFFF8FAFC)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val context = this
        val scope = rememberCoroutineScope()
        
        var apiBaseUrl by remember { mutableStateOf("https://elf-clan.vercel.app/") }
        var isSyncing by remember { mutableStateOf(false) }
        var syncResult by remember { mutableStateOf<String?>(null) }
        
        // Cache Stats (Removed since we don't cache player lists locally anymore)
        var cachedEventsCount by remember { mutableStateOf(0) }

        // Scanner Simulation inputs
        var ocrTextChest by remember { mutableStateOf("Legendary Clan Chest") }
        var ocrTextPlayer by remember { mutableStateOf("Relativty") }
        var ocrTextSource by remember { mutableStateOf("Monst") }
        var ocrTextTimer by remember { mutableStateOf("18h 35m") }
        var scanStatus by remember { mutableStateOf<String?>(null) }

        // Fetch stats locally
        fun refreshCacheStats() {
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context)
                val events = db.chestEventDao().getAllEvents().size
                withContext(Dispatchers.Main) {
                    cachedEventsCount = events
                }
            }
        }

        LaunchedEffect(Unit) {
            refreshCacheStats()
        }

        var selectedTabIndex by remember { mutableStateOf(0) }
        val tabs = listOf("Overlay", "Simulator")

        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Color(0xFFDFB239)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            if (selectedTabIndex == 0) {
                OverlayControlScreen(
                    onStartService = {
                        if (!Settings.canDrawOverlays(context)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            context.startActivity(intent)
                        } else {
                            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        }
                    },
                    onStopService = {
                        stopService(Intent(context, ScannerService::class.java))
                    }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
            // Title Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFFDFB239))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "ELF CHEST RADAR",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFDFB239),
                    letterSpacing = 1.sp
                )
            }

            // Sync Configuration Panel (Next.js Linker)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFDFB239).copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "CLOUD CONNECTION CONFIG",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDFB239),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = apiBaseUrl,
                        onValueChange = { apiBaseUrl = it },
                        label = { Text("Next.js Server API Endpoint") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFDFB239),
                            unfocusedBorderColor = Color(0xFF2E2E3A)
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            isSyncing = true
                            scope.launch {
                                try {
                                    ApiService.setBaseUrl(apiBaseUrl)
                                    val api = ApiService.create()
                                    val success = SyncManager.syncEvents(context, api)
                                    withContext(Dispatchers.Main) {
                                        syncResult = if (success) "✅ Sync Successful!" else "❌ Sync Failed (Operating Offline)"
                                        refreshCacheStats()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        syncResult = "❌ Error: ${e.message}"
                                    }
                                } finally {
                                    isSyncing = false
                                }
                            }
                        },
                        enabled = !isSyncing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDFB239))
                    ) {
                        Text(
                            text = if (isSyncing) "SYNCING..." else "CONNECT & SYNC ROSTER",
                            color = Color(0xFF030307),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    syncResult?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Local SQL Database Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFDFB239).copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "LOCAL ROOM SQL CACHE STATUS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDFB239),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                      ) {
                        Text(text = "Unsynced Chest Events:", fontSize = 13.sp)
                        Text(text = "$cachedEventsCount events", fontWeight = FontWeight.Bold, color = Color(0xFFDFB239))
                    }
                }
            }

            // Batch Simulator Panel
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFDFB239).copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "BATCH OCR SCANNING SIMULATOR",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDFB239),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = ocrTextChest,
                        onValueChange = { ocrTextChest = it },
                        label = { Text("Raw OCR Chest Text") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFDFB239))
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ocrTextPlayer,
                        onValueChange = { ocrTextPlayer = it },
                        label = { Text("Raw OCR Player Name") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFDFB239))
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ocrTextSource,
                        onValueChange = { ocrTextSource = it },
                        label = { Text("Raw OCR Source") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFDFB239))
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ocrTextTimer,
                        onValueChange = { ocrTextTimer = it },
                        label = { Text("Raw OCR Timer Remaining") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFDFB239))
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    scanStatus = "🔄 Normalizing OCR Fields..."
                                    ApiService.setBaseUrl(apiBaseUrl)
                                    val api = ApiService.create()
                                    val norm = Normalizer()

                                    // Run OCR Ports
                                    val normalizedChest = ocrTextChest.trim()
                                    val normalizedPlayer = norm.normalizePlayer(ocrTextPlayer)
                                    val normalizedSource = norm.normalizeSource(ocrTextSource)
                                    val claimTime = norm.parseTimer(ocrTextTimer)
                                    
                                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                    val gameDay = "chests_${sdf.format(Date(claimTime))}"
                                    
                                    scanStatus = "📡 Normalization Complete:\n➔ Player: $normalizedPlayer\n➔ Source: $normalizedSource\n➔ Timer Time: ${Date(claimTime)}\n\nUploading to Next.js API..."
                                    
                                    val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                                    isoFormatter.timeZone = TimeZone.getTimeZone("UTC")
                                    val timeIso = isoFormatter.format(Date(claimTime))

                                    // PUSH TO NEXT.JS API in Real-Time!
                                    api.uploadChest(
                                        ChestRequest(
                                            chestName = normalizedChest,
                                            fromPlayer = normalizedPlayer,
                                            source = normalizedSource,
                                            time = timeIso,
                                            gameDay = gameDay,
                                            originalTimer = ocrTextTimer
                                        )
                                    )

                                    scanStatus = "✅ Scanned drop uploaded in REAL-TIME!\nCheck your browser dashboard immediately."
                                } catch (e: Exception) {
                                    scanStatus = "❌ Error uploading: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDFB239))
                    ) {
                        Text(
                            text = "SIMULATE BATCH SCAN & PUSH",
                            color = Color(0xFF030307),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    scanStatus?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = it,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFF8FAFC)
                        )
                    }
                }
            }
                }
            }
        }
    }

    @Composable
    fun OverlayControlScreen(onStartService: () -> Unit, onStopService: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "OVERLAY SCANNER MODE",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFDFB239)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Use this mode to scan drops while playing Total Battle. It will show a floating button over the game.",
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onStartService,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDFB239))
            ) {
                Text("START OVERLAY SERVICE", color = Color.Black)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onStopService,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.7f))
            ) {
                Text("STOP SERVICE", color = Color.White)
            }
        }
    }
}
