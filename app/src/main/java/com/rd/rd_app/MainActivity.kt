package com.rd.rd_app

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.ScrollView
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.rd.rd_app.ui.screen.chat.ChatScreen
import com.rd.rd_app.ui.screen.config.ConfigScreen
import com.rd.rd_app.ui.screen.login.LoginScreen
import com.rd.rd_app.ui.screen.login.LoginViewModel
import com.rd.rd_app.ui.screen.ocr.OcrScreen
import com.rd.rd_app.ui.screen.profile.ProfileScreen
import com.rd.rd_app.ui.theme.RdAppTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashLog = File(filesDir, "crash.log")

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                crashLog.writeText("${ex.javaClass.name}: ${ex.message}\n$sw")
            } catch (_: Exception) {}
            previousHandler?.uncaughtException(thread, ex)
        }

        try {
            ConfigManager.init(this)
            enableEdgeToEdge()
            setContent {
                RdAppTheme {
                    RdAppApp()
                }
            }
        } catch (e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val errorText = "${e.javaClass.name}: ${e.message}\n\n$sw"
            Log.e("RdApp", errorText)
            crashLog.writeText(errorText)
            showErrorFallback(errorText)
        }
    }

    private fun showErrorFallback(errorText: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "App 启动失败"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        }
        val scroll = ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply {
                text = errorText
                textSize = 12f
                setTypeface(Typeface.MONOSPACE)
                setPadding(0, 16, 0, 16)
            })
        }
        val btn = Button(this).apply {
            text = "重试"
            setOnClickListener { recreate() }
        }
        layout.addView(title)
        layout.addView(scroll)
        layout.addView(btn)
        setContentView(layout)
    }
}

data class ChatMessage(val text: String, val isUser: Boolean)

enum class AppDestinations(
    val label: String,
    val icon: Int,
) {
    HOME("主页", R.drawable.ic_home),
    CONFIG("设置", R.drawable.ic_config),
    PROFILE("我的", R.drawable.ic_account_box),
}

@Composable
fun RdAppApp() {
    var isLoggedIn by rememberSaveable { mutableStateOf(ConfigManager.isLoginValid()) }
    var showRecorder by rememberSaveable { mutableStateOf(false) }
    var showOcr by rememberSaveable { mutableStateOf(false) }
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    Log.d("RdAppApp", "Recompose — isLoggedIn=$isLoggedIn, configLoginValid=${ConfigManager.isLoginValid()}")

    // Back press: go to HOME first, then exit
    BackHandler(enabled = currentDestination != AppDestinations.HOME) {
        currentDestination = AppDestinations.HOME
    }

    if (showRecorder) {
        VideoRecordingScreen(onExit = { showRecorder = false })
    } else if (showOcr) {
        OcrScreen(onExit = { showOcr = false })
    } else if (!isLoggedIn) {
        LoginScreen(
            onLoginSuccess = { _ -> isLoggedIn = true },
            modifier = Modifier.fillMaxSize(),
            viewModel = viewModel<LoginViewModel>().also { it.resetLoginSuccess() }
        )
    } else {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                AppDestinations.entries.forEach { dest ->
                    item(
                        icon = {
                            Icon(
                                painterResource(dest.icon),
                                contentDescription = dest.label
                            )
                        },
                        label = { Text(dest.label) },
                        selected = dest == currentDestination,
                        onClick = { currentDestination = dest }
                    )
                }
            }
        ) {
            when (currentDestination) {
                AppDestinations.HOME -> ChatScreen(modifier = Modifier.fillMaxSize())
                AppDestinations.CONFIG -> ConfigScreen(modifier = Modifier.fillMaxSize())
                AppDestinations.PROFILE -> ProfileScreen(
                    onLogout = {
                        Log.d("RdAppApp", "ProfileScreen onLogout called — setting isLoggedIn=false")
                        isLoggedIn = false
                        currentDestination = AppDestinations.HOME
                    },
                    onStartRecorder = { showRecorder = true },
                    onStartOcr = { showOcr = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
