package com.feldman.scholix.api

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks if the Scholix session cookie is still valid by calling /api/user/me.
 * Returns true if authenticated, false otherwise.
 */
suspend fun checkScholixLoggedIn(context: Context): Boolean {
    Log.d("ScholixAuth", "🟡 Starting Scholix login check")

    return try {
        ApiService.loadSessionCookie(context)
        val cookie = ApiService.sessionCookie

        if (cookie.isNullOrBlank()) {
            Log.w("ScholixAuth", "⚠️ No saved session cookie found — user likely logged out")
            return false
        }

        Log.d("ScholixAuth", "🔹 Using cookie: $cookie")

        val urlString = "${ApiService.BASE_URL}/user/me"
        Log.d("ScholixAuth", "🌐 Connecting to $urlString")

        val conn = (java.net.URL(urlString).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Cookie", cookie)
            connectTimeout = 5000
            readTimeout = 5000
        }

        conn.connect()
        val code = conn.responseCode
        val ok = code == 200
        if (!ok) Log.w("ScholixAuth", "❌ Scholix session invalid (HTTP $code)")
        ok
    } catch (e: Exception) {
        Log.e("ScholixAuth", "💥 checkScholixLoggedIn failed", e)
        false
    }
}

/**
 * Returns how many platforms are saved in local storage.
 */
suspend fun countPlatforms(context: Context): Int = withContext(Dispatchers.IO) {
    return@withContext try {
        val platforms = PlatformStorage.loadPlatforms(context)
        val count = platforms.size
        Log.d("PlatformState", "📊 Found $count platforms saved locally")
        count
    } catch (e: Exception) {
        Log.e("PlatformState", "💥 Failed to count platforms", e)
        0
    }
}

/**
 * Returns true if there’s at least one platform currently logged in.
 */
suspend fun hasLoggedInPlatforms(context: Context): Boolean = withContext(Dispatchers.IO) {
    return@withContext try {
        ApiService.loadSessionCookie(context)
        val cookie = ApiService.sessionCookie

        if (cookie.isNullOrBlank()) {
            Log.w("PlatformState", "⚠️ No Scholix session cookie found — cannot fetch platforms")
            return@withContext false
        }

        val url = URL("${ApiService.BASE_URL}/user/platforms")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Cookie", cookie)
            connectTimeout = 5000
            readTimeout = 5000
        }

        conn.connect()
        val code = conn.responseCode
        val body = conn.inputStream.bufferedReader().readText()

        if (code != 200) {
            Log.w("PlatformState", "❌ Failed to fetch platforms: HTTP $code — body: $body")
            return@withContext false
        }

        val json = JSONObject(body)
        val platformsObj = json.optJSONObject("platforms")
        if (platformsObj == null || platformsObj.length() == 0) {
            Log.w("PlatformState", "⚠️ No platforms found in response")
            return@withContext false
        }

        var loggedInCount = 0
        val keys = platformsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val platform = platformsObj.optJSONObject(key)
            val loggedIn = platform?.optBoolean("logged_in", false) ?: false
            Log.d("PlatformState", "🔹 Platform $key logged_in=$loggedIn")
            if (loggedIn) loggedInCount++
        }

        val anyLoggedIn = loggedInCount > 0
        Log.i("PlatformState", "✅ Found ${platformsObj.length()} platforms, $loggedInCount logged in")
        anyLoggedIn
    } catch (e: Exception) {
        Log.e("PlatformState", "💥 hasLoggedInPlatforms API call failed", e)
        false
    }
}

suspend fun scholixLogout(context: Context): Boolean = withContext(Dispatchers.IO) {
    Log.d("ScholixAuth", "🔴 Starting logout process")

    try {
        ApiService.loadSessionCookie(context)
        val cookie = ApiService.sessionCookie

        if (!cookie.isNullOrBlank()) {
            val url = URL("${ApiService.BASE_URL}/logout")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Cookie", cookie)
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }

            conn.connect()
            val code = conn.responseCode
            Log.d("ScholixAuth", "🌐 Logout request returned HTTP $code")

            if (code != 200) {
                Log.w("ScholixAuth", "⚠️ Logout endpoint returned non-200 ($code)")
            }
        } else {
            Log.w("ScholixAuth", "⚠️ No active session cookie found during logout")
        }

        // 🔹 Clear local session and platforms
        ApiService.clearSessionCookie(context)

        Log.i("ScholixAuth", "✅ Scholix session cleared successfully")
        true
    } catch (e: Exception) {
        Log.e("ScholixAuth", "💥 Logout failed", e)
        false
    }
}