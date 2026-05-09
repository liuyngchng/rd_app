package com.rd.rd_app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Vosk-based offline voice recorder.
 * Uses SpeechService (the Android-idiomatic Vosk API) to manage microphone input
 * and perform real-time speech recognition with a small Chinese model.
 */
class VoiceRecorder {

    interface Listener {
        fun onPartialResult(partialText: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }

    var listener: Listener? = null

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var isRecording = false
    private val finalTranscript = StringBuilder()

    val isRecordingActive: Boolean get() = isRecording

    /**
     * Initialize Vosk model. Must be called before [startRecording].
     * Copies model from assets to app internal storage if not already present.
     */
    @Throws(IOException::class)
    fun init(context: Context) {
        val modelDir = File(context.filesDir, MODEL_ASSET_DIR)
        if (!modelDir.exists()) {
            copyModelFromAssets(context, modelDir)
        }
        model = Model(modelDir.absolutePath)
        Log.d(TAG, "Vosk model initialized from: ${modelDir.absolutePath}")
    }

    /**
     * Start recording and speech recognition.
     */
    fun startRecording() {
        if (isRecording) return
        val mModel = model ?: run {
            listener?.onError("Model not initialized, call init() first")
            return
        }

        finalTranscript.clear()

        val recognizer = Recognizer(mModel, SAMPLE_RATE.toFloat())
        val service = SpeechService(recognizer, SAMPLE_RATE.toFloat())

        service.startListening(object : RecognitionListener {
            override fun onResult(hypothesis: String) {
                // Complete utterance recognized
                val text = JSONObject(hypothesis).optString("text", "")
                if (text.isNotEmpty()) {
                    finalTranscript.append(text).append("\n")
                    listener?.onFinalResult(finalTranscript.toString())
                }
            }

            override fun onPartialResult(hypothesis: String) {
                // In-flight partial result — uses "partial" key
                val text = JSONObject(hypothesis).optString("partial", "")
                if (text.isNotEmpty()) {
                    listener?.onPartialResult(text)
                }
            }

            override fun onFinalResult(hypothesis: String) {
                // Final flush result after stop()
                val text = JSONObject(hypothesis).optString("text", "")
                if (text.isNotEmpty()) {
                    finalTranscript.append(text)
                }
                listener?.onFinalResult(finalTranscript.toString())
            }

            override fun onError(e: Exception) {
                Log.e(TAG, "SpeechService error", e)
                listener?.onError(e.message ?: "Unknown error")
            }

            override fun onTimeout() {
                // no-op
            }
        })

        speechService = service
        isRecording = true
    }

    /**
     * Stop recording and return the complete transcript.
     */
    fun stopRecording(): String {
        isRecording = false
        speechService?.apply {
            stop()
            shutdown()
        }
        speechService = null
        return finalTranscript.toString()
    }

    /**
     * Release all resources.
     */
    fun release() {
        isRecording = false
        try {
            speechService?.apply {
                stop()
                shutdown()
            }
        } catch (_: Exception) {}
        speechService = null
        model?.close()
        model = null
        finalTranscript.clear()
    }

    // ── Private helpers ──

    /**
     * Recursively copy the Vosk model directory from assets to internal storage.
     */
    @Throws(IOException::class)
    private fun copyModelFromAssets(context: Context, destDir: File) {
        val assets = context.assets.list(MODEL_ASSET_DIR)
            ?: throw IOException("Model directory not found in assets: $MODEL_ASSET_DIR")

        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IOException("Failed to create model directory: $destDir")
        }

        for (assetName in assets) {
            val assetPath = "$MODEL_ASSET_DIR/$assetName"
            val destFile = File(destDir, assetName)

            if (context.assets.list(assetPath)?.isNotEmpty() == true) {
                destFile.mkdirs()
                copyAssetDirectory(context, assetPath, destFile)
            } else {
                copyAssetFile(context, assetPath, destFile)
            }
        }
        Log.d(TAG, "Model copied from assets to: ${destDir.absolutePath}")
    }

    @Throws(IOException::class)
    private fun copyAssetDirectory(context: Context, assetPath: String, destDir: File) {
        val children = context.assets.list(assetPath) ?: return
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IOException("Failed to create dir: $destDir")
        }
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childDest = File(destDir, child)
            if (context.assets.list(childAssetPath)?.isNotEmpty() == true) {
                childDest.mkdirs()
                copyAssetDirectory(context, childAssetPath, childDest)
            } else {
                copyAssetFile(context, childAssetPath, childDest)
            }
        }
    }

    @Throws(IOException::class)
    private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val MODEL_ASSET_DIR = "vosk-model-small-cn-0.22"
        private const val SAMPLE_RATE = 16000
    }
}
