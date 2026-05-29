package com.example.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.example.db.AppDatabase
import com.example.db.ConfigEntity
import com.example.db.ConfirmNonceEntity
import com.example.db.VoteEntity
import com.example.db.VotedRollEntity
import com.example.db.VoterEntity
import com.example.db.ElectionEntity
import com.example.db.DbDesignStats
import com.example.db.VotingOptionEntity
import com.example.db.DatabaseSeeder
import org.mindrot.jbcrypt.BCrypt
import androidx.room.withTransaction
import com.example.security.JwtHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

object VotingServer {
    private const val TAG = "VotingServer"
    private var server: HttpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val serverExecutor = Executors.newFixedThreadPool(8) // Multi-threaded executor prevents blocking/bottlenecking of parallel requests

    // Logs flow for display inside the Android App's dashboard console
    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow = _logsFlow.asStateFlow()

    // Active port allocation flow to handle dynamic backup bindings
    private val _serverPortFlow = MutableStateFlow(3000)
    val serverPortFlow = _serverPortFlow.asStateFlow()

    // Server readiness flow
    private val _serverReadyFlow = MutableStateFlow(false)
    val serverReadyFlow = _serverReadyFlow.asStateFlow()

    // Host configurations parsed from assets .env
    var port = 3000
    var jwtSecret = "super_secure_unpredictable_secret_key_64_characters_long"
    var votingEndTimeStr = "2026-08-01T14:00:00Z"

    // Rate Limit Trackers by IP and endpoint path
    private val rateLimitTrackers = ConcurrentHashMap<String, RateLimitTracker>()

    fun addLog(message: String) {
        val timestamp = Instant.now().toString().substring(11, 19)
        Log.i(TAG, "[$timestamp] $message")
        synchronized(_logsFlow) {
            val list = _logsFlow.value.toMutableList()
            list.add("[$timestamp] $message")
            if (list.size > 200) list.removeAt(0)
            _logsFlow.value = list
        }
    }

    fun clearLogs() {
        synchronized(_logsFlow) {
            _logsFlow.value = emptyList()
        }
        addLog("SYSTEM TRANSACTION LOGGER: Session logs purged by operator.")
    }

    class RateLimitTracker {
        private val timestamps = ConcurrentLinkedQueue<Long>()
        fun allow(limit: Int): Boolean {
            val now = System.currentTimeMillis()
            while (!timestamps.isEmpty() && now - timestamps.peek() > 60000) {
                timestamps.poll()
            }
            if (timestamps.size >= limit) return false
            timestamps.add(now)
            return true
        }
    }

    private fun checkRateLimit(ip: String, path: String, limit: Int): Boolean {
        val key = "$ip:$path"
        val tracker = rateLimitTrackers.getOrPut(key) { RateLimitTracker() }
        return tracker.allow(limit)
    }

