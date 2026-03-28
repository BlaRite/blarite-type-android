package com.blarite.androidtype

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

// This class is the keyboard service itself.
// Android creates it when the user switches to this IME in a text field.
// Instead of drawing a full keyboard, we show a small listening bar,
// capture microphone audio, and buffer transcript text until the user finishes.
class TransparentBridgeIME : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val logTag = "BlaRiteIme"

    // These objects let Compose behave more like it does in an Activity.
    // An InputMethodService is not a normal screen, so we provide the
    // lifecycle, ViewModel store, and saved state owners manually.
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStoreInternal = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    // This coroutine scope is used for work that belongs to the IME session,
    // like opening the realtime speech client and reacting to microphone events.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // The UI reads this state and redraws automatically when values change.
    private val uiState = MutableStateFlow(BridgeUiState())

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = viewModelStoreInternal

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // These references point at the currently active pieces of the IME session.
    // They are nullable because Android may create/destroy the keyboard view many times.
    private var voiceInputView: VoiceBridgeInputView? = null
    private var microphoneBridge: MicrophoneBridge? = null
    private var speechClient: RealtimeSpeechClient? = null
    // This flag means "when finalization finishes, switch back to the previous keyboard".
    private var pendingReturnToPreviousIme = false
    // This guards shutdown so cleanup code is not run twice from overlapping callbacks.
    private var sessionClosing = false
    // Final transcript text is accumulated here and only committed to the host app on `Done`.
    private val bufferedTranscriptForCommit = StringBuilder()

    override fun onCreate() {
        // Compose needs saved-state support to be attached before use.
        savedStateRegistryController.performAttach()
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreateInputView(): View {
        // Android calls this when it needs the keyboard UI view.
        // We return a stable custom View that fills the keyboard area and
        // always shows recognition progress where the user expects it.
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        return voiceInputView ?: VoiceBridgeInputView(this).apply {
            onDone = { requestFinishAfterCurrentRecognition() }
            onSwitchKeyboard = { escapeToAnotherKeyboard() }
            onOpenSettings = { openConfiguration() }
            voiceInputView = this
            renderUiState(uiState.value)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(logTag, "onStartInputView(restarting=$restarting inputType=${info?.inputType} imeOptions=${info?.imeOptions})")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        // This is the moment the IME becomes visible to the user.
        // We start listening right away so the experience feels instant.
        startVoiceSession()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(logTag, "onStartInput(restarting=$restarting inputType=${attribute?.inputType} imeOptions=${attribute?.imeOptions})")
    }

    override fun onWindowShown() {
        super.onWindowShown()
        Log.d(logTag, "onWindowShown()")
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onWindowHidden() {
        Log.d(logTag, "onWindowHidden()")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onWindowHidden()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Log.d(logTag, "onFinishInputView(finishingInput=$finishingInput)")
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        // When Android says input is finished, we stop streaming and clean up.
        Log.d(logTag, "onFinishInput() pendingReturnToPreviousIme=$pendingReturnToPreviousIme bufferedLength=${bufferedTranscriptForCommit.length}")
        finishVoiceSession(shouldReturnToPreviousIme = false)
        super.onFinishInput()
    }

    override fun onDestroy() {
        // Final cleanup in case the service is torn down completely.
        finishVoiceSession(shouldReturnToPreviousIme = false)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStoreInternal.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startVoiceSession() {
        // Reset any old session before starting a new one.
        Log.d(logTag, "startVoiceSession()")
        finishVoiceSession(shouldReturnToPreviousIme = false)
        sessionClosing = false
        pendingReturnToPreviousIme = false
        bufferedTranscriptForCommit.clear()

        if (!hasRecordAudioPermission()) {
            // Without microphone permission, the IME cannot do anything useful,
            // so we show a clear status message and stop here.
            setUiState(
                BridgeUiState(
                    isListening = false,
                    status = "Microphone permission required",
                    transcriptPreview = "",
                    waveformLevel = 0f,
                    showDone = false
                )
            )
            return
        }

        // Pull the latest settings every time the IME opens so changes from the
        // configuration screen take effect immediately without restarting the app.
        val apiKey = SpeechServiceConfigStore.getEffectiveApiKey(this)
        val languageHints = SpeechServiceConfigStore.getLanguageHints(this)

        if (apiKey.isBlank()) {
            Log.e(logTag, "No speech service API key is configured")
            setUiState(
                BridgeUiState(
                    isListening = false,
                    status = "Speech service API key is not configured",
                    transcriptPreview = "",
                    waveformLevel = 0f,
                    showDone = false
                )
            )
            return
        }

        setUiState(
            BridgeUiState(
                isListening = false,
                status = "Connecting to speech service...",
                transcriptPreview = "",
                waveformLevel = 0f,
                showDone = false
            )
        )

        val client = RealtimeSpeechClient(
            apiKey = apiKey,
            languageHints = languageHints,
            callbackScope = serviceScope
        )
        speechClient = client
        // The IME reacts to speech-service events through this listener.
        client.connect(object : RealtimeSpeechClient.Listener {
            override fun onReady() {
                if (sessionClosing) {
                    Log.d(logTag, "Speech service became ready after sessionClosing")
                    return
                }

                Log.d(logTag, "Speech service websocket ready; starting microphone capture")

                // MicrophoneBridge is the component that reads raw PCM audio from Android.
                val bridge = MicrophoneBridge(
                    scope = serviceScope,
                    onAudioFrame = { frame ->
                        speechClient?.sendAudio(frame)
                    },
                    onLevelChanged = { level ->
                        // The waveform level is purely visual; it does not affect recognition.
                        updateUiState { current ->
                            current.copy(waveformLevel = level, isListening = !pendingReturnToPreviousIme, showDone = !pendingReturnToPreviousIme)
                        }
                    },
                    onError = { message ->
                        setUiState(
                            BridgeUiState(
                                isListening = false,
                                status = message,
                                transcriptPreview = uiState.value.transcriptPreview,
                                waveformLevel = 0f,
                                showDone = false
                            )
                        )
                        finishVoiceSession(shouldReturnToPreviousIme = pendingReturnToPreviousIme)
                    }
                )

                if (!bridge.start()) {
                    Log.e(logTag, "MicrophoneBridge.start() returned false")
                    setUiState(
                        BridgeUiState(
                            isListening = false,
                            status = "Unable to start microphone capture",
                            transcriptPreview = uiState.value.transcriptPreview,
                            waveformLevel = 0f,
                            showDone = false
                        )
                    )
                    finishVoiceSession(shouldReturnToPreviousIme = false)
                    return
                }

                microphoneBridge = bridge
                updateUiState { current ->
                    current.copy(
                        isListening = true,
                        status = "Listening... transcribing",
                        showDone = true
                    )
                }
            }

            override fun onStatusChanged(status: String) {
                Log.d(logTag, "Speech service status: $status")
                updateUiState { current ->
                    current.copy(
                        // Avoid overwriting the more important "Finalizing transcription..."
                        // message once the user has already pressed Done.
                        status = if (pendingReturnToPreviousIme && status == "Connected to speech service") {
                            current.status
                        } else {
                            status
                        }
                    )
                }
            }

            override fun onTranscriptUpdated(fullTranscriptPreview: String, committedText: String) {
                if (committedText.isNotEmpty()) {
                    Log.d(logTag, "Committed transcript delta length=${committedText.length}")
                    // Keep final text inside the IME until the user explicitly taps Done.
                    bufferedTranscriptForCommit.append(committedText)
                    Log.d(logTag, "Buffered transcript length=${bufferedTranscriptForCommit.length}")
                }
                updateUiState { current ->
                    current.copy(
                        isListening = !pendingReturnToPreviousIme,
                        status = if (pendingReturnToPreviousIme) {
                            "Finalizing transcription..."
                        } else {
                            "Recognizing speech..."
                        },
                        transcriptPreview = fullTranscriptPreview,
                        showDone = !pendingReturnToPreviousIme
                    )
                }
            }

            override fun onFinished() {
                Log.d(logTag, "Speech service finished stream")
                finishVoiceSession(shouldReturnToPreviousIme = pendingReturnToPreviousIme)
            }

            override fun onError(error: Throwable) {
                Log.e(logTag, "Speech service callback error", error)
                setUiState(
                    BridgeUiState(
                        isListening = false,
                        status = error.message ?: "Speech service connection error",
                        transcriptPreview = uiState.value.transcriptPreview,
                        waveformLevel = 0f,
                        showDone = false
                    )
                )
                finishVoiceSession(shouldReturnToPreviousIme = pendingReturnToPreviousIme)
            }
        })
    }

    private fun requestFinishAfterCurrentRecognition() {
        if (sessionClosing || pendingReturnToPreviousIme) {
            Log.d(logTag, "requestFinishAfterCurrentRecognition() ignored; sessionClosing=$sessionClosing pendingReturnToPreviousIme=$pendingReturnToPreviousIme")
            return
        }
        // This is the path taken when the user taps Done.
        // We stop the mic first, then ask the speech service to flush and finalize any pending audio.
        Log.d(logTag, "requestFinishAfterCurrentRecognition()")
        pendingReturnToPreviousIme = true
        updateUiState { current ->
            current.copy(isListening = false, status = "Finalizing transcription...", waveformLevel = 0f, showDone = false)
        }
        microphoneBridge?.stop()
        microphoneBridge = null
        val client = speechClient
        if (client == null) {
            finishVoiceSession(shouldReturnToPreviousIme = true)
            return
        }
        client.finalizeInput()
    }

    private fun escapeToAnotherKeyboard() {
        if (sessionClosing) {
            Log.d(logTag, "escapeToAnotherKeyboard() ignored because sessionClosing")
            return
        }

        // This is the emergency exit path: leave immediately instead of waiting for a finalize round-trip.
        Log.d(logTag, "escapeToAnotherKeyboard()")

        pendingReturnToPreviousIme = true
        microphoneBridge?.stop()
        microphoneBridge = null
        speechClient?.close()
        speechClient = null

        updateUiState { current ->
            current.copy(
                isListening = false,
                status = "Switching keyboard...",
                waveformLevel = 0f,
                showDone = false
            )
        }

        val switched = runCatching { switchToPreviousInputMethod() }.getOrDefault(false)
        Log.d(logTag, "escapeToAnotherKeyboard() switchToPreviousInputMethod result=$switched")
        if (!switched) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            requestHideSelf(0)
        }

        pendingReturnToPreviousIme = false
    }

    private fun openConfiguration() {
        if (sessionClosing) {
            Log.d(logTag, "openConfiguration() ignored because sessionClosing")
            return
        }

        // Opening settings should stop the live session, but it should not commit text
        // or automatically switch back to another keyboard.
        Log.d(logTag, "openConfiguration()")
        finishVoiceSession(shouldReturnToPreviousIme = false)
        requestHideSelf(0)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun finishVoiceSession(shouldReturnToPreviousIme: Boolean) {
        // Guard against duplicate shutdown calls from multiple paths.
        if (sessionClosing) {
            Log.d(logTag, "finishVoiceSession() ignored because sessionClosing")
            return
        }
        Log.d(logTag, "finishVoiceSession(shouldReturnToPreviousIme=$shouldReturnToPreviousIme bufferedLength=${bufferedTranscriptForCommit.length})")
        sessionClosing = true

        updateUiState { current ->
            current.copy(
                isListening = false,
                status = if (shouldReturnToPreviousIme) {
                    "Closing voice session..."
                } else {
                    current.status.ifBlank { "Idle" }
                },
                waveformLevel = 0f,
                showDone = false
            )
        }

        microphoneBridge?.stop()
        microphoneBridge = null

        if (shouldReturnToPreviousIme && bufferedTranscriptForCommit.isNotEmpty()) {
            // This is the only place where recognized text is inserted into the host editor.
            val transcriptToCommit = bufferedTranscriptForCommit.toString()
            Log.d(logTag, "Committing buffered transcript length=${transcriptToCommit.length}")
            currentInputConnection?.commitText(transcriptToCommit, 1)
            bufferedTranscriptForCommit.clear()
        } else if (!shouldReturnToPreviousIme) {
            // If we are closing for any other reason, discard the buffer so stale text
            // cannot leak into the next dictation session.
            bufferedTranscriptForCommit.clear()
        }

        speechClient?.close()
        speechClient = null

        if (shouldReturnToPreviousIme) {
            val switched = runCatching { switchToPreviousInputMethod() }.getOrDefault(false)
            Log.d(logTag, "finishVoiceSession() switchToPreviousInputMethod result=$switched")

            if (!switched) {
                // Fallback: at least hide this IME if the switch request fails.
                requestHideSelf(0)
                updateUiState { current ->
                    current.copy(status = "Voice session finished")
                }
            }
        }

        pendingReturnToPreviousIme = false
        sessionClosing = false
    }

    private fun setUiState(state: BridgeUiState) {
        // Update the stored state first, then push it to the current input view if it exists.
        uiState.value = state
        renderUiState(state)
    }

    private fun updateUiState(transform: (BridgeUiState) -> BridgeUiState) {
        // This helper avoids repeating "read old state, make copy, assign new state" everywhere.
        setUiState(transform(uiState.value))
    }

    private fun renderUiState(state: BridgeUiState) {
        // The IME view can be null briefly during lifecycle transitions, so this call is safe.
        voiceInputView?.render(
            isListening = state.isListening,
            status = state.status,
            transcriptPreview = state.transcriptPreview,
            waveformLevel = state.waveformLevel,
            showDone = state.showDone
        )
    }

    private fun currentImeWindowToken(): IBinder? {
        // We try a couple of places to obtain the window token because it is
        // the handle Android uses to identify the currently displayed IME window.
        return window?.window?.decorView?.windowToken ?: window?.window?.attributes?.token
    }

    private fun hasRecordAudioPermission(): Boolean {
        // Android microphone access is protected by a runtime permission.
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}

// This is the small state object that drives the Compose listening bar.
// Keeping it in one data class makes UI updates predictable and easy to track.
private data class BridgeUiState(
    // `isListening` drives both the header text and the animated waveform.
    val isListening: Boolean = false,
    // A short user-facing explanation of what the IME is currently doing.
    val status: String = "Tap into a text field to start voice mode",
    // Live transcript preview shown inside the IME panel.
    val transcriptPreview: String = "",
    // Normalized microphone loudness from 0.0 to 1.0.
    val waveformLevel: Float = 0f,
    // We hide Done while finalization is in progress to prevent double taps.
    val showDone: Boolean = false
)

@Composable
private fun VoiceBridgeBar(
    state: BridgeUiState,
    onDone: () -> Unit
) {
    // This is the whole IME UI: a compact bar with status text,
    // an animated waveform, and a Done button.
    Surface(color = Color.Transparent) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color(0xF0202530), Color(0xF01F3A5F))
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.isListening) "Listening..." else "Voice Bridge",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.transcriptPreview.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.transcriptPreview,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                WaveformView(
                    level = state.waveformLevel,
                    isActive = state.isListening,
                    modifier = Modifier.size(width = 68.dp, height = 34.dp)
                )
                if (state.showDone) {
                    Button(
                        onClick = onDone,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.16f),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveformView(
    level: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    // We animate the raw microphone level so the bars feel smooth instead of jumpy.
    val animatedLevel by animateFloatAsState(
        targetValue = if (isActive) level.coerceIn(0.08f, 1f) else 0f,
        label = "voice-waveform"
    )

    // Draw five rounded bars whose height changes with the current audio level.
    Canvas(modifier = modifier) {
        val barCount = 5
        val gap = size.width / 9f
        val barWidth = gap
        repeat(barCount) { index ->
            val normalizedBias = 0.35f + (index * 0.13f)
            val barHeight = size.height * (0.18f + animatedLevel * normalizedBias)
            val left = index * gap * 1.8f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = Offset(left, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

// This interface isolates the IME from the actual speech-service transport details.
// Today it uses a placeholder implementation, but later it can be backed by
// a real WebSocket client without changing the rest of the keyboard logic.
private interface SpeechStreamingClient {
    suspend fun connect(listener: Listener)
    fun sendAudio(frame: ByteArray)
    fun finishInput()
    fun close()

    interface Listener {
        fun onStatusChanged(status: String)
        fun onTranscriptionDelta(delta: String)
        fun onError(error: Throwable)
    }
}

private class PlaceholderSpeechStreamingClient(
    private val apiKey: String
) : SpeechStreamingClient {
    private var listener: SpeechStreamingClient.Listener? = null
    private var closed = false

    override suspend fun connect(listener: SpeechStreamingClient.Listener) {
        // Save the listener so we can send future status updates back to the IME.
        this.listener = listener
        listener.onStatusChanged(
            if (apiKey.isBlank()) {
                "Listening... configure SONIOX_API_KEY to prepare the bridge"
            } else {
                "Listening... replace the placeholder with the real speech-service WebSocket client"
            }
        )
    }

    override fun sendAudio(frame: ByteArray) {
        // In the placeholder version we accept audio but do not send it anywhere yet.
        if (closed || frame.isEmpty()) {
            return
        }
    }

    override fun finishInput() {
        if (!closed) {
            listener?.onStatusChanged("Finishing transcription...")
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        listener?.onStatusChanged("Session closed")
    }
}

// This helper owns raw microphone capture.
// It converts the incoming samples to bytes and estimates loudness for the UI.
// It does not decide when recognition ends; the IME controls that manually.
private class MicrophoneBridge(
    private val scope: CoroutineScope,
    private val onAudioFrame: (ByteArray) -> Unit,
    private val onLevelChanged: (Float) -> Unit,
    private val onError: (String) -> Unit
) {
    // The speech service expects 16 kHz mono PCM audio, so the recorder uses the same format.
    private val sampleRateHz = 16_000

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        // Ask Android for the minimum safe audio buffer size for this format.
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize <= 0) {
            onError("AudioRecord failed to provide a valid buffer size")
            return false
        }

        // AudioRecord is Android's low-level microphone API.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBufferSize, sampleRateHz)
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("AudioRecord failed to initialize")
            return false
        }

        val startRecordingResult = runCatching {
            record.startRecording()
        }
        if (startRecordingResult.isFailure) {
            record.release()
            onError(startRecordingResult.exceptionOrNull()?.message ?: "AudioRecord failed to start")
            return false
        }

        audioRecord = record

        // Run audio capture off the main thread so UI rendering stays responsive.
        captureJob = scope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(max(minBufferSize / 2, 1024))

            // Keep reading microphone audio until the coroutine is cancelled.
            while (isActive) {
                val readCount = record.read(shortBuffer, 0, shortBuffer.size)
                if (readCount <= 0) {
                    continue
                }

                var energy = 0.0
                val frame = ByteArray(readCount * 2)
                for (index in 0 until readCount) {
                    // Each sample is copied into a byte array so it can be sent
                    // over the network in PCM 16-bit little-endian form later.
                    val sample = shortBuffer[index]
                    energy += sample.toDouble() * sample.toDouble()
                    frame[index * 2] = (sample.toInt() and 0xFF).toByte()
                    frame[index * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                }

                // RMS is a common way to estimate how "loud" the audio is.
                val rms = (sqrt(energy / readCount) / Short.MAX_VALUE.toDouble()).toFloat()
                withContext(Dispatchers.Main.immediate) {
                    onLevelChanged(rms.coerceIn(0f, 1f))
                }
                onAudioFrame(frame)
            }
        }

        return true
    }

    fun stop() {
        // Cancel background work and release the microphone immediately.
        captureJob?.cancel()
        captureJob = null
        runCatching {
            audioRecord?.stop()
        }
        audioRecord?.release()
        audioRecord = null
    }
}
