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
    val NAVBAR_V2 = booleanPreferencesKey("navbar_v2_migrated")
}

val defaultNavbarPages = setOf("Grades", "Schedule", "Attendance", "Settings")

fun Context.navbarPagesFlow(): Flow<Set<String>> =
    appPrefsDataStore.data.map { prefs ->
        val saved = prefs[PrefKeys.NAVBAR_PAGES]
        if (saved == null) {
            defaultNavbarPages
        } else if (prefs[PrefKeys.NAVBAR_V2] != true) {
            val withoutTiktek = saved.filter { it != "Tiktek" }.toSet()
            if (withoutTiktek.size > 4) {
                withoutTiktek.take(4).toSet()
            } else {
                withoutTiktek.ifEmpty { defaultNavbarPages }
            }
        } else {
            val clamped = saved.filter { it != "Tiktek" }.toSet()
            if (clamped.size > 4) clamped.take(4).toSet() else clamped.ifEmpty { defaultNavbarPages }
        }
    }

suspend fun Context.setNavbarPages(pages: Set<String>) {
    require(pages.size in 1..4)
    appPrefsDataStore.edit {
        it[PrefKeys.NAVBAR_PAGES] = pages
        it[PrefKeys.NAVBAR_V2] = true
    }
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
