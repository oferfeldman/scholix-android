package com.feldman.scholix.pages

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.feldman.scholix.api.ApiService
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.PlatformInfo
import com.feldman.scholix.api.Type
import com.feldman.scholix.api.checkScholixLoggedIn
import com.feldman.scholix.api.platformOptions
import com.feldman.scholix.ui.components.ActionRow
import com.feldman.scholix.ui.components.SegmentedOption
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginPage(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scholixLoggedIn by remember { mutableStateOf(false) }
    var scholixLoading by remember { mutableStateOf(false) }

// 🔹 Auto-check Scholix login on launch
    LaunchedEffect(Unit) {
        scholixLoading = true
        val alreadyLoggedIn = withContext(Dispatchers.IO) {
            checkScholixLoggedIn(context)
        }
        scholixLoggedIn = alreadyLoggedIn
        scholixLoading = false
    }

    var scholixEmail by remember { mutableStateOf("") }
    var scholixPassword by remember { mutableStateOf("") }
    var isSignupMode by remember { mutableStateOf(false) }
    var scholixError by remember { mutableStateOf<String?>(null) }


    // Platform selection
    var selectedPlatform by remember { mutableStateOf<PlatformInfo?>(null) }
    var loginFields by remember { mutableStateOf<LoginFields?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val showPlatformPage = scholixLoggedIn && selectedPlatform == null
    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .consumeWindowInsets(WindowInsets.ime)
            .padding(24.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ───── Stage 1: Scholix account login/signup ─────
        AnimatedVisibility(visible = !scholixLoggedIn, enter = fadeIn(), exit = fadeOut()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isSignupMode) "Create Scholix Account" else "Login to Scholix",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = scholixEmail,
                    onValueChange = { scholixEmail = it },
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Default.Email, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                var passwordVisible by remember { mutableStateOf(false) }

                OutlinedTextField(
                    value = scholixPassword,
                    onValueChange = { scholixPassword = it },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    trailingIcon = {
                        val icon = if (passwordVisible)
                            Icons.Default.VisibilityOff else Icons.Default.Visibility
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(icon, null)
                        }
                    },
                    visualTransformation = if (passwordVisible)
                        VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                AnimatedVisibility(visible = scholixLoading, enter = fadeIn(), exit = fadeOut()) {
                    CircularWavyProgressIndicator()
                }

                AnimatedVisibility(visible = !scholixLoading, enter = fadeIn(), exit = fadeOut()) {
                    Button(
                        onClick = {
                            if (scholixEmail.isBlank() || scholixPassword.isBlank()) {
                                scholixError = "Please fill in all fields"
                                return@Button
                            }

                            scholixLoading = true
                            scholixError = null

                            scope.launch(Dispatchers.IO) {
                                try {
                                    val body = JSONObject().apply {
                                        put("email", scholixEmail)
                                        put("password", scholixPassword)
                                        put("name", scholixEmail.substringBefore("@"))
                                    }

                                    val endpoint = if (isSignupMode) "signup" else "login"
                                    val response = ApiService.postJson(endpoint, body)

                                    Log.d("ScholixAuth", "Response: $response")

                                    withContext(Dispatchers.Main) {
                                        ApiService.saveSessionCookie(context)
                                        scholixLoggedIn = true
                                        scholixLoading = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        scholixError = "Auth failed: ${e.message}"
                                        scholixLoading = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(if (isSignupMode) "Sign Up" else "Sign In", style = MaterialTheme.typography.titleLarge)
                    }
                }

                scholixError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { isSignupMode = !isSignupMode }) {
                    Text(
                        if (isSignupMode)
                            "Already have an account? Log in"
                        else
                            "Don't have an account? Sign up"
                    )
                }
            }
        }
        val backdrop = rememberLayerBackdrop()

        // ───── Stage 2: Choose platform ─────
        AnimatedVisibility(visible = showPlatformPage, enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Choose Your Platform",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                ActionRow {
                    addVerticalActionList(
                        options = platformOptions.map {
                            SegmentedOption(it, text = it.name, iconRes = it.iconRes)
                        },
                        onClick = { option ->
                            selectedPlatform = option
                            loginFields = option.factory().getLoginFields()
                            errorMessage = null
                        },
                        backdrop = backdrop,
                        isGlass = false
                    )
                }
            }
        }

        // ───── Stage 3: Platform login ─────
        AnimatedVisibility(visible = selectedPlatform != null, enter = fadeIn(), exit = fadeOut()) {
            selectedPlatform?.let { platform ->
                val fields = loginFields ?: platform.factory().getLoginFields()

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = platform.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    DynamicLoginFields(
                        fields = fields,
                        onFieldsChanged = { loginFields = it },
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onLogin = {
                            val missing = fields.getFields().any { it.value.isNullOrBlank() }
                            if (missing) {
                                errorMessage = "Please fill in all fields"
                                return@DynamicLoginFields
                            }

                            isLoading = true
                            errorMessage = null

                            scope.launch(Dispatchers.IO) {
                                try {
                                    val body = JSONObject().apply {
                                        put("name", platform.apiName)
                                        fields.getFields().forEach {
                                            put(it.id, JSONObject().apply {
                                                put("type", when (val t = it.type) {
                                                    Type.Id -> "id"
                                                    Type.Username -> "username"
                                                    Type.Password -> "password"
                                                    Type.Email -> "email"
                                                    Type.Token -> "token"
                                                    is Type.Custom -> t.name.lowercase()
                                                })
                                                put("value", it.value)
                                            })
                                        }
                                    }

                                    val response = ApiService.postJson("user/platform", body)
                                    Log.d("PlatformLogin", "Response: $response")

                                    withContext(Dispatchers.Main) {
                                        scholixLoggedIn = false
                                        selectedPlatform = null
                                        isLoading = false
                                        onLoginSuccess()
                                    }

                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        errorMessage = "Login failed: ${e.message}"
                                        isLoading = false
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DynamicLoginFields(
    fields: LoginFields,
    onFieldsChanged: (LoginFields) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onLogin: () -> Unit
) {
    var mutableFields by remember { mutableStateOf(fields) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        fields.getFields().forEach { field ->
            var value by remember { mutableStateOf(field.value ?: "") }
            var passwordVisible by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    mutableFields.setValue(field.id, it)
                    onFieldsChanged(mutableFields)
                },
                label = { Text(field.id.replaceFirstChar { c -> c.uppercase() }) },
                leadingIcon = {
                    when (field.type) {
                        Type.Username, Type.Id -> Icon(Icons.Default.Person, null)
                        Type.Password -> Icon(Icons.Default.Lock, null)
                        else -> {}
                    }
                },
                trailingIcon = if (field.type == Type.Password) {
                    {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible)
                                    Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                } else null,
                visualTransformation = if (field.type == Type.Password && !passwordVisible)
                    PasswordVisualTransformation() else VisualTransformation.None,
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
            CircularWavyProgressIndicator()
        }

        AnimatedVisibility(visible = !isLoading, enter = fadeIn(), exit = fadeOut()) {
            Button(
                onClick = onLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Sign In", style = MaterialTheme.typography.titleLarge)
            }
        }

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
