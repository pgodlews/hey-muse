package io.github.pgodlews.heymuse.server

import android.content.Context
import android.util.Log
import io.github.pgodlews.heymuse.Assistant
import io.github.pgodlews.heymuse.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID

class SetupServer(
    private val context: Context,
    private val settings: Settings,
    private val onConfigSaved: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "HeyMuseSetupServer"
        const val DEFAULT_PORT = 8080
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    val sessionToken: String = UUID.randomUUID().toString()
    private var actualPort: Int = DEFAULT_PORT

    private val _statusText = MutableStateFlow("Waiting for scan...")
    val statusText: StateFlow<String> = _statusText

    private val _isConfigSaved = MutableStateFlow(false)
    val isConfigSaved: StateFlow<Boolean> = _isConfigSaved

    fun getUrl(): String? {
        val ip = NetworkUtils.getLocalIpAddress(context) ?: return null
        return "http://$ip:$actualPort/?token=$sessionToken"
    }

    fun start(): Boolean {
        try {
            var ss: ServerSocket? = null
            for (p in DEFAULT_PORT..DEFAULT_PORT + 10) {
                try {
                    ss = ServerSocket(p)
                    actualPort = p
                    break
                } catch (_: Exception) {
                }
            }

            if (ss == null) {
                Log.e(TAG, "Failed to bind any port from $DEFAULT_PORT to ${DEFAULT_PORT + 10}")
                return false
            }

            serverSocket = ss
            Log.i(TAG, "SetupServer started on port $actualPort")

            serverJob = scope.launch {
                while (isActive) {
                    try {
                        val client = ss.accept()
                        scope.launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (!ss.isClosed) {
                            Log.w(TAG, "Socket accept error: ${e.message}")
                        }
                        break
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            return false
        }
    }

    fun stop() {
        try {
            serverJob?.cancel()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        } finally {
            serverSocket = null
            Log.i(TAG, "SetupServer stopped")
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val fullPath = parts[1]

            val pathParts = fullPath.split("?", limit = 2)
            val path = pathParts[0]
            val queryMap = mutableMapOf<String, String>()
            if (pathParts.size > 1) {
                for (param in pathParts[1].split("&")) {
                    val kv = param.split("=", limit = 2)
                    if (kv.isNotEmpty()) {
                        queryMap[kv[0]] = if (kv.size > 1) URLDecoder.decode(kv[1], "UTF-8") else ""
                    }
                }
            }

            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val lower = line!!.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val reqToken = queryMap["token"]
            if (reqToken != sessionToken) {
                sendResponse(out, 403, "text/plain", "Forbidden: Invalid or missing session token.")
                return
            }

            _statusText.value = "Phone connected!"

            when {
                method == "GET" && (path == "/" || path.isEmpty()) -> {
                    val html = buildSetupHtml()
                    sendResponse(out, 200, "text/html; charset=utf-8", html)
                }

                method == "POST" && path == "/api/setup" -> {
                    if (contentLength > 65536) {
                        sendResponse(out, 413, "text/plain", "Payload Too Large")
                        return
                    }
                    val bodyChars = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val read = reader.read(bodyChars, readTotal, contentLength - readTotal)
                        if (read == -1) break
                        readTotal += read
                    }
                    val body = String(bodyChars, 0, readTotal)
                    val json = JSONObject(body)

                    val sdkToken = json.optString("sdkToken", "").trim()
                    val speechKey = json.optString("speechKey", "").trim()
                    val voice = json.optString("voice", Settings.BUILT_IN).trim()
                    val displayName = json.optString("displayName", "").trim()
                    val speechBase = json.optString("speechBase", "").trim()
                    val speechModel = json.optString("speechModel", "").trim()
                    val speechStyle = json.optString("speechStyle", "").trim()

                    if (sdkToken.isNotEmpty()) {
                        settings.sdkToken = sdkToken
                    }
                    settings.speechKey = speechKey
                    settings.voice = voice
                    if (displayName.isNotEmpty()) {
                        settings.displayName = displayName
                    }
                    settings.speechBase = speechBase
                    settings.speechModel = speechModel
                    settings.speechStyle = speechStyle

                    Assistant.settingsChanged(micChanged = false)
                    onConfigSaved?.invoke()

                    _statusText.value = "✓ Configuration saved!"
                    _isConfigSaved.value = true

                    val resJson = JSONObject().put("status", "ok").put("message", "Configuration saved!").toString()
                    sendResponse(out, 200, "application/json", resJson)
                }

                else -> {
                    sendResponse(out, 404, "text/plain", "Not Found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client request (${e.javaClass.simpleName})")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendResponse(out: OutputStream, code: Int, contentType: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val statusMsg = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        val headers = "HTTP/1.1 $code $statusMsg\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun buildSetupHtml(): String {
        val currentVoice = settings.voice
        val currentDisplayName = settings.displayName

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Hey Muse · Setup</title>
    <style>
        :root {
            --bg: #0B0A16;
            --card: #17152A;
            --border: #2E2A4D;
            --text: #F2F0FA;
            --muted: #9D98B8;
            --accent: #B9A4FF;
            --warm: #FF9E80;
            --green: #7DE2A6;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background: var(--bg);
            color: var(--text);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }
        .card {
            background: var(--card);
            border: 1px solid var(--border);
            border-radius: 18px;
            max-width: 480px;
            width: 100%;
            padding: 28px;
            box-shadow: 0 10px 40px rgba(0,0,0,0.6);
        }
        .header {
            text-align: center;
            margin-bottom: 24px;
        }
        .header h1 {
            font-size: 24px;
            font-weight: 700;
            background: linear-gradient(135deg, var(--accent), var(--warm));
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            margin-bottom: 6px;
        }
        .header p { color: var(--muted); font-size: 14px; }
        .form-group { margin-bottom: 20px; }
        label {
            display: block;
            font-size: 13px;
            font-weight: 600;
            margin-bottom: 8px;
            color: var(--text);
        }
        .input-row {
            display: flex;
            gap: 8px;
        }
        input, select {
            width: 100%;
            padding: 12px 14px;
            background: #0B0A16;
            border: 1px solid var(--border);
            border-radius: 8px;
            color: var(--text);
            font-size: 15px;
            outline: none;
            transition: border-color 0.2s;
        }
        input:focus, select:focus {
            border-color: var(--accent);
        }
        button.paste-btn {
            background: #221F3B;
            border: 1px solid var(--border);
            color: var(--accent);
            padding: 0 16px;
            border-radius: 8px;
            font-weight: 600;
            cursor: pointer;
            white-space: nowrap;
        }
        .help-link {
            display: block;
            margin-top: 6px;
            font-size: 12px;
            color: var(--accent);
            text-decoration: none;
        }
        .help-link:hover { text-decoration: underline; }
        .details-box {
            margin-top: 16px;
            border-top: 1px solid var(--border);
            padding-top: 16px;
        }
        details summary {
            cursor: pointer;
            color: var(--muted);
            font-size: 13px;
            font-weight: 600;
            user-select: none;
        }
        details[open] summary { margin-bottom: 14px; }
        .submit-btn {
            width: 100%;
            padding: 14px;
            background: linear-gradient(135deg, #7C5CFC, #B9A4FF);
            border: none;
            border-radius: 10px;
            color: #0B0A16;
            font-size: 16px;
            font-weight: 700;
            cursor: pointer;
            transition: transform 0.1s, opacity 0.2s;
            margin-top: 16px;
        }
        .submit-btn:active { transform: scale(0.98); }
        .submit-btn:disabled { opacity: 0.5; cursor: not-allowed; }
        .success-box {
            display: none;
            text-align: center;
            padding: 24px 0;
        }
        .success-icon {
            font-size: 48px;
            color: var(--green);
            margin-bottom: 12px;
        }
        .success-title { font-size: 20px; font-weight: 700; margin-bottom: 8px; }
        .success-desc { color: var(--muted); font-size: 14px; }
    </style>
</head>
<body>
    <div class="card">
        <div id="formView">
            <div class="header">
                <h1>Hey Muse for Portal</h1>
                <p>Configure from your phone over local Wi-Fi</p>
            </div>

            <form id="setupForm" onsubmit="handleSave(event)">
                <div class="form-group">
                    <label for="sdkToken">Muse SDK Token</label>
                    <div class="input-row">
                        <input type="password" id="sdkToken" placeholder="mgst_..." autocomplete="off" autocorrect="off" autocapitalize="off" />
                        <button type="button" class="paste-btn" onclick="pasteField('sdkToken')">Paste</button>
                    </div>
                    <a href="https://gadgets.muse.ai/settings/sdk-tokens" target="_blank" class="help-link">Get an SDK token at gadgets.muse.ai &rarr;</a>
                </div>

                <div class="form-group">
                    <label for="voice">Voice</label>
                    <select id="voice">
                        <option value="builtin" ${if (currentVoice == "builtin") "selected" else ""}>Built-in Voice (SVOX Pico · offline)</option>
                        <option value="coral" ${if (currentVoice == "coral") "selected" else ""}>Coral (Warm & Natural · OpenAI)</option>
                        <option value="alloy" ${if (currentVoice == "alloy") "selected" else ""}>Alloy (Balanced · OpenAI)</option>
                        <option value="echo" ${if (currentVoice == "echo") "selected" else ""}>Echo (Smooth · OpenAI)</option>
                        <option value="fable" ${if (currentVoice == "fable") "selected" else ""}>Fable (British Accent · OpenAI)</option>
                        <option value="onyx" ${if (currentVoice == "onyx") "selected" else ""}>Onyx (Deep · OpenAI)</option>
                        <option value="nova" ${if (currentVoice == "nova") "selected" else ""}>Nova (Energetic · OpenAI)</option>
                        <option value="shimmer" ${if (currentVoice == "shimmer") "selected" else ""}>Shimmer (Clear · OpenAI)</option>
                        <option value="sage" ${if (currentVoice == "sage") "selected" else ""}>Sage (Gentle · OpenAI)</option>
                        <option value="verse" ${if (currentVoice == "verse") "selected" else ""}>Verse (Dynamic · OpenAI)</option>
                    </select>
                </div>

                <div class="form-group">
                    <label for="speechKey">Cloud Speech Key (Optional)</label>
                    <div class="input-row">
                        <input type="password" id="speechKey" placeholder="sk-..." autocomplete="off" autocorrect="off" autocapitalize="off" />
                        <button type="button" class="paste-btn" onclick="pasteField('speechKey')">Paste</button>
                    </div>
                    <span style="font-size: 12px; color: var(--muted); display: block; margin-top: 4px;">Needed for OpenAI cloud voices; Pico built-in voice needs no key.</span>
                </div>

                <div class="form-group">
                    <label for="displayName">Device Name</label>
                    <input type="text" id="displayName" value="$currentDisplayName" />
                </div>

                <details class="details-box">
                    <summary>Advanced Speech Options</summary>
                    <div class="form-group" style="margin-top: 12px;">
                        <label for="speechBase">Custom Speech Endpoint Base URL</label>
                        <input type="text" id="speechBase" placeholder="https://api.openai.com/v1" />
                    </div>
                    <div class="form-group">
                        <label for="speechModel">Speech Model</label>
                        <input type="text" id="speechModel" placeholder="gpt-4o-mini-tts" />
                    </div>
                    <div class="form-group">
                        <label for="speechStyle">Speech Style Prompt</label>
                        <input type="text" id="speechStyle" placeholder="warmly, and not too fast" />
                    </div>
                </details>

                <button type="submit" id="submitBtn" class="submit-btn">Save to Portal</button>
            </form>
        </div>

        <div id="successView" class="success-box">
            <div class="success-icon">✓</div>
            <div class="success-title">Configuration Saved!</div>
            <div class="success-desc">Hey Muse has been updated and is ready on your Portal. You can close this page.</div>
        </div>
    </div>

    <script>
        const token = new URLSearchParams(window.location.search).get('token');

        async function pasteField(id) {
            try {
                const text = await navigator.clipboard.readText();
                if (text) document.getElementById(id).value = text.trim();
            } catch (err) {
                alert('Please paste directly into the text field.');
            }
        }

        async function handleSave(e) {
            e.preventDefault();
            const btn = document.getElementById('submitBtn');
            btn.disabled = true;
            btn.textContent = 'Saving...';

            const payload = {
                token: token,
                sdkToken: document.getElementById('sdkToken').value.trim(),
                voice: document.getElementById('voice').value,
                speechKey: document.getElementById('speechKey').value.trim(),
                displayName: document.getElementById('displayName').value.trim(),
                speechBase: document.getElementById('speechBase').value.trim(),
                speechModel: document.getElementById('speechModel').value.trim(),
                speechStyle: document.getElementById('speechStyle').value.trim()
            };

            try {
                const res = await fetch('/api/setup?token=' + token, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                if (res.ok) {
                    document.getElementById('formView').style.display = 'none';
                    document.getElementById('successView').style.display = 'block';
                } else {
                    const data = await res.json();
                    alert(data.message || 'Error saving configuration');
                    btn.disabled = false;
                    btn.textContent = 'Save to Portal';
                }
            } catch (err) {
                alert('Connection error: ' + err.message);
                btn.disabled = false;
                btn.textContent = 'Save to Portal';
            }
        }
    </script>
</body>
</html>
        """.trimIndent()
    }
}
