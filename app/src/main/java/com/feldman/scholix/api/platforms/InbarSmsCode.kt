package com.feldman.scholix.api.platforms

/** Only accept a unique five-digit code from the single SMS shared by the user. */
internal fun inbarSmsCode(message: String): String? =
    Regex("(?<![0-9])[0-9]{5}(?![0-9])").findAll(message).map { it.value }.toList().singleOrNull()
