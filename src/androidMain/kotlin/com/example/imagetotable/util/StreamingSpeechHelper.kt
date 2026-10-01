package com.example.imagetotable.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class StreamingSpeechHelper(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun isOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Streaming mode with live partial results */
    fun start(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStateChanged: (Boolean) -> Unit
    ) {
        beginSession(
            partialResultsEnabled = true,
            onPartialResult = onPartialResult,
            onResult = onFinalResult,
            onError = onError,
            onListeningStateChanged = onListeningStateChanged
        )
    }

    /** One-shot mode — no live partial text, single final result */
    fun startOneShot(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStateChanged: (Boolean) -> Unit
    ) {
        beginSession(
            partialResultsEnabled = false,
            onPartialResult = {},
            onResult = onResult,
            onError = onError,
            onListeningStateChanged = onListeningStateChanged
        )
    }

    private fun beginSession(
        partialResultsEnabled: Boolean,
        onPartialResult: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStateChanged: (Boolean) -> Unit
    ) {
        if (!isAvailable()) {
            onError("Speech recognition not available on this device")
            return
        }
        stop()

        val useOnDevice = isOnDeviceAvailable()
        val newRecognizer = if (useOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = newRecognizer

        newRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                onListeningStateChanged(true)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
                onListeningStateChanged(false)
            }
            override fun onError(error: Int) {
                isListening = false
                onListeningStateChanged(false)

                if (useOnDevice && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    newRecognizer.destroy()
                    recognizer = null
                    beginFallbackSession(partialResultsEnabled, onPartialResult, onResult, onError, onListeningStateChanged)
                    return
                }

                onError(errorMessage(error))
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                onListeningStateChanged(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val finalText = matches?.firstOrNull()
                if (!finalText.isNullOrBlank()) onResult(finalText)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (!partialResultsEnabled) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.firstOrNull()
                if (!partialText.isNullOrBlank()) onPartialResult(partialText)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResultsEnabled)
            if (useOnDevice) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        newRecognizer.startListening(recognizerIntent)
    }

    private fun beginFallbackSession(
        partialResultsEnabled: Boolean,
        onPartialResult: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStateChanged: (Boolean) -> Unit
    ) {
        val fallbackRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = fallbackRecognizer

        fallbackRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                onListeningStateChanged(true)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
                onListeningStateChanged(false)
            }
            override fun onError(error: Int) {
                isListening = false
                onListeningStateChanged(false)
                onError(errorMessage(error))
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                onListeningStateChanged(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val finalText = matches?.firstOrNull()
                if (!finalText.isNullOrBlank()) onResult(finalText)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (!partialResultsEnabled) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.firstOrNull()
                if (!partialText.isNullOrBlank()) onPartialResult(partialText)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResultsEnabled)
        }
        fallbackRecognizer.startListening(intent)
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
        SpeechRecognizer.ERROR_NETWORK -> "Network error — no internet and no offline model available"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again"
        else -> "Speech recognition error ($error)"
    }

    fun stop() {
        recognizer?.apply {
            try { stopListening() } catch (e: Exception) { /* ignore */ }
            destroy()
        }
        recognizer = null
        isListening = false
    }
}
