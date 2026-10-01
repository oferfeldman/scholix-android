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
import com.feldman.scholix.ui.HiddenInbarLogin
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
                val saved = remember {
                    runCatching {
                        InbarPlatform.fromJson(JSONObject(getSharedPreferences("inbar_test", MODE_PRIVATE)
                            .getString("account", "{}").orEmpty())) as InbarPlatform
                    }.getOrNull()
                }
                var identity by remember { mutableStateOf(saved?.getUsername().orEmpty()) }
                var mobile by remember { mutableStateOf(saved?.mobile.orEmpty()) }
                var pending by remember { mutableStateOf<InbarPlatform?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                var evidence by remember { mutableStateOf("") }
                pending?.let { signingIn ->
                    HiddenInbarLogin(account = signingIn, onSmsRequested = { requested ->
                        withContext(Dispatchers.IO) {
                            getSharedPreferences("inbar_test", MODE_PRIVATE).edit()
                                .putString("account", requested.toJson().toString()).apply()
                        }
                    }, onResult = { signedIn, failure ->
                        if (signedIn != null) {
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
                        }
                        pending = null
                        error = failure
                    })
                }
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .imePadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text("Scholix — Inbar device test", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    if (pending != null) {
                        CircularProgressIndicator()
                        Text("Signing in")
                    } else if (account == null) {
                        OutlinedTextField(identity, { identity = it }, label = { Text("ID") })
                        OutlinedTextField(mobile, { mobile = it }, label = { Text("Mobile") })
                        Button(onClick = {
                            val fields = InbarPlatform().getLoginFields().apply {
                                setValue("id", identity); setValue("mobile", mobile)
                            }
                            pending = InbarPlatform(fields)
                            error = null
                        }, enabled = identity.isNotBlank() && mobile.isNotBlank()) { Text("Sign in") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
