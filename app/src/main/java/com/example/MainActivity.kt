package com.example

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.BorderStroke
import com.example.db.AppDatabase
import com.example.db.ConfigEntity
import com.example.db.DatabaseSeeder
import com.example.server.VotingServer
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SlateBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateBlue
import com.example.ui.theme.SlateGreen
import com.example.ui.theme.SlateGrey
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateWhite
import com.example.ui.theme.SlateRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Boot up the embedded transactional Java HTTP server
        VotingServer.start(this)

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStopServer = { VotingServer.stop() },
                        onStartServer = { VotingServer.start(this) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        VotingServer.stop()
        super.onDestroy()
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onStopServer: () -> Unit,
    onStartServer: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }
    
    // Server logs list from Flow
    val logs by VotingServer.logsFlow.collectAsStateWithLifecycle()

    // Screen State Management
    var selectedTab by remember { mutableStateOf(0) } // 0: Voter, 1: Admin Dashboard, 2: Control Hub
    var webUrl by remember { mutableStateOf("http://localhost:3000") }
    var showLogsConsole by remember { mutableStateOf(false) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Safely load the new URL only when selected tab changes the webUrl target,
    // avoiding reloading when sub-navigating or when a database log updates the flow.
    LaunchedEffect(webUrl) {
        webViewRef?.let { webView ->
            val current = webView.url?.removeSuffix("/") ?: ""
            val target = webUrl.removeSuffix("/")
            if (current != target) {
                webView.loadUrl(webUrl)
            }
        }
    }

    // Auto-seed data on launch if voters table is completely empty
    LaunchedEffect(Unit) {
        val votersCount = withContext(Dispatchers.IO) {
            database.voterDao().getVotersCount()
        }
        if (votersCount == 0) {
            VotingServer.addLog("AUTOLOAD PRE-FLIGHT: SQLite voters count was 0. Triggering initial database seeder...")
            val success = DatabaseSeeder.seedDatabase(context, database, VotingServer.votingEndTimeStr)
            if (success) {
                VotingServer.addLog("AUTOLOAD PRE-FLIGHT: Database successfully seeded with 7 voters, 2 managers.")
            } else {
                VotingServer.addLog("AUTOLOAD PRE-FLIGHT: Database seeder failed to insert rows.")
            }
        }
    }

    // Effect to update web WebView target URL based on selected tabs
    LaunchedEffect(selectedTab) {
        webUrl = when (selectedTab) {
            0 -> "http://localhost:3000"
            1 -> "http://localhost:3000/admin.html"
            else -> webUrl
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlateBg)
    ) {
        // App Header Control bar
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LAYOUT DESIGN VOTING",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = SlateWhite,
                            letterSpacing = 1.15.sp
                        )
                        Text(
                            text = "Verified cryptographic ballots",
                            fontSize = 11.sp,
                            color = SlateGrey
                        )
                    }

                    // Green Active Server Dot
                    Surface(
                        color = SlateGreen,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .size(12.dp)
                            .padding(end = 4.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "SECURE & ACTIVE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateGreen
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Standard Material Theme Tabs Bar
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = SlateBlue,
                    divider = {}
                ) {
                    Tab(
                        selected = (selectedTab == 0),
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = "VOTER APP",
                                fontWeight = if (selectedTab == 0) FontWeight.ExtraBold else FontWeight.Medium,
                                fontSize = 12.sp,
                                color = if (selectedTab == 0) SlateBlue else SlateGrey
                            )
                        }
                    )
                    Tab(
                        selected = (selectedTab == 1),
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                text = "ADMIN PANEL",
                                fontWeight = if (selectedTab == 1) FontWeight.ExtraBold else FontWeight.Medium,
                                fontSize = 12.sp,
                                color = if (selectedTab == 1) SlateBlue else SlateGrey
                            )
                        }
                    )
                    Tab(
                        selected = (selectedTab == 2),
                        onClick = { selectedTab = 2 },
                        text = {
                            Text(
                                text = "STATE HUB",
                                fontWeight = if (selectedTab == 2) FontWeight.ExtraBold else FontWeight.Medium,
                                fontSize = 12.sp,
                                color = if (selectedTab == 2) SlateBlue else SlateGrey
                            )
                        }
                    )
                }
            }
        }

        // Expanded Screen Content Frame
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            if (selectedTab == 0 || selectedTab == 1) {
                // Interactive Embedded browser View
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    allowFileAccess = true
                                    allowContentAccess = true
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        return false
                                    }
                                }
                                webViewRef = this
                                loadUrl(webUrl)
                            }
                        },
                        update = { view ->
                            webViewRef = view
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                // Configuration Controls Panel
                ControlHubPanel(
                    database = database,
                    onSeedTriggered = {
                        coroutineScope.launch {
                            val success = DatabaseSeeder.seedDatabase(context, database, VotingServer.votingEndTimeStr)
                            if (success) {
                                Toast.makeText(context, "Database seeded successfully!", Toast.LENGTH_SHORT).show()
                                VotingServer.addLog("ADMIN OVERLAY: Seeding trigger invoked successfully!")
                            } else {
                                Toast.makeText(context, "Seeding failed. See logs below.", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onTimeOverrideUtc = { overrideIsoString ->
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                val formatted = overrideIsoString.replace("T", " ").replace("Z", "")
                                database.configDao().insertConfig(ConfigEntity("voting_end_time", formatted))
                                // If override is past cutoff, set voting_open state directly to 0
                                val nowSec = Instant.now().epochSecond
                                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                    .withZone(java.time.ZoneOffset.UTC)
                                val endSec = try {
                                    java.time.Instant.from(formatter.parse(formatted)).epochSecond
                                } catch (e: Exception) {
                                    nowSec + 7200L
                                }
                                if (nowSec >= endSec) {
                                    database.configDao().insertConfig(ConfigEntity("voting_open", "0"))
                                } else {
                                    database.configDao().insertConfig(ConfigEntity("voting_open", "1"))
                                }
                            }
                            Toast.makeText(context, "End time successfully overridden in SQLite config!", Toast.LENGTH_SHORT).show()
                            VotingServer.addLog("ADMIN OVERLAY: Overrode voting_end_time config to '$overrideIsoString'.")
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun ControlHubPanel(
    database: AppDatabase,
    onSeedTriggered: () -> Unit,
    onTimeOverrideUtc: (String) -> Unit
) {
    var votesCount by remember { mutableStateOf(0) }
    var configStartTimeText by remember { mutableStateOf("LOADING") }
    var configEndTimeText by remember { mutableStateOf("LOADING") }
    
    var startInputText by remember { mutableStateOf("") }
    var endInputText by remember { mutableStateOf("") }
    
    val coroutineScope = rememberCoroutineScope()
    var isSeeding by remember { mutableStateOf(false) }
    var isOverriding by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Query status stats reactively
    LaunchedEffect(Unit) {
        val count = withContext(Dispatchers.IO) { database.votedRollDao().getVotedRollsCount() }
        val rawStart = withContext(Dispatchers.IO) { database.configDao().getConfigValue("voting_start_time") } ?: "2026-05-01 00:00:00"
        val rawEnd = withContext(Dispatchers.IO) { database.configDao().getConfigValue("voting_end_time") } ?: "2026-08-01 14:00:00"
        votesCount = count
        configStartTimeText = rawStart
        configEndTimeText = rawEnd
        startInputText = rawStart
        endInputText = rawEnd
    }

    val saveTemporalSettings = { startStr: String, endStr: String ->
        coroutineScope.launch {
            val (updatedStart, updatedEnd) = withContext(Dispatchers.IO) {
                database.configDao().insertConfig(ConfigEntity("voting_start_time", startStr.trim()))
                database.configDao().insertConfig(ConfigEntity("voting_end_time", endStr.trim()))
                
                // Read back
                val rawStart = database.configDao().getConfigValue("voting_start_time") ?: "2026-05-01 00:00:00"
                val rawEnd = database.configDao().getConfigValue("voting_end_time") ?: "2026-08-01 14:00:00"
                
                // Evaluate and update voting_open inside SQLite config
                val nowSec = Instant.now().epochSecond
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneOffset.UTC)
                val startSec = try {
                    java.time.Instant.from(formatter.parse(rawStart)).epochSecond
                } catch (e: Exception) {
                    0L
                }
                val endSec = try {
                    java.time.Instant.from(formatter.parse(rawEnd)).epochSecond
                } catch (e: Exception) {
                    nowSec + 7200L
                }
                
                if (nowSec >= endSec) {
                    database.configDao().insertConfig(ConfigEntity("voting_open", "0"))
                } else if (nowSec >= startSec) {
                    database.configDao().insertConfig(ConfigEntity("voting_open", "1"))
                }
                
                Pair(rawStart, rawEnd)
            }
            
            // Safe main-thread UI state mutations
            configStartTimeText = updatedStart
            configEndTimeText = updatedEnd
            startInputText = updatedStart
            endInputText = updatedEnd
            
            Toast.makeText(context, "Temporal configurations successfully saved!", Toast.LENGTH_SHORT).show()
            VotingServer.addLog("ADMIN OVERLAY: Modified voting window settings. Start: '$startStr', End: '$endStr'")
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateSurface)
            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "State Overrides & Reset Engine",
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                color = SlateWhite,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                text = "Simulate all double-submission exceptions, rate limits, and time cutoff guards natively in SQLite.",
                fontSize = 12.sp,
                color = SlateGrey,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // Action card: Re-Seed database
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateBg),
                border = BorderStroke(1.dp, SlateBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "DATABASE SEED WORKER",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateBlue
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Parses voters.csv and management_passwords.csv from raw assets and structures database.",
                        fontSize = 12.sp,
                        color = SlateGrey
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (!isSeeding) {
                                isSeeding = true
                                onSeedTriggered()
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(3000)
                                    isSeeding = false
                                }
                            }
                        },
                        enabled = !isSeeding,
                        colors = ButtonDefaults.buttonColors(containerColor = SlateBlue),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text(if (isSeeding) "SEEDING..." else "RUN SEEDING TRANSACTION", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Action card: Set time cutoff overrides
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateBg),
                border = BorderStroke(1.dp, SlateBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "TEMPORAL SYSTEM OVERRIDES",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateBlue
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Customize the active window of the selection process. Format: YYYY-MM-DD HH:MM:SS (UTC)",
                        fontSize = 11.sp,
                        color = SlateGrey
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    Text(
                        text = "START TIME",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateBlue
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    OutlinedTextField(
                        value = startInputText,
                        onValueChange = { startInputText = it },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = SlateWhite, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SlateBlue,
                            unfocusedBorderColor = SlateBorder,
                            focusedContainerColor = SlateSurface,
                            unfocusedContainerColor = SlateSurface
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "END TIME",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateBlue
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    OutlinedTextField(
                        value = endInputText,
                        onValueChange = { endInputText = it },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = SlateWhite, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SlateBlue,
                            unfocusedBorderColor = SlateBorder,
                            focusedContainerColor = SlateSurface,
                            unfocusedContainerColor = SlateSurface
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    Button(
                        onClick = {
                            saveTemporalSettings(startInputText, endInputText)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SlateBlue),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("SAVE TEMPORAL SETTINGS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = SlateBorder)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "QUICK SHIFT PRESETS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateGrey
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Choice 1: Close Windows
                        Button(
                            onClick = {
                                if (!isOverriding) {
                                    isOverriding = true
                                    val now = Instant.now()
                                    val startIsoStr = now.minusSeconds(7200).toString() // 2 hours past
                                    val endIsoStr = now.minusSeconds(3600).toString() // 1 hour past
                                    val formattedStart = startIsoStr.replace("T", " ").substring(0, 19)
                                    val formattedEnd = endIsoStr.replace("T", " ").substring(0, 19)
                                    
                                    saveTemporalSettings(formattedStart, formattedEnd)
                                    
                                    coroutineScope.launch {
                                        kotlinx.coroutines.delay(2000)
                                        isOverriding = false
                                    }
                                }
                            },
                            enabled = !isOverriding,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            Text("FORCE CLOSED (Past)", fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }

                        // Choice 2: Re-open
                        Button(
                            onClick = {
                                if (!isOverriding) {
                                    isOverriding = true
                                    val formattedStart = "2026-05-01 00:00:00"
                                    val formattedEnd = "2026-08-01 14:00:00"
                                    
                                    saveTemporalSettings(formattedStart, formattedEnd)
                                    
                                    coroutineScope.launch {
                                        kotlinx.coroutines.delay(2000)
                                        isOverriding = false
                                    }
                                }
                            },
                            enabled = !isOverriding,
                            colors = ButtonDefaults.buttonColors(containerColor = SlateGreen),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            Text("FORCE OPEN (Future)", fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        // Info table: accounts preloaded
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateBg),
                border = BorderStroke(1.dp, SlateBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "PRE-CONFIGURED TEST PASSPORT CREDENTIALS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateGrey
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text("• Alice Smith (Voter S1-S5): Roll = 202601", fontSize = 12.sp, color = SlateWhite)
                    Text("• Bob Johnson (Manager / S1-S5 + Admin): Roll = 202602, Pass = pass1234", fontSize = 12.sp, color = SlateWhite)
                    Text("• Charlie Brown (Voter S1-S5): Roll = 202603", fontSize = 12.sp, color = SlateWhite)
                    Text("• Diana Prince (Manager / S1-S5 + Admin): Roll = 202604, Pass = adminSecure!", fontSize = 12.sp, color = SlateWhite)
                }
            }
        }
    }
}
