package com.feldman.scholix.api

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Year
import kotlin.system.measureTimeMillis

data class Course(
    val name: String,
    val platformId: String,
    val semesterPicker: Boolean = false
)

data class Grade(val subject: String, val name: String, val grade: String, val type: String)

sealed class GradesUiState {
    object Loading : GradesUiState()
    data class Success(
        val courses: List<Course>,
        val selectedCourseIndex: Int,
        val grades: List<Grade>,
        val average: Int,
        val finalGrade: Grade?,
        val isRefreshing: Boolean
    ) : GradesUiState()
    data class Error(val message: String) : GradesUiState()
}
private const val PREFS_NAME = "scholix_prefs"
private const val KEY_COURSES = "saved_courses"

class GradesViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<GradesUiState>(GradesUiState.Loading)
    val uiState: StateFlow<GradesUiState> = _uiState

    private var courses = listOf<Course>()
    private var selectedCourseIndex = 0
    private var semester = "A"
    private var year = Year.now().value
    private fun saveCoursesLocally(context: Context, courses: List<Course>) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = JSONArray().apply {
                for (c in courses) {
                    put(JSONObject().apply {
                        put("name", c.name)
                        put("platformId", c.platformId)
                        put("semesterPicker", c.semesterPicker)
                    })
                }
            }
            prefs.edit().putString(KEY_COURSES, arr.toString()).apply()
            Log.d("GradesViewModel", "💾 Saved ${courses.size} courses locally")
        } catch (e: Exception) {
            Log.w("GradesViewModel", "⚠️ Failed to save courses locally: ${e.message}")
        }
    }

    private fun loadCoursesLocally(context: Context): List<Course> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_COURSES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                Course(
                    name = obj.optString("name", "Course ${i + 1}"),
                    platformId = obj.optString("platformId", ""),
                    semesterPicker = obj.optBoolean("semesterPicker", false)
                )
            }
        } catch (e: Exception) {
            Log.w("GradesViewModel", "⚠️ Failed to load local courses: ${e.message}")
            emptyList()
        }
    }

    fun loadCourses(context: Context) {
        _uiState.value = GradesUiState.Loading

        viewModelScope.launch {
            try {
                // 1️⃣ Try load locally first
                val localCourses = loadCoursesLocally(context)
                if (localCourses.isNotEmpty()) {
                    courses = localCourses
                    selectedCourseIndex = 0
                    Log.d("GradesViewModel", "📦 Loaded ${courses.size} courses from cache")
                    // Display immediately while network refreshes in background
                    loadGrades(context)
                    // Continue to update in background
                    viewModelScope.launch(Dispatchers.IO) {
                        refreshCoursesFromServer(context)
                    }
                    return@launch
                }

                // 2️⃣ Otherwise, fetch from network
                refreshCoursesFromServer(context)

            } catch (e: Exception) {
                _uiState.value = GradesUiState.Error("Failed to load courses: ${e.message}")
            }
        }
    }
    private suspend fun refreshCoursesFromServer(context: Context) {
        try {
            val cookie = withContext(Dispatchers.IO) {
                ApiService.loadSessionCookie(context)
                ApiService.sessionCookie
            } ?: run {
                _uiState.value = GradesUiState.Error("No active Scholix session. Please log in.")
                return
            }

            val response = withContext(Dispatchers.IO) {
                ApiService.getJson("user/courses", cookie)
            }

            val json = JSONObject(response)
            val coursesArray = json.optJSONArray("courses") ?: JSONArray()
            if (coursesArray.length() == 0) {
                _uiState.value = GradesUiState.Error("No courses found. Try logging in.")
                return
            }

            courses = (0 until coursesArray.length()).map { i ->
                val obj = coursesArray.getJSONObject(i)
                val courseObj = obj.optJSONObject("course") ?: JSONObject()
                Course(
                    name = courseObj.optString("name", "Course ${i + 1}"),
                    platformId = obj.optString("platform_id", ""),
                    semesterPicker = courseObj.optBoolean("semesterPicker", false),
                )
            }

            // ✅ Save locally for next time
            saveCoursesLocally(context, courses)

            selectedCourseIndex = 0
            loadGrades(context)
        } catch (e: Exception) {
            Log.e("GradesViewModel", "💥 Failed to refresh courses", e)
            _uiState.value = GradesUiState.Error("Failed to refresh courses: ${e.message}")
        }
    }


    fun selectCourse(index: Int) {
        selectedCourseIndex = index
    }

    fun selectSemester(year: Int, semester: String) {
        this.year = year
        this.semester = semester
    }

    fun refreshGrades(context: Context) {
        loadGrades(context, isRefresh = true)
    }
    fun loadGrades(
        context: Context,
        index: Int = selectedCourseIndex,
        isRefresh: Boolean = false
    ) {
        viewModelScope.launch {
            try {
                ApiService.loadSessionCookie(context)
                val cookie = ApiService.sessionCookie ?: return@launch
                val course = courses.getOrNull(index) ?: return@launch

                val body = JSONObject().apply {
                    put("platform_id", course.platformId)
                    put("course", course.name)
                    put("year", year)
                    put("semester", semester.lowercase())
                }

                var json: JSONObject? = null
                val elapsed = measureTimeMillis {
                    json = withContext(Dispatchers.IO) {
                        ApiService.postJson("getgrades", body)
                    }
                }

                Log.d("GradesViewModel", "⏱️ loadGrades() response in ${elapsed}ms for ${course.name}")

                val response = json ?: return@launch
                if (response.has("error")) {
                    _uiState.value = GradesUiState.Error(response.getString("error"))
                    return@launch
                }

                val gradesArray = response.optJSONArray("grades") ?: JSONArray()
                val (grades, average, finalGrade) = processGrades(gradesArray)

                _uiState.value = GradesUiState.Success(
                    courses = courses,
                    selectedCourseIndex = index,
                    grades = grades,
                    average = average,
                    finalGrade = finalGrade,
                    isRefreshing = false
                )
            } catch (e: Exception) {
                Log.e("GradesViewModel", "💥 loadGrades() failed", e)
                _uiState.value = GradesUiState.Error("Failed to load grades: ${e.message}")
            }
        }
    }
    fun loadGrades(context: Context, isRefresh: Boolean = false) {
        viewModelScope.launch {
            try {
                val cookie = withContext(Dispatchers.IO) {
                    ApiService.loadSessionCookie(context)
                    ApiService.sessionCookie
                } ?: run {
                    _uiState.value = GradesUiState.Error("No Scholix session found.")
                    return@launch
                }

                val course = courses[selectedCourseIndex]
                val body = JSONObject().apply {
                    put("platform_id", course.platformId)
                    put("course", course.name)
                    put("year", year)
                    put("semester", semester.lowercase())
                }

                Log.d("GradesViewModel", "📡 POST /api/getgrades $body")

                val json = withContext(Dispatchers.IO) {
                    ApiService.postJson("getgrades", body)
                }


                if (json.has("error")) {
                    _uiState.value = GradesUiState.Error(json.getString("error"))
                    return@launch
                }

                val gradesArray = json.optJSONArray("grades") ?: JSONArray()
                val (grades, average, finalGrade) = processGrades(gradesArray)

                _uiState.value = GradesUiState.Success(
                    courses = courses,
                    selectedCourseIndex = selectedCourseIndex,
                    grades = grades,
                    average = average,
                    finalGrade = finalGrade,
                    isRefreshing = isRefresh
                )
            } catch (e: Exception) {
                Log.e("GradesViewModel", "💥 Failed to load grades", e)
                _uiState.value = GradesUiState.Error("Failed to load grades: ${e.message}")
            }
        }
    }

    private fun processGrades(gradesArray: JSONArray): Triple<List<Grade>, Int, Grade?> {
        val list = mutableListOf<Grade>()
        var finalGrade: Grade? = null
        var sum = 0
        var count = 0

        for (i in 0 until gradesArray.length()) {
            val g = gradesArray.optJSONObject(i) ?: continue
            if (g.optString("grade") == "null") continue

            val gradeInt = g.optInt("grade", -1)
            val gradeString = g.optString("grade", "-1")
            val type = g.optString("type", "")

            if (type == "final") {
                finalGrade = Grade(
                    subject = g.optString("subject", ""),
                    name = g.optString("name", ""),
                    grade = gradeString,
                    type = "final"
                )
            } else {
                if (gradeInt >= 0) {
                    sum += gradeInt
                    count++
                }
                list.add(
                    Grade(
                        subject = g.optString("subject", ""),
                        name = g.optString("name", ""),
                        grade = gradeString,
                        type = type
                    )
                )
            }
        }

        val avg = if (count > 0) sum / count else 0
        return Triple(list, avg, finalGrade)
    }
}
