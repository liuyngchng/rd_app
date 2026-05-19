package com.rd.rd_app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 语音采集 + 识别。
 *
 * 双模式：
 *   1. 网络模式（默认）— 通过 WebSocket 将 PCM 音频发送到服务端 FunASR 识别
 *   2. Vosk 模式（降级）— 本地 Vosk 模型识别
 *
 * [useNetwork] 设为 false 强制使用本地 Vosk；网络模式连接失败时自动降级到 Vosk。
 * [serverUrl] 指定 WebSocket 代理服务器地址。
 *
 * Listener 回调与原有保持一致，上层 UI 无需改动。
 */
class VoiceRecorder {

    interface Listener {
        fun onPartialResult(partialText: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }

    var listener: Listener? = null

    /** true（默认）使用 WebSocket → FunASR；false 强制使用本地 Vosk。 */
    var useNetwork: Boolean = true

    /** WebSocket 连接的服务端的地址。 */
    var serverUrl: String = ""

    // ── Vosk ──
    private var voskModel: Model? = null
    private var speechService: SpeechService? = null

    // ── AudioRecord + WebSocket ──
    private var audioRecord: AudioRecord? = null
    private var webSocket: WebSocket? = null
    private var recordingThread: Thread? = null

    private var isRecording = false
    private val finalTranscript = StringBuilder()

    val isRecordingActive: Boolean get() = isRecording

    // ══════════════════════════════════════════════════════════════════
    //  init / release
    // ══════════════════════════════════════════════════════════════════

    /**
     * 初始化 Vosk 模型（复制到内部存储），网络/降级均需提前调用。
     */
    @Throws(IOException::class)
    fun init(context: Context) {
        if (voskModel != null) return
        val modelDir = File(context.filesDir, MODEL_ASSET_DIR)
        if (!modelDir.exists()) {
            copyModelFromAssets(context, modelDir)
        }
        voskModel = Model(modelDir.absolutePath)
        Log.d(TAG, "Vosk model ready: ${modelDir.absolutePath}")
    }

    /**
     * 释放所有资源。
     */
    fun release() {
        stopRecording()
        voskModel?.close()
        voskModel = null
        finalTranscript.clear()
    }

    // ══════════════════════════════════════════════════════════════════
    //  开始 / 停止
    // ══════════════════════════════════════════════════════════════════

    @Synchronized
    fun startRecording() {
        if (isRecording) return
        finalTranscript.clear()
        isRecording = true

        if (useNetwork) {
            startNetwork()
        } else {
            startVosk()
        }
    }

    @Synchronized
    fun stopRecording(): String {
        isRecording = false

        if (speechService != null) {
            stopVosk()
        } else {
            stopNetwork()
        }

        return finalTranscript.toString()
    }

    // ══════════════════════════════════════════════════════════════════
    //  网络模式 — AudioRecord + OkHttp WebSocket
    //  将音频发送至远端服务器进行转录
    // ══════════════════════════════════════════════════════════════════

    private fun startNetwork() {
        val sampleRate = 16000
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ),
            sampleRate / 10 * 2,   // 至少 100ms
        )

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.w(TAG, "AudioRecord init failed, fallback to Vosk")
            startVosk()
            return
        }
        audioRecord = record

        // 发起 WebSocket 连接
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        webSocket = client.newWebSocket(
            Request.Builder().url(serverUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected to $serverUrl")
                    // 发配置消息
                    ws.send(JSONObject().apply {
                        put("wav_name", "android_mic")
                        put("audio_fs", 16000)
                    }.toString())
                    // 启动音频采集线程
                    startCaptureThread(ws)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    onWsResult(text)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                    Log.e(TAG, "WebSocket failure: ${t.message}")
                    listener?.onError("服务连接失败: ${t.message}")
                    // 降级到 Vosk
                    fallbackToVosk()
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code $reason")
                }
            }
        )
    }

    private fun startCaptureThread(ws: WebSocket) {
        val record = audioRecord ?: return
        record.startRecording()

        recordingThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(4096)  // ~128ms @ 16kHz mono 16bit

            while (isRecording) {
                val bytesRead = record.read(buffer, 0, buffer.size)
                if (bytesRead <= 0) break
                if (!ws.send(buffer.copyOf(bytesRead))) {
                    Log.w(TAG, "WS send failed")
                    break
                }
            }
        }, "AudioCapture").apply { start() }
    }

    private fun onWsResult(jsonText: String) {
        try {
            val result = JSONObject(jsonText)
            val text = result.optString("text", "")
            if (text.isEmpty()) return

            if (result.optBoolean("is_final", false)) {
                finalTranscript.append(text).append("\n")
                listener?.onFinalResult(finalTranscript.toString())
            } else {
                listener?.onPartialResult(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WS result parse error", e)
        }
    }

    private fun stopNetwork() {
        // 1. 停止录音线程
        recordingThread?.join(1500)
        recordingThread = null

        // 2. 停止 AudioRecord
        audioRecord?.apply {
            try {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            } catch (_: Exception) {}
            release()
        }
        audioRecord = null

        // 3. 发结束信号
        try {
            webSocket?.send(JSONObject().apply { put("is_speaking", false) }.toString())
        } catch (_: Exception) {}

        // 4. 关闭 WebSocket
        webSocket?.close(1000, "Stop")
        webSocket = null
    }

    /**
     * 网络连接失败时降级到 Vosk。
     */
    private fun fallbackToVosk() {
        if (!isRecording) return
        // 清理网络资源
        stopNetwork()
        // 切到 Vosk
        Log.i(TAG, "Falling back to local Vosk")
        startVosk()
    }

    // ══════════════════════════════════════════════════════════════════
    //  Vosk 模式（本地转录模式），本地模型将语音转换为文本
    // ══════════════════════════════════════════════════════════════════

    private fun startVosk() {
        val model = voskModel ?: run {
            listener?.onError("模型未初始化")
            isRecording = false
            return
        }

        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        val service = SpeechService(recognizer, SAMPLE_RATE.toFloat())

        service.startListening(object : RecognitionListener {
            override fun onResult(hypothesis: String) {
                val text = JSONObject(hypothesis).optString("text", "")
                if (text.isNotEmpty()) {
                    finalTranscript.append(text).append("\n")
                    listener?.onFinalResult(finalTranscript.toString())
                }
            }

            override fun onPartialResult(hypothesis: String) {
                val text = JSONObject(hypothesis).optString("partial", "")
                if (text.isNotEmpty()) {
                    listener?.onPartialResult(text)
                }
            }

            override fun onFinalResult(hypothesis: String) {
                val text = JSONObject(hypothesis).optString("text", "")
                if (text.isNotEmpty()) {
                    finalTranscript.append(text)
                }
                listener?.onFinalResult(finalTranscript.toString())
            }

            override fun onError(e: Exception) {
                Log.e(TAG, "Vosk error", e)
                listener?.onError(e.message ?: "识别错误")
            }

            override fun onTimeout() {}
        })

        speechService = service
        Log.d(TAG, "Vosk recording started")
    }

    private fun stopVosk() {
        speechService?.apply {
            try {
                stop()
                shutdown()
            } catch (_: Exception) {}
        }
        speechService = null
    }

    // ══════════════════════════════════════════════════════════════════
    //  Assets 模型复制
    // ══════════════════════════════════════════════════════════════════

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
