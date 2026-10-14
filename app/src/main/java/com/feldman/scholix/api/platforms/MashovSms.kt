package com.feldman.scholix.api.platforms

import java.io.IOException

class MashovCaptchaRequired : IOException("Mashov requires CAPTCHA before sending another SMS code")

fun mashovSmsCode(message: String): String? {
    if (!message.contains("משוב", ignoreCase = true) &&
        !message.contains("mashov", ignoreCase = true) &&
        !message.contains("קוד", ignoreCase = true)) return null
    return Regex("""(?<!\d)(\d{4,8})(?!\d)""")
        .findAll(message)
        .map { it.groupValues[1] }
        .firstOrNull()
}
