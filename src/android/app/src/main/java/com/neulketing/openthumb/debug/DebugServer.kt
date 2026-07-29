package com.neulketing.openthumb.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Debug-only JSON-RPC 2.0 server on port 8321.
 * Listens on all interfaces (0.0.0.0) so it's reachable from the local network for debugging.
 * Mirrors the iOS DebugServer for parity with the debug-server CLI skill.
 *
 * IMPORTANT: Only start this in debug builds. Never start in release.
 */
class DebugServer(
    private val context: Context,
    private val port: Int = 5321,
) {
    companion object {
        private const val TAG = "DebugServer"

        /** Largest request body accepted. Bigger ones are refused with 413. */
        const val MAX_BODY_BYTES = 32 * 1024 * 1024

        /**
         * [T-android-debugserver-auth] Connection auth decision, kept pure for
         * unit testing. **Every** connection must present the device token,
         * loopback included.
         *
         * Loopback used to be exempt on the reasoning that 127.0.0.1 meant
         * "adb forward, i.e. the developer's own machine". It does not: any
         * app on the phone holding INTERNET — which is nearly all of them —
         * can open a loopback socket, and this RPC surface exposes
         * `provider.export` (stored API keys), `debug.readFile` and sandbox
         * command execution. The exemption made those readable by any
         * installed app, and `Access-Control-Allow-Origin: *` extended the
         * same reach to any web page the device's browser loaded.
         *
         * Requiring the token everywhere keeps the adb workflow working — adb
         * can read it with `run-as`, which is exactly the privilege a
         * co-installed app does not have — and closes both paths.
         */
        fun isAuthorized(isLoopback: Boolean, providedToken: String?, expectedToken: String): Boolean {
            if (expectedToken.isEmpty()) return false
            val provided = providedToken ?: return false
            if (provided.length != expectedToken.length) return false
            // Constant-time compare — don't leak the token via timing.
            var diff = 0
            for (i in expectedToken.indices) {
                diff = diff or (provided[i].code xor expectedToken[i].code)
            }
            return diff == 0
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val rpcHandler = DebugRPCHandler(context)

    /**
     * [T-android-debugserver-auth] Per-install token required from
     * non-loopback clients. Generated once, persisted in filesDir so the
     * developer can read it with:
     *   adb shell run-as com.neulketing.openthumb cat files/debug_server_token
     * Also logged at startup (logcat is adb-only — not readable remotely).
     */
    private val authToken: String by lazy {
        val f = java.io.File(context.filesDir, "debug_server_token")
        if (f.exists()) {
            f.readText().trim().ifEmpty { generateToken(f) }
        } else {
            generateToken(f)
        }
    }

    private fun generateToken(f: java.io.File): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        runCatching { f.writeText(token) }
        return token
    }

    @Volatile
    private var stopped = false

    fun start() {
        if (serverSocket != null) return
        stopped = false

        acceptJob = scope.launch {
            try {
                // Listen on all interfaces (0.0.0.0) for network access
                val ss = ServerSocket(port, 10)
                serverSocket = ss
                Log.i(TAG, "Debug server listening on port $port (all interfaces)")
                Log.i(TAG, "Remote (non-loopback) clients must send X-Minis-Token: $authToken")

                while (!stopped) {
                    try {
                        val client = ss.accept()
                        // SupervisorJob per connection: an Error escaping one
                        // request (OOM, StackOverflow) must not cancel the
                        // accept loop and silence the whole server.
                        launch(SupervisorJob(coroutineContext[Job])) { handleConnection(client) }
                    } catch (e: Exception) {
                        if (!stopped) Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server on port $port: ${e.message}")
            }
        }
    }

    fun stop() {
        stopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        Log.i(TAG, "Server stopped")
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                // Headers are parsed off the raw stream rather than a
                // BufferedReader: a buffered reader pulls whole chunks, so it
                // swallows the head of the body and leaves the byte-exact body
                // read below unable to find it. Header lines are short, so
                // reading them a byte at a time costs nothing.
                val input = s.getInputStream()
                val writer = PrintWriter(s.getOutputStream(), true)
                val reader = HeaderLineReader(input)

                // Read HTTP request line
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ", limit = 3)
                if (parts.size < 2) {
                    sendResponse(writer, 400, rpcHandler.errorJSON(-32700, "Parse error"))
                    return
                }
                val method = parts[0]
                // [T-android-debugserver-skill] Path (query stripped) so the
                // unauthenticated GET skill routes can be dispatched.
                val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"

                // Handle CORS preflight
                if (method == "OPTIONS") {
                    sendCorsPreflightResponse(writer)
                    return
                }

                // Read headers
                var contentLength = 0
                var providedToken: String? = null
                var accept = ""
                while (true) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isEmpty()) break
                    val lower = headerLine.lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    if (lower.startsWith("accept:")) {
                        accept = lower.substringAfter(":").trim()
                    }
                    // [T-android-debugserver-auth] Token via X-Minis-Token or
                    // Authorization: Bearer — either spelling accepted.
                    if (lower.startsWith("x-minis-token:")) {
                        providedToken = headerLine.substringAfter(":").trim()
                    }
                    if (lower.startsWith("authorization:")) {
                        val v = headerLine.substringAfter(":").trim()
                        if (v.lowercase().startsWith("bearer ")) {
                            providedToken = v.substring(7).trim()
                        }
                    }
                }

                // [T-android-debugserver-auth] Gate BEFORE any RPC dispatch:
                // loopback (adb forward) is exempt; LAN clients need the token.
                val isLoopback = s.inetAddress?.isLoopbackAddress == true
                if (!isAuthorized(isLoopback, providedToken, authToken)) {
                    Log.w(TAG, "401 unauthorized ${if (isLoopback) "loopback" else s.inetAddress?.hostAddress ?: "?"} (missing/wrong token)")
                    sendResponse(writer, 401, rpcHandler.errorJSON(-32000, "Unauthorized — send X-Minis-Token (see `adb shell run-as com.neulketing.openthumb cat files/debug_server_token`)"))
                    return
                }

                // [T-android-debugserver-skill] Self-serve bootstrap routes,
                // mirroring iOS: a client that only has curl can pull the manual
                // and a working reference client straight off the device.
                //
                // Deliberately placed AFTER the auth gate above: unlike iOS
                // (whose /skill is unauthenticated because every RPC is still
                // sealed by the v1 envelope), Android's RPCs are plaintext, so
                // the token remains the only barrier for LAN clients and must not
                // be bypassed. Over `adb forward` (loopback) it's token-free,
                // which is the path the tooling actually uses.
                if (method == "GET") {
                    val wantsHuman = accept.contains("text/html") ||
                        accept.contains("text/markdown") ||
                        accept.contains("text/plain")
                    when (path) {
                        "/schema" -> {
                            sendResponse(writer, 200, schemaJSON())
                            return
                        }
                        "/", "/skill", "/skill/" -> {
                            // A bare `GET /` from a tool (no human Accept) keeps
                            // returning the machine schema, same contract as iOS.
                            if (path == "/" && !wantsHuman) {
                                sendResponse(writer, 200, schemaJSON())
                            } else {
                                sendSkill(writer, wantsHuman)
                            }
                            return
                        }
                        "/skill/examples/python", "/skill/examples/minis_rpc_android.py" -> {
                            sendSkillAsset(writer, "examples/minis_rpc_android.py", "text/x-python; charset=utf-8")
                            return
                        }
                        "/skill/examples/curl", "/skill/examples/curl.md" -> {
                            sendSkillAsset(writer, "examples/curl.md", "text/markdown; charset=utf-8")
                            return
                        }
                    }
                }

                if (method != "POST") {
                    sendResponse(writer, 405, rpcHandler.errorJSON(-32600, "Only POST accepted"))
                    return
                }

                if (contentLength <= 0) {
                    sendResponse(writer, 400, rpcHandler.errorJSON(-32700, "Empty body"))
                    return
                }
                // Content-Length is attacker-controlled: a single header
                // claiming 2GB allocated a 2GB ByteArray, and the resulting
                // OutOfMemoryError is an Error, not an Exception, so the catch
                // below never saw it — it killed the connection coroutine and
                // with it the accept loop, leaving the whole debug server dead
                // until the app restarted.
                if (contentLength > MAX_BODY_BYTES) {
                    sendResponse(writer, 413, rpcHandler.errorJSON(-32600, "Body too large"))
                    return
                }

                // Read body.
                //
                // Content-Length counts BYTES, so the loop has to count bytes
                // too. Filling a CharArray instead stalls on any non-ASCII
                // payload: one Korean character is three UTF-8 bytes, so the
                // reader runs out of input long before `totalRead` reaches the
                // byte count, blocks for more that never arrives, and dies on
                // the 30s socket timeout — the caller sees an empty response
                // with no error. ASCII-only bodies happen to work, which is
                // why this hid until a Korean prompt was sent.
                val body = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val n = input.read(body, totalRead, contentLength - totalRead)
                    if (n < 0) break
                    totalRead += n
                }
                val jsonBody = String(body, 0, totalRead, Charsets.UTF_8)

                val responseJSON = runBlocking {
                    rpcHandler.handle(jsonBody)
                }

                sendResponse(writer, 200, responseJSON)
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message}")
            }
        }
    }

    /// [T-android-debugserver-skill] Capability descriptor. Mirrors the iOS
    /// shape so a shared client can detect the platform: `auth` reports the
    /// Android scheme ("token-lan" — plaintext payloads, token only for
    /// non-loopback) rather than iOS's "v1" envelope, and there is no pair_path.
    private fun schemaJSON(): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        return """{"app":"OpenThumb","platform":"android","version":"$version",""" +
            """"rpc":"jsonrpc-2.0","auth":"token-lan","transport":"plaintext",""" +
            """"rpc_path":"/","skill_path":"/skill"}"""
    }

    /// GET / and /skill. Human clients get raw markdown; tools get JSON with the
    /// manual plus every reference client inlined, so one request bootstraps.
    private fun sendSkill(writer: PrintWriter, wantsHuman: Boolean) {
        val skill = readSkillAsset("SKILL.md")
        if (skill == null) {
            sendResponse(writer, 503, """{"error":"skill assets not bundled in this build"}""")
            return
        }
        if (wantsHuman) {
            sendResponse(writer, 200, skill, "text/markdown; charset=utf-8")
            return
        }
        val py = readSkillAsset("examples/minis_rpc_android.py") ?: ""
        val curl = readSkillAsset("examples/curl.md") ?: ""
        val payload = org.json.JSONObject().apply {
            put("skill", skill)
            put("clients", org.json.JSONObject().apply {
                put("python", py)
                put("curl", curl)
            })
            put("client_paths", org.json.JSONObject().apply {
                put("python", "/skill/examples/python")
                put("curl", "/skill/examples/curl")
            })
        }
        sendResponse(writer, 200, payload.toString())
    }

    private fun sendSkillAsset(writer: PrintWriter, assetPath: String, contentType: String) {
        val body = readSkillAsset(assetPath)
        if (body == null) {
            sendResponse(writer, 503, """{"error":"asset not bundled: $assetPath"}""")
            return
        }
        sendResponse(writer, 200, body, contentType)
    }

    /// Reads from the DEBUG-only asset source set staged by
    /// scripts/gen_debug_skill_android.sh. Absent in release (where this whole
    /// server never starts) and absent if the generator didn't run.
    private fun readSkillAsset(relPath: String): String? = try {
        context.assets.open("debug-skill/$relPath").bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun sendResponse(
        writer: PrintWriter,
        statusCode: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 $statusCode $statusText\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n")
        // No Access-Control-Allow-Origin: this is a device-local developer
        // RPC and the clients are curl/adb, not browsers. With the wildcard,
        // any page the phone's browser loaded could call it cross-origin and
        // read the response — including provider.export.
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, X-Minis-Token, Authorization\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    private fun sendCorsPreflightResponse(writer: PrintWriter) {
        writer.print("HTTP/1.1 204 No Content\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, X-Minis-Token, Authorization\r\n")
        writer.print("Access-Control-Max-Age: 86400\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
    }
}

/**
 * Minimal HTTP header-line reader over a raw stream.
 *
 * Reads one byte at a time and stops at CRLF/LF, so it never buffers past the
 * blank line that ends the headers. That is the point: the body after it must
 * be read as an exact byte count (Content-Length), which a chunk-buffering
 * reader would make impossible.
 */
internal class HeaderLineReader(private val input: java.io.InputStream) {
    /** Next header line without its terminator, or null at end of stream. */
    fun readLine(): String? {
        val buf = java.io.ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").removeSuffix("\r")
            buf.write(b)
        }
    }
}
