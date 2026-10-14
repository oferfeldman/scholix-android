package com.feldman.scholix.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.appPrefsDataStore by preferencesDataStore(name = "app_prefs")

/** How the activity's screen orientation is locked. */
enum class OrientationMode(val key: String) {
    AUTO("auto"),
    PORTRAIT("portrait"),
    LANDSCAPE("landscape");

    companion object {
        fun fromKey(key: String?): OrientationMode =
            entries.find { it.key == key } ?: AUTO
    }
}

private object PrefKeys {
    val EXPRESSIVE_DESIGN = booleanPreferencesKey("expressive_design")
    val ORIENTATION_MODE = stringPreferencesKey("orientation_mode")
    val NAVBAR_PAGES = stringSetPreferencesKey("navbar_pages")
    val NAVIGATION_ORDER = stringPreferencesKey("navigation_order")
}

val defaultNavigationOrder = listOf("Grades", "Schedule", "Attendance", "Settings", "Messages", "Homework", "Locker", "Tiktek")

fun Context.navigationOrderFlow(): Flow<List<String>> =
    appPrefsDataStore.data.map { prefs ->
        val saved = prefs[PrefKeys.NAVIGATION_ORDER]?.split("|")
            ?: defaultNavigationOrder.filter { it in (prefs[PrefKeys.NAVBAR_PAGES] ?: emptySet()) }
        (saved + defaultNavigationOrder).distinct().filter { it in defaultNavigationOrder }
    }

suspend fun Context.setNavigationOrder(pages: List<String>) {
    appPrefsDataStore.edit { it[PrefKeys.NAVIGATION_ORDER] = pages.distinct().joinToString("|") }
}

fun Context.expressiveDesignFlow(): Flow<Boolean> =
    appPrefsDataStore.data.map { prefs -> prefs[PrefKeys.EXPRESSIVE_DESIGN] ?: true }

suspend fun Context.setExpressiveDesign(enabled: Boolean) {
    appPrefsDataStore.edit { it[PrefKeys.EXPRESSIVE_DESIGN] = enabled }
}

fun Context.orientationModeFlow(): Flow<OrientationMode> =
    appPrefsDataStore.data.map { prefs -> OrientationMode.fromKey(prefs[PrefKeys.ORIENTATION_MODE]) }

suspend fun Context.setOrientationMode(mode: OrientationMode) {
    appPrefsDataStore.edit { it[PrefKeys.ORIENTATION_MODE] = mode.key }
}
