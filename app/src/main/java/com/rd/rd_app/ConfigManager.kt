package com.rd.rd_app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

object ConfigManager {
    private const val PREFS_NAME = "llm_config"
    private const val KEY_API_URL = "api_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_LOGGED_USER = "logged_user"
    private const val KEY_LOGIN_TIME = "login_time"
    private const val KEY_CHAT_MESSAGES = "chat_messages"
    private const val LOGIN_VALID_DAYS = 7L

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var apiUrl: String
        get() = prefs.getString(KEY_API_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_URL, value) }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    var modelName: String
        get() = prefs.getString(KEY_MODEL_NAME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_MODEL_NAME, value) }

    val savedUsername: String
        get() = prefs.getString(KEY_LOGGED_USER, "") ?: ""

    fun isLoginValid(): Boolean {
        val username = savedUsername
        if (username.isBlank()) return false
        val lastTime = prefs.getLong(KEY_LOGIN_TIME, 0L)
        if (lastTime == 0L) return false
        return System.currentTimeMillis() - lastTime < LOGIN_VALID_DAYS * 24 * 60 * 60 * 1000L
    }

    fun saveLogin(username: String) {
        prefs.edit {
            putString(KEY_LOGGED_USER, username)
            putLong(KEY_LOGIN_TIME, System.currentTimeMillis())
        }
    }

    fun clearLogin() {
        prefs.edit {
            putString(KEY_LOGGED_USER, "")
            putLong(KEY_LOGIN_TIME, 0L)
        }
    }

    fun saveMessages(messages: List<ChatMessage>) {
        val json = JSONArray()
        for (msg in messages) {
            val obj = JSONObject()
            obj.put("text", msg.text)
            obj.put("isUser", msg.isUser)
            json.put(obj)
        }
        prefs.edit { putString(KEY_CHAT_MESSAGES, json.toString()) }
    }

    fun loadMessages(): List<ChatMessage> {
        val jsonStr = prefs.getString(KEY_CHAT_MESSAGES, null) ?: return emptyList()
        return try {
            val json = JSONArray(jsonStr)
            (0 until json.length()).map { i ->
                val obj = json.getJSONObject(i)
                ChatMessage(obj.getString("text"), obj.getBoolean("isUser"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
