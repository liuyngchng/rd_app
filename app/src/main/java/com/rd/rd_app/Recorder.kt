package com.rd.rd_app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.view.TextureView.SurfaceTextureListener
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Camera ID detection ──

private fun findCameraIds(context: Context): CameraIds {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    var rear: String? = null
    var front: String? = null
    try {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> rear = id
                CameraCharacteristics.LENS_FACING_FRONT -> front = id
            }
        }
    } catch (_: Exception) {}
    return CameraIds(rear, front)
}

private data class CameraIds(val rear: String?, val front: String?)

/** Proactively check if the device supports concurrent front+back cameras (Android 12+) */
private fun supportsDualConcurrentCameras(context: Context, ids: CameraIds): Boolean? {
    val rear = ids.rear ?: return false
    val front = ids.front ?: return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null // unknown, fall back to try-and-fail

    return try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val concurrentSets = manager.getConcurrentCameraIds()
        concurrentSets.any { set -> set.contains(rear) && set.contains(front) }
    } catch (_: Exception) {
        null
    }
}

// ── Permission check ──

private fun checkRecordingPermissions(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
           ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

// ── Publish video to system gallery ──

private fun addVideoToGallery(context: Context, file: File) {
    if (!file.exists()) return
    try {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DualRecorder")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { `in` -> `in`.copyTo(out) }
            }
            // Remove the internal copy since the gallery now has it
            file.delete()
            Log.d("DualRecorder", "Video published to gallery: ${file.name}")
        }
    } catch (e: Exception) {
        Log.e("DualRecorder", "Failed to publish video to gallery", e)
    }
}

// ── Composable ──

