package com.feldman.scholix.lemida

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

/** Synthetic enrollment changes; isolated preferences, no browser, real account or SMS. */
class LemidaCourseDiscoveryTest {
    private class Courses : LemidaTransport {
        var user = "42"
        var courses = listOf(LemidaCourse(1, "Existing course"))
        var failCourse: Int? = null
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override suspend fun prepare() = Unit
        override suspend fun close() = Unit
        override suspend fun get(url: String) = """<body><a href='/login/logout.php'>Logout</a>
            <script>M.cfg={"userId":"$user","sesskey":"fixture"};</script></body>"""
        override suspend fun post(url: String, body: String): String {
            gate?.let { entered.complete(Unit); it.await() }
            val request = JSONArray(body).getJSONObject(0)
            val args = request.getJSONObject("args")
            val data: Any = if (request.getString("methodname") == "core_courseformat_get_state") {
                val id = args.getInt("courseid")
                if (id == failCourse) throw IOException("Synthetic course read failed")
                if (id == 1) """{"cm":[{"name":"Exercise","url":"https://lemida.biu.ac.il/mod/assign/view.php?id=9"}]}"""
                else """{"cm":[]}"""
            } else {
                val batch = if(args.getInt("offset")==0)courses else emptyList()
                JSONObject().put("courses", JSONArray(batch.map { JSONObject().put("id",it.id).put("fullname",it.name) }))
                    .put("nextoffset", courses.size)
            }
            return JSONArray().put(JSONObject().put("error",false).put("data",data)).toString()
        }
    }
    private fun isolated(block: suspend (LemidaRepository, Courses, android.content.SharedPreferences) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "lemida_courses_test_${UUID.randomUUID()}"
        val transport = Courses()
        try { block(LemidaRepository(context,name) { _,_,_ -> transport },transport,context.getSharedPreferences(name,Context.MODE_PRIVATE)) }
        finally { context.deleteSharedPreferences(name) }
    }
    @Test fun subsequentSyncAddsCoursesBeforeTheyHaveAnyHomework() = isolated { repo, transport, _ ->
        repo.sync()
        val existing = repo.cached()
        transport.courses += LemidaCourse(2,"New course")
        repo.sync()
        assertEquals(transport.courses,repo.cachedCourses())
        assertEquals(existing,repo.cached())
        val snapshot = withTimeout(5_000) { repo.snapshots.first() }
        assertEquals(2,snapshot.courses.size)
        assertEquals(existing,snapshot.homework)
    }
    @Test fun incompleteSyncPreservesThePreviousCompleteCourseCatalog() = isolated { repo, transport, _ ->
        repo.sync()
        val original = repo.cachedCourses()
        val updated = repo.lastSync()
        transport.courses += LemidaCourse(2,"New course")
        transport.failCourse = 2
        try {repo.sync();fail("Incomplete update must fail")}catch(_:IOException){}
        assertEquals(original,repo.cachedCourses())
        assertEquals(updated,repo.lastSync())
        assertEquals(1,repo.cached().size)
    }
    @Test fun anotherAccountsCoursesReplaceTheOldCatalog() = isolated { repo, transport, _ ->
        repo.sync()
        transport.user = "99"
        transport.courses = listOf(LemidaCourse(3,"Another account course"))
        repo.sync()
        assertEquals(transport.courses,repo.cachedCourses())
        assertTrue(repo.cached().isEmpty())
    }
    @Test fun existingInstallationsKeepTheirCourseFiltersBeforeFirstRefresh() = isolated { repo, _, prefs ->
        prefs.edit().putString("homework",LemidaParser.encode(listOf(
            Homework("1:assign:9",1,"Existing course","Exercise","assign","","")))).commit()
        assertEquals(listOf(LemidaCourse(1,"Existing course")),repo.cachedCourses())
    }
    @Test fun cachedScreenRemainsAvailableWhileNetworkSyncIsWaiting() = isolated { repo, transport, _ ->
        repo.sync()
        val previous = repo.cached()
        transport.gate = CompletableDeferred()
        coroutineScope {
            val sync = async(Dispatchers.IO) {repo.sync()}
            try {
                withTimeout(5_000) {transport.entered.await()}
                val snapshot = withTimeout(2_000) {repo.snapshots.first()}
                assertEquals(previous,snapshot.homework)
                assertTrue(snapshot.loaded)
            } finally {transport.gate!!.complete(Unit);sync.await()}
        }
    }
}
