package com.feldman.scholix.api.platforms

import android.content.Context
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.UnsafeOkHttpClient
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.Year
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class DemoPlatform: Platform {
    override var platformDisplayName: String = "DemoPlatform"

    private val loginFields = LoginFields()

    override var loggedIn: Boolean = false
    override var editing: Boolean = false

    var displayName: String? = null
    var _username: String? = null
    var _password: String? = null

    private val courses: ArrayList<JSONObject> = ArrayList()
    override var id: String = generateId()
        private set

    override val suportsGrades: Boolean = true
    override val supportsSchedule: Boolean = true
    override val supportsAttendance: Boolean = true
    private val STATIC_SCHEDULE = JSONArray().apply {

        // ===== DAY 0 =====
        put(
            JSONArray().apply {
                // ORIGINAL DAY 0
                put(
                    JSONObject().apply {
                        put("1", hour(1,"ביולוגיה","אורי רווה",findColorClass("ביולוגיה")))
                        put("2", hour(2,"של\"ח","נחשון שמר",findColorClass("של\"ח")))
                        put("3", hour(3,"עברית","חגית ליבוביץ",findColorClass("עברית")))
                        put("4", hour(4,"תנ\"ך","חי אשרי",findColorClass("תנ\"ך")))
                        put("5", hour(5,"אנגלית א","ויקי ינאי",findColorClass("אנגלית א")))
                        put("6", hour(6,"אנגלית א","ויקי ינאי",findColorClass("אנגלית א")))
                        put("7", hour(7,"מתמטיקה האצה","רמי טבקה",findColorClass("מתמטיקה האצה")))
                        put("8", hour(8,"מתמטיקה האצה","רמי טבקה",findColorClass("מתמטיקה האצה")))
                    }
                )

                // UPDATED DAY 0
                put(
                    JSONObject().apply {
                        put("1", hour(1,"מתמטיקה","רון נתניהו","lightgreen-cell"))
                        put("2", hourCanceled(2,"עברית","יעל כהן"))
                        put("3", hourExam(3,"אנגלית","מירב שלו","lime-cell"))
                        put("4", hourSub(4,"היסטוריה","תמר אוחנה"))
                        put("5", hour(5,"תנ\"ך","רועי ברק","lightgrey-cell"))
                    }
                )
            }
        )

        // ===== DAY 1 =====
        put(
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("1", hour(1,"של\"ח","נחשון שמר",findColorClass("של\"ח")))
                        put("2", hour(2,"היסטוריה","אביגיל קרפל",findColorClass("היסטוריה")))
                        put("3", hour(3,"דוברי אנגלית","רוני אהרן","pink-cell"))
                        put("4", hour(4,"דוברי אנגלית","רוני אהרן","pink-cell"))
                        put("5", hour(5,"חינוך גופני","מיכה פולק",findColorClass("חינוך גופני")))
                        put("6", hour(6,"עברית","חגית ליבוביץ",findColorClass("עברית")))
                    }
                )

                put(
                    JSONObject().apply {
                        put("1", hour(1,"של\"ח","נחשון שמר",findColorClass("של\"ח")))
                        put("2", hour(2,"עברית","חגית ליבוביץ",findColorClass("עברית")))
                        put("3", hour(3,"דוברי אנגלית - מילוי מקום","איסקוב ענבר טלי","pink-cell"))
                        put("4", hour(4,"דוברי אנגלית - מילוי מקום","איסקוב ענבר טלי","pink-cell"))
                    }
                )
            }
        )

        // ===== DAY 2 =====
        put(
            JSONArray().apply {

                put(
                    JSONObject().apply {
                        put("1", hour(1,"עברית","יעל כהן","lightpurple-cell"))
                        put("2", hour(2,"אנגלית","מירב שלו","lime-cell"))
                        put("3", hour(3,"חינוך","שרה דוד","pink-cell"))
                        put("4", hour(4,"ערבית","אורי חן","lightblue-cell"))
                    }
                )

                put(
                    JSONObject().apply {
                        put("1", hour(1,"עברית","יעל כהן","lightpurple-cell"))
                        put("2", hourExam(2,"אנגלית","מירב שלו","lime-cell"))
                        put("3", hourSub(3,"חינוך","דנה רבין"))
                        put("4", hour(4,"ערבית","אורי חן","lightblue-cell"))
                    }
                )
            }
        )

        // ===== DAY 3 =====
        put(
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("1", hour(1,"ספרות","מירב שלו","blue-cell"))
                        put("2", hour(2,"עברית","יעל כהן","lightpurple-cell"))
                        put("3", hour(3,"אנגלית","רועי ברק","lime-cell"))
                        put("4", hour(4,"מדעים","אורי מוח","lightyellow-cell"))
                        put("5", hour(5,"של\"ח","אורי חן","lightgreen-cell"))
                        put("6", hour(6,"חינוך","שרה דוד","pink-cell"))
                    }
                )

                put(
                    JSONObject().apply {
                        put("1", hourExam(1,"ספרות","מירב שלו","blue-cell"))
                        put("2", hour(2,"עברית","יעל כהן","lightpurple-cell"))
                        put("3", hourSub(3,"אנגלית","דנה דרור"))
                        put("4", hour(4,"מדעים","אורי מוח","lightyellow-cell"))
                        put("5", hour(5,"של\"ח","אורי חן","lightgreen-cell"))
                        put("6", hour(6,"חינוך","שרה דוד","pink-cell"))
                    }
                )
            }
        )

        // ===== DAY 4 =====
        put(
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("1", hour(1,"היסטוריה","רון נתניהו","lightred-cell"))
                        put("2", hour(2,"עברית","יעל כהן","lightpurple-cell"))
                        put("3", hour(3,"אנגלית","מירב שלו","lime-cell"))
                        put("4", hour(4,"תנ\"ך","יוסי כהן","lightgrey-cell"))
                    }
                )

                put(
                    JSONObject().apply {
                        put("1", hour(1,"היסטוריה","רון נתניהו","lightred-cell"))
                        put("2", hourExam(2,"עברית","יעל כהן","lightpurple-cell"))
                        put("3", hourSub(3,"אנגלית","שירי כהן"))
                        put("4", hour(4,"תנ\"ך","יוסי כהן","lightgrey-cell"))
                    }
                )
            }
        )

        // ===== DAY 5 =====
        put(
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("1", hour(1,"של\"ח","רועי ברק","lightgreen-cell"))
                        put("2", hour(2,"ספרות","שרה דוד","blue-cell"))
                        put("3", hour(3,"עברית","יעל כהן","lightpurple-cell"))
                        put("4", hour(4,"אנגלית","מירב שלו","lime-cell"))
                        put("5", hour(5,"חינוך","אורי מוח","pink-cell"))
                        put("6", hour(6,"מדעים","רון נתניהו","lightyellow-cell"))
                    }
                )

                put(
                    JSONObject().apply {
                        put("1", hour(1,"של\"ח","רועי ברק","lightgreen-cell"))
                        put("2", hourCanceled(2,"ספרות","שרה דוד"))
                        put("3", hour(3,"עברית","יעל כהן","lightpurple-cell"))
                        put("4", hourExam(4,"אנגלית","מירב שלו","lime-cell"))
                        put("5", hourSub(5,"חינוך","אביב שלו"))
                        put("6", hour(6,"מדעים","רון נתניהו","lightyellow-cell"))
                    }
                )
            }
        )

    }
    private fun hour(num: Int, subject: String, teacher: String, color: String) =
        JSONObject().put("num", num).put("subject", subject).put("teacher", teacher)
            .put("colorClass", color).put("changes", "").put("exams", "")

    private fun hourCanceled(num: Int, subject: String, teacher: String) =
        JSONObject().put("num", num).put("subject", subject).put("teacher", teacher)
            .put("colorClass", "cancel-cell").put("changes", "ביטול שיעור").put("exams", "")

    private fun hourExam(num: Int, subject: String, teacher: String, color: String) =
        JSONObject().put("num", num).put("subject", subject).put("teacher", teacher)
            .put("colorClass", color).put("changes", "").put("exams", "מבחן פתע")

    private fun hourSub(num: Int, subject: String, teacher: String) =
        JSONObject().put("num", num).put("subject", subject + " / מילוי מקום")
            .put("teacher", teacher + " (מילוי מקום)").put("colorClass", "lightred-cell")
            .put("changes", "מילוי מקום").put("exams", "")


    init {
        if (id.isBlank()) {
            id = generateId()
        }
    }


    constructor() {
        loggedIn = true
        displayName = "demo"
        this._username = "demo"
        this._password = "demo"
        courses.add(
            JSONObject()
                .put("name", "demo")
                .put("year", Year.now().value)
        )

    }

    fun getGrades(): JSONArray = getGrades("all")

    override fun getScheduleIndexes(): JSONArray {
        return JSONArray().put(0).put(1).put(2).put(3).put(4).put(5)
    }
    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject {
        return STATIC_SCHEDULE.getJSONArray(dayIndex).getJSONObject(0)
    }

    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject {
        return STATIC_SCHEDULE.getJSONArray(dayIndex).getJSONObject(1)
    }




    private fun createGrade(subject: String, name: String, gradeValue: Int, type: String): JSONObject {
        val date = LocalDate.of(LocalDate.now().year, 1, 1).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        val submission = JSONObject()
            .put("type", type)
            .put("grade", gradeValue)
            .put("date", date)
        val submissions = JSONArray().put(submission)

        return JSONObject()
            .put("subject", subject)
            .put("name", name)
            .put("date", date)
            .put("grade", gradeValue)
            .put("submissions", submissions)
    }

    @Throws(JSONException::class, IOException::class)
    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
        // In Demo mode we just ignore semester and return the same grades.
        return getGrades(course)
    }

    override fun getAttendanceEvents(period: String): JSONObject {
        val result = JSONObject()
        val eventsByType = JSONObject()

        // --- חיסורים ---
        val absences = JSONArray().apply {
            put(
                JSONObject()
                    .put("type", "חיסור")
                    .put("date", "2025-09-02")
                    .put("subject", "מתמטיקה")
                    .put("teacher", "רון נתניהו")
            )
            put(
                JSONObject()
                    .put("type", "חיסור")
                    .put("date", "2025-09-07")
                    .put("subject", "עברית")
                    .put("teacher", "יעל כהן")
            )
            put(
                JSONObject()
                    .put("type", "חיסור")
                    .put("date", "2025-10-01")
                    .put("subject", "אנגלית")
                    .put("teacher", "מירב שלו")
            )
        }

        // --- איחורים ---
        val late = JSONArray().apply {
            put(
                JSONObject()
                    .put("type", "איחור")
                    .put("date", "2025-09-05")
                    .put("subject", "היסטוריה")
                    .put("teacher", "אורי מוח")
            )
            put(
                JSONObject()
                    .put("type", "איחור")
                    .put("date", "2025-09-14")
                    .put("subject", "תנ\"ך")
                    .put("teacher", "רועי ברק")
            )
        }

        // --- בעיות התנהגות ---
        val discipline = JSONArray().apply {
            put(
                JSONObject()
                    .put("type", "הערת התנהגות")
                    .put("date", "2025-09-11")
                    .put("subject", "ערבית")
                    .put("teacher", "אורי חן")
            )
            put(
                JSONObject()
                    .put("type", "הערת התנהגות")
                    .put("date", "2025-09-21")
                    .put("subject", "מדעים")
                    .put("teacher", "רועי ברק")
            )
        }

        // --- חיזוקים חיוביים ---
        val positive = JSONArray().apply {
            put(
                JSONObject()
                    .put("type", "חיזוק חיובי")
                    .put("date", "2025-09-03")
                    .put("subject", "אנגלית")
                    .put("teacher", "מירב שלו")
            )
            put(
                JSONObject()
                    .put("type", "חיזוק חיובי")
                    .put("date", "2025-09-15")
                    .put("subject", "עברית")
                    .put("teacher", "יעל כהן")
            )
            put(
                JSONObject()
                    .put("type", "חיזוק חיובי")
                    .put("date", "2025-10-02")
                    .put("subject", "מתמטיקה")
                    .put("teacher", "רון נתניהו")
            )
        }

        // --- הוספה למבנה הראשי ---
        eventsByType.put("חיסור", absences)
        eventsByType.put("איחור", late)
        eventsByType.put("הערת התנהגות", discipline)
        eventsByType.put("חיזוק חיובי", positive)

        result.put("events", eventsByType)
        return result
    }



    override fun getAttendanceEvents(year: Int, period: String): JSONObject {
        return getAttendanceEvents(period)
    }
    fun getGrades(course: String): JSONArray {
        val grades = JSONArray()
        grades.put(createGrade("ביולוגיה", "רפלקציה לNERD", 99, "מבחן"))
        grades.put(createGrade("היסטרויה", "עבדה", 99, "מבחן"))
        grades.put(createGrade("עברית", "מבחן בעברית", 99, "מבחן"))
        grades.put(createGrade("מתמטיקה האצה", "מבחן במתמטיקה", 100, "מבחן"))
        grades.put(createGrade("אנגלית - א", "book report", 99, "מבחן"))
        grades.put(createGrade("ביולוגיה", "בוחן בביולוגיה", 99, "מבחן"))
        grades.put(createGrade("ערבית", "בוחן בערבית", 99, "מבחן"))
        grades.put(createGrade("ספרות", "מבדק בספרות", 99, "מבחן"))
        grades.put(createGrade("ביולוגיה", "הגדרה החיים", 99, "מבחן"))
        grades.put(createGrade("אנגלית - א", "בוחן אוצר מילים מספר 2", 99, "מבחן"))
        grades.put(createGrade("ביולוגיה", "שפה כימית אופק דיגיטלי", 99, "מבחן"))
        grades.put(createGrade("של\"ח", "סיור מפגש טבע", 99, "מבחן"))
        return grades
    }

    override fun toString(): String {
        return "DemoPlatform(loggedIn=$loggedIn, editing=$editing, name=$displayName, _username=$_username, password=$_password, courses=$courses)"
    }

