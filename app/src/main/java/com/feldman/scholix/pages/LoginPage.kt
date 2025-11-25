package com.feldman.scholix.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.LocalAutofillHighlightColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import com.feldman.scholix.api.*
import com.feldman.scholix.ui.components.ActionRow
import com.feldman.scholix.ui.components.SegmentedOption
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginPage(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()


    var selectedPlatform by remember { mutableStateOf<PlatformInfo?>(null) }
    var loginFields by remember { mutableStateOf<LoginFields?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val showLoginPage = selectedPlatform != null
    val backdrop = rememberLayerBackdrop()

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .consumeWindowInsets(WindowInsets.ime)
            .padding(24.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {

        AnimatedVisibility(visible = !showLoginPage, enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Choose Your Platform",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(16.dp))

                ActionRow {
                    addVerticalActionList(
                        options = listOf(
                            *platformOptions.map { option ->
                                SegmentedOption(
                                    option,
                                    text = option.name,
                                    iconRes = option.iconRes
                                )
                            }.toTypedArray()
                        ),
                        onClick = { option ->
                            selectedPlatform = option
                            loginFields = option.factory().getLoginFields()
                            errorMessage = null
                        },
                        isGlass = false,
                        backdrop = backdrop
                    )

                }
            }

            // ─── Platform Selection Page ────────────────────────
//            Column(horizontalAlignment = Alignment.CenterHorizontally) {
//                Text(
//                    text = "Choose Your Platform",
//                    style = MaterialTheme.typography.headlineMedium,
//                    color = MaterialTheme.colorScheme.primary
//                )
//
//                Spacer(Modifier.height(16.dp))
//
//                LazyVerticalGrid(
//                    columns = GridCells.Adaptive(minSize = 120.dp),
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .heightIn(max = 280.dp),
//                    verticalArrangement = Arrangement.spacedBy(12.dp),
//                    horizontalArrangement = Arrangement.spacedBy(12.dp)
//                ) {
//                    items(platformOptions) { option ->
//                        PlatformCard(
//                            option = option,
//                            onSelect = {
//                                selectedPlatform = option
//                                loginFields = option.factory().getLoginFields()
//                                errorMessage = null
//                            }
//                        )
//                    }
//                }
//            }
        }

        // ─── Inner Login Page ─────────────────────────────
        AnimatedVisibility(visible = showLoginPage, enter = fadeIn(), exit = fadeOut()) {
            selectedPlatform?.let { platform ->
                val fields = loginFields ?: platform.factory().getLoginFields()

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                selectedPlatform = null
                                errorMessage = null
                                loginFields = null
                            },
                            modifier = Modifier.align(Alignment.CenterStart),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                tint = MaterialTheme.colorScheme.onSurface,
                                contentDescription = "Back"
                            )
                        }

                        // 🔹 Centered title text
                        Text(
                            text = platform.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    DynamicLoginFields(
                        fields = fields,
                        onFieldsChanged = { loginFields = it },
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onSubmit = {
                            val missing = fields.getFields().any { it.value.isNullOrBlank() }
                            if (missing) {
                                errorMessage = "Please fill in all fields"
                                return@DynamicLoginFields
                            }

                            isLoading = true
                            errorMessage = null

                            scope.launch {
                                try {
                                    val created = withContext(Dispatchers.IO) {
                                        val info = selectedPlatform!!

                                        // Try to call constructor(LoginFields)
                                        val platformClass = info.factory()::class.java
                                        val constructor = platformClass.constructors.find { ctor ->
                                            ctor.parameterTypes.size == 1 && ctor.parameterTypes[0] == LoginFields::class.java
                                        }

                                        val instance = if (constructor != null) {
                                            // Platform supports direct loginFields constructor (e.g., WebtopPlatform)
                                            constructor.newInstance(fields) as Platform
                                        } else {
                                            // Fall back: create a blank one, then apply login fields
                                            info.factory().apply {
                                                applyLoginFields(fields)
                                            }
                                        }

                                        instance
                                    }

                                    val ok = withContext(Dispatchers.IO) { created.isLoggedIn() }
                                    if (ok) {
                                        PlatformStorage.savePlatforms(context, listOf(created))

                                        try {
                                            val credentialManager = CredentialManager.create(context)

                                            val username = fields.getValue("username") ?: ""
                                            val password = fields.getValue("password") ?: ""

                                            if (username.isNotBlank() && password.isNotBlank()) {
                                                val request = CreatePasswordRequest(username, password)
                                                scope.launch {
                                                    try {
                                                        credentialManager.createCredential(
                                                            request = request,
                                                            context = context
                                                        )
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                }
                                            }

                                            onLoginSuccess()

                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }

                                        onLoginSuccess()
                                    }
                                    else errorMessage = "Invalid credentials"

                                } catch (e: Exception) {
                                    errorMessage = "Login failed: ${e.localizedMessage}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        buttonText = "Add",
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
    buttonText: String,
    onSubmit: () -> Unit,
    onCancel: (() -> Unit)? = null
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

            val autofillContentType = when (field.type) {
                Type.Username, Type.Id -> ContentType.Username
                Type.Password -> ContentType.Password
                Type.Email -> ContentType.EmailAddress
                else -> null
            }

            CompositionLocalProvider(LocalAutofillHighlightColor provides Color.Transparent) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .semantics {
                            autofillContentType?.let { contentType = it }
                        },
                    keyboardOptions = KeyboardOptions.Default.copy(
                        imeAction = if (field.type == Type.Password) ImeAction.Done else ImeAction.Next
                    )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
            CircularWavyProgressIndicator()
        }

        AnimatedVisibility(visible = !isLoading, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                onCancel?.let {
                    OutlinedButton(
                        onClick = it,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Cancel")
                    }
                }

                Button(
                    onClick = onSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(buttonText, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
