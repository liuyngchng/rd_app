package com.rd.rd_app.ui.screen.objectdetection

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.atomic.AtomicBoolean

enum class DetectionInputMode { PHOTO_CAPTURE, LIVE_SCAN, GALLERY_PICK }

data class DetectedObject(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)

class ObjectDetectionViewModel : ViewModel() {

    companion object {
        private const val TAG = "ObjectDetectionVM"
        private const val MODEL_FILE = "efficientdet_lite2.tflite"
        private const val MAX_IMAGE_DIMENSION = 1024
        private const val MAX_RESULTS = 10
        private const val SCORE_THRESHOLD = 0.5f
        private const val DISPLAY_INTERVAL_MS = 2000L

        // COCO 80-class English → Chinese label mapping
        private val LABEL_ZH = mapOf(
            "person" to "人", "bicycle" to "自行车", "car" to "汽车",
            "motorcycle" to "摩托车", "airplane" to "飞机", "bus" to "公交车",
            "train" to "火车", "truck" to "卡车", "boat" to "船",
            "traffic light" to "红绿灯", "fire hydrant" to "消防栓",
            "stop sign" to "停车牌", "parking meter" to "停车计费器", "bench" to "长椅",
            "bird" to "鸟", "cat" to "猫", "dog" to "狗",
            "horse" to "马", "sheep" to "羊", "cow" to "牛",
            "elephant" to "大象", "bear" to "熊", "zebra" to "斑马",
            "giraffe" to "长颈鹿", "backpack" to "背包", "umbrella" to "雨伞",
            "handbag" to "手提包", "tie" to "领带", "suitcase" to "行李箱",
            "frisbee" to "飞盘", "skis" to "滑雪板", "snowboard" to "单板滑雪板",
            "sports ball" to "球", "kite" to "风筝", "baseball bat" to "棒球棒",
            "baseball glove" to "棒球手套", "skateboard" to "滑板", "surfboard" to "冲浪板",
            "tennis racket" to "网球拍", "bottle" to "瓶子", "wine glass" to "酒杯",
            "cup" to "杯子", "fork" to "叉子", "knife" to "刀",
            "spoon" to "勺子", "bowl" to "碗", "banana" to "香蕉",
            "apple" to "苹果", "sandwich" to "三明治", "orange" to "橙子",
            "broccoli" to "西兰花", "carrot" to "胡萝卜", "hot dog" to "热狗",
            "pizza" to "披萨", "donut" to "甜甜圈", "cake" to "蛋糕",
            "chair" to "椅子", "couch" to "沙发", "potted plant" to "盆栽",
            "bed" to "床", "dining table" to "餐桌", "toilet" to "马桶",
            "tv" to "电视", "laptop" to "笔记本电脑", "mouse" to "鼠标",
            "remote" to "遥控器", "keyboard" to "键盘", "cell phone" to "手机",
            "microwave" to "微波炉", "oven" to "烤箱", "toaster" to "烤面包机",
            "sink" to "水槽", "refrigerator" to "冰箱", "book" to "书",
            "clock" to "时钟", "vase" to "花瓶", "scissors" to "剪刀",
            "teddy bear" to "泰迪熊", "hair drier" to "吹风机", "toothbrush" to "牙刷"
        )

        fun translateLabel(en: String): String = LABEL_ZH[en] ?: en
    }

    private var detector: ObjectDetector? = null

    private val _inputMode = MutableStateFlow(DetectionInputMode.PHOTO_CAPTURE)
    val inputMode: StateFlow<DetectionInputMode> = _inputMode.asStateFlow()

    // Fast updates for bounding box overlay
    private val _detections = MutableStateFlow<List<DetectedObject>>(emptyList())
    val detections: StateFlow<List<DetectedObject>> = _detections.asStateFlow()

    // Throttled updates for results panel text
    private val _displayDetections = MutableStateFlow<List<DetectedObject>>(emptyList())
    val displayDetections: StateFlow<List<DetectedObject>> = _displayDetections.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _scanEnabled = MutableStateFlow(false)
    val scanEnabled: StateFlow<Boolean> = _scanEnabled.asStateFlow()

    private val processingFrame = AtomicBoolean(false)
    private var lastDisplayUpdateMs = 0L

    fun initDetector(context: android.content.Context) {
        if (detector != null) return
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(SCORE_THRESHOLD)
                .build()
            detector = ObjectDetector.createFromFileAndOptions(context, MODEL_FILE, options)
            Log.d(TAG, "Detector initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detector", e)
            _errorMessage.value = "模型加载失败：${e.message}"
        }
    }

    fun switchMode(mode: DetectionInputMode) {
        _inputMode.value = mode
        _detections.value = emptyList()
        _displayDetections.value = emptyList()
        _errorMessage.value = null
        if (mode != DetectionInputMode.LIVE_SCAN) {
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
        if (processingFrame.getAndSet(true)) return

        val d = detector
        if (d == null) {
            _errorMessage.value = "检测器未初始化"
            processingFrame.set(false)
            return
        }

        viewModelScope.launch {
            // Only show processing indicator for single-shot modes (photo/gallery),
            // not for live scan (the red "检测中" badge already indicates activity)
            if (!_scanEnabled.value) {
                _isProcessing.value = true
            }
            _errorMessage.value = null

            try {
                val results = runDetection(bitmap, d)

                if (_scanEnabled.value) {
                    // Throttle UI updates to every 2s during live scan
                    val now = System.currentTimeMillis()
                    if (now - lastDisplayUpdateMs >= DISPLAY_INTERVAL_MS) {
                        _detections.value = results
                        _displayDetections.value = results
                        lastDisplayUpdateMs = now
                    }
                } else {
                    _detections.value = results
                    _displayDetections.value = results
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection failed", e)
                _errorMessage.value = "检测失败：${e.message ?: "未知错误"}"
            } finally {
                if (!_scanEnabled.value) {
                    _isProcessing.value = false
                }
                processingFrame.set(false)
            }
        }
    }

    private suspend fun runDetection(bitmap: Bitmap, d: ObjectDetector): List<DetectedObject> =
        withContext(Dispatchers.IO) {
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
                val image = org.tensorflow.lite.support.image.TensorImage.fromBitmap(scaledBitmap)
                val results: List<Detection> = d.detect(image)

                results.map { detection ->
                    val category = detection.categories.firstOrNull()
                    DetectedObject(
                        label = translateLabel(category?.label ?: "unknown"),
                        confidence = category?.score ?: 0f,
                        boundingBox = RectF(detection.boundingBox)
                    )
                }.sortedByDescending { it.confidence }
            } finally {
                if (scaledBitmap !== bitmap) {
                    scaledBitmap.recycle()
                }
            }
        }

    override fun onCleared() {
        super.onCleared()
        detector?.close()
        detector = null
    }
}
