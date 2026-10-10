package com.feldman.scholix.lemida

import java.io.IOException

open class LemidaSessionExpired(message: String = "Sign in to Lemida again to resume automatic updates.") : IOException(message)
class LemidaVerificationRequired : LemidaSessionExpired("Open Sign in to Lemida and complete the browser verification.")
