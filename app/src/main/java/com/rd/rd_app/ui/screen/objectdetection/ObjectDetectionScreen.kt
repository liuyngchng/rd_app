package com.rd.rd_app.ui.screen.objectdetection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.math.min

private val DETECTION_COLORS = listOf(
    Color(0xFF4CAF50), // green  - cat
    Color(0xFF2196F3), // blue   - dog
    Color(0xFFFF9800), // orange
    Color(0xFFE91E63), // pink
    Color(0xFF9C27B0), // purple
    Color(0xFF00BCD4), // cyan
    Color(0xFFFFEB3B), // yellow
    Color(0xFFFF5722), // deep orange
    Color(0xFF795548), // brown
    Color(0xFF607D8B), // blue grey
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectDetectionScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ObjectDetectionViewModel = viewModel()
) {
    val inputMode by viewModel.inputMode.collectAsState()
    val detections by viewModel.detections.collectAsState()
    val displayDetections by viewModel.displayDetections.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val scanEnabled by viewModel.scanEnabled.collectAsState()

    val context = LocalContext.current
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var galleryBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Track camera permission state (resets on app reinstall)
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Initialize detector with context
    LaunchedEffect(Unit) {
        viewModel.initDetector(context)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            photoUri?.let { uri ->
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bitmap = inputStream?.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) {
                        galleryBitmap?.recycle()
                        galleryBitmap = bitmap
                        viewModel.processImage(bitmap)
                    }
                } catch (e: Exception) {
                    Log.e("ObjectDetection", "Failed to process photo", e)
                    Toast.makeText(context, "拍照失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bitmap = inputStream?.use { stream -> BitmapFactory.decodeStream(stream) }
                if (bitmap != null) {
                    galleryBitmap?.recycle()
                    galleryBitmap = bitmap
                    viewModel.processImage(bitmap)
                }
            } catch (e: Exception) {
                Log.e("ObjectDetection", "Failed to load gallery image", e)
                Toast.makeText(context, "图片加载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            when (inputMode) {
                DetectionInputMode.PHOTO_CAPTURE -> {
                    val photoFile = File(context.cacheDir, "detection_photo.jpg")
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", photoFile
                    )
                    photoUri = uri
                    cameraLauncher.launch(uri)
                }
                else -> {}
            }
        } else {
            Toast.makeText(context, "需要相机权限才能使用此功能", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestCameraAndCapture() {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val photoFile = File(context.cacheDir, "detection_photo.jpg")
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", photoFile
            )
            photoUri = uri
            cameraLauncher.launch(uri)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("目标检测", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Mode tabs
            ModeTabRow(
                selectedMode = inputMode,
                onModeSelected = { viewModel.switchMode(it) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Preview area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.05f))
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                when (inputMode) {
                    DetectionInputMode.PHOTO_CAPTURE -> {
                        if (galleryBitmap != null) {
                            DetectionOverlay(
                                bitmap = galleryBitmap!!,
                                detections = detections,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.PhotoCamera,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "点击下方按钮拍照",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    DetectionInputMode.LIVE_SCAN -> {
                        LiveDetectionPreview(
                            scanEnabled = scanEnabled,
                            hasCameraPermission = hasCameraPermission,
                            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            onFrameCaptured = { bitmap -> viewModel.processImage(bitmap) },
                            detections = detections,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    DetectionInputMode.GALLERY_PICK -> {
                        if (galleryBitmap != null) {
                            DetectionOverlay(
                                bitmap = galleryBitmap!!,
                                detections = detections,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "从相册选择图片",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }

            // Results panel
            DetectionResultsPanel(
                detections = displayDetections,
                isProcessing = isProcessing,
                errorMessage = errorMessage,
                onRetry = {
                    galleryBitmap?.let { viewModel.processImage(it) }
                },
                onDismissError = { viewModel.clearError() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Bottom controls
            BottomControls(
                inputMode = inputMode,
                scanEnabled = scanEnabled,
                onTakePhoto = { requestCameraAndCapture() },
                onToggleScan = { viewModel.toggleScan(it) },
                onPickGallery = { galleryLauncher.launch("image/*") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ModeTabRow(
    selectedMode: DetectionInputMode,
    onModeSelected: (DetectionInputMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedMode == DetectionInputMode.PHOTO_CAPTURE,
            onClick = { onModeSelected(DetectionInputMode.PHOTO_CAPTURE) },
            label = { Text("拍照检测") },
            leadingIcon = {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
        FilterChip(
            selected = selectedMode == DetectionInputMode.GALLERY_PICK,
            onClick = { onModeSelected(DetectionInputMode.GALLERY_PICK) },
            label = { Text("相册选取") },
            leadingIcon = {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
        FilterChip(
            selected = selectedMode == DetectionInputMode.LIVE_SCAN,
            onClick = { onModeSelected(DetectionInputMode.LIVE_SCAN) },
            label = { Text("实时检测") },
            leadingIcon = {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
    }
}

@Composable
private fun DetectionResultsPanel(
    detections: List<DetectedObject>,
    isProcessing: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(160.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (detections.isNotEmpty()) "检测到 ${detections.size} 个物体" else "检测结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (detections.isNotEmpty()) {
                    val ctx = LocalContext.current
                    IconButton(
                        onClick = {
                            val summary = detections.joinToString("\n") { d ->
                                "${d.label}: ${(d.confidence * 100).toInt()}%"
                            }
                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, "目标检测结果：\n$summary")
                            }
                            ctx.startActivity(
                                android.content.Intent.createChooser(shareIntent, "分享检测结果")
                            )
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "分享",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentAlignment = if (isProcessing || detections.isEmpty()) Alignment.Center else Alignment.TopStart
            ) {
                when {
                    isProcessing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                "正在检测...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    errorMessage != null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.TextButton(onClick = onDismissError) {
                                    Text("关闭")
                                }
                                androidx.compose.material3.TextButton(onClick = onRetry) {
                                    Text("重试")
                                }
                            }
                        }
                    }

                    detections.isNotEmpty() -> {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            detections.forEachIndexed { index, obj ->
                                val color = DETECTION_COLORS[index % DETECTION_COLORS.size]
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = obj.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.width(72.dp)
                                    )
                                    LinearProgressIndicator(
                                        progress = { obj.confidence },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = color,
                                        trackColor = color.copy(alpha = 0.15f),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${(obj.confidence * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(36.dp),
                                        textAlign = TextAlign.End
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = "检测结果将在这里显示",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomControls(
    inputMode: DetectionInputMode,
    scanEnabled: Boolean,
    onTakePhoto: () -> Unit,
    onToggleScan: (Boolean) -> Unit,
    onPickGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (inputMode) {
            DetectionInputMode.PHOTO_CAPTURE -> {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onTakePhoto),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = "拍照",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            DetectionInputMode.LIVE_SCAN -> {
                Button(
                    onClick = { onToggleScan(!scanEnabled) },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(if (scanEnabled) "停止检测" else "开始检测")
                }
            }

            DetectionInputMode.GALLERY_PICK -> {
                Button(
                    onClick = onPickGallery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("从相册选取")
                }
            }
        }
    }
}

@Composable
private fun DetectionOverlay(
    bitmap: Bitmap,
    detections: List<DetectedObject>,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.clipToBounds()) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        if (detections.isNotEmpty()) {
            val density = LocalDensity.current
            val strokeWidthPx = with(density) { 2.5.dp.toPx() }

            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val imageWidth = bitmap.width.toFloat()
                val imageHeight = bitmap.height.toFloat()
                val canvasWidth = size.width
                val canvasHeight = size.height

                val scaleX = canvasWidth / imageWidth
                val scaleY = canvasHeight / imageHeight
                val scale = kotlin.math.min(scaleX, scaleY)

                val offsetX = (canvasWidth - imageWidth * scale) / 2f
                val offsetY = (canvasHeight - imageHeight * scale) / 2f

                for ((index, obj) in detections.withIndex()) {
                    val color = DETECTION_COLORS[index % DETECTION_COLORS.size]
                    val colorArgb = color.toArgb()
                    val box = obj.boundingBox

                    val left = offsetX + box.left * imageWidth * scale
                    val top = offsetY + box.top * imageHeight * scale
                    val right = offsetX + box.right * imageWidth * scale
                    val bottom = offsetY + box.bottom * imageHeight * scale

                    // Draw bounding box stroke
                    drawRect(
                        color = Color(colorArgb).copy(alpha = 0.7f),
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidthPx)
                    )
                }
            }

        }
    }
}

@Composable
private fun LiveDetectionPreview(
    scanEnabled: Boolean,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onFrameCaptured: (Bitmap) -> Unit,
    detections: List<DetectedObject>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val cameraDeviceRef = remember { mutableStateOf<CameraDevice?>(null) }
    val captureSessionRef = remember { mutableStateOf<CameraCaptureSession?>(null) }
    var cameraOpened by remember { mutableStateOf(false) }

    val cameraThread = remember { HandlerThread("DetectionCamera").apply { start() } }
    val cameraHandler = remember { Handler(cameraThread.looper) }

    DisposableEffect(Unit) {
        onDispose {
            captureSessionRef.value?.close()
            cameraDeviceRef.value?.close()
            cameraThread.quitSafely()
        }
    }

    fun openCamera(texture: TextureView) {
        if (cameraOpened) return
        val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager

        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return

            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) return

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDeviceRef.value = camera
                    cameraOpened = true
                    startPreview(camera, texture, cameraHandler, captureSessionRef)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraOpened = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraOpened = false
                    Log.e("ObjectDetection", "Camera error: $error")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e("ObjectDetection", "Failed to open camera", e)
        }
    }

    // Retry camera open when permission is granted after the surface is already available
    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            val tv = textureView
            if (tv != null && tv.isAvailable && !cameraOpened) {
                openCamera(tv)
            }
        }
    }

    LaunchedEffect(scanEnabled) {
        if (!scanEnabled) return@LaunchedEffect

        // Brief delay for camera to start, then capture first frame immediately
        kotlinx.coroutines.delay(300)

        while (scanEnabled) {
            val tv = textureView ?: break
            if (tv.isAvailable) {
                try {
                    val bitmap = tv.bitmap
                    if (bitmap != null) {
                        onFrameCaptured(bitmap)
                    }
                } catch (e: Exception) {
                    Log.e("ObjectDetection", "Frame capture failed", e)
                }
            }
            // Refresh UI every 2 seconds to avoid flickering
            kotlinx.coroutines.delay(2000)
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) {
                            textureView = this@apply
                            openCamera(this@apply)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) {}

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Show permission request UI when camera permission is denied
        if (!hasCameraPermission) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "需要相机权限才能使用实时检测",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Button(onClick = onRequestPermission) {
                    Text("授予权限")
                }
            }
        }

        if (scanEnabled && hasCameraPermission) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(
                        MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                )
                Text(
                    "检测中",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }

        // Bounding box overlay on live preview
        if (detections.isNotEmpty() && scanEnabled && hasCameraPermission) {
            val density = LocalDensity.current
            val strokeWidthPx = with(density) { 2.5.dp.toPx() }

            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val tv = textureView ?: return@Canvas
                val previewWidth = tv.width.toFloat()
                val previewHeight = tv.height.toFloat()
                if (previewWidth == 0f || previewHeight == 0f) return@Canvas

                val canvasWidth = size.width
                val canvasHeight = size.height
                val scaleX = canvasWidth / previewWidth
                val scaleY = canvasHeight / previewHeight
                val scale = min(scaleX, scaleY)

                for ((index, obj) in detections.withIndex()) {
                    val color = DETECTION_COLORS[index % DETECTION_COLORS.size]
                    val colorArgb = color.toArgb()
                    val box = obj.boundingBox

                    val left = box.left * previewWidth * scale
                    val top = box.top * previewHeight * scale
                    val right = box.right * previewWidth * scale
                    val bottom = box.bottom * previewHeight * scale

                    drawRect(
                        color = Color(colorArgb).copy(alpha = 0.7f),
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidthPx)
                    )
                }
            }
        }
    }
}

private fun startPreview(
    camera: CameraDevice,
    textureView: TextureView,
    handler: Handler,
    sessionRef: MutableState<CameraCaptureSession?>
) {
    try {
        val surface = Surface(textureView.surfaceTexture)
        @Suppress("DEPRECATION")
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    sessionRef.value = session
                    try {
                        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        requestBuilder.addTarget(surface)
                        session.setRepeatingRequest(requestBuilder.build(), null, handler)
                    } catch (e: Exception) {
                        Log.e("ObjectDetection", "Failed to start preview", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("ObjectDetection", "Capture session configuration failed")
                }
            },
            handler
        )
    } catch (e: Exception) {
        Log.e("ObjectDetection", "Failed to create capture session", e)
    }
}
