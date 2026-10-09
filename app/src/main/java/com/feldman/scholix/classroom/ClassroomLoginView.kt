package com.feldman.scholix.classroom

import android.accounts.AccountManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Class
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionItemPosition
import com.feldman.motion.MotionSegmentedPicker
import com.feldman.scholix.R
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.GoogleClassroomPlatform
import com.feldman.scholix.api.platforms.StudentsPortalPlatform
import com.feldman.scholix.api.platforms.WebtopPlatform
import com.feldman.scholix.ui.HiddenClassroomMoeLogin
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CLASSROOM_LOGIN_METHODS = listOf("moe", "google")

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClassroomLoginView(
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    initialEmail: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Detect Google accounts already on device
    val deviceAccounts = remember {
        runCatching {
            AccountManager.get(context).getAccountsByType("com.google").map { it.name }
        }.getOrNull().orEmpty()
    }
    val detectedSchoolAccount = remember(deviceAccounts) {
        deviceAccounts.firstOrNull { it.contains("educ.org.il", ignoreCase = true) || it.contains(".edu", ignoreCase = true) }
    }

    var loginMethod by remember { mutableStateOf("moe") }
    var schoolEmail by remember { mutableStateOf(initialEmail ?: detectedSchoolAccount.orEmpty()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Check for saved MOE credentials from Webtop or Education Portal
    val existingPlatforms = remember { PlatformStorage.loadPlatforms(context) }
    val savedMoePlatform = remember(existingPlatforms) {
        existingPlatforms.firstOrNull {
            (it is StudentsPortalPlatform && !it.getUsername().isNullOrBlank()) ||
            (it is WebtopPlatform && it.isMoe() && !it.getUsername().isNullOrBlank())
        }
    }

    var moeUsername by remember { mutableStateOf(savedMoePlatform?.getUsername().orEmpty()) }
    var moePassword by remember { mutableStateOf(savedMoePlatform?.getPassword().orEmpty()) }
    var moePasswordVisible by remember { mutableStateOf(false) }
    var pendingMoeLogin by remember { mutableStateOf<Pair<String, String>?>(null) }

    val authorization = remember { Identity.getAuthorizationClient(context) }

    fun processResult(authResult: AuthorizationResult, targetEmail: String?) {
        val token = authResult.accessToken
        if (token.isNullOrBlank()) {
            isLoading = false
            loadingMessage = null
            errorMessage = "לא התקבל מזהה התחברות מגוגל. אנא נסה שוב."
            return
        }

        if (authResult.grantedScopes.isNotEmpty() && !authResult.grantedScopes.any { it.contains("classroom", ignoreCase = true) }) {
            isLoading = false
            loadingMessage = null
            errorMessage = "הרשאות Classroom לא אושרו. יש לאשר את הגישה לקורסים."
            return
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val provider = GoogleClassroomPlatform.connect(context, token, targetEmail)
                    PlatformStorage.addPlatform(context, provider)
                }
                isLoading = false
                loadingMessage = null
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isLoading = false
                loadingMessage = null
                errorMessage = e.localizedMessage ?: "שגיאה בחיבור ל-Google Classroom."
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            val authResult = authorization.getAuthorizationResultFromIntent(result.data)
            processResult(authResult, schoolEmail.trim().takeIf { it.isNotBlank() })
        } catch (e: ApiException) {
            isLoading = false
            loadingMessage = null
            Log.w("ClassroomAuth", "Google authorization failed: status=${e.statusCode}, result=${result.resultCode}")
            errorMessage = if (e.status.statusMessage.orEmpty().contains("SERVICE_DISABLED")) {
                "Google דחתה את הרשאת הגישה ל-Classroom בחשבון הזה (SERVICE_DISABLED). בחשבון בית ספר, יש לפנות למנהל Google Workspace כדי לבדוק את הרשאות הגישה של Scholix."
            } else {
                "Google authorization failed (${e.statusCode}): ${e.status.statusMessage.orEmpty()}"
            }
        } catch (e: Exception) {
            isLoading = false
            loadingMessage = null
            errorMessage = e.localizedMessage ?: "אימות גוגל נכשל"
        }
    }

    fun startAuthorization(targetEmail: String? = schoolEmail.trim().takeIf { it.isNotBlank() }) {
        isLoading = true
        loadingMessage = "מתחבר אל Google Classroom..."
        errorMessage = null

        val request = GoogleClassroomPlatform.request(targetEmail)
        authorization.authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent != null) {
                        val isr = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        launcher.launch(isr)
                    } else {
                        isLoading = false
                        loadingMessage = null
                        errorMessage = "חזר מענה לא תקין מאימות גוגל."
                    }
                } else {
                    processResult(result, targetEmail)
                }
            }
            .addOnFailureListener { e ->
                isLoading = false
                loadingMessage = null
                errorMessage = e.localizedMessage ?: "אימות גוגל נכשל"
            }
    }

    // Off-screen MOE SSO flow
    pendingMoeLogin?.let { (user, pass) ->
        HiddenClassroomMoeLogin(
            username = user,
            password = pass,
            onStatus = { status -> loadingMessage = status },
            onResult = { email, error ->
                pendingMoeLogin = null
                if (email != null) {
                    schoolEmail = email
                    startAuthorization(email)
                } else {
                    isLoading = false
                    loadingMessage = null
                    errorMessage = error ?: "ההתחברות למשרד החינוך נכשלה"
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Brand Header
        MotionCard(
            modifier = Modifier.fillMaxWidth(),
            position = MotionItemPosition.Alone
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.School,
                            contentDescription = "Google Classroom",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Google Classroom",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "סנכרן קורסים, מטלות וציונים מ-Google Classroom ישירות לתוך Scholix.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Quick connect banner if an educational Google account exists on device
        if (detectedSchoolAccount != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isLoading) {
                        schoolEmail = detectedSchoolAccount
                        startAuthorization(detectedSchoolAccount)
                    }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.School,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "חשבון בית-ספר זוהה במכשיר:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Text(
                            text = detectedSchoolAccount,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Button(
                        onClick = {
                            schoolEmail = detectedSchoolAccount
                            startAuthorization(detectedSchoolAccount)
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("התחבר")
                    }
                }
            }
        }

        // Login Method Picker (הזדהות משרד החינוך vs חשבון גוגל)
        MotionSegmentedPicker(
            options = listOf("הזדהות משרד החינוך", "חשבון גוגל"),
            icons = null,
            selectedIndex = CLASSROOM_LOGIN_METHODS.indexOf(loginMethod).coerceAtLeast(0),
            onSelected = { index ->
                val method = CLASSROOM_LOGIN_METHODS[index]
                if (loginMethod != method) {
                    loginMethod = method
                    errorMessage = null
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Method 1: Ministry of Education SSO Form
        if (loginMethod == "moe") {
            MotionCard(
                modifier = Modifier.fillMaxWidth(),
                position = MotionItemPosition.Alone
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "הזדהות משרד החינוך",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "התחבר עם פרטי משרד החינוך שלך. כתובת ה-Classroom הבית-ספרית תזוהה אוטומטית.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Saved MOE credentials hint
                    if (savedMoePlatform != null && savedMoePlatform.getUsername().isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    moeUsername = savedMoePlatform.getUsername()
                                    moePassword = savedMoePlatform.getPassword()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_moe),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "שימוש בפרטים שמורים: ${savedMoePlatform.getUsername()}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = moeUsername,
                        onValueChange = { moeUsername = it },
                        label = { Text("שם משתמש / קוד משתמש") },
                        placeholder = { Text("שם משתמש של משרד החינוך") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = moePassword,
                        onValueChange = { moePassword = it },
                        label = { Text("סיסמה") },
                        placeholder = { Text("סיסמת משרד החינוך") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { moePasswordVisible = !moePasswordVisible }) {
                                Icon(
                                    imageVector = if (moePasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (moePasswordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        visualTransformation = if (moePasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick = {
                            if (moeUsername.isBlank() || moePassword.isBlank()) {
                                errorMessage = "אנא הזן שם משתמש וסיסמה של משרד החינוך"
                                return@Button
                            }
                            isLoading = true
                            loadingMessage = "מאמת מול משרד החינוך ומזהה חשבון בית-ספר..."
                            errorMessage = null
                            pendingMoeLogin = Pair(moeUsername.trim(), moePassword.trim())
                        },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(loadingMessage ?: "מתחבר...")
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_moe),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "התחבר עם משרד החינוך",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        } else {
            // Method 2: Direct Google Sign-In with Optional School Email
            MotionCard(
                modifier = Modifier.fillMaxWidth(),
                position = MotionItemPosition.Alone
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "בחירת חשבון גוגל",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "באפשרותך להזין את כתובת המייל הבית-ספרית או לבחור חשבון מתוך המכשיר:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = schoolEmail,
                        onValueChange = { schoolEmail = it },
                        label = { Text("כתובת מייל בית-ספרית (אופציונלי)") },
                        placeholder = { Text("username@educ.org.il") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick = { startAuthorization() },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(loadingMessage ?: "מתחבר...")
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_docs),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (schoolEmail.isNotBlank()) "התחבר עם חשבון זה" else "בחר חשבון גוגל מהמכשיר",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Required Permissions Info
        MotionCard(
            modifier = Modifier.fillMaxWidth(),
            position = MotionItemPosition.Alone
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "מה מסונכרן באפליקציה",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                PermissionItem(
                    icon = Icons.Default.Class,
                    title = "קורסים וכיתות",
                    subtitle = "צפייה בכיתות ובמקצועות שבהם הינך רשום/ה"
                )
                PermissionItem(
                    icon = Icons.Default.Assignment,
                    title = "מטלות, ציונים וקבצים",
                    subtitle = "מעקב אחר הגשות, מועדי הגשה ומשוב מהמורים"
                )
                PermissionItem(
                    icon = Icons.Default.Campaign,
                    title = "הודעות ועדכונים",
                    subtitle = "קריאת הודעות ועדכונים שפורסמו בלוח הכיתה"
                )
            }
        }

        // Error message if any
        if (errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Cancel Button
        OutlinedButton(
            onClick = onCancel,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("ביטול")
        }
    }
}

@Composable
private fun PermissionItem(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
