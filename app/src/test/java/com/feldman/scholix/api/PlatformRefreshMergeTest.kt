package com.feldman.scholix.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformRefreshMergeTest {
    private fun profile(id: String, state: String) = JSONObject().put("id", id).put("session", state)
    private fun list(vararg profiles: JSONObject) = JSONArray(profiles.toList())

    @Test fun refreshCannotOverwriteSignInCompletedWhileItWasRunning() {
        val before = list(profile("account", "expired"))
        val current = list(profile("account", "new verified session"))
        val result = mergeRefreshedPlatforms(before, current, list(profile("account", "failed refresh")))
        assertEquals(current.toString(), result.toString())
    }

    @Test fun unchangedAccountReceivesRefreshedSession() {
        val before = list(profile("account", "old"))
        val update = list(profile("account", "refreshed"))
        assertEquals(update.toString(), mergeRefreshedPlatforms(before, before, update).toString())
    }

    @Test fun refreshPreservesAddedAccountsAndDoesNotRestoreDeletedAccounts() {
        val before = list(profile("removed", "old"), profile("kept", "old"))
        val current = list(profile("new", "verified"), profile("kept", "old"))
        val updates = list(profile("removed", "refreshed"), profile("kept", "refreshed"))
        val result = mergeRefreshedPlatforms(before, current, updates)
        assertEquals(list(profile("new", "verified"), profile("kept", "refreshed")).toString(), result.toString())
    }

    @Test fun accountEditsDuringRefreshArePreserved() {
        val before = list(profile("account", "old"))
        val edited = profile("account", "old").put("name", "My provider")
        val current = list(edited)
        assertEquals(current.toString(), mergeRefreshedPlatforms(before, current,
            list(profile("account", "refreshed"))).toString())
    }
}
