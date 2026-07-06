package com.rd.rd_app.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rd.rd_app.ConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LoginViewModel : ViewModel() {

    private val _username = MutableStateFlow("test")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("test")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess.asStateFlow()

    fun updateUsername(value: String) {
        _username.value = value
        _errorMessage.value = null
    }

    fun updatePassword(value: String) {
        _password.value = value
        _errorMessage.value = null
    }

    fun resetLoginSuccess() {
        _loginSuccess.value = false
    }

    fun login() {
        if (_username.value == "test" && _password.value == "test") {
            ConfigManager.saveLogin(_username.value)
            _loginSuccess.value = true
        } else {
            _errorMessage.value = "用户名或密码错误"
        }
    }
}