@Composable
fun DualRecorderScreen(onExit: () -> Unit) {
    val context = LocalContext.current

    var isRecording by remember { mutableStateOf(false) }
    var currentTimeText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var fileCount by remember { mutableIntStateOf(0) }
    var rearReady by remember { mutableStateOf(false) }
    var frontReady by remember { mutableStateOf(false) }
    var dualSupported by remember { mutableStateOf<Boolean?>(null) } // true/false on 12+, null on <12
    var permissionsReady by remember { mutableStateOf(false) }

    val recordingScope = remember { CoroutineScope(Dispatchers.IO + SupervisorJob()) }

    val recorderDir = remember {
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "DualRecorder").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    // Wrapper references – held in remember so DisposableEffect can always clean them up
    val rearRef = remember { mutableStateOf<CameraSession?>(null) }
    val frontRef = remember { mutableStateOf<CameraSession?>(null) }

    // Two TextureViews
    val rearTextureView = remember { TextureView(context) }
    val frontTextureView = remember { TextureView(context) }

    // ── runtime permission request ──
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            permissionsReady = true
        } else {
            errorMsg = "需要相机和录音权限"
        }
    }

    // Request permissions on first launch
    LaunchedEffect(Unit) {
        if (!checkRecordingPermissions(context)) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else {
            permissionsReady = true
        }
    }

    // ── helpers ──

    // Track recorded files that need to be published to system gallery
    val pendingGalleryFiles = remember { mutableListOf<File>() }

    fun refreshFileCount() {
        fileCount = recorderDir.listFiles()?.count { it.name.endsWith(".mp4") } ?: 0
    }

    fun manageFileCount() {
        val files = recorderDir.listFiles()
            ?.filter { it.name.endsWith(".mp4") }
            ?.sortedBy { it.lastModified() } ?: return
        if (files.size > 50) {
            files.take(files.size - 50).forEach { it.delete() }
        }
        refreshFileCount()
    }

    fun startNewRecording() {
        manageFileCount()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val rearFile = File(recorderDir, "REAR_${ts}.mp4")
        val frontFile = File(recorderDir, "FRONT_${ts}.mp4")
        rearRef.value?.startRecording(rearFile)
        frontRef.value?.startRecording(frontFile)
        pendingGalleryFiles.addAll(listOf(rearFile, frontFile))
        refreshFileCount()
    }

    fun stopAllRecording() {
        rearRef.value?.stopRecording()
        frontRef.value?.stopRecording()
        // Publish all pending files to system gallery
        val files = pendingGalleryFiles.toList()
        pendingGalleryFiles.clear()
        files.forEach { addVideoToGallery(context, it) }
    }

    fun cleanupAll() {
        try { rearRef.value?.release() } catch (_: Exception) {}
        try { frontRef.value?.release() } catch (_: Exception) {}
        rearRef.value = null
        frontRef.value = null
        // Clean up any pending files that were never published
        pendingGalleryFiles.forEach { addVideoToGallery(context, it) }
        pendingGalleryFiles.clear()
    }

    // ── initialise cameras (runs when permissionsReady becomes true) ──
    LaunchedEffect(permissionsReady) {
        if (!permissionsReady) return@LaunchedEffect

        delay(300) // ensure AndroidView is laid out

        val ids = findCameraIds(context)
        val rearId = ids.rear
        val frontId = ids.front

        if (rearId == null) {
            errorMsg = "未找到后置摄像头"
            return@LaunchedEffect
        }

        // ── Proactive dual-camera hardware check (Android 12+) ──
        dualSupported = supportsDualConcurrentCameras(context, ids)
        if (dualSupported == false && frontId != null) {
            // Device explicitly reports no concurrent front+back support
            Log.w("DualRecorder", "Hardware does not support concurrent front+back cameras")
        }

        val rearSession = CameraSession(context, rearTextureView, rearId, "后置", 5_000_000)
        rearRef.value = rearSession

        // Open rear camera – wait for it to finish
        var rearOk = false
        suspendCancellableCoroutine<Unit> { cont ->
            rearSession.open(
                onReady = { rearOk = true; cont.resume(Unit) {} },
                onFail = { err ->
                    errorMsg = err
                    cont.resume(Unit) {}
                }
            )
        }

        if (!rearOk) return@LaunchedEffect
        rearReady = true

        // Try opening front camera if available (skip if hardware says no)
        if (frontId != null && dualSupported != false) {
            val frontSession = CameraSession(context, frontTextureView, frontId, "前置", 2_000_000)
            frontRef.value = frontSession

            suspendCancellableCoroutine<Unit> { cont ->
                frontSession.open(
                    onReady = { frontReady = true; cont.resume(Unit) {} },
                    onFail = { err ->
                        Log.w("DualRecorder", "Front camera unavailable: $err")
                        // Continue with rear only
                        frontRef.value = null
                        cont.resume(Unit) {}
                    }
                )
            }
        }
    }

    // ── time ticker ──
    LaunchedEffect(Unit) {
        while (true) {
            val now = Calendar.getInstance()
            currentTimeText = String.format(
                "%04d-%02d-%02d %02d:%02d:%02d",
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                now.get(Calendar.SECOND)
            )
            delay(1000)
        }
    }

    // ── cleanup on dispose ──
    DisposableEffect(Unit) {
        onDispose {
            cleanupAll()
            recordingScope.cancel()
        }
    }

    // ── UI ──
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (errorMsg != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMsg ?: "",
                    color = Color.Red,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    cleanupAll()
                    onExit()
                }) {
                    Text("返回")
                }
            }
            return
        }

        // ── Camera previews ──
        Column(modifier = Modifier.fillMaxSize()) {
            // Rear camera (top half)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    factory = { rearTextureView },
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    text = "后置摄像头",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
                if (!rearReady) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("相机加载中…", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.5f))
            )

            // Front camera (bottom half)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    factory = { frontTextureView },
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    text = if (frontReady) "前置摄像头" else "前置摄像头 (不可用)",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
                if (!frontReady) {
                    val frontMsg = when {
                        dualSupported == false -> "不支持同时使用前后摄像头"
                        frontRef.value != null -> "相机加载中…"
                        else -> "前置不可用"
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = frontMsg,
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // ── Time overlay ──
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
        ) {
            Text(
                text = currentTimeText,
                color = Color.White,
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        // ── Recording indicator ──
        if (isRecording) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                    Text("REC", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("$fileCount/50", color = Color.White, fontSize = 11.sp)
                }
            }
        }

        // ── Bottom controls ──
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = {
                    isRecording = false
                    recordingScope.coroutineContext[Job]?.let { j ->
                        j.children.forEach { it.cancel() }
                    }
                    cleanupAll()
                    onExit()
                },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(alpha = 0.2f)
                )
            ) {
                Text("← 返回", color = Color.White)
            }

            // Record / Stop button
            Button(
                onClick = {
                    if (isRecording) {
                        isRecording = false
                        stopAllRecording()
                        recordingScope.coroutineContext[Job]?.let { j ->
                            j.children.forEach { it.cancel() }
                        }
                        refreshFileCount()
                        Toast.makeText(context, "录制已停止", Toast.LENGTH_SHORT).show()
                    } else {
                        if (!checkRecordingPermissions(context)) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                            return@Button
                        }
                        if (!rearReady) {
                            Toast.makeText(context, "相机未就绪", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isRecording = true
                        startNewRecording()

                        recordingScope.launch {
                            while (isActive && isRecording) {
                                delay(5 * 60 * 1000L)
                                if (!isRecording) break
                                stopAllRecording()
                                startNewRecording()
                            }
                        }

                        Toast.makeText(context, "开始录制", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else Color.White
                )
            ) {
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.White, RoundedCornerShape(4.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                }
            }

            Spacer(modifier = Modifier.width(64.dp))
        }
    }
}

