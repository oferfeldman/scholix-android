package com.feldman.scholix.api.platforms

/** Only accept a unique five-digit code from the single SMS shared by the user. */
internal fun inbarSmsCode(message: String): String? =
    Regex("(?<![0-9])[0-9]{5}(?![0-9])").findAll(message).map { it.value }.toList().singleOrNull()

/** Direct reception only accepts an Inbar/Bar-Ilan-branded verification message. */
internal fun inbarAutomaticSmsCode(message: String, sender: String): String? {
    val brand = (sender + " " + message).lowercase().replace(Regex("[\\s־–—_-]"), "")
    if (listOf("inbar", "barilan", "biu", "אינבר", "בראילן").none { it in brand }) return null
    return inbarSmsCode(message)
}
