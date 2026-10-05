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
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillManager
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.getSystemService
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import com.feldman.scholix.api.*
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.feldman.scholix.api.platforms.StudentsPortalPlatform
import com.feldman.scholix.api.platforms.MashovPlatform
import com.feldman.scholix.api.platforms.MashovSchool
import com.feldman.scholix.api.platforms.WebtopPlatform
import com.feldman.scholix.api.platforms.InbarPlatform
import com.feldman.scholix.ui.HiddenMoeLogin
import com.feldman.scholix.ui.HiddenInbarLogin
import com.feldman.scholix.ui.HiddenWebtopMoeLogin
import com.feldman.scholix.ui.components.ProviderPickerList
import com.feldman.scholix.ui.components.WebtopLoginMethodPicker
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonDefaults
import com.feldman.motion.MotionButtonState
import com.feldman.motion.MotionSymbols
import com.feldman.motion.rememberSymbolPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val ProviderSheetActionButtonWidth = 80.dp
internal val ProviderSheetActionButtonHeight = 56.dp

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
    var inbarLogin by remember { mutableStateOf<InbarPlatform?>(null) }
    val savedInbar = remember(selectedPlatform) {
        if (selectedPlatform?.name == "Inbar (Bar-Ilan)")
            PlatformStorage.loadPlatforms(context).filterIsInstance<InbarPlatform>().lastOrNull()
        else null
    }

    // Webtop can be added either with a username/password or via Ministry-of-
    // Education (MOE) SSO. This picks which; it is only shown for Webtop.
    val loginMethod = remember { mutableStateOf("password") }
    val isWebtop = selectedPlatform?.name == "Webtop"
    // The Education Portal uses normal username/password fields; the credentials
    // are relayed by an off-screen engine because its IdP is bot-protected.
    val isPortal = selectedPlatform?.name == "Education Portal"
    val useMoeLogin = isWebtop && loginMethod.value == "moe"

    val showLoginPage = selectedPlatform != null

    // Credentials for the off-screen MOE sign-in (set when the user submits).
    var portalCreds by remember { mutableStateOf<Pair<String, String>?>(null) }
    var webtopMoeCreds by remember { mutableStateOf<Pair<String, String>?>(null) }

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

                ProviderPickerList(
                    providers = platformOptions,
                    onSelect = { option ->
                        selectedPlatform = option
                        val draft = option.factory()
                        loginFields = draft.getLoginFields().apply {
                            if (draft is InbarPlatform) PlatformStorage.loadPlatforms(context)
                                .filterIsInstance<InbarPlatform>().lastOrNull()?.let { loadFrom(it) }
                        }
                        errorMessage = null
                    }
                )
            }
        }

        // ─── Inner Login Page ──────────────────────────────
        AnimatedVisibility(visible = showLoginPage && inbarLogin == null, enter = fadeIn(), exit = fadeOut()) {
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
                                loginMethod.value = "password"
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

                    // Webtop supports two ways in: username/password or the
                    // Ministry-of-Education SSO. Offer the choice here.
                    if (isWebtop) {
                        WebtopLoginMethodPicker(
                            state = loginMethod,
                            onSelectedChange = { errorMessage = null }
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    if (useMoeLogin) {
                        // MOE sign-in: same fields, but these are the Ministry of
                        // Education credentials; the SSO uses HTTP requests.
                        Text(
                            text = "Enter your Ministry of Education username and " +
                                "password. Signing in happens in the background.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    run {
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

                            if (platform.name == "Inbar (Bar-Ilan)") {
                                inbarLogin = (savedInbar?.forSmsLogin() ?: InbarPlatform(fields)).apply {
                                    applyLoginFields(fields)
                                }
                                return@DynamicLoginFields
                            }

                            if (isPortal) {
                                // Relayed to the MOE page off-screen; no browser UI.
                                portalCreds = Pair(
                                    fields.getValue("username").orEmpty(),
                                    fields.getValue("password").orEmpty()
                                )
                                return@DynamicLoginFields
                            }

                            // Read the picker's state here rather than the value
                            // captured when this lambda was built: a snapshot can go
                            // stale and send MOE credentials down the Webtop password
                            // path, which fails as "invalid credentials".
                            if (isWebtop && loginMethod.value == "moe") {
                                webtopMoeCreds = Pair(
                                    fields.getValue("username").orEmpty(),
                                    fields.getValue("password").orEmpty()
                                )
                                return@DynamicLoginFields
                            }

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

                                    val ok = withContext(Dispatchers.IO) {
                                        created.isLoggedIn() ||
                                            (created.refreshCookies() && created.isLoggedIn())
                                    }
                                    if (ok) {
                                        withContext(Dispatchers.IO) {
                                            PlatformStorage.addPlatforms(context, listOf(created))
                                        }

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

        inbarLogin?.let { pending ->
            HiddenInbarLogin(account = pending,
                onSmsRequested = { account ->
                    withContext(Dispatchers.IO) { PlatformStorage.saveInbarLoginProgress(context, account) }
                }, onResult = { account, error ->
                    if (account != null) withContext(Dispatchers.IO) {
                        PlatformStorage.saveInbarLoginProgress(context, account)
                    }
                    inbarLogin = null
                    isLoading = false
                    errorMessage = error
                    if (account != null) onLoginSuccess()
                })
        }

        // Off-screen MOE sign-in for the Education Portal (1dp, invisible).
        portalCreds?.let { (user, pass) ->
            HiddenMoeLogin(username = user, password = pass) { cookies, csrt, error ->
                portalCreds = null
                if (cookies == null) {
                    errorMessage = error ?: "Ministry of Education login failed"
                    isLoading = false
                    return@HiddenMoeLogin
                }
                scope.launch {
                    try {
                        val created = withContext(Dispatchers.IO) {
                            StudentsPortalPlatform.loginWithCookies(cookies, csrt, user, pass)
                        }
                        val ok = withContext(Dispatchers.IO) { created.isLoggedIn() }
                        if (ok) {
                            withContext(Dispatchers.IO) {
                                PlatformStorage.addPlatforms(context, listOf(created))
                            }
                            onLoginSuccess()
                        } else {
                            errorMessage = "Ministry of Education login failed"
                        }
                    } catch (e: Exception) {
                        errorMessage = "Login failed: ${e.localizedMessage}"
                    } finally {
                        isLoading = false
                    }
                }
            }
        }

        // Off-screen MOE sign-in for Webtop (1dp, invisible).
        webtopMoeCreds?.let { (user, pass) ->
            HiddenWebtopMoeLogin(username = user, password = pass) { key, error ->
                webtopMoeCreds = null
                if (key == null) {
                    errorMessage = error ?: "Ministry of Education login failed"
                    isLoading = false
                    return@HiddenWebtopMoeLogin
                }
                scope.launch {
                    try {
                        val created = withContext(Dispatchers.IO) {
                            WebtopPlatform.loginWithMoe(key, user, pass)
                        }
                        val ok = withContext(Dispatchers.IO) { created.isLoggedIn() }
                        if (ok) {
                            withContext(Dispatchers.IO) {
                                PlatformStorage.addPlatforms(context, listOf(created))
                            }
                            onLoginSuccess()
                        } else {
                            errorMessage = "Could not start the Webtop session"
                        }
                    } catch (e: Exception) {
                        errorMessage = "Login failed: ${e.localizedMessage}"
                    } finally {
                        isLoading = false
                    }
                }
            }
        }

        if (isLoading) {
            GradesLoadingIndicator(modifier = Modifier.fillMaxSize())
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
    onCancel: (() -> Unit)? = null,
    useMotionButtons: Boolean = false
) {
    var mutableFields by remember(fields) { mutableStateOf(fields) }
    val autofillManager = LocalAutofillManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        fields.getFields().forEach { field ->
            var value by remember(field.id, field.value) { mutableStateOf(field.value ?: "") }
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
                    enabled = !isLoading,
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
                            Type.Email -> Icon(Icons.Default.Email, null)
                            Type.Token -> Icon(Icons.Default.Key, null)
                            is Type.Custom -> Icon(
                                when (field.type.name) {
                                    "mobile" -> Icons.Default.Phone
                                    "schoolCode" -> Icons.Default.School
                                    "schoolYear" -> Icons.Default.CalendarToday
                                    else -> Icons.Default.Edit
                                },
                                null
                            )
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
                    ),

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
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (useMotionButtons) {
                    if (onCancel != null) {
                        MotionButton(
                            icon = MotionSymbols.ic_close,
                            onClick = onCancel,
                            sizes = MotionButtonDefaults.sizes(width = ProviderSheetActionButtonWidth, height = ProviderSheetActionButtonHeight, iconSize = 22.dp),
                            states = MotionButtonDefaults.states(default = MotionButtonState(
                                backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ))
                        )
                    }
                    MotionButton(
                        icon = MotionSymbols.ic_check,
                        onClick = {
                            autofillManager?.commit()
                            onSubmit()
                        },
                        sizes = MotionButtonDefaults.sizes(width = ProviderSheetActionButtonWidth, height = ProviderSheetActionButtonHeight, iconSize = 22.dp)
                    )
                } else {
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
                        onClick = {
                            autofillManager?.commit()
                            onSubmit()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(buttonText, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
