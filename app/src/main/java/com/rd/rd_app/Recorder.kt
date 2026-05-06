package com.rd.rd_app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import android.widget.Toast
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
import java.util.concurrent.atomic.AtomicBoolean

// ── Camera2 wrapper for a single camera ──

private class CameraDeviceWrapper(
    private val context: Context,
    private val textureView: TextureView,
    val cameraId: String,
    private val prefix: String,         // "REAR" or "FRONT"
    private val outputDir: File,
    private val recordBitRate: Int,
    private val onError: (String) -> Unit,
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var previewSurface: Surface? = null

    val isRecording: Boolean get() = mediaRecorder != null

    // Background thread for camera ops
    private val handlerThread = HandlerThread("Camera-$prefix").apply { start() }
    val handler = Handler(handlerThread.looper)

    /** Initialise: wait for TextureView surface, then open camera */
    fun open(onReady: () -> Unit) {
        if (textureView.isAvailable) {
            doOpenCamera()
            onReady()
        } else {
            textureView.surfaceTextureListener = object : SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    doOpenCamera()
                    onReady()
                }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        }
    }

    private fun doOpenCamera() {
        try {
            val surfaceTexture = textureView.surfaceTexture ?: return
            // Configure aspect ratio
            surfaceTexture.setDefaultBufferSize(1280, 720)
            previewSurface = Surface(surfaceTexture)

            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    onError("相机 $prefix 打开失败 ($error)")
                }
            }, handler)
        } catch (e: Exception) {
            onError("相机 $prefix 初始化失败: ${e.message}")
        }
    }

    private fun startPreview() {
        val surface = previewSurface ?: return
        cameraDevice?.createCaptureSession(listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = cameraDevice?.createCaptureRequest(
                        CameraDevice.TEMPLATE_PREVIEW
                    )?.apply { addTarget(surface) }?.build()
                    if (request != null) session.setRepeatingRequest(request, null, handler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError("相机 $prefix 预览配置失败")
                }
            }, handler)
    }

    /** Start MediaRecorder and switch session to include recording surface */
    fun startRecording(file: File) {
        val device = cameraDevice ?: return
        val previewSurf = previewSurface ?: return

        mediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(1280, 720)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(recordBitRate)
            setAudioEncodingBitRate(128_000)
            prepare()
        }

        val recorderSurface = mediaRecorder!!.surface
        device.createCaptureSession(listOf(previewSurf, recorderSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)?.apply {
                        addTarget(previewSurf)
                        addTarget(recorderSurface)
                    }?.build()
                    if (request != null) {
                        session.setRepeatingRequest(request, null, handler)
                        mediaRecorder?.start()
                        Log.d("DualRecorder", "$prefix recording started: ${file.name}")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError("$prefix 录制会话配置失败")
                    mediaRecorder?.release()
                    mediaRecorder = null
                }
            }, handler)
    }

    /** Stop recording and switch back to preview-only session */
    fun stopRecording() {
        try {
            mediaRecorder?.apply {
                try { stop() } catch (e: Exception) { Log.w("DualRecorder", "$prefix stop error", e) }
                release()
            }
        } catch (_: Exception) {}
        mediaRecorder = null
        startPreview()
    }

    /** Full cleanup */
    fun release() {
        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
        } catch (_: Exception) {}
        mediaRecorder = null
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        handlerThread.quitSafely()
    }
}

// ── Permission check ──

private fun checkRecordingPermissions(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
           ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

// ── Composable ──

@Composable
fun DualRecorderScreen(onExit: () -> Unit) {
    val context = LocalContext.current

    var isRecording by remember { mutableStateOf(false) }
    var currentTimeText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var fileCount by remember { mutableIntStateOf(0) }
    var camerasReady by remember { mutableStateOf(false) }

    val recordingScope = remember { CoroutineScope(Dispatchers.IO + SupervisorJob()) }

    val recorderDir = remember {
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "DualRecorder").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    // Two TextureViews (they live inside AndroidView)
    val rearTextureView = remember { TextureView(context) }
    val frontTextureView = remember { TextureView(context) }

    // Camera wrappers are initialised lazily after TextureViews are laid out
    var rearCam by remember { mutableStateOf<CameraDeviceWrapper?>(null) }
    var frontCam by remember { mutableStateOf<CameraDeviceWrapper?>(null) }

    // Helper to count MP4 files
    fun refreshFileCount() {
        fileCount = recorderDir.listFiles()?.count { it.name.endsWith(".mp4") } ?: 0
    }

    // Delete oldest when exceeding 50
    fun manageFileCount() {
        val files = recorderDir.listFiles()
            ?.filter { it.name.endsWith(".mp4") }
            ?.sortedBy { it.lastModified() } ?: return
        if (files.size > 50) {
            files.take(files.size - 50).forEach { it.delete() }
        }
        refreshFileCount()
    }

    // Start recording on both cameras
    fun startNewRecording() {
        manageFileCount()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        rearCam?.startRecording(File(recorderDir, "REAR_${ts}.mp4"))
        frontCam?.startRecording(File(recorderDir, "FRONT_${ts}.mp4"))
        refreshFileCount()
    }

    fun stopAllRecording() {
        rearCam?.stopRecording()
        frontCam?.stopRecording()
    }

    // Initialise cameras once TextureViews are attached
    LaunchedEffect(Unit) {
        // Small delay to ensure AndroidView is laid out
        delay(200)
        val onCamError: (String) -> Unit = { msg ->
            errorMsg = msg
        }

        val rear = CameraDeviceWrapper(
            context, rearTextureView, "0", "REAR",
            recorderDir, 5_000_000, onCamError
        )
        val front = CameraDeviceWrapper(
            context, frontTextureView, "1", "FRONT",
            recorderDir, 2_000_000, onCamError
        )

        var rearReady = false
        var frontReady = false

        rear.open {
            rearReady = true
            if (frontReady) { rearCam = rear; frontCam = front; camerasReady = true }
        }
        front.open {
            frontReady = true
            if (rearReady) { rearCam = rear; frontCam = front; camerasReady = true }
        }
    }

    // Time ticker
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

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            stopAllRecording()
            rearCam?.release()
            frontCam?.release()
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
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = errorMsg ?: "", color = Color.Red, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onExit) { Text("返回") }
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
                    text = "前置摄像头",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
            }
        }

        // ── Time overlay (top centre) ──
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

        // ── Recording indicator (top-right) ──
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
            // Back button
            FilledTonalButton(
                onClick = {
                    if (isRecording) {
                        isRecording = false
                        stopAllRecording()
                        recordingScope.coroutineContext[Job]?.let { j -> j.children.forEach { it.cancel() } }
                    }
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
                        // STOP
                        isRecording = false
                        stopAllRecording()
                        recordingScope.coroutineContext[Job]?.let { j -> j.children.forEach { it.cancel() } }
                        refreshFileCount()
                        Toast.makeText(context, "录制已停止", Toast.LENGTH_SHORT).show()
                    } else {
                        // START
                        if (!checkRecordingPermissions(context)) {
                            Toast.makeText(context, "需要相机和录音权限", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!camerasReady) {
                            Toast.makeText(context, "相机未就绪", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isRecording = true
                        startNewRecording()

                        // 5-minute auto-split loop
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
