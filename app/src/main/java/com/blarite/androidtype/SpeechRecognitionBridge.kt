package com.blarite.androidtype

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

// This helper wraps Android's built-in speech recognizer behind a small API.
// It gives the IME live partial text, final text, and microphone level updates.
class SpeechRecognitionBridge(
    context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onLevelChanged: (Float) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null
    private var destroyed = false

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError("Speech recognition is not available on this device")
            return false
        }

        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.getOrElse {
            onError(it.message ?: "Unable to create speech recognizer")
            return false
        }

        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onStatusChanged("Listening...")
            }

            override fun onBeginningOfSpeech() {
                onStatusChanged("Speech detected")
            }

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                onLevelChanged(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                onStatusChanged("Finishing transcription...")
            }

            override fun onError(error: Int) {
                if (destroyed) {
                    return
                }
                onError(errorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                if (destroyed) {
                    return
                }
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                onFinalResult(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (destroyed) {
                    return
                }
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                onPartialResult(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        return runCatching {
            recognizer.startListening(recognizerIntent)
            true
        }.getOrElse {
            recognizer.destroy()
            speechRecognizer = null
            onError(it.message ?: "Unable to start speech recognition")
            false
        }
    }

    fun requestStop() {
        speechRecognizer?.let { recognizer ->
            runCatching { recognizer.stopListening() }
        }
    }

    fun destroy() {
        destroyed = true
        speechRecognizer?.let { recognizer ->
            runCatching { recognizer.cancel() }
            recognizer.destroy()
        }
        speechRecognizer = null
    }

    private fun errorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Speech recognizer does not have microphone permission"
            SpeechRecognizer.ERROR_NETWORK -> "Speech recognizer network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognizer network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
            SpeechRecognizer.ERROR_SERVER -> "Speech recognizer server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            else -> "Speech recognizer error ($error)"
        }
    }
}
