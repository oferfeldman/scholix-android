package com.feldman.scholix.api

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import androidx.core.content.edit
import kotlinx.coroutines.withContext

object ApiService {
    const val BASE_URL = "https://scholix.web.app/api"
    var sessionCookie: String? = null
    private const val KEY_USER_ID = "user_id"
    private const val PREFS_NAME = "scholix_prefs"

    fun saveUserId(context: Context, userId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    fun getUserId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USER_ID, null)
    }

    fun clearUserData(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_USER_ID).apply()
    }
    fun saveSessionCookie(context: Context) {
        println("saving session cookie")
        val prefs = context.getSharedPreferences("scholix_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString("session_cookie", sessionCookie) }
    }

    fun loadSessionCookie(context: Context) {
        println("loading session cookie")
        val prefs = context.getSharedPreferences("scholix_prefs", Context.MODE_PRIVATE)
        sessionCookie = prefs.getString("session_cookie", null)
    }

    fun clearSessionCookie(context: Context) {
        val prefs = context.getSharedPreferences("scholix_prefs", Context.MODE_PRIVATE)
        prefs.edit { remove("session_cookie") }
        sessionCookie = null
    }


    fun postJson(endpoint: String, body: JSONObject, useAuth: Boolean = true): JSONObject {
        val url = URL("$BASE_URL/$endpoint")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")

        if (useAuth && sessionCookie != null) {
            conn.setRequestProperty("Cookie", sessionCookie)
        }

        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        // Capture new session cookie if provided
        conn.headerFields["Set-Cookie"]?.firstOrNull()?.let {
            if (it.startsWith("session=")) {
                sessionCookie = it.substringBefore(";")
            }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""

        // 🔹 Try to parse JSON, fallback to plain text
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w("ApiService", "Non-JSON response: $text")
            JSONObject().apply {
                put("raw", text)
                put("status_code", code)
            }
        }
    }

    suspend fun getJson(endpoint: String, cookie: String): String =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = URL("$BASE_URL/$endpoint")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Cookie", cookie)
                connectTimeout = 5000
                readTimeout = 5000
            }

            conn.connect()
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText()
            Log.d("ApiService", "GET $endpoint → $code")
            body
        }


}