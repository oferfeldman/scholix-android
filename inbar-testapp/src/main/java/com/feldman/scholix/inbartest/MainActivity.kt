package com.feldman.scholix.inbartest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feldman.scholix.api.platforms.InbarPlatform
import com.feldman.scholix.ui.InbarLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Device smoke test for production Inbar code, installed alongside Scholix. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var account by remember { mutableStateOf<InbarPlatform?>(null) }
                var evidence by remember { mutableStateOf("") }
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .imePadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text("Scholix — Inbar device test", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    if (account == null) {
                        InbarLogin(onSuccess = { signedIn ->
                            // Verify the encrypted session survives provider recreation.
                            val restored = withContext(Dispatchers.IO) {
                                val encoded = signedIn.toJson()
                                check(!encoded.has("password") && !encoded.has("smsCode"))
                                getSharedPreferences("inbar_test", MODE_PRIVATE).edit()
                                    .putString("account", encoded.toString()).apply()
                                (InbarPlatform.fromJson(JSONObject(encoded.toString())) as InbarPlatform).also {
                                    check(it.isLoggedIn()) { "Encrypted session could not be restored" }
                                    check(it.refreshCookies()) { "Restored session could not authenticate" }
                                }
                            }
                            account = restored
                            evidence = "SMS login and encrypted session round trip succeeded."
                        }, onCancel = { finish() })
                    } else {
                        Text(evidence)
                        Spacer(Modifier.height(16.dp))
                        account!!.getCourses().forEach { course ->
                            Text("${course.optInt("year")} · ${course.optString("name")}")
                            val grades = course.getJSONArray("grades")
                            for (i in 0 until grades.length()) {
                                val grade = grades.getJSONObject(i)
                                Text("${grade.optString("name")}: ${grade.optString("grade")}")
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}
