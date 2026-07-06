package com.rd.rd_app.ui.screen.ocr

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    }

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

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

    fun processImage(bitmap: Bitmap) {
        if (processingFrame.getAndSet(true)) {
            return // Skip if already processing a frame
        }

        viewModelScope.launch {
            _isProcessing.value = true
            _errorMessage.value = null

            try {
                val result = runRecognition(bitmap)

                _recognizedText.value = result.first
                _textBlocks.value = result.second
            } catch (e: Exception) {
                Log.e(TAG, "OCR recognition failed", e)
                _errorMessage.value = "文字识别失败：${e.message ?: "未知错误"}"
            } finally {
                _isProcessing.value = false
                processingFrame.set(false)
            }
        }
    }

    private suspend fun runRecognition(bitmap: Bitmap): Pair<String, List<OcrTextBlock>> =
        withContext(Dispatchers.IO) {
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
                val inputImage = InputImage.fromBitmap(scaledBitmap, 0)
                val result = suspendCancellableCoroutine { continuation ->
                    recognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            val blocks = visionText.textBlocks.map { block ->
                                OcrTextBlock(
                                    text = block.text,
                                    boundingBox = block.boundingBox,
                                    cornerPoints = block.cornerPoints,
                                    lines = block.lines.map { line ->
                                        OcrTextLine(
                                            text = line.text,
                                            boundingBox = line.boundingBox,
                                            cornerPoints = line.cornerPoints,
                                            elements = line.elements.map { element ->
                                                OcrTextElement(
                                                    text = element.text,
                                                    boundingBox = element.boundingBox
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                            continuation.resume(Pair(visionText.text, blocks))
                        }
                        .addOnFailureListener { e ->
                            continuation.resumeWithException(e)
                        }
                }
                result
            } finally {
                // Only recycle if we created a new scaled copy
                if (scaledBitmap !== bitmap) {
                    scaledBitmap.recycle()
                }
            }
        }

    override fun onCleared() {
        super.onCleared()
        recognizer.close()
    }
}
