package com.feldman.scholix.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionButton
import com.feldman.scholix.api.platforms.SchoolyPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun SchoolyLoginView(
    account: SchoolyPlatform? = null,
    showTitle: Boolean = true,
    onSuccess: (SchoolyPlatform) -> Unit,
    onCancel: () -> Unit,
) {
    val draft = remember(account?.id) { account?.let { SchoolyPlatform.fromJson(it.toJson()) } ?: SchoolyPlatform() }
    val scope = rememberCoroutineScope()
    var step by remember(draft) { mutableStateOf(if (draft.isLoggedIn()) "student" else "id") }
    var identity by remember(draft) { mutableStateOf(draft.getUsername()) }
    var code by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf(emptyList<JSONObject>()) }
    var schools by remember { mutableStateOf(emptyList<JSONObject>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not connect to Schooly." }
            finally { busy = false }
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) Text("Schooly", style = MaterialTheme.typography.headlineSmall)
        when (step) {
            "id" -> {
                Text("Enter your own national ID. Schooly sends a verification code to a contact method registered with your school.")
                OutlinedTextField(value = identity, onValueChange = { identity = it.filter(Char::isDigit).take(9) },
                    label = { Text("National ID") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                MotionButton(text = "Continue", enabled = !busy && identity.length in 5..9, onClick = {
                    submit {
                        contacts = withContext(Dispatchers.IO) { draft.identify(identity) }
                        step = "contact"
                    }
                }, modifier = Modifier.fillMaxWidth())
            }
            "contact" -> {
                Text("Choose where to receive the code", style = MaterialTheme.typography.titleMedium)
                contacts.forEach { contact ->
                    val label = contact.optString("label").ifBlank { contact.optString("type") }
                    val value = contact.optString("masked").ifBlank { contact.optString("value") }
                    MotionButton(text = "$label · $value", enabled = !busy, onClick = {
                        submit {
                            withContext(Dispatchers.IO) { draft.sendCode(contact) }
                            code = ""
                            step = "code"
                        }
                    }, modifier = Modifier.fillMaxWidth())
                }
            }
            "code" -> {
                OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Verification code") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                MotionButton(text = "Verify", enabled = !busy && code.length == 6, onClick = {
                    submit {
                        schools = withContext(Dispatchers.IO) { draft.verifyCode(code) }
                        step = if (schools.isEmpty()) "student" else "school"
                    }
                }, modifier = Modifier.fillMaxWidth())
                MotionButton(text = "Send another code", enabled = !busy, onClick = { step = "contact" }, modifier = Modifier.fillMaxWidth())
            }
            "school" -> {
                Text("Choose your school", style = MaterialTheme.typography.titleMedium)
                schools.forEach { school ->
                    MotionButton(text = school.optString("school_name"), enabled = !busy, onClick = {
                        submit {
                            withContext(Dispatchers.IO) { draft.verifyCode(code, school.optString("school_id")) }
                            step = "student"
                        }
                    }, modifier = Modifier.fillMaxWidth())
                }
            }
            "student" -> {
                Text("Choose the student for your schedule. Grades for each linked student are available in Grades.")
                draft.getStudents().forEach { student ->
                    MotionButton(text = SchoolyPlatform.studentLabel(student), enabled = !busy, onClick = {
                        draft.selectStudent(student.optString("student_login_id"))
                        onSuccess(draft)
                    }, modifier = Modifier.fillMaxWidth())
                }
                MotionButton(text = "Sign in again", enabled = !busy, onClick = { step = "id" }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (busy) Text("Connecting to Schooly…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        MotionButton(text = "Cancel", enabled = !busy, onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
    }
}
