package com.rd.rd_app.ui.screen.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rd.rd_app.ChatMessage
import com.rd.rd_app.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow(ConfigManager.loadMessages().toMutableList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // Start with empty chat
    }

    fun updateInputText(value: String) {
        _inputText.value = value
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank() || _isLoading.value) return

        addMessage(ChatMessage(text, true))
        _inputText.value = ""
        _isLoading.value = true

        viewModelScope.launch {
            val reply = callLlmApi(_messages.value.toList())
            if (reply != null) {
                addMessage(ChatMessage(reply, false))
            } else {
                addMessage(ChatMessage("请求失败，请检查模型配置和网络连接", false))
            }
            _isLoading.value = false
        }
    }

    fun clearMessages() {
        _messages.update { mutableListOf() }
        ConfigManager.saveMessages(emptyList())
    }

    private fun addMessage(message: ChatMessage) {
        _messages.update { current ->
            current.apply {
                add(message)
                while (size > MAX_MESSAGES) removeAt(0)
            }
        }
        persistMessages()
    }

    private fun persistMessages() {
        ConfigManager.saveMessages(_messages.value)
    }

    private suspend fun callLlmApi(conversation: List<ChatMessage>): String? = withContext(Dispatchers.IO) {
        try {
            val url = ConfigManager.apiUrl.trimEnd('/') + "/chat/completions"

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
                put("model", ConfigManager.modelName)
                put("messages", messagesArray)
                put("stream", false)
            }

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${ConfigManager.apiKey}")
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
                Log.e("ChatViewModel", "API error ${conn.responseCode}: $error")
                null
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Request failed", e)
            null
        }
    }

    companion object {
        private const val MAX_MESSAGES = 100
    }
}
