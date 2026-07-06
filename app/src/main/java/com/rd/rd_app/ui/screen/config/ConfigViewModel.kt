package com.rd.rd_app.ui.screen.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rd.rd_app.ConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConfigViewModel : ViewModel() {

    private val _apiUrl = MutableStateFlow(ConfigManager.apiUrl)
    val apiUrl: StateFlow<String> = _apiUrl.asStateFlow()

    private val _apiKey = MutableStateFlow(ConfigManager.apiKey)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _modelName = MutableStateFlow(ConfigManager.modelName)
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    private val _isEditing = MutableStateFlow(
        ConfigManager.apiUrl.isBlank() && ConfigManager.apiKey.isBlank() && ConfigManager.modelName.isBlank()
    )
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    fun updateApiUrl(value: String) { _apiUrl.value = value }
    fun updateApiKey(value: String) { _apiKey.value = value }
    fun updateModelName(value: String) { _modelName.value = value }

    fun startEditing() {
        _apiUrl.value = ConfigManager.apiUrl
        _apiKey.value = ConfigManager.apiKey
        _modelName.value = ConfigManager.modelName
        _isEditing.value = true
    }

    fun cancelEditing() {
        _isEditing.value = false
    }

    fun saveConfig() {
        val url = _apiUrl.value.trim()
        val key = _apiKey.value.trim()
        val model = _modelName.value.trim()

        if (url.isBlank()) {
            _snackbarMessage.value = "API 地址不能为空"
            return
        }
        if (key.isBlank()) {
            _snackbarMessage.value = "API 密钥不能为空"
            return
        }
        if (model.isBlank()) {
            _snackbarMessage.value = "模型名称不能为空"
            return
        }

        ConfigManager.apiUrl = url
        ConfigManager.apiKey = key
        ConfigManager.modelName = model
        _isEditing.value = false
        _snackbarMessage.value = "配置已保存"
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }
}
