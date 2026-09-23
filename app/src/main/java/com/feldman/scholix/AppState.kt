package com.feldman.scholix

import androidx.compose.runtime.compositionLocalOf
import com.feldman.scholix.api.Platform
import com.feldman.scholix.pages.LockerRepository
import com.feldman.scholix.pages.LockerViewModel
import com.feldman.scholix.pages.MessagesViewModel
import org.json.JSONObject

class AppState(
    val preloadedCourses: List<JSONObject>,
    val repository: LockerRepository,
    val platforms: List<Platform>,
    val onPlatformsChanged: () -> Unit,
    val onLogout: () -> Unit,
    val onLoginSuccess: () -> Unit,
    val lockerViewModel: LockerViewModel? = null,
    val navigationPages: List<AppDest> = emptyList(),
    val overflowPages: List<AppDest> = emptyList(),
    val messagesViewModel: MessagesViewModel? = null,
)

val LocalAppState = compositionLocalOf<AppState> {
    error("No AppState provided")
}
