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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.example.db.DbDesignStats
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
    val database = remember { AppDatabase.getDatabase(context) }
    val serverPort by VotingServer.serverPortFlow.collectAsStateWithLifecycle()
    val serverReady by VotingServer.serverReadyFlow.collectAsStateWithLifecycle()

    // Screen State Management (0: Voter Station, 1: Admin Console)
    var selectedTab by remember { mutableStateOf(0) }
    var voterWebView by remember { mutableStateOf<WebView?>(null) }

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

                // Standard Material Theme Tabs Bar - Custom-configured names
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = SlateBlue,
                    divider = {}
                ) {
                    Tab(
                        selected = (selectedTab == 0),
                        onClick = { 
                            selectedTab = 0 
                            voterWebView?.loadUrl("javascript:returnToElectionList()")
                        },
                        text = {
                            Text(
                                text = "VOTING STATION",
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
                                text = "ADMIN PORTAL",
                                fontWeight = if (selectedTab == 1) FontWeight.ExtraBold else FontWeight.Medium,
                                fontSize = 12.sp,
                                color = if (selectedTab == 1) SlateBlue else SlateGrey
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
            // Persistence Voter WebView Container
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (selectedTab == 0) 1f else 0f
                        translationX = if (selectedTab == 0) 0f else 10000f
                    }
                    .border(
                        if (selectedTab == 0) 1.dp else 0.dp,
                        if (selectedTab == 0) SlateBorder else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                useWideViewPort = false
                                loadWithOverviewMode = false
                                textZoom = 100
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                allowFileAccess = true
                                allowContentAccess = true
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    return false
                                }
                            }
                            voterWebView = this
                        }
                    },
                    update = { view ->
                        if (serverReady && serverPort > 0) {
                            val targetUrl = "http://127.0.0.1:$serverPort"
                            val currentUrl = view.url
                            val needsLoad = currentUrl.isNullOrEmpty() || (!currentUrl.startsWith(targetUrl) && !currentUrl.startsWith("$targetUrl/"))
                            if (needsLoad) {
                                view.loadUrl(targetUrl)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Persistence Admin WebView Container
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (selectedTab == 1) 1f else 0f
                        translationX = if (selectedTab == 1) 0f else 10000f
                    }
                    .border(
                        if (selectedTab == 1) 1.dp else 0.dp,
                        if (selectedTab == 1) SlateBorder else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                useWideViewPort = false
                                loadWithOverviewMode = false
                                textZoom = 100
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                allowFileAccess = true
                                allowContentAccess = true
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    return false
                                }
                            }
                        }
                    },
                    update = { view ->
                        if (serverReady && serverPort > 0) {
                            val targetUrl = "http://127.0.0.1:$serverPort/admin.html"
                            val currentUrl = view.url
                            val needsLoad = currentUrl.isNullOrEmpty() || (!currentUrl.startsWith(targetUrl) && !currentUrl.startsWith("$targetUrl/"))
                            if (needsLoad) {
                                view.loadUrl(targetUrl)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
