package com.feldman.scholix.lockerim

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** LockerimRB 5.1.0.2354's customer API. All lock credentials come from the signed-in account. */
class LockerimApi(
    private val baseUrl: String = "https://rbapi.lockerim.co.il",
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
) {
    fun request(
        path: String,
        token: String = "",
        body: JSONObject? = null,
        method: String = if (body == null) "GET" else "POST"
    ): Any {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/${path.trimStart('/')}".toHttpUrl())
            .header("Accept", "application/json")
        if (token.isNotBlank()) request.header("Authorization", "Bearer $token")
        request.method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(request.build()).execute().use { response ->
            val text = response.body.string()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val errors = json?.optJSONArray("errors")
            if (!response.isSuccessful || json?.optBoolean("isSucceeded", true) == false || (errors != null && errors.length() > 0)) {
                val message = errors?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: if (response.code == 401) "Your Lockerim session expired. Reconnect to continue."
                    else "Lockerim request failed (${response.code})."
                throw LockerimApiException(response.code, message)
            }
            return json?.opt("content")?.takeUnless { it == JSONObject.NULL }
                ?: throw IOException("Lockerim returned an empty response.")
        }
    }

    fun sendCode(phone: String, captcha: String): JSONObject = request("Account/Login", body = JSONObject()
        .put("phoneNumber", phone).put("RecaptchaKey", captcha)) as JSONObject

    fun resendCode(otpToken: String, captcha: String): JSONObject = request("Account/ResendLoginOtp", body = JSONObject()
        .put("otpToken", otpToken).put("RecaptchaKey", captcha)) as JSONObject

    fun verifyCode(otpToken: String, code: String, captcha: String): JSONObject = request("Account/ValidateLoginOtp", body = JSONObject()
        .put("otpToken", otpToken).put("otp", code).put("RecaptchaKey", captcha)) as JSONObject
}

class LockerimApiException(val status: Int, message: String) : IOException(message)

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
