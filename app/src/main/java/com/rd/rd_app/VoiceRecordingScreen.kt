package com.rd.rd_app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun VoiceRecordingScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var isInitialized by remember { mutableStateOf(false) }
    var partialText by remember { mutableStateOf("") }
    var accumulatedText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaved by remember { mutableStateOf(false) }
    var savedFilePath by remember { mutableStateOf<String?>(null) }

    val recorder = remember { VoiceRecorder() }
    val listState = rememberLazyListState()

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Permission granted, start initializing
            initializeAndStart(context, recorder, onError = { errorMessage = it }) {
                isInitialized = true
            }
        } else {
            errorMessage = "需要录音权限才能使用录音功能"
        }
    }

    // Set up listener
    LaunchedEffect(Unit) {
        recorder.listener = object : VoiceRecorder.Listener {
            override fun onPartialResult(text: String) {
                partialText = text
            }

            override fun onFinalResult(text: String) {
                accumulatedText = text
                partialText = ""
            }

            override fun onError(error: String) {
                errorMessage = error
            }
        }
    }

    // Auto-scroll transcript
    LaunchedEffect(accumulatedText) {
        if (accumulatedText.isNotEmpty()) {
            val lines = accumulatedText.split("\n")
            if (lines.isNotEmpty()) {
                listState.animateScrollToItem(lines.size - 1)
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            if (recorder.isRecordingActive) {
                recorder.stopRecording()
            }
            recorder.release()
        }
    }

    // ── UI ──
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ── Top bar ──
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        if (recorder.isRecordingActive) {
                            recorder.stopRecording()
                        }
                        recorder.release()
                        onExit()
                    }) {
                        Text("← 返回", fontSize = 16.sp)
                    }

                    Spacer(Modifier.weight(1f))

                    Text(
                        text = "录音转文字",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.weight(1f))

                    // Save button (only when transcript exists)
                    if (accumulatedText.isNotBlank() && !isSaved) {
                        TextButton(onClick = {
                            scope.launch {
                                val path = saveTranscript(context, accumulatedText)
                                if (path != null) {
                                    savedFilePath = path
                                    isSaved = true
                                    Toast.makeText(context, "已保存到: $path", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) {
                            Text("保存", fontSize = 16.sp)
                        }
                    }
                }
            }

            // ── Error message ──
            if (errorMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 14.sp
                    )
                }
            }

            // ── Transcript display area ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                if (accumulatedText.isBlank() && partialText.isBlank()) {
                    // Empty state hint
                    Text(
                        text = if (isRecording) "正在聆听..." else "点击下方按钮开始录音",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.Center),
                        textAlign = TextAlign.Center
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val lines = accumulatedText.split("\n").filter { it.isNotBlank() }
                        items(lines) { line ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = line,
                                    modifier = Modifier.padding(10.dp),
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Show partial text as a dimmed item
                        if (partialText.isNotBlank()) {
                            item {
                                Text(
                                    text = partialText,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            // ── Recording indicator ──
            if (isRecording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "录音中",
                        color = Color.Red,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Saved indicator ──
            if (isSaved && savedFilePath != null) {
                Text(
                    text = "已保存: ${savedFilePath?.substringAfterLast("/")}",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            // ── Recording button ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        if (isRecording) {
                            // Stop recording
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    recorder.stopRecording()
                                }
                                isRecording = false
                                Toast.makeText(context, "录音已停止", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // Check permission and start
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@Button
                            }

                            if (!isInitialized) {
                                initializeAndStart(context, recorder, onError = { errorMessage = it }) {
                                    isInitialized = true
                                }
                            } else {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        recorder.startRecording()
                                    }
                                    isRecording = true
                                    isSaved = false
                                    savedFilePath = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary
                    )
                ) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color.White, RoundedCornerShape(4.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Initialize VoiceRecorder on IO thread and start recording.
 */
private fun initializeAndStart(
    context: Context,
    recorder: VoiceRecorder,
    onError: (String) -> Unit,
    onReady: () -> Unit
) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
        try {
            withContext(Dispatchers.IO) {
                recorder.init(context)
            }
            withContext(Dispatchers.IO) {
                recorder.startRecording()
            }
            onReady()
            Toast.makeText(context, "开始录音", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            onError("初始化失败: ${e.message}")
        }
    }
}

/**
 * Save transcript to a text file in the app's Documents directory.
 */
private suspend fun saveTranscript(context: Context, text: String): String? = withContext(Dispatchers.IO) {
    try {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        }
        dir?.let { if (!it.exists()) it.mkdirs() }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "录音_$timestamp.txt")

        FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
        file.absolutePath
    } catch (e: Exception) {
        null
    }
}
