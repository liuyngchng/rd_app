package com.rd.rd_app.ui.screen.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class OcrInputMode { PHOTO_CAPTURE, LIVE_SCAN, GALLERY_PICK }

data class OcrTextElement(
    val text: String,
    val boundingBox: Rect?
)

data class OcrTextLine(
    val text: String,
    val boundingBox: Rect?,
    val cornerPoints: Array<Point>?,
    val elements: List<OcrTextElement>
)

data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect?,
    val cornerPoints: Array<Point>?,
    val lines: List<OcrTextLine>
)

class OcrViewModel : ViewModel() {

    companion object {
        private const val TAG = "OcrViewModel"
        private const val MAX_IMAGE_DIMENSION = 1920
        private const val DISPLAY_INTERVAL_MS = 2000L
    }

    private var ocr: PaddleOCR? = null
    private var initFailed = false
    private var initErrorMessage: String? = null
    private val initDeferred = java.util.concurrent.CompletableFuture<Boolean>()

    private val _inputMode = MutableStateFlow(OcrInputMode.PHOTO_CAPTURE)
    val inputMode: StateFlow<OcrInputMode> = _inputMode.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _textBlocks = MutableStateFlow<List<OcrTextBlock>>(emptyList())
    val textBlocks: StateFlow<List<OcrTextBlock>> = _textBlocks.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _scanEnabled = MutableStateFlow(false)
    val scanEnabled: StateFlow<Boolean> = _scanEnabled.asStateFlow()

    private val processingFrame = AtomicBoolean(false)
    private var lastDisplayUpdateMs = 0L

    fun initOCR(context: Context) {
        if (ocr != null || initFailed) return
        viewModelScope.launch {
            try {
                ocr = withContext(Dispatchers.IO) {
                    // Try loading OpenCV manually first for better error messages
                    try {
                        System.loadLibrary("opencv_java4")
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "Failed to load opencv_java4: ${e.message}")
                        throw IllegalStateException("OpenCV native library load failed: ${e.message}")
                    }
                    PaddleOCR.create(context.applicationContext, PaddleOCRConfig(
                        detThresh = 0.3f,
                        detBoxThresh = 0.6f,
                        recScoreThresh = 0.5f,
                    ))
                }
                Log.d(TAG, "PaddleOCR initialized in ${ocr?.coldLoadTimeMs}ms")
                initDeferred.complete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize PaddleOCR", e)
                initFailed = true
                initErrorMessage = "OCR 引擎加载失败：${e.message}"
                _errorMessage.value = initErrorMessage
                initDeferred.complete(false)
            }
        }
    }

    fun switchMode(mode: OcrInputMode) {
        _inputMode.value = mode
        _recognizedText.value = ""
        _textBlocks.value = emptyList()
        _errorMessage.value = null
        if (mode != OcrInputMode.LIVE_SCAN) {
            _scanEnabled.value = false
        }
    }

    fun toggleScan(enabled: Boolean) {
        _scanEnabled.value = enabled
        if (!enabled) {
            processingFrame.set(false)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun processImage(bitmap: Bitmap, rotationDegrees: Int = 0) {
        if (processingFrame.getAndSet(true)) {
            return
        }

        viewModelScope.launch {
            if (!_scanEnabled.value) {
                _isProcessing.value = true
            }
            _errorMessage.value = null

            try {
                val result = runRecognition(bitmap, rotationDegrees)

                if (_scanEnabled.value) {
                    val now = System.currentTimeMillis()
                    if (now - lastDisplayUpdateMs >= DISPLAY_INTERVAL_MS) {
                        _recognizedText.value = result.first
                        _textBlocks.value = result.second
                        lastDisplayUpdateMs = now
                    }
                } else {
                    _recognizedText.value = result.first
                    _textBlocks.value = result.second
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR recognition failed", e)
                _errorMessage.value = "文字识别失败：${e.message ?: "未知错误"}"
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
                if (!_scanEnabled.value) {
                    _isProcessing.value = false
                }
                processingFrame.set(false)
            }
        }
    }

    private suspend fun runRecognition(bitmap: Bitmap, rotationDegrees: Int = 0): Pair<String, List<OcrTextBlock>> =
        withContext(Dispatchers.IO) {
            // Wait for OCR init to complete if still in progress
            if (ocr == null) {
                val ok = initDeferred.get()
                if (!ok) {
                    throw IllegalStateException(initErrorMessage ?: "OCR 引擎初始化失败")
                }
            }
            val engine = ocr ?: throw IllegalStateException("OCR 引擎未初始化，请稍后重试")

            // Downscale large images to save memory
            val scaledBitmap = if (bitmap.width > MAX_IMAGE_DIMENSION || bitmap.height > MAX_IMAGE_DIMENSION) {
                val scale = minOf(
                    MAX_IMAGE_DIMENSION.toFloat() / bitmap.width,
                    MAX_IMAGE_DIMENSION.toFloat() / bitmap.height
                )
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }

            try {
                val runResult = engine.recognize(scaledBitmap)
                Log.d(TAG, "OCR done: ${runResult.lineCount} lines in ${runResult.totalTimeMs}ms")

                val blocks = runResult.results.map { mapToBlock(it) }
                val fullText = runResult.results.joinToString("\n") { it.text }
                Pair(fullText, blocks)
            } finally {
                if (scaledBitmap !== bitmap) {
                    scaledBitmap.recycle()
                }
            }
        }

    private fun mapToBlock(result: OCRResult): OcrTextBlock {
        val cornerPoints = result.box.points.map { Point(it.x.toInt(), it.y.toInt()) }.toTypedArray()
        val rect = pointsToRect(cornerPoints)
        val element = OcrTextElement(text = result.text, boundingBox = rect)
        val line = OcrTextLine(
            text = result.text,
            boundingBox = rect,
            cornerPoints = cornerPoints,
            elements = listOf(element)
        )
        return OcrTextBlock(
            text = result.text,
            boundingBox = rect,
            cornerPoints = cornerPoints,
            lines = listOf(line)
        )
    }

    private fun pointsToRect(points: Array<Point>): Rect {
        val left = points.minOf { it.x }
        val top = points.minOf { it.y }
        val right = points.maxOf { it.x }
        val bottom = points.maxOf { it.y }
        return Rect(left, top, right, bottom)
    }

    override fun onCleared() {
        super.onCleared()
        ocr?.let {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    it.release()
                } catch (_: Exception) {}
            }
        }
    }
}
