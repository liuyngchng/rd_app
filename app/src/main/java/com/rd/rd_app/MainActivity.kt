package com.rd.rd_app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rd.rd_app.ui.theme.Rd_appTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigManager.init(this)
        enableEdgeToEdge()
        setContent {
            Rd_appTheme {
                Rd_appApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun Rd_appApp() {
    var isLoggedIn by rememberSaveable { mutableStateOf(false) }
    var loggedInUsername by rememberSaveable { mutableStateOf("") }
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    if (!isLoggedIn) {
        LoginPage(
            onLoginSuccess = { username -> loggedInUsername = username; isLoggedIn = true },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                AppDestinations.entries.forEach {
                    item(
                        icon = {
                            Icon(
                                painterResource(it.icon),
                                contentDescription = it.label
                            )
                        },
                        label = { Text(it.label) },
                        selected = it == currentDestination,
                        onClick = { currentDestination = it }
                    )
                }
            }
        ) {
            when (currentDestination) {
                AppDestinations.HOME -> ChatPage(
                    modifier = Modifier.fillMaxSize()
                )
                AppDestinations.CONFIG -> ConfigPage(
                    modifier = Modifier.fillMaxSize()
                )
                AppDestinations.PROFILE -> ProfilePage(
                    username = loggedInUsername,
                    onLogout = { isLoggedIn = false; currentDestination = AppDestinations.HOME },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: Int,
) {
    HOME("主页", R.drawable.ic_home),
    CONFIG("设置", R.drawable.ic_config),
    PROFILE("我的", R.drawable.ic_account_box),
}

object ConfigManager {
    private const val PREFS_NAME = "llm_config"
    private const val KEY_API_URL = "api_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL_NAME = "model_name"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var apiUrl: String
        get() = prefs.getString(KEY_API_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_URL, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var modelName: String
        get() = prefs.getString(KEY_MODEL_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL_NAME, value).apply()
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "$name, 这是 rd 的测试 app.",
        modifier = modifier
    )
}

@Composable
fun ConfigPage(modifier: Modifier = Modifier) {
    val hasConfig = ConfigManager.apiUrl.isNotBlank() || ConfigManager.apiKey.isNotBlank() || ConfigManager.modelName.isNotBlank()
    var isEditing by rememberSaveable { mutableStateOf(!hasConfig) }
    var currentStep by rememberSaveable { mutableStateOf(0) }
    var apiUrl by remember { mutableStateOf(ConfigManager.apiUrl) }
    var apiKey by remember { mutableStateOf(ConfigManager.apiKey) }
    var modelName by remember { mutableStateOf(ConfigManager.modelName) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val stepTitles = listOf("API URL", "API 密钥", "模型名称")
    val stepDescriptions = listOf(
        "请输入模型的 API 地址",
        "请输入 API 密钥",
        "请输入模型名称"
    )

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isEditing) {
                Text(
                    text = "模型配置",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ConfigInfoRow(label = "API URL", value = ConfigManager.apiUrl)
                        ConfigInfoRow(label = "API 密钥", value = if (ConfigManager.apiKey.isNotBlank()) "••••••" else "")
                        ConfigInfoRow(label = "模型名称", value = ConfigManager.modelName)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = { isEditing = true; currentStep = 0 },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("修    改",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            } else {
                if (currentStep > 0) {
                    Text(
                        text = "<-",
                        modifier = Modifier.clickable { currentStep-- },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = "模型配置",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "步骤 ${currentStep + 1} / 3：${stepTitles[currentStep]}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = stepDescriptions[currentStep],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (currentStep) {
                    0 -> OutlinedTextField(
                        value = apiUrl,
                        onValueChange = { apiUrl = it },
                        label = { Text("API URL") },
                        placeholder = { Text("https://api.deepseek.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    1 -> OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API 密钥") },
                        placeholder = { Text("sk-...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    2 -> OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = { Text("模型名称") },
                        placeholder = { Text("deepseek-chat") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))

                    if (currentStep < 2) {
                        Button(
                            onClick = { currentStep++ },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            enabled = when (currentStep) {
                                0 -> apiUrl.isNotBlank()
                                1 -> apiKey.isNotBlank()
                                else -> true
                            }
                        ) {
                            Text("下一步",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                ConfigManager.apiUrl = apiUrl
                                ConfigManager.apiKey = apiKey
                                ConfigManager.modelName = modelName
                                apiUrl = apiUrl
                                apiKey = apiKey
                                modelName = modelName
                                isEditing = false
                                scope.launch {
                                    snackbarHostState.showSnackbar("配置已保存", duration = SnackbarDuration.Short)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("保    存",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ConfigInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value.ifBlank { "未设置" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (value.isNotBlank()) MaterialTheme.colorScheme.onSurface else Color.Gray
        )
    }
}

data class ChatMessage(val text: String, val isUser: Boolean)

@Composable
fun ChatPage(modifier: Modifier = Modifier) {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var inputText by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        messages.add(ChatMessage("你好！我是AI助手，有什么可以帮你的吗？", false))
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                ChatBubble(message)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (isLoading) "等待回复..." else "输入消息...") },
                singleLine = true,
                enabled = !isLoading
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (inputText.isNotBlank() && !isLoading) {
                        val userMsg = inputText.trim()
                        messages.add(ChatMessage(userMsg, true))
                        inputText = ""
                        isLoading = true

                        scope.launch {
                            val reply = callLlmApi(
                                configUrl = ConfigManager.apiUrl,
                                apiKey = ConfigManager.apiKey,
                                model = ConfigManager.modelName,
                                conversation = messages.toList()
                            )
                            if (reply != null) {
                                messages.add(ChatMessage(reply, false))
                            } else {
                                messages.add(ChatMessage("请求失败，请检查模型配置和网络连接", false))
                            }
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_send),
                    contentDescription = "发送",
                    tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private suspend fun callLlmApi(
    configUrl: String,
    apiKey: String,
    model: String,
    conversation: List<ChatMessage>
): String? = withContext(Dispatchers.IO) {
    try {
        val url = configUrl.trimEnd('/') + "/chat/completions"

        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply {
            put("role", "system")
            put("content", "你是一个聊天助手，使用中文给出回答.")
        })
        conversation.forEach { msg ->
            messagesArray.put(JSONObject().apply {
                put("role", if (msg.isUser) "user" else "assistant")
                put("content", msg.text)
            })
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("stream", false)
        }

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000

        conn.outputStream.write(requestBody.toString().toByteArray())

        if (conn.responseCode == 200) {
            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } else {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            Log.e("ChatPage", "API error ${conn.responseCode}: $error")
            null
        }
    } catch (e: Exception) {
        Log.e("ChatPage", "Request failed", e)
        null
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val alignment = if (message.isUser) Alignment.TopEnd else Alignment.TopStart
    val bgColor = if (message.isUser)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (message.isUser)
        Color.White
    else
        MaterialTheme.colorScheme.onSurface

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            ),
            color = bgColor
        ) {
            Text(
                text = message.text,
                color = textColor,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun ProfilePage(username: String, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            Toast.makeText(context, "拍照成功", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            cameraLauncher.launch(null)
        } else {
            Toast.makeText(context, "需要相机权限才能使用扫一扫", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "当前用户：$username",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = onLogout,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("退    出",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
            }
        }

        // Top-right circled "+" button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = "+",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("拍照") },
                    onClick = {
                        showMenu = false
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            cameraLauncher.launch(null)
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Rd_appTheme {
        Greeting("小安卓")
    }
}

@Composable
fun LoginPage(onLoginSuccess: (username: String) -> Unit, modifier: Modifier = Modifier) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "AI 助手",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = MaterialTheme.typography.headlineMedium.fontSize * 1.5f
            ),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(48.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; errorMessage = "" },
            label = { Text("用户名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                color = Color.Black
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; errorMessage = "" },
            label = { Text("密码") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                color = Color.Black
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                color = Color.Red,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = {
                if (username == "avata" && password == "avata") {
                    onLoginSuccess(username)
                } else {
                    errorMessage = "用户名或密码错误"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("登   录",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
        }
    }
}