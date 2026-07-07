package com.rd.rd_app.ui.screen.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import com.rd.rd_app.ConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProfileViewModel : ViewModel() {
    companion object {
        private const val TAG = "ProfileViewModel"
    }

    private val _username = MutableStateFlow(ConfigManager.savedUsername)
    val username: StateFlow<String> = _username.asStateFlow()

    private val _logoutTriggered = MutableStateFlow(false)
    val logoutTriggered: StateFlow<Boolean> = _logoutTriggered.asStateFlow()

    private val _startRecorder = MutableStateFlow(false)
    val startRecorder: StateFlow<Boolean> = _startRecorder.asStateFlow()

    private val _startOcr = MutableStateFlow(false)
    val startOcr: StateFlow<Boolean> = _startOcr.asStateFlow()

    private val _startObjectDetection = MutableStateFlow(false)
    val startObjectDetection: StateFlow<Boolean> = _startObjectDetection.asStateFlow()

    fun logout() {
        Log.d(TAG, "logout() called — clearing login")
        ConfigManager.clearLogin()
        Log.d(TAG, "isLoginValid after clear: ${ConfigManager.isLoginValid()}")
        _logoutTriggered.value = true
        Log.d(TAG, "logoutTriggered set to true")
    }

    fun resetLogoutTrigger() {
        _logoutTriggered.value = false
    }

    fun onRecorderStarted() {
        _startRecorder.value = false
    }

    fun onOcrStarted() {
        _startOcr.value = false
    }

    fun onObjectDetectionStarted() {
        _startObjectDetection.value = false
    }

    fun triggerRecorder() {
        _startRecorder.value = true
    }

    fun triggerOcr() {
        _startOcr.value = true
    }

    fun triggerObjectDetection() {
        _startObjectDetection.value = true
    }
}
