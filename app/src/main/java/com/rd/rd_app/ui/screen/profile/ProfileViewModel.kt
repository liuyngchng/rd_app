package com.rd.rd_app.ui.screen.profile

import androidx.lifecycle.ViewModel
import com.rd.rd_app.ConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProfileViewModel : ViewModel() {

    private val _username = MutableStateFlow(ConfigManager.savedUsername)
    val username: StateFlow<String> = _username.asStateFlow()

    private val _logoutTriggered = MutableStateFlow(false)
    val logoutTriggered: StateFlow<Boolean> = _logoutTriggered.asStateFlow()

    private val _startRecorder = MutableStateFlow(false)
    val startRecorder: StateFlow<Boolean> = _startRecorder.asStateFlow()

    fun logout() {
        ConfigManager.clearLogin()
        _logoutTriggered.value = true
    }

    fun onRecorderStarted() {
        _startRecorder.value = false
    }

    fun triggerRecorder() {
        _startRecorder.value = true
    }
}