//    private fun findColorClass(subject: String): String {
//        SUBJECT_COLORS[subject]?.let { return it }
//        for (key in SUBJECT_COLORS.keys) {
//            if (subject.contains(key)) return SUBJECT_COLORS[key] ?: ""
//        }
//
//        val colorPool = arrayOf("red", "green", "blue", "orange", "yellow", "purple", "teal", "lime", "pink")
//        val randomColor = "custom-${colorPool[Random().nextInt(colorPool.size)]}-cell"
//        SUBJECT_COLORS[subject] = randomColor
//        return randomColor
//    }
    private fun findColorClass(subject: String): String {
        val map = mapOf(
            "מתמטיקה האצה" to "lightgreen-cell",
            "מדעים" to "lightyellow-cell",
            "של`ח" to "lightgreen-cell",
            "חינוך" to "pink-cell",
            "ערבית" to "lightblue-cell",
            "היסטוריה" to "lightred-cell",
            "עברית" to "lightpurple-cell",
            "חינוך גופני" to "lightorange-cell",
            "נחשון" to "lightyellow-cell",
            "אנגלית" to "lime-cell",
            "ספרות" to "blue-cell",
            "תנך" to "lightgrey-cell",
            "תנ`ך" to "lightgrey-cell",
            "cancel" to "cancel-cell"
        )
        return map[subject] ?: "custom-pink-cell"
    }
    override fun isLoggedIn(): Boolean = loggedIn

    override fun refreshCookies(): Boolean {
        loggedIn = true
        return true
    }

    override fun toJson(): JSONObject {
        return JSONObject()
            .put("class", javaClass.name)
            .put("id", id)
            .put("loggedIn", loggedIn)
            .put("name", displayName)
            .put("_username", _username)
            .put("password", _password)
            .put("platformDisplayName", platformDisplayName)
    }






    override fun isEditing(): Boolean = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun setName(name: String) { this.displayName = name }
    override fun setUsername(_username: String) { this._username = _username }
    override fun setPassword(password: String) { this._password = password }
    override fun getCourses(): ArrayList<JSONObject> = courses
    override fun getName(): String = displayName ?: ""
    override fun getUsername(): String = _username ?: ""
    override fun getPassword(): String = _password ?: ""

    override fun getSubjectList(): List<String> {
        // Return a static/dummy list of subjects for demo mode
        return listOf(
            "מתמטיקה",
            "עברית",
            "אנגלית",
            "היסטוריה",
            "תנ\"ך",
            "ספרות",
            "ערבית",
            "מדעים",
            "חינוך",
            "של\"ח"
        )
    }


    override fun getInfo(): JSONObject {
        return JSONObject()
            .put("name", "Webtop")
            .put("supportsEndpoints", JSONArray(listOf("grades", "schedule", "disciplineEvents")))
            .put("loginVariables", JSONArray(listOf("username", "password")))
            .put("supportsSchedule", true)
            .put("supportsGrades", true)
            .put("supportsAttendance", true)
    }
    companion object : Platform.Companion {

        private val SUBJECT_COLORS: MutableMap<String, String> = HashMap<String, String>().apply {
            put("מתמטיקה האצה", "lightgreen-cell")
            put("מדעים", "lightyellow-cell")
            put("של`ח", "lightgreen-cell")
            put("חינוך", "pink-cell")
            put("ערבית", "lightblue-cell")
            put("היסטוריה", "lightred-cell")
            put("עברית", "lightpurple-cell")
            put("חינוך גופני", "lightorange-cell")
            put("נחשון", "lightyellow-cell")
            put("אנגלית", "lime-cell")
            put("ספרות", "blue-cell")
            put("תנך", "lightgrey-cell")
            put("תנ`ך", "lightgrey-cell")
            put("cancel", "cancel-cell")
        }

        @JvmStatic
        override fun fromJson(obj: JSONObject): DemoPlatform {
            val p = DemoPlatform()
            p.id = obj.optString("id", "").ifEmpty { generateId() }
            p.loggedIn = obj.optBoolean("loggedIn", false)
            p.displayName = obj.optString("name", "demo")
            p.platformDisplayName = obj.optString("platformDisplayName")
            return p
        }

        override fun checkCredentials(loginFields: LoginFields): Boolean {
            return try {
                val p = DemoPlatform()
                p.isLoggedIn()
            } catch (e: Exception) {
                Log.e("DemoPlatform", "checkCredentials failed", e)
                false
            }
        }
    }
    @Throws(JSONException::class, IOException::class)
    override fun getMessages(page: Int): JSONArray {
        val messages = JSONArray()

        // Mocked sample inbox messages
        val msg1 = JSONObject()
            .put("messageId", "MSG001")
            .put("subject", "ברוך הבא לסמסטר א׳!")
            .put("from", "דיקן הסטודנטים")
            .put("date", "2025-09-01T08:45:00")
            .put("hasRead", 0)
            .put("filesAttached", false)

        val msg2 = JSONObject()
            .put("messageId", "MSG002")
            .put("subject", "תזכורת להרצאה מוקלטת - מבוא לאלגברה")
            .put("from", "פרופ׳ כהן")
            .put("date", "2025-09-05T11:00:00")
            .put("hasRead", 1)
            .put("filesAttached", true)

        val msg3 = JSONObject()
            .put("messageId", "MSG003")
            .put("subject", "הודעה חשובה לגבי מערכת השעות")
            .put("from", "מזכירות הפקולטה")
            .put("date", "2025-09-10T09:30:00")
            .put("hasRead", 0)
            .put("filesAttached", false)

        messages.put(msg1).put(msg2).put(msg3)

        Log.d("BarIlanPlatform", "Demo messages loaded: ${messages.length()} items")
        return messages
    }

    @Throws(JSONException::class, IOException::class)
    override fun getMessageDetails(messageId: String): JSONObject {
        val message = JSONObject()

        when (messageId) {
            "MSG001" -> {
                message.put("subject", "ברוך הבא לסמסטר א׳!")
                    .put("from", "דיקן הסטודנטים")
                    .put("date", "2025-09-01T08:45:00")
                    .put("contentHtml", "<p>סטודנטים יקרים,<br>ברוכים הבאים לסמסטר החדש. אנו מאחלים לכם הצלחה רבה!</p>")
                    .put("attachments", JSONArray())
            }

            "MSG002" -> {
                val attachments = JSONArray().put(
                    JSONObject()
                        .put("name", "lecture1-recording.mp4")
                        .put("url", "https://example.com/lecture1-recording.mp4")
                        .put("size", 120.5)
                        .put("sizeUnit", "MB")
                )
                message.put("subject", "תזכורת להרצאה מוקלטת - מבוא לאלגברה")
                    .put("from", "פרופ׳ כהן")
                    .put("date", "2025-09-05T11:00:00")
                    .put("contentHtml", "<p>שלום לכולם,<br>הרצאה מוקלטת זמינה כעת במערכת. מומלץ לעבור עליה לפני השיעור הבא.</p>")
                    .put("attachments", attachments)
            }

            "MSG003" -> {
                message.put("subject", "הודעה חשובה לגבי מערכת השעות")
                    .put("from", "מזכירות הפקולטה")
                    .put("date", "2025-09-10T09:30:00")
                    .put("contentHtml", "<p>שלום,<br>אנא שימו לב כי מערכת השעות עודכנה. בדקו את זמני השיעורים באתר.</p>")
                    .put("attachments", JSONArray())
            }

            else -> {
                message.put("error", "Message not found")
            }
        }

        Log.d("BarIlanPlatform", "Demo message details loaded for ID: $messageId")
        return message
    }
    override fun getLoginFields(): LoginFields = loginFields


    override suspend fun downloadAttachment(context: Context, attachment: JSONObject): Boolean {
        val name = attachment.optString("name", "DemoFile.txt")
        val type = attachment.optString("type", "text/plain")

        return try {
            // 🕒 simulate network delay
            delay(1500)

            // 🎯 Create fake file path in Downloads folder
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, name)

            // 📝 Write fake content
            val demoText = "This is a demo attachment.\nFile name: $name\nType: $type\nGenerated by Scholix Demo Platform."
            FileOutputStream(file).use { it.write(demoText.toByteArray()) }

            // ✅ Notify user
            with(android.os.Handler(context.mainLooper)) {
                post {
                    Toast.makeText(context, "הקובץ נשמר בתיקיית ההורדות: ${file.name}", Toast.LENGTH_LONG).show()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            with(android.os.Handler(context.mainLooper)) {
                post {
                    Toast.makeText(context, "שגיאה בהורדת קובץ ההדגמה.", Toast.LENGTH_SHORT).show()
                }
            }
            false
        }
    }
}
