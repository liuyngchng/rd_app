package com.rd.rd_app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*


/**
 * 语音转文本页面
 */
@Composable
fun VoiceRecordingScreen(
    onExit: () -> Unit,
    serverUrl: String = "ws://172.20.10.6:19001",
    useNetwork: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var isInitialized by remember { mutableStateOf(false) }
    var isInitializing by remember { mutableStateOf(false) }
    var partialText by remember { mutableStateOf("") }
    var accumulatedText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaved by remember { mutableStateOf(false) }
    var savedFilePath by remember { mutableStateOf<String?>(null) }
    var showFileList by remember { mutableStateOf(false) }
    var selectedFileContent by remember { mutableStateOf<String?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var selectedFilePath by remember { mutableStateOf<String?>(null) }
    var fileList by remember { mutableStateOf<List<File>>(emptyList()) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }

    // Load file list when switching to file browser
    LaunchedEffect(showFileList) {
        if (showFileList) {
            withContext(Dispatchers.IO) {
                val dir = getTranscriptsDir(context)
                fileList = dir?.listFiles { f -> f.name.endsWith(".txt") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
            }
        }
    }

    val recorder = remember { VoiceRecorder().apply {
        this.serverUrl = serverUrl
        this.useNetwork = useNetwork
    } }
    val listState = rememberLazyListState()

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Permission granted, start initializing
            isInitializing = true
            scope.launch {
                try {
                    initializeAndStart(context, recorder,
                        onError = {
                            errorMessage = it
                            isInitializing = false
                        },
                        onReady = {
                            isInitialized = true
                            isRecording = true
                            isInitializing = false
                        }
                    )
                } catch (e: Throwable) {
                    errorMessage = "初始化失败: ${e.message}"
                    isInitializing = false
                }
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
            Spacer(Modifier.height(12.dp))

            // ── Top bar ──
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Column {
                    // ── Row 1: Title only (centered, all modes) ──
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (showFileList && selectedFileContent != null) selectedFileName
                                   else if (showFileList) "录音文件"
                                   else "录音转文字",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // ── Row 2: Back button (left) + actions (right) ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Back button (all modes)
                        if (showFileList && selectedFileContent != null) {
                            TextButton(onClick = { selectedFileContent = null }) {
                                Text("← 返回列表", fontSize = 15.sp)
                            }
                        } else if (showFileList) {
                            TextButton(onClick = { showFileList = false }) {
                                Text("← 返回录音", fontSize = 15.sp)
                            }
                        } else {
                            TextButton(onClick = {
                                if (recorder.isRecordingActive) {
                                    recorder.stopRecording()
                                }
                                recorder.release()
                                onExit()
                            }) {
                                Text("← 返回", fontSize = 15.sp)
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        // Right-aligned buttons
                        if (showFileList && selectedFileContent != null) {
                            // File content viewer: share button
                            TextButton(onClick = {
                                selectedFilePath?.let { path ->
                                    shareFile(context, path)
                                }
                            }) {
                                Text("分享", fontSize = 14.sp)
                            }
                        } else if (!showFileList) {
                            // Recording mode: browse + save buttons
                            TextButton(onClick = {
                                showFileList = true
                            }) {
                                Text("浏览文件", fontSize = 14.sp)
                            }
                            if (accumulatedText.isNotBlank() && !isSaved) {
                                Spacer(Modifier.width(4.dp))
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
                                    Text("保存", fontSize = 14.sp)
                                }
                            }
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

            // ── Main content: file list / file viewer / recording ──
            if (showFileList && selectedFileContent != null) {
                // ── File content viewer ──
                val content = selectedFileContent
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    if (content != null) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val lines = content.split("\n").filter { it.isNotBlank() }
                            if (lines.isEmpty()) {
                                item {
                                    Text(
                                        text = "(空文件)",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 16.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
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
                            }
                        }
                    }
                }
            } else if (showFileList) {
                // ── File list ──
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    if (fileList.isEmpty()) {
                        Text(
                            text = "暂无录音文件",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            modifier = Modifier.align(Alignment.Center),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(fileList) { file ->
                                val fileSizeBytes = file.length()
                                val fileSizeDisplay = if (fileSizeBytes < 1024 * 1024) {
                                    "%.2f KB".format(fileSizeBytes / 1024.0)
                                } else {
                                    "%.2f MB".format(fileSizeBytes / (1024.0 * 1024.0))
                                }
                                Surface(
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                try {
                                                    selectedFileContent = file.readText()
                                                    selectedFileName = file.name
                                                    selectedFilePath = file.absolutePath
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        errorMessage = "读取文件失败: ${e.message}"
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = file.name,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = fileSizeDisplay,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row {
                                            Text(
                                                text = "查看 →",
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .clickable(enabled = true, onClick = { /* handled by Surface click */ })
                                            )
                                            Spacer(Modifier.width(16.dp))
                                            Text(
                                                text = "删除",
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .clickable {
                                                        fileToDelete = file
                                                        showDeleteConfirmDialog = true
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
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
                                    isInitializing = false
                                    Toast.makeText(context, "录音已停止", Toast.LENGTH_SHORT).show()
                                }
                            } else if (!isInitializing) {
                                // Check permission and start
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@Button
                                }

                                if (!isInitialized) {
                                    isInitializing = true
                                    scope.launch {
                                        try {
                                            initializeAndStart(context, recorder,
                                                onError = {
                                                    errorMessage = it
                                                    isInitializing = false
                                                },
                                                onReady = {
                                                    isInitialized = true
                                                    isRecording = true
                                                    isInitializing = false
                                                }
                                            )
                                        } catch (e: Throwable) {
                                            errorMessage = "初始化失败: ${e.message}"
                                            isInitializing = false
                                        }
                                    }
                                } else {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                recorder.startRecording()
                                            }
                                            isRecording = true
                                            isSaved = false
                                            savedFilePath = null
                                        } catch (e: Exception) {
                                            errorMessage = "启动录音失败: ${e.message}"
                                        }
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

        // ── Delete confirmation dialog ──
        if (showDeleteConfirmDialog && fileToDelete != null) {
            AlertDialog(
                onDismissRequest = {
                    showDeleteConfirmDialog = false
                    fileToDelete = null
                },
                title = { Text("确认删除") },
                text = { Text("确定要删除文件「${fileToDelete?.name}」吗？此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = {
                        val file = fileToDelete
                        showDeleteConfirmDialog = false
                        fileToDelete = null
                        if (file != null) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    file.delete()
                                }
                                // Refresh file list
                                withContext(Dispatchers.IO) {
                                    val dir = getTranscriptsDir(context)
                                    fileList = dir?.listFiles { f -> f.name.endsWith(".txt") }
                                        ?.sortedByDescending { it.lastModified() }
                                        ?: emptyList()
                                }
                                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDeleteConfirmDialog = false
                        fileToDelete = null
                    }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

/**
 * Initialize VoiceRecorder on IO thread and start recording.
 * Caller must launch this in its own coroutine scope (e.g. composable scope).
 */
private suspend fun initializeAndStart(
    context: Context,
    recorder: VoiceRecorder,
    onError: (String) -> Unit,
    onReady: () -> Unit
) {
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

/**
 * Get the directory where transcripts are stored.
 */
private fun getTranscriptsDir(context: Context): File? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
    } else {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    }
}

/**
 * Share a text file via Android share sheet using FileProvider,
 * so the actual .txt file is sent to other apps (not just the text content).
 */
private fun shareFile(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享到"))
    } catch (e: Exception) {
        Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
