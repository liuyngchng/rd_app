package com.rd.rd_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
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
import com.rd.rd_app.ui.screen.profile.ProfileScreen
import com.rd.rd_app.ui.theme.RdAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigManager.init(this)
        enableEdgeToEdge()
        setContent {
            RdAppTheme {
                RdAppApp()
            }
        }
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
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    if (showRecorder) {
        VideoRecordingScreen(onExit = { showRecorder = false })
    } else if (!isLoggedIn) {
        LoginScreen(
            onLoginSuccess = { _ -> isLoggedIn = true },
            modifier = Modifier.fillMaxSize()
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
                        isLoggedIn = false
                        currentDestination = AppDestinations.HOME
                    },
                    onStartRecorder = { showRecorder = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
