package com.example.server

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.Executors

interface HttpHandler {
    @Throws(Exception::class)
    fun handle(exchange: HttpExchange)
}

class Headers : LinkedHashMap<String, MutableList<String>>() {
    fun getFirst(key: String): String? {
        val entry = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        return entry?.value?.firstOrNull()
    }

    fun set(key: String, value: String) {
        val entry = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        val list = entry?.value ?: mutableListOf<String>().also { this[key] = it }
        list.clear()
        list.add(value)
    }

    fun add(key: String, value: String) {
        val entry = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        val list = entry?.value ?: mutableListOf<String>().also { this[key] = it }
        list.add(value)
    }
}

class HttpExchange(
    private val socket: Socket,
    val requestURI: URI,
    val requestMethod: String,
    val requestHeaders: Headers,
    val requestBody: InputStream,
    val remoteAddress: InetSocketAddress
) {
    val responseHeaders = Headers()
    val responseBody: OutputStream = socket.getOutputStream()
    private var responseCode = -1
    private var responseLength = -1L
    private var headersSent = false

    @Throws(Exception::class)
    fun sendResponseHeaders(rCode: Int, responseLen: Long) {
        if (headersSent) return
        responseCode = rCode
        responseLength = responseLen

        try {
            val writer = responseBody.writer(Charsets.UTF_8)
            writer.write("HTTP/1.1 $rCode ${getStatusText(rCode)}\r\n")
            
            // Write standard custom or security headers
            for ((key, values) in responseHeaders) {
                for (v in values) {
                    writer.write("$key: $v\r\n")
                }
            }
            if (responseLen >= 0) {
                writer.write("Content-Length: $responseLen\r\n")
            }
            writer.write("Connection: close\r\n\r\n")
            writer.flush()
            headersSent = true
        } catch (e: Exception) {
            Log.e("HttpExchange", "Failed to write HTTP response headers", e)
            throw e
        }
    }

    private fun getStatusText(code: Int): String {
        return when (code) {
            200 -> "OK"
            204 -> "No Content"
            302 -> "Found"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            else -> "OK"
        }
    }
}

class LimitedInputStream(private val inner: InputStream, private val limit: Long) : InputStream() {
    private var bytesRead = 0L

    override fun read(): Int {
        if (bytesRead >= limit) return -1
        val result = inner.read()
        if (result != -1) {
            bytesRead++
        }
        return result
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bytesRead >= limit) return -1
        val maxLen = minOf(len.toLong(), limit - bytesRead).toInt()
        val result = inner.read(b, off, maxLen)
        if (result != -1) {
            bytesRead += result
        }
        return result
    }
}

class HttpServer private constructor(private val address: InetSocketAddress) {
    var executor: Executor = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false
    private val contexts = mutableMapOf<String, HttpHandler>()

    companion object {
        @JvmStatic
        fun create(addr: InetSocketAddress, backlog: Int): HttpServer {
            return HttpServer(addr)
        }
    }

    fun createContext(path: String, handler: HttpHandler) {
        contexts[path] = handler
    }

    @Throws(Exception::class)
    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(address)
        serverSocket = ss
        running = true
        Log.i("HttpServerShim", "Android HttpServer started successfully on port ${address.port}")

        Thread {
            while (running) {
                try {
                    val clientSocket = ss.accept()
                    // Ensure timeout is configured for idle socket connections
                    clientSocket.soTimeout = 15000
                    executor.execute {
                        handleClient(clientSocket)
                    }
                } catch (e: Exception) {
                    if (running) {
                        Log.e("HttpServerShim", "Accept socket loop error", e)
                    }
                }
            }
        }.start()
    }

    fun stop(delay: Int) {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("HttpServerShim", "Error during server close", e)
        }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                
                // Read headers up to \r\n\r\n
                val headerBytes = ByteArrayOutputStream()
                var consecutiveNewlines = 0
                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    headerBytes.write(b)
                    
                    if (b == '\n'.code) {
                        val bytes = headerBytes.toByteArray()
                        val len = bytes.size
                        if (len >= 4 && 
                            bytes[len - 1] == '\n'.code.toByte() && 
                            bytes[len - 2] == '\r'.code.toByte() && 
                            bytes[len - 3] == '\n'.code.toByte() && 
                            bytes[len - 4] == '\r'.code.toByte()) {
                            break
                        }
                        if (len >= 2 && 
                            bytes[len - 1] == '\n'.code.toByte() && 
                            bytes[len - 2] == '\n'.code.toByte()) {
                            break
                        }
                    }
                }
                
                val headersText = headerBytes.toString("UTF-8")
                if (headersText.trim().isEmpty()) return
                
                val lines = headersText.split(Regex("\r?\n"))
                if (lines.isEmpty()) return
                val requestLine = lines[0]
                val requestParts = requestLine.split(" ")
                if (requestParts.size < 2) return
                
                val method = requestParts[0]
                val rawUri = requestParts[1]
                val uri = try { URI(rawUri) } catch (e: Exception) { URI("/") }
                val path = uri.path ?: "/"
                
                val reqHeaders = Headers()
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    if (line.isEmpty()) continue
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        reqHeaders.add(key, value)
                    }
                }
                
                val contentLengthStr = reqHeaders.getFirst("Content-Length")
                val contentLength = contentLengthStr?.toLongOrNull() ?: 0L
                val reqBodyStream = LimitedInputStream(input, contentLength)
                
                val remoteAddr = s.remoteSocketAddress as? InetSocketAddress 
                    ?: InetSocketAddress("127.0.0.1", s.port)
                
                val exchange = HttpExchange(s, uri, method, reqHeaders, reqBodyStream, remoteAddr)
                
                val handler = contexts[path] ?: contexts["/"]
                if (handler != null) {
                    try {
                        handler.handle(exchange)
                    } catch (e: Exception) {
                        Log.e("HttpServerShim", "Handler execution failed", e)
                        try {
                            exchange.sendResponseHeaders(500, -1)
                        } catch (ex: Exception) { /* ignore */ }
                    }
                } else {
                    exchange.sendResponseHeaders(404, -1)
                }
            }
        } catch (e: Exception) {
            Log.e("HttpServerShim", "Error processing client request", e)
        }
    }
}
