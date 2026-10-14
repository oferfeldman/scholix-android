package com.feldman.scholix.api.platforms

import java.io.IOException

class MashovCaptchaRequired : IOException("Mashov requires CAPTCHA before sending another SMS code")

class MashovSmsRateLimited : IOException("Mashov is limiting SMS requests")

fun mashovSmsCode(message: String, sender: String = ""): String? {
    val source = "$message $sender".replace("\"", "").replace("״", "")
    if (!source.contains("משוב", ignoreCase = true) &&
        !source.contains("mashov", ignoreCase = true)) return null
    return Regex("""(?<!\d)(\d{4,8})(?!\d)""")
        .findAll(message)
        .map { it.groupValues[1] }
        .firstOrNull()
}