// ── Camera session (single camera management) ──

private class CameraSession(
    private val context: Context,
    private val textureView: TextureView,
    private val cameraId: String,
    private val label: String,
    private val bitRate: Int,
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var sensorOrientation: Int = 0
    private var lensFacing: Int? = null

    private val handlerThread = HandlerThread("cam-$label").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /** Open camera. Either onReady or onFail will be called exactly once. */
    fun open(onReady: () -> Unit, onFail: (String) -> Unit) {
        try {
            if (textureView.isAvailable) {
                initSurfaceAndOpenCamera(onReady, onFail)
            } else {
                textureView.surfaceTextureListener = object : SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                        initSurfaceAndOpenCamera(onReady, onFail)
                    }
                    override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
                }
            }
        } catch (e: Exception) {
            onFail("$label 初始化失败: ${e.message}")
        }
    }

    private fun initSurfaceAndOpenCamera(onReady: () -> Unit, onFail: (String) -> Unit) {
        try {
            val surfaceTexture = textureView.surfaceTexture ?: run {
                onFail("$label 预览表面未就绪")
                return
            }
            surfaceTexture.setDefaultBufferSize(1280, 720)
            previewSurface = Surface(surfaceTexture)

            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // Cache sensor orientation and lens facing for rotation hint
            val chars = manager.getCameraCharacteristics(cameraId)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            lensFacing = chars.get(CameraCharacteristics.LENS_FACING)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview(onFail)
                    onReady()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    onFail("$label 打开失败 ($error)")
                }
            }, handler)
        } catch (e: SecurityException) {
            onFail("$label 权限不足")
        } catch (e: Exception) {
            onFail("$label 打开异常: ${e.message}")
        }
    }

    private fun startPreview(onFail: (String) -> Unit) {
        val surface = previewSurface ?: return
        val device = cameraDevice ?: return
        try {
            device.createCaptureSession(listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            ?.apply { addTarget(surface) }?.build()
                        if (req != null) session.setRepeatingRequest(req, null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onFail("$label 预览配置失败")
                    }
                }, handler)
        } catch (e: Exception) {
            onFail("$label 预览异常: ${e.message}")
        }
    }

    fun startRecording(file: File) {
        val device = cameraDevice ?: return
        val previewSurf = previewSurface ?: return

        try {
            val mr = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(file.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(bitRate)
                setAudioEncodingBitRate(128_000)

                // Set orientation hint to correct rotation
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val displayRotation = windowManager.defaultDisplay.rotation
                val degrees = when (displayRotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val orientationHint = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    (sensorOrientation + degrees) % 360
                } else {
                    (sensorOrientation - degrees + 360) % 360
                }
                setOrientationHint(orientationHint)

                prepare()
            }
            mediaRecorder = mr

            val recorderSurface = mr.surface
            device.createCaptureSession(listOf(previewSurf, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            ?.apply {
                                addTarget(previewSurf)
                                addTarget(recorderSurface)
                            }?.build()
                        if (req != null) {
                            session.setRepeatingRequest(req, null, handler)
                            mr.start()
                            Log.d("DualRecorder", "$label recording: ${file.name}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("DualRecorder", "$label record session failed")
                        try { mr.release() } catch (_: Exception) {}
                        mediaRecorder = null
                    }
                }, handler)
        } catch (e: Exception) {
            Log.e("DualRecorder", "$label startRecording error", e)
        }
    }

    fun stopRecording() {
        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
        } catch (_: Exception) {}
        mediaRecorder = null
        // Restart preview-only session
        val surf = previewSurface ?: return
        val device = cameraDevice ?: return
        try {
            device.createCaptureSession(listOf(surf),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            ?.apply { addTarget(surf) }?.build()
                        if (req != null) session.setRepeatingRequest(req, null, handler)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, handler)
        } catch (_: Exception) {}
    }

    fun release() {
        try { mediaRecorder?.apply { try { stop() } catch (_: Exception) {}; release() } } catch (_: Exception) {}
        mediaRecorder = null
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        handlerThread.quitSafely()
    }
}