    fun start(context: Context) {
        if (server != null) return
        
        scope.launch {
            loadEnv(context)
            val database = AppDatabase.getDatabase(context)

            var attempts = 0
            val maxAttempts = 10
            var boundSuccessfully = false
            val startPort = port

            while (attempts < maxAttempts && !boundSuccessfully) {
                val currentPort = startPort + attempts
                try {
                    val candidateServer = HttpServer.create(InetSocketAddress(currentPort), 0).apply {
                        executor = serverExecutor
                        createContext("/", RequestHandler(context, database))
                        start()
                    }
                    server = candidateServer
                    port = currentPort
                    _serverPortFlow.value = currentPort
                    _serverReadyFlow.value = true
                    boundSuccessfully = true
                    addLog("Server successfully started on port $port")
                    addLog("Trust Proxy headers enabled. WAL Database journal is active.")
                } catch (e: Exception) {
                    attempts++
                    Log.w(TAG, "Port $currentPort already in use or bind failed: ${e.message}, trying next...")
                }
            }
            if (!boundSuccessfully) {
                addLog("FATAL: Failed to launch HttpServer on any port from $startPort to ${startPort + maxAttempts - 1}")
            }
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
        _serverReadyFlow.value = false
        addLog("Server stopped.")
    }

    private fun loadEnv(context: Context) {
        try {
            val file = File(context.filesDir, ".env")
            val contents = if (file.exists()) {
                file.readText()
            } else {
                context.assets.open(".env").use { it.reader().readText() }
            }
            
            contents.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        when (key) {
                            "PORT" -> port = value.toIntOrNull() ?: 3000
                            "JWT_SECRET" -> jwtSecret = value
                            "VOTING_END_TIME" -> votingEndTimeStr = value
                        }
                    }
                }
            }
            addLog("Loaded .env settings. Port=$port, EndTime=$votingEndTimeStr")
        } catch (e: Exception) {
            addLog("WARNING: .env not found or unreadable, using hardcoded fallbacks.")
        }
    }

    private class RequestHandler(
        private val context: Context,
        private val db: AppDatabase
    ) : HttpHandler {

        private val rollPattern = Regex("^[a-zA-Z0-9_\\-]+$")
        private val designFilePattern = Regex("^[1-5]_(front|back)\\.jpg$")

        override fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod

            // Extract client identity tracing IP (Trust Proxy simulation)
            val ip = exchange.requestHeaders.getFirst("X-Forwarded-For")
                ?: exchange.requestHeaders.getFirst("X-Real-IP")
                ?: exchange.remoteAddress.address.hostAddress ?: "127.0.0.1"

            // Precedence handling CORs pre-flight
            if (method == "OPTIONS") {
                sendResponse(exchange, 204, "")
                return
            }

            // Static assets request pre-routing gate for designs
            if (path.startsWith("/static/designs/")) {
                val filename = path.substringAfter("/static/designs/")
                if (!designFilePattern.matches(filename)) {
                    addLog("SECURITY DENIED: Raw path file pattern violation for '$filename' from IP $ip")
                    sendResponse(exchange, 404, "{\"error\": \"Asset layout access denied\"}")
                    return
                }
                
                // Serve dynamic vector bitmap design file
                serveDesignImage(exchange, filename)
                return
            }

            // Serve normal static assets
            if (method == "GET" && (path == "/" || path == "/index.html" || path == "/admin" || path == "/admin.html" || path == "/styles.css")) {
                serveStaticFile(exchange, path)
                return
            }

            // Route execution
            when (path) {
                "/status" -> {
                    if (method == "GET") handleStatus(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/api/elections" -> {
                    if (method == "GET") handleElectionsList(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/auth/verify" -> {
                    if (method == "POST") {
                        if (!checkRateLimit(ip, "/auth/verify", 20)) {
                            addLog("RATE_LIMIT: /auth/verify limit hit by IP $ip")
                            sendResponse(exchange, 429, "Too many login attempts")
                            return
                        }
                        handleVerify(exchange)
                    } else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/auth/confirm" -> {
                    if (method == "POST") {
                        if (!checkRateLimit(ip, "/auth/confirm", 10)) {
                            addLog("RATE_LIMIT: /auth/confirm limit hit by IP $ip")
                            sendResponse(exchange, 429, "Verification processing busy")
                            return
                        }
                        handleConfirm(exchange)
                    } else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/admin/login" -> {
                    if (method == "POST") {
                        if (!checkRateLimit(ip, "/admin/login", 5)) {
                            addLog("RATE_LIMIT: /admin/login brute block enabled by IP $ip")
                            sendResponse(exchange, 429, "Brute force block active")
                            return
                        }
                        handleAdminLogin(exchange)
                    } else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/votes/submit" -> {
                    if (method == "POST") {
                        if (!checkRateLimit(ip, "/votes/submit", 5)) {
                            addLog("RATE_LIMIT: /votes/submit rate exceed by IP $ip")
                            sendResponse(exchange, 429, "Submission rate exceeded")
                            return
                        }
                        handleSubmitVotes(exchange)
                    } else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/auth/logout" -> {
                    if (method == "POST" || method == "GET") {
                        clearCookieSession(exchange)
                    } else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/admin/status" -> {
                    if (method == "GET") handleAdminStatus(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/admin/voters-voted" -> {
                    if (method == "GET") handleAdminVotersVoted(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/admin/vote-stats" -> {
                    if (method == "GET") handleAdminVoteStats(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/admin/results" -> {
                    if (method == "GET") handleAdminResults(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/admin/elections/create" -> {
                    if (method == "POST") handleAdminCreateElection(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/admin/elections/delete" -> {
                    if (method == "POST") handleAdminDeleteElection(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/admin/elections/adjust-time" -> {
                    if (method == "POST") handleAdminAdjustTime(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/api/elections/options" -> {
                    if (method == "GET") handleGetElectionsOptions(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/admin/elections/options" -> {
                    if (method == "POST") handlePostElectionsOptions(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/admin/elections/options/delete" -> {
                    if (method == "POST") handleDeleteElectionOption(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                "/admin/reseed" -> {
                    if (method == "POST") handleAdminReseed(exchange)
                    else sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}")
                }
                else -> {
                    sendResponse(exchange, 404, "{\"error\":\"Not Found\"}")
                }
            }
        }

        private fun readBody(exchange: HttpExchange): String {
            return exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        private fun isSocketCloseException(e: Throwable): Boolean {
            return e is java.io.IOException && (
                e is java.net.SocketException ||
                e.message?.contains("Broken pipe", ignoreCase = true) == true ||
                e.message?.contains("Socket closed", ignoreCase = true) == true ||
                e.message?.contains("Connection reset", ignoreCase = true) == true ||
                e.message?.contains("shutdown", ignoreCase = true) == true ||
                e.message?.contains("Closed", ignoreCase = true) == true
            )
        }

        private fun sendResponse(exchange: HttpExchange, statusCode: Int, body: String, contentType: String = "application/json") {
            try {
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", contentType)
                exchange.responseHeaders.set("Access-Control-Allow-Origin", exchange.requestHeaders.getFirst("Origin") ?: "*")
                exchange.responseHeaders.set("Access-Control-Allow-Credentials", "true")
                exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Authorization, Cookie")
                exchange.responseHeaders.set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                exchange.responseHeaders.set("Pragma", "no-cache")
                exchange.responseHeaders.set("Expires", "0")
                exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (e: Exception) {
                if (isSocketCloseException(e)) {
                    Log.d(TAG, "Client closed connection before response could be fully written: ${e.message}")
                } else {
                    Log.e(TAG, "Error writing HTTP response", e)
                }
            }
        }

        private fun getCookieValue(exchange: HttpExchange, name: String): String? {
            val cookiesHeader = exchange.requestHeaders.getFirst("Cookie") ?: return null
            return cookiesHeader.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter("$name=")
        }

        private fun verifyUser(exchange: HttpExchange): JwtHelper.Claims? {
            // Check auth cookie `token`
            val token = getCookieValue(exchange, "token") ?: exchange.requestHeaders.getFirst("Authorization")?.substringAfter("Bearer ")?.trim()
            if (token.isNullOrEmpty()) return null
            return JwtHelper.verifyToken(token, jwtSecret)
        }

        private suspend fun getElectionWindowSettings(electionId: Long): Pair<Boolean, Long> {
            val election = db.electionDao().getElectionById(electionId) ?: return Pair(false, 0L)
            val nowSeconds = Instant.now().epochSecond
            val remaining = maxOf(0L, election.endsAt - nowSeconds)
            val finalOpenStatus = remaining > 0
            return Pair(finalOpenStatus, remaining)
        }

        private fun handleElectionsList(exchange: HttpExchange) = runBlocking {
            try {
                val elections = db.electionDao().getAllElections()
                val nowSeconds = Instant.now().epochSecond
                val jsonArr = JSONArray()
                for (el in elections) {
                    val remaining = maxOf(0L, el.endsAt - nowSeconds)
                    val item = JSONObject().apply {
                        put("id", el.id)
                        put("title", el.title)
                        put("ends_at", el.endsAt)
                        put("created_at", el.createdAt)
                        put("time_remaining_seconds", remaining)
                        put("voting_open", remaining > 0)
                        
                        val voteCount = db.votedRollDao().getVotedRollsCount(el.id)
                        put("voted_count", voteCount)
                    }
                    jsonArr.put(item)
                }
                sendResponse(exchange, 200, jsonArr.toString())
            } catch (e: Exception) {
                sendResponse(exchange, 500, "{\"error\": \"Internal server error\"}")
            }
        }

        // POST /auth/verify -> {name, is_management, confirm_nonce}
        private fun handleVerify(exchange: HttpExchange) = runBlocking {
            try {
                val body = readBody(exchange)
                val json = JSONObject(body)
                val roll = json.optString("roll", "").trim()
                val electionId = json.optLong("election_id", -1L)

                // 1. Sanitization validation
                if (roll.isEmpty() || !rollPattern.matches(roll) || roll.length > 30) {
                    addLog("INPUT SANITIZATION DENIED: Alphanumeric mismatch pattern for roll '$roll'")
                    sendResponse(exchange, 400, "{\"error\": \"Valid alphanumeric roll ID required\"}")
                    return@runBlocking
                }

                // 2. Deterministic Inline Garbage Sweeper
                val currentTimeSec = System.currentTimeMillis() / 1000
                val deletedNoncesCount = db.confirmNonceDao().deleteExpiredNonces(currentTimeSec)
                if (deletedNoncesCount > 0) {
                    addLog("GARBAGE SWEEPER: Swept and released $deletedNoncesCount expired confirm_nonce objects.")
                }

                // 3. Query voters
                val voter = db.voterDao().getVoter(roll)
                if (voter == null) {
                    addLog("AUTH DISMISSED: Missing record card profile parameters for roll '$roll'")
                    sendResponse(exchange, 404, "{\"error\": \"Profile not found in registry\"}")
                    return@runBlocking
                }

                if (electionId == -1L) {
                    val responseJson = JSONObject().apply {
                        put("name", voter.name)
                        put("is_management", voter.isManagement == 1)
                    }
                    sendResponse(exchange, 200, responseJson.toString())
                    return@runBlocking
                }

                val election = db.electionDao().getElectionById(electionId)
                if (election == null) {
                    sendResponse(exchange, 404, "{\"error\": \"Selected election not found in database\"}")
                    return@runBlocking
                }
                if (voter == null) {
                    addLog("AUTH DISMISSED: Missing record card profile parameters for roll '$roll'")
                    sendResponse(exchange, 404, "{\"error\": \"Profile not found in registry\"}")
                    return@runBlocking
                }

                // 4. Scan voted_rolls
                val alreadyVoted = db.votedRollDao().getVotedRoll(roll, electionId)
                if (alreadyVoted != null) {
                     addLog("DUPLICATE SIGNATURE ARRESTED: Roll signature has already voted in election $electionId: $roll")
                     sendResponse(exchange, 409, "{\"error\": \"You have already voted in this election\"}")
                     return@runBlocking
                }

                // 5. Query temporal conditions
                val (isOpen, _) = getElectionWindowSettings(electionId)
                if (!isOpen) {
                    addLog("TEMPORAL RESTRICTION ENFORCED: Roll verification rejected. Voting matches closed criteria.")
                    sendResponse(exchange, 403, "{\"error\": \"Voting window has closed for this election\"}")
                    return@runBlocking
                }

                // 6. Generate cryptographic nonce token matching standard secure hex
                val secureNonce = java.util.UUID.randomUUID().toString().replace("-", "") +
                        java.util.UUID.randomUUID().toString().replace("-", "")
                
                db.confirmNonceDao().insertConfirmNonce(
                    ConfirmNonceEntity(
                        nonce = secureNonce,
                        roll = roll,
                        electionId = electionId,
                        createdAt = currentTimeSec,
                        expiresAt = currentTimeSec + 300 // Absolute 5-minute lifecycle limits
                    )
                )

                addLog("VERIFIED SUCCESS: Generated confirm_nonce verification token for voter ${voter.name} ($roll)")

                val responseJson = JSONObject().apply {
                    put("name", voter.name)
                    put("is_management", voter.isManagement == 1)
                    put("confirm_nonce", secureNonce)
                }

                sendResponse(exchange, 200, responseJson.toString())
            } catch (e: Exception) {
                addLog("VERIFY ERROR: Exception caught: ${e.message}")
                sendResponse(exchange, 500, "{\"error\": \"Internal server error\"}")
            }
        }

        // POST /auth/confirm -> Sets secure session cookie + JWT
        private fun handleConfirm(exchange: HttpExchange) = runBlocking {
            try {
                val body = readBody(exchange)
                val json = JSONObject(body)
                val nonce = json.optString("confirm_nonce", "").trim()

                if (nonce.isEmpty()) {
                    sendResponse(exchange, 400, "{\"error\": \"Missing confirm_nonce payload token parameters\"}")
                    return@runBlocking
                }

                // 1. Lookup confirm_nonces
                val nonceRecord = db.confirmNonceDao().getConfirmNonce(nonce)
                val currentTimeSec = System.currentTimeMillis() / 1000
                if (nonceRecord == null || nonceRecord.expiresAt < currentTimeSec) {
                    addLog("CONFIRM DENIED: Provided nonce '$nonce' is invalid or expired.")
                    sendResponse(exchange, 401, "{\"error\": \"Verification state expired, please restart identification process\"}")
                    return@runBlocking
                }

                val electionId = nonceRecord.electionId

                // 2. Clear token from database immediately to guarantee single-use boundaries
                db.confirmNonceDao().deleteConfirmNonce(nonce)

                // 3. Validate database time boundaries
                val (isOpen, _) = getElectionWindowSettings(electionId)
                if (!isOpen) {
                    addLog("TEMPORAL CONFIRM DENIED: Voting window closed prior to confirmation.")
                    sendResponse(exchange, 403, "{\"error\": \"Voting window has closed\"}")
                    return@runBlocking
                }

                // 4. TOCTOU check against voted_rolls
                val alreadyVoted = db.votedRollDao().getVotedRoll(nonceRecord.roll, electionId)
                if (alreadyVoted != null) {
                    sendResponse(exchange, 409, "{\"error\": \"You have already voted in this election\"}")
                    return@runBlocking
                }

                // Retrieve voter info
                val voter = db.voterDao().getVoter(nonceRecord.roll) ?: VoterEntity(nonceRecord.roll, "Anonymous", 0)

                // 5. Generate and sign a cookie-wrapped JWT holding {roll, is_management, election_id}
                val election = db.electionDao().getElectionById(electionId)
                val expiryTime = election?.endsAt ?: (currentTimeSec + 7200)
                val maxAgeSeconds = maxOf(0L, expiryTime - currentTimeSec)

                val token = JwtHelper.createToken(voter.roll, voter.isManagement == 1, expiryTime, electionId, jwtSecret)

                addLog("CONFIRM SUCCESS: Issued JWT authentication token for ${voter.name} (${voter.roll})")

                // Set HttpOnly, SameSite=Strict cookie wrapper
                exchange.responseHeaders.add(
                    "Set-Cookie",
                    "token=$token; Path=/; Max-Age=$maxAgeSeconds; HttpOnly; SameSite=Strict"
                )

                val responseJson = JSONObject().apply {
                    put("ok", true)
                    put("name", voter.name)
                    put("roll", voter.roll)
                    put("is_management", voter.isManagement == 1)
                    put("token", token)
                }
                sendResponse(exchange, 200, responseJson.toString())
            } catch (e: Exception) {
                addLog("CONFIRM EXCEPTION: ${e.message}")
                sendResponse(exchange, 500, "{\"error\": \"Internal server error\"}")
            }
        }

        // POST /admin/login
        private fun handleAdminLogin(exchange: HttpExchange) = runBlocking {
            try {
                val body = readBody(exchange)
                val json = JSONObject(body)
                val roll = json.optString("roll", "").trim()
                val password = json.optString("password", "")

                if (roll.isEmpty() || !rollPattern.matches(roll)) {
                    sendResponse(exchange, 400, "{\"error\": \"Valid alphanumeric roll ID required\"}")
                    return@runBlocking
                }

                // 1. Fetch management hash
                val adminAuth = db.managementAuthDao().getManagementAuth(roll)
                if (adminAuth == null) {
                    addLog("ADMIN ACCESS REFUSED: Admin authorization details absent for $roll")
                    sendResponse(exchange, 401, "{\"error\": \"Invalid administration credentials\"}")
                    return@runBlocking
                }

                // 2. Perform Blowfish BCrypt verification
                val matched = BCrypt.checkpw(password, adminAuth.passwordHash)
                if (!matched) {
                    addLog("ADMIN ACCESS REFUSED: Password verification mismatch for $roll")
                    sendResponse(exchange, 401, "{\"error\": \"Invalid administration credentials\"}")
                    return@runBlocking
                }

                addLog("ADMIN ACCESS GRANTED: Successfully authorized $roll as Admin controller.")

                // Issue admin JWT
                val currentTimeSec = System.currentTimeMillis() / 1000
                val jwtExpiryTime = currentTimeSec + 7200 // 2-hour admin access limits
                val token = JwtHelper.createToken(roll, true, jwtExpiryTime, null, jwtSecret)

                exchange.responseHeaders.add(
                    "Set-Cookie",
                    "token=$token; Path=/; Max-Age=7200; HttpOnly; SameSite=Strict"
                )

                val responseJson = JSONObject().apply {
                    put("ok", true)
                    put("is_management", true)
                }
                sendResponse(exchange, 200, responseJson.toString())
            } catch (e: Exception) {
                addLog("ADMIN LOGIN ERROR: ${e.message}")
                sendResponse(exchange, 500, "{\"error\": \"Internal processing fault\"}")
            }
        }

        // POST /votes/submit -> atomic Room transaction blocks
        private fun handleSubmitVotes(exchange: HttpExchange) = runBlocking {
            try {
                val claims = verifyUser(exchange)
                if (claims == null) {
                    sendResponse(exchange, 401, "{\"error\": \"Session has expired or credentials invalid\"}")
                    return@runBlocking
                }

                val electionId = claims.electionId
                if (electionId == null) {
                    sendResponse(exchange, 400, "{\"error\": \"Invalid session: matching election not identified\"}")
                    return@runBlocking
                }

                val body = readBody(exchange)
                val json = JSONObject(body)
                val votesArray = json.optJSONArray("votes")

                val customOptions = db.votingOptionDao().getOptionsForElection(electionId)
                val requiredCount = if (customOptions.isEmpty()) 5 else customOptions.size

                if (votesArray == null || votesArray.length() != requiredCount) {
                    sendResponse(exchange, 400, "{\"error\": \"Invalid layout counts. Must specify exactly $requiredCount designs\"}")
                    return@runBlocking
                }

                val nowSeconds = System.currentTimeMillis() / 1000

                // Validate votes format
                val votesList = mutableListOf<VoteEntity>()
                for (i in 0 until requiredCount) {
                    val vObj = votesArray.getJSONObject(i)
                    val designId = vObj.getInt("design_id")
                    val choice = vObj.getString("choice").trim()
                    if (designId !in 1..requiredCount || choice.isEmpty()) {
                        sendResponse(exchange, 400, "{\"error\": \"Invalid layout constraints on design choices\"}")
                        return@runBlocking
                    }
                    votesList.add(VoteEntity(id = 0, roll = claims.roll, electionId = electionId, designId = designId, choice = choice, submittedAt = nowSeconds))
                }

                // Check temporal ceiling limits
                val (isOpen, _) = getElectionWindowSettings(electionId)
                if (!isOpen) {
                    sendResponse(exchange, 403, "{\"error\": \"Voting window has closed\"}")
                    return@runBlocking
                }

                // Wrap inside transactional database structure
                var stateConflict = false
                try {
                    db.withTransaction {
                        // 1. Verify TOCTOU double-vote check again inside isolated block
                        val exists = db.votedRollDao().getVotedRoll(claims.roll, electionId)
                        if (exists != null) {
                            stateConflict = true
                            throw IllegalStateException("ALREADY_VOTED")
                        }

                        // 2. Insert Safety Log Block locks boundary immediately
                        db.votedRollDao().insertVotedRoll(VotedRollEntity(claims.roll, electionId, nowSeconds))

                        // 3. Write individual votes data
                        for (v in votesList) {
                            db.voteDao().insertVote(v)
                        }
                    }
                } catch (e: Exception) {
                    if (e.message == "ALREADY_VOTED" || e.message?.contains("CONSTRAINT") == true) {
                        stateConflict = true
                    } else {
                        throw e
                    }
                }

                if (stateConflict) {
                    addLog("TRANSACTION ABORTED: Roll ${claims.roll} double voting attempt blocked.")
                    sendResponse(exchange, 409, "{\"error\": \"Vote already tracked for this roll in this election\"}")
                    return@runBlocking
                }

                addLog("TRANSACTION SUCCESS: Voter ${claims.roll} submitted 5 layout cards successfully for election $electionId!")

                // Kill session cookie immediately on successful submission
                exchange.responseHeaders.add(
                    "Set-Cookie",
                    "token=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"
                )

                sendResponse(exchange, 200, "{\"ok\": true}")
            } catch (e: Exception) {
                addLog("SUBMIT VOTE EXCEPTION: ${e.message}")
                sendResponse(exchange, 500, "{\"error\": \"Internal processing fault\"}")
            }
        }

        // GET /status
        private fun handleStatus(exchange: HttpExchange) = runBlocking {
            val query = exchange.requestURI.query ?: ""
            val electionId = query.split("&")
                .firstOrNull { it.startsWith("election_id=") }
                ?.substringAfter("election_id=")?.toLongOrNull() ?: db.electionDao().getAllElections().firstOrNull()?.id ?: -1L

            if (electionId == -1L) {
                val json = JSONObject().apply {
                    put("voting_open", false)
                    put("time_remaining_seconds", 0)
                }
                sendResponse(exchange, 200, json.toString())
                return@runBlocking
            }

            val (isOpen, remaining) = getElectionWindowSettings(electionId)
            val json = JSONObject().apply {
                put("voting_open", isOpen)
                put("time_remaining_seconds", remaining)
            }
            sendResponse(exchange, 200, json.toString())
        }

        // GET /admin/status
        private fun handleAdminStatus(exchange: HttpExchange) = runBlocking {
            val claims = verifyUser(exchange)
            if (claims == null || !claims.isManagement) {
                sendResponse(exchange, 403, "{\"error\": \"Access denied\"}")
                return@runBlocking
            }

            val query = exchange.requestURI.query ?: ""
            val electionId = query.split("&")
                .firstOrNull { it.startsWith("election_id=") }
                ?.substringAfter("election_id=")?.toLongOrNull() ?: db.electionDao().getAllElections().firstOrNull()?.id ?: -1L

            val election = db.electionDao().getElectionById(electionId)
            val totalVotersCount = db.voterDao().getVotersCount()
            val votedCount = if (electionId != -1L) db.votedRollDao().getVotedRollsCount(electionId) else 0

            val nowSeconds = java.time.Instant.now().epochSecond
            val remaining = if (election != null) maxOf(0L, election.endsAt - nowSeconds) else 0L
            val isOpen = remaining > 0

            val json = JSONObject().apply {
                put("election_id", electionId)
                put("election_title", election?.title ?: "No Active Election")
                put("voting_open", isOpen)
                put("time_remaining_seconds", remaining)
                put("total_voters", totalVotersCount)
                put("voted_count", votedCount)
                put("ends_at", election?.endsAt ?: 0L)
                put("server_time", nowSeconds)
            }
            sendResponse(exchange, 200, json.toString())
        }

        // GET /admin/voters-voted?page=1
        private fun handleAdminVotersVoted(exchange: HttpExchange) = runBlocking {
            val claims = verifyUser(exchange)
            if (claims == null || !claims.isManagement) {
                sendResponse(exchange, 403, "{\"error\": \"Access denied\"}")
                return@runBlocking
            }

            val query = exchange.requestURI.query ?: ""
            val pageVal = query.split("&")
                .firstOrNull { it.startsWith("page=") }
                ?.substringAfter("page=")?.toIntOrNull() ?: 1

            val electionId = query.split("&")
                .firstOrNull { it.startsWith("election_id=") }
                ?.substringAfter("election_id=")?.toLongOrNull() ?: db.electionDao().getAllElections().firstOrNull()?.id ?: -1L

            val pageSize = 50
            val offset = (pageVal - 1) * pageSize

            val votedRolls = if (electionId != -1L) db.votedRollDao().getVotedRollsPaginated(electionId, pageSize, offset) else emptyList()
            val totalCount = if (electionId != -1L) db.votedRollDao().getVotedRollsCount(electionId) else 0

            val dataArray = JSONArray().apply {
                for (r in votedRolls) {
                    put(JSONObject().apply {
                        put("roll", r.roll)
                        put("voted_at", r.votedAt)
                    })
                }
            }

            val json = JSONObject().apply {
                put("data", dataArray)
                put("total", totalCount)
                put("page", pageVal)
                put("page_size", pageSize)
            }
            sendResponse(exchange, 200, json.toString())
        }

        private suspend fun getElectionStatsProgrammatic(electionId: Long): List<DbDesignStats> {
            if (electionId == -1L) return emptyList()
            val customOptions = db.votingOptionDao().getOptionsForElection(electionId)
            if (customOptions.isEmpty()) {
                return db.voteDao().getVoteStats(electionId)
            }
            val votes = db.voteDao().getVotesForElection(electionId)
            return customOptions.mapIndexed { index, option ->
                val designId = index + 1
                val optionVotes = votes.filter { it.designId == designId }
                
                var firstChoice = "yes"
                var secondChoice = "no"
                try {
                    val parsed = org.json.JSONObject(option.optionText)
                    val choices = parsed.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) firstChoice = choices.getString(0)
                    if (choices != null && choices.length() > 1) secondChoice = choices.getString(1)
                } catch (e: Exception) {}

                val yesVotes = optionVotes.count { 
                    val c = it.choice.lowercase()
                    c == "yes" || c.startsWith("yes") || c == firstChoice.lowercase() || c == "agree" || c == "approve" || c == "approved"
                }
                val noVotes = optionVotes.count { 
                    val c = it.choice.lowercase()
                    c == "no" || c.startsWith("no") || c == secondChoice.lowercase() || c == "disagree" || c == "reject" || c == "rejected"
                }
                DbDesignStats(
                    design_id = designId,
                    yes_votes = yesVotes,
                    no_votes = noVotes,
                    total_votes = optionVotes.size
                )
            }
        }

        // GET /admin/vote-stats
        private fun handleAdminVoteStats(exchange: HttpExchange) = runBlocking {
            val claims = verifyUser(exchange)
            if (claims == null || !claims.isManagement) {
                sendResponse(exchange, 403, "{\"error\": \"Access denied\"}")
                return@runBlocking
            }

            val query = exchange.requestURI.query ?: ""
            val electionId = query.split("&")
                .firstOrNull { it.startsWith("election_id=") }
                ?.substringAfter("election_id=")?.toLongOrNull() ?: db.electionDao().getAllElections().firstOrNull()?.id ?: -1L

            val customOptions = db.votingOptionDao().getOptionsForElection(electionId)
            val votes = db.voteDao().getVotesForElection(electionId)
            val stats = getElectionStatsProgrammatic(electionId)
            val statsArray = JSONArray().apply {
                for (s in stats) {
                    val option = customOptions.getOrNull(s.design_id - 1)
                    val breakdown = JSONObject()
                    if (option != null) {
                        try {
                            val parsed = org.json.JSONObject(option.optionText)
                            val choices = parsed.optJSONArray("choices")
                            if (choices != null) {
                                val optionVotes = votes.filter { it.designId == s.design_id }
                                for (i in 0 until choices.length()) {
                                    val ch = choices.getString(i)
                                    val count = optionVotes.count { it.choice.lowercase() == ch.lowercase() }
                                    breakdown.put(ch, count)
                                }
                            }
                        } catch (e: Exception) {}
                    }
                    put(JSONObject().apply {
                        put("design_id", s.design_id)
                        put("yes_votes", s.yes_votes)
                        put("no_votes", s.no_votes)
                        put("total_votes", s.total_votes)
                        if (breakdown.length() > 0) {
                            put("choices_breakdown", breakdown)
                        }
                    })
                }
            }

            val json = JSONObject().apply {
                put("stats", statsArray)
            }
            sendResponse(exchange, 200, json.toString())
        }

        // GET /admin/results  - locked until threshold matches end time limit
        private fun handleAdminResults(exchange: HttpExchange) = runBlocking {
            val claims = verifyUser(exchange)
            if (claims == null || !claims.isManagement) {
                sendResponse(exchange, 403, "{\"error\": \"Access denied\"}")
                return@runBlocking
            }

            val query = exchange.requestURI.query ?: ""
            val electionId = query.split("&")
                .firstOrNull { it.startsWith("election_id=") }
                ?.substringAfter("election_id=")?.toLongOrNull() ?: db.electionDao().getAllElections().firstOrNull()?.id ?: -1L

            val remaining = if (electionId != -1L) {
                val election = db.electionDao().getElectionById(electionId)
                if (election != null) maxOf(0L, election.endsAt - java.time.Instant.now().epochSecond) else 0L
            } else 0L

            if (remaining > 0) {
                // If requested prior to termination bounds, return 403 with descriptive error
                sendResponse(exchange, 403, "{\"error\": \"Results not yet available\"}")
                return@runBlocking
            }

            // Calculations panel compiled based on:
            // score = yes_votes - no_votes descending
            // handling ties with ascending design_id secondary lookup
            val customOptions = db.votingOptionDao().getOptionsForElection(electionId)
            val votes = db.voteDao().getVotesForElection(electionId)
            val stats = getElectionStatsProgrammatic(electionId)

            val ranked = stats.map { s ->
                val score = s.yes_votes - s.no_votes
                RankedResult(s.design_id, s.yes_votes, s.no_votes, s.total_votes, score)
            }.sortedWith(
                compareByDescending<RankedResult> { it.score }
                    .thenBy { it.designId } // tie break with ascending ID
            )

            val resultsArray = JSONArray().apply {
                for (r in ranked) {
                    val option = customOptions.getOrNull(r.designId - 1)
                    val breakdown = JSONObject()
                    if (option != null) {
                        try {
                            val parsed = org.json.JSONObject(option.optionText)
                            val choices = parsed.optJSONArray("choices")
                            if (choices != null) {
                                val optionVotes = votes.filter { it.designId == r.designId }
                                for (i in 0 until choices.length()) {
                                    val ch = choices.getString(i)
                                    val count = optionVotes.count { it.choice.lowercase() == ch.lowercase() }
                                    breakdown.put(ch, count)
                                }
                            }
                        } catch (e: Exception) {}
                    }
                    put(JSONObject().apply {
                        put("design_id", r.designId)
                        put("yes_votes", r.yesVotes)
                        put("no_votes", r.noVotes)
                        put("total_votes", r.totalVotes)
                        put("score", r.score)
                        if (breakdown.length() > 0) {
                            put("choices_breakdown", breakdown)
                        }
                    })
                }
            }

            sendResponse(exchange, 200, resultsArray.toString())
        }

        // POST /admin/elections/create
        private fun handleAdminCreateElection(exchange: HttpExchange) = runBlocking {
            val claims = verifyUser(exchange)
            if (claims == null || !claims.isManagement) {
                sendResponse(exchange, 403, "{\"error\": \"Access denied\"}")
                return@runBlocking
            }

            try {
                val body = readBody(exchange)
                val json = JSONObject(body)
                val title = json.optString("title", "").trim()
                val durationSeconds = json.optLong("duration_seconds", 0L)

                if (title.isEmpty() || durationSeconds <= 0L) {
                    sendResponse(exchange, 400, "{\"error\": \"Invalid title or duration\"}")
                    return@runBlocking
                }

                val nowSeconds = java.time.Instant.now().epochSecond
                val endsAt = nowSeconds + durationSeconds

                val newElectionId = db.electionDao().insertElection(
                    ElectionEntity(
                        title = title,
                        endsAt = endsAt,
                        createdAt = nowSeconds
                    )
                )

                addLog("ADMIN EVENT: Created new election '$title' with ID $newElectionId, ends in $durationSeconds seconds")
                sendResponse(exchange, 200, "{\"ok\": true, \"id\": $newElectionId}")
            } catch (e: Exception) {
                sendResponse(exchange, 500, "{\"error\": \"Failed to create election: ${e.message}\"}")
            }
        }

        // POST /admin/elections/adjust-time
        private fun handleAdminAdjustTime(exchange: HttpExchange) = runBlocking {
            val claims = verifyUser(exchange)
            if (claims == null || !claims.isManagement) {
                sendResponse(exchange, 403, "{\"error\": \"Access denied\"}")
                return@runBlocking
            }

            try {
                val body = readBody(exchange)
                val json = JSONObject(body)
                val electionId = json.optLong("election_id", -1L)
                val deltaSeconds = json.optLong("delta_seconds", 0L)
                val endsAt = json.optLong("ends_at", -1L)

                if (electionId == -1L || (deltaSeconds == 0L && endsAt == -1L)) {
                    sendResponse(exchange, 400, "{\"error\": \"Invalid parameters: must specify election_id and either delta_seconds or ends_at\"}")
                    return@runBlocking
                }

                val election = db.electionDao().getElectionById(electionId)
                if (election == null) {
                    sendResponse(exchange, 404, "{\"error\": \"Election not found\"}")
                    return@runBlocking
                }

                val nowSeconds = java.time.Instant.now().epochSecond

                if (endsAt != -1L) {
                    if (endsAt <= nowSeconds) {
                        sendResponse(exchange, 400, "{\"error\": \"The chosen end time must be in the future relative to the current server time.\"}")
                        return@runBlocking
                    }
                    election.endsAt = endsAt
                    addLog("ADMIN EVENT: Set exact end time of election ID $electionId to $endsAt")
                } else {
                    val currentRemaining = election.endsAt - nowSeconds
                    val baseTime = if (currentRemaining > 0) election.endsAt else nowSeconds
                    election.endsAt = maxOf(nowSeconds, baseTime + deltaSeconds)
                    addLog("ADMIN EVENT: Adjusted remaining time of election ID $electionId by $deltaSeconds seconds")
                }

                db.electionDao().updateElection(election)

                sendResponse(exchange, 200, "{\"ok\": true, \"new_ends_at\": ${election.endsAt}}")
            } catch (e: Exception) {
                sendResponse(exchange, 500, "{\"error\": \"Failed to adjust election time: ${e.message}\"}")
            }
        }

        // POST /admin/reseed
        private fun handleAdminReseed(exchange: HttpExchange) = runBlocking {
            val claims = verifyUser(exchange)
            if (claims == null || !claims.isManagement) {
                sendResponse(exchange, 403, "{\"error\": \"Access denied\"}")
                return@runBlocking
            }

            try {
                db.withTransaction {
                    db.voteDao().deleteAllVotes()
                    db.votedRollDao().deleteAllVotedRolls()
                }
                
                // Idempotently reload default csv files
                val success = DatabaseSeeder.seedDatabase(context, db, "2026-08-01 14:00:00")
                if (success) {
                    addLog("ADMIN EVENT: Database was successfully reseeded! Votes and voted rolls cleared, default registry reloaded.")
                    sendResponse(exchange, 200, "{\"ok\": true, \"message\": \"Database successfully reseeded.\"}")
                } else {
                    sendResponse(exchange, 500, "{\"error\": \"Idempotent seeding failed\"}")
                }
            } catch (e: Exception) {
                sendResponse(exchange, 500, "{\"error\": \"Reseed exception: ${e.message}\"}")
            }
        }

        private data class RankedResult(
            val designId: Int,
            val yesVotes: Int,
            val noVotes: Int,
            val totalVotes: Int,
            val score: Int
        )

        private fun clearCookieSession(exchange: HttpExchange) {
            exchange.responseHeaders.add(
                "Set-Cookie",
                "token=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"
            )
            sendResponse(exchange, 200, "{\"ok\": true}")
        }

        private fun serveStaticFile(exchange: HttpExchange, rawPath: String) {
            try {
                val pathStr = if (rawPath == "/") "index.html" else rawPath.trimStart('/')
                val assetPath = "web/$pathStr"
                
                context.assets.open(assetPath).use { stream ->
                    val bytes = stream.readBytes()
                    val contentType = when {
                        pathStr.endsWith(".html") -> "text/html"
                        pathStr.endsWith(".css") -> "text/css"
                        pathStr.endsWith(".js") -> "application/javascript"
                        else -> "text/plain"
                    }
                    exchange.responseHeaders.set("Content-Type", contentType)
                    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            } catch (e: Exception) {
                if (isSocketCloseException(e)) {
                    Log.d(TAG, "Client closed connection while serving static asset: $rawPath")
                } else {
                    Log.e(TAG, "Error serving static asset: $rawPath", e)
                    try {
                        sendResponse(exchange, 404, "File Not Found")
                    } catch (ignored: Exception) {}
                }
            }
        }

        private fun serveDesignImage(exchange: HttpExchange, filename: String) {
            try {
                // Parse layout matching format: 1_front.jpg
                val designId = filename.substringBefore('_').toIntOrNull() ?: 1
                val isBack = filename.contains("back")

                // Produce dynamic, attractive JPEG representation in-memory to save layout assets space
                val bitmap = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                
                // Draw cool cosmic slate visual design
                val themeSlate = Color.parseColor("#121824")
                val themeAccent = Color.parseColor("#3B82F6")
                
                canvas.drawColor(themeSlate)
                
                val pPaint = Paint().apply {
                    color = themeAccent
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }
                
                // Draw simple stylized layout represent t-shirt
                canvas.drawRoundRect(80f, 120f, 320f, 430f, 20f, 20f, pPaint)
                canvas.drawCircle(200f, 120f, 40f, pPaint) // Collar cutout
                
                // Text overlay description
                val tPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 24f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("DESIGN SCHEME #$designId", 200f, 240f, tPaint)
                
                val sideText = if (isBack) "REAR VIEW ASSETS" else "FRONT GRAPHIC VIEW"
                tPaint.textSize = 20f
                tPaint.color = Color.parseColor("#94A3B8")
                canvas.drawText(sideText, 200f, 280f, tPaint)

                // High visibility aesthetic stamp
                tPaint.color = themeAccent
                tPaint.textSize = 32f
                canvas.drawText("VOTE", 200f, 400f, tPaint)

                val outStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outStream)
                val bytes = outStream.toByteArray()

                exchange.responseHeaders.set("Content-Type", "image/jpeg")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                bitmap.recycle()
            } catch (e: Exception) {
                if (isSocketCloseException(e)) {
                    Log.d(TAG, "Client closed connection while serving design image: $filename")
                } else {
                    Log.e(TAG, "Error generating design asset visual representation", e)
                    try {
                        sendResponse(exchange, 404, "{\"error\": \"Design load failed\"}")
                    } catch (ignored: Exception) {}
                }
            }
        }

        // GET /api/elections/options
        private fun handleGetElectionsOptions(exchange: HttpExchange) = runBlocking {
            try {
                val query = exchange.requestURI.query ?: ""
                val electionId = query.split("&")
                    .firstOrNull { it.startsWith("election_id=") }
                    ?.substringAfter("election_id=")?.toLongOrNull()

                if (electionId == null) {
                    sendResponse(exchange, 400, "{\"error\": \"election_id required\"}")
                    return@runBlocking
                }

                val options = db.votingOptionDao().getOptionsForElection(electionId)
                val array = JSONArray().apply {
                    for (opt in options) {
                        put(JSONObject().apply {
                            put("id", opt.id)
                            put("election_id", opt.electionId)
                            put("option_text", opt.optionText)
                            put("image_data", opt.imageData)
                        })
                    }
                }
                sendResponse(exchange, 200, array.toString())
            } catch (e: Exception) {
                sendResponse(exchange, 500, "{\"error\": \"${e.message}\"}")
            }
        }

        // POST /admin/elections/options
        private fun handlePostElectionsOptions(exchange: HttpExchange) = runBlocking {
            val claims = verifyUser(exchange)
            if (claims == null || !claims.isManagement) {
                sendResponse(exchange, 403, "{\"error\": \"Access denied\"}")
                return@runBlocking
            }

            try {
                val body = readBody(exchange)
                val json = JSONObject(body)
                val electionId = json.getLong("election_id")
                val optionText = json.getString("option_text").trim()
                val imageData = json.getString("image_data")

                if (optionText.isEmpty()) {
                    sendResponse(exchange, 400, "{\"error\": \"Option text cannot be empty\"}")
                    return@runBlocking
                }

                val optionId = db.votingOptionDao().insertVotingOption(
                    VotingOptionEntity(
                        electionId = electionId,
                        optionText = optionText,
                        imageData = imageData
                    )
                )

                sendResponse(exchange, 200, "{\"ok\": true, \"id\": $optionId}")
            } catch (e: Exception) {
                sendResponse(exchange, 500, "{\"error\": \"Failed to add option: ${e.message}\"}")
            }
        }

        // POST /admin/elections/options/delete
        private fun handleDeleteElectionOption(exchange: HttpExchange) = runBlocking {
            val claims = verifyUser(exchange)
            if (claims == null || !claims.isManagement) {
                sendResponse(exchange, 403, "{\"error\": \"Access denied\"}")
                return@runBlocking
            }

            try {
                val body = readBody(exchange)
                val json = JSONObject(body)
                val optionId = json.getLong("id")

                db.votingOptionDao().deleteOptionById(optionId)
                sendResponse(exchange, 200, "{\"ok\": true}")
            } catch (e: Exception) {
                sendResponse(exchange, 500, "{\"error\": \"Failed to delete option: ${e.message}\"}")
            }
        }

        // POST /admin/elections/delete
        private fun handleAdminDeleteElection(exchange: HttpExchange) = runBlocking {
            val claims = verifyUser(exchange)
            if (claims == null || !claims.isManagement) {
                sendResponse(exchange, 403, "{\"error\": \"Access denied\"}")
                return@runBlocking
            }

            try {
                val body = readBody(exchange)
                val json = JSONObject(body)
                val electionId = json.getLong("election_id")
                val password = json.getString("password")

                val roll = claims.roll
                val adminAuth = db.managementAuthDao().getManagementAuth(roll)
                if (adminAuth == null) {
                    sendResponse(exchange, 401, "{\"error\": \"Invalid administration credentials\"}")
                    return@runBlocking
                }

                val matched = BCrypt.checkpw(password, adminAuth.passwordHash)
                if (!matched) {
                    sendResponse(exchange, 401, "{\"error\": \"Passcode authorization failed\"}")
                    return@runBlocking
                }

                db.electionDao().deleteElection(electionId)

                addLog("ADMIN EVENT: Admin $roll deleted election ID $electionId")
                sendResponse(exchange, 200, "{\"ok\": true}")
            } catch (e: Exception) {
                sendResponse(exchange, 500, "{\"error\": \"Failed to delete election: ${e.message}\"}")
            }
        }
    }
}
