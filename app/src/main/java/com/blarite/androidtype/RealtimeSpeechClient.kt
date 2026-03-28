package com.blarite.androidtype

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// This class owns the low-level conversation with the Soniox realtime API.
// The IME tells it when to connect, when to send microphone audio, and when
// to manually finalize the session after the user taps `Done`.
class RealtimeSpeechClient(
    private val apiKey: String,
    private val languageHints: List<String>,
    private val callbackScope: CoroutineScope
) {
    private val logTag = "BlaRiteIme"

    // The IME implements this listener so the networking layer can report back
    // without knowing anything about Android Views or InputMethodService.
    interface Listener {
        fun onReady()
        fun onStatusChanged(status: String)
        fun onTranscriptUpdated(fullTranscriptPreview: String, committedText: String)
        fun onFinished()
        fun onError(error: Throwable)
    }

    // OkHttp provides the WebSocket implementation used to talk to Soniox.
    // `readTimeout(0)` means "do not time out while waiting for messages".
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var listener: Listener? = null
    private var webSocket: WebSocket? = null
    // Once the user taps Done, we stop sending more audio and ask Soniox to finalize.
    private var finalizationRequested = false
    private var closed = false
    // We guard this so `onFinished()` is only delivered once even if Soniox sends
    // multiple signals that the stream is finished.
    private var finishedDelivered = false
    // This builder stores the text that Soniox has already confirmed as final.
    private val committedTranscript = StringBuilder()
    // Soniox may resend earlier final tokens in later messages, so we remember how
    // many final tokens we have already processed.
    private var committedFinalCount = 0

    fun connect(listener: Listener) {
        if (apiKey.isBlank()) {
            Log.e(logTag, "connect() aborted because apiKey is blank")
            postError(IllegalStateException("SONIOX_API_KEY is not configured"))
            return
        }

        Log.d(logTag, "Connecting to realtime speech websocket")
        this.listener = listener
        // The Soniox realtime endpoint is a WebSocket URL, not a normal HTTP REST URL.
        val request = Request.Builder()
            .url("wss://stt-rt.soniox.com/transcribe-websocket")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(logTag, "Realtime speech websocket opened")
                // Soniox expects a JSON configuration message first, before audio frames.
                webSocket.send(buildConfigurationMessage().toString())
                postStatus("Connected to speech service")
                postReady()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // All server messages arrive here as JSON strings.
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(logTag, "Realtime speech websocket closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(logTag, "Realtime speech websocket closed code=$code reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(logTag, "Realtime speech websocket failure", t)
                if (closed) {
                    return
                }
                postError(t)
            }
        })
    }

    fun sendAudio(frame: ByteArray) {
        // Ignore audio if the stream is closing or if the user has already pressed Done.
        if (frame.isEmpty() || finalizationRequested || closed) {
            return
        }
        webSocket?.send(frame.toByteString())
    }

    fun finalizeInput() {
        // Only ask Soniox to finalize once.
        if (finalizationRequested || closed) {
            return
        }
        Log.d(logTag, "Sending speech-service finalize control message")
        finalizationRequested = true
        postStatus("Finalizing transcription...")
        webSocket?.send(JSONObject().put("type", "finalize").toString())
    }

    fun close() {
        // This closes local resources even if the remote side does not respond right away.
        if (closed) {
            return
        }
        Log.d(logTag, "Closing realtime speech websocket client")
        closed = true
        runCatching { webSocket?.close(1000, "Done") }
        webSocket = null
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun handleMessage(text: String) {
        // Soniox speaks JSON, so the first step is turning the text into a JSONObject.
        val payload = runCatching { JSONObject(text) }.getOrElse {
            Log.e(logTag, "Failed to parse Soniox message: $text", it)
            postError(it)
            return
        }

        // Soniox API-level errors come back as normal JSON payloads, not just socket failures.
        if (payload.has("error_code") || payload.has("error_message")) {
            Log.e(logTag, "Soniox API error: ${payload.optString("error_message", "unknown")}")
            postError(IllegalStateException(payload.optString("error_message", "Soniox returned an error")))
            return
        }

        // Most useful speech data comes through the `tokens` array.
        processTokens(payload.optJSONArray("tokens"))

        if (payload.optBoolean("finished")) {
            if (finalizationRequested) {
                Log.d(logTag, "Speech stream finished after explicit finalization")
                deliverFinished()
            } else {
                Log.d(logTag, "Ignoring speech-service finished message because no explicit finalization was requested")
            }
        }
    }

    private fun processTokens(tokens: JSONArray?) {
        // Some server messages are only status updates and contain no tokens.
        if (tokens == null || tokens.length() == 0) {
            return
        }

        // Soniox distinguishes between partial tokens (still changing) and final tokens
        // (stable enough to keep permanently).
        val finalTokens = mutableListOf<JSONObject>()
        val partialTokens = mutableListOf<JSONObject>()

        for (index in 0 until tokens.length()) {
            val token = tokens.optJSONObject(index) ?: continue
            if (token.optBoolean("is_final", false)) {
                finalTokens.add(token)
            } else {
                partialTokens.add(token)
            }
        }

        // Only process the final tokens we have not seen before.
        val newFinalTokens = if (finalTokens.size > committedFinalCount) {
            finalTokens.subList(committedFinalCount, finalTokens.size)
        } else {
            emptyList()
        }
        committedFinalCount = finalTokens.size

        // Soniox uses `<fin>` as a special end marker when manual finalization completes.
        val finalizationCompleted = newFinalTokens.any { token ->
            token.optString("text", "") == "<fin>"
        }

        // Build only the newly committed text that became final in this message.
        // We strip the control markers because they are not real user text.
        val committedDelta = buildString {
            newFinalTokens.forEach { token ->
                val text = token.optString("text", "")
                    .replace("<end>", "")
                    .replace("<fin>", "")
                append(text)
            }
        }
        if (committedDelta.isNotEmpty()) {
            committedTranscript.append(committedDelta)
        }

        // Partial tokens are appended only for preview so the user can see speech-in-progress.
        val previewTail = buildString {
            partialTokens.forEach { token ->
                val text = token.optString("text", "")
                    .replace("<end>", "")
                    .replace("<fin>", "")
                append(text)
            }
        }

        // The full preview is: all previously finalized text + the newest unfinished tail.
        val fullPreview = committedTranscript.toString() + previewTail.toString()
        postTranscript(fullPreview, committedDelta)

        if (finalizationRequested && finalizationCompleted) {
            Log.d(logTag, "Speech-service manual finalization completed")
            deliverFinished()
        }
    }

    private fun buildConfigurationMessage(): JSONObject {
        // This JSON is the "session config" that Soniox expects before audio arrives.
        return JSONObject()
            .put("api_key", apiKey)
            .put("model", "stt-rt-v4")
            .put("audio_format", "pcm_s16le")
            .put("sample_rate", 16000)
            .put("num_channels", 1)
            // We keep endpoint detection off so the user stays in control of when the IME ends.
            .put("enable_endpoint_detection", false)
            .apply {
                if (languageHints.isNotEmpty()) {
                    // Language hints bias recognition toward the expected languages.
                    put("language_hints", JSONArray(languageHints))
                }
            }
    }

    private fun postReady() {
        // Jump back to the main thread before talking to UI code.
        callbackScope.launch(Dispatchers.Main.immediate) {
            listener?.onReady()
        }
    }

    private fun postStatus(status: String) {
        callbackScope.launch(Dispatchers.Main.immediate) {
            listener?.onStatusChanged(status)
        }
    }

    private fun postTranscript(fullTranscriptPreview: String, committedText: String) {
        callbackScope.launch(Dispatchers.Main.immediate) {
            listener?.onTranscriptUpdated(fullTranscriptPreview, committedText)
        }
    }

    private fun postError(error: Throwable) {
        callbackScope.launch(Dispatchers.Main.immediate) {
            listener?.onError(error)
        }
    }

    private fun deliverFinished() {
        // This prevents duplicate finish callbacks when both `<fin>` and `finished=true`
        // arrive for the same session.
        if (finishedDelivered) {
            return
        }
        finishedDelivered = true
        callbackScope.launch(Dispatchers.Main.immediate) {
            listener?.onFinished()
        }
    }
}
