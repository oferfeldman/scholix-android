package com.feldman.scholix.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonDefaults
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionItemPosition
import com.feldman.scholix.R
import com.feldman.scholix.api.platforms.ShahafPlatform
import com.feldman.scholix.ui.HiddenShahafMoeLogin
import com.feldman.scholix.ui.MoeSmsLoginButton
import com.feldman.scholix.ui.MoeSmsLoginDetails
import com.feldman.scholix.ui.components.ChipPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class ShahafGroupOption(val id: String, val label: String)
private data class ShahafGroupParameter(
    val id: String,
    val label: String,
    val selectedId: String,
    val options: List<ShahafGroupOption>,
)

private fun JSONArray?.jsonObjects(): List<JSONObject> = if (this == null) emptyList()
else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.shahafGroupParameters(): List<ShahafGroupParameter> {
    val result = linkedMapOf<String, ShahafGroupParameter>()
    optJSONArray("views").jsonObjects().forEach { view ->
        view.optJSONArray("p").jsonObjects().forEach { parameter ->
            val id = parameter.opt("id")?.toString().orEmpty().trim()
            val label = parameter.optString("l").trim()
            val searchable = "$id $label"
            val isGroup = listOf("הקב", "הרחב", "קבוצ", "מסלול", "group", "track", "extension")
                .any { searchable.contains(it, ignoreCase = true) }
            if (id.isBlank() || !isGroup) return@forEach
            val options = parameter.optJSONArray("o").jsonObjects().mapNotNull { option ->
                val optionId = option.opt("id")?.toString().orEmpty().trim()
                val optionLabel = option.optString("l").trim().ifBlank { optionId }
                optionId.takeIf(String::isNotBlank)?.let { ShahafGroupOption(it, optionLabel) }
            }.distinctBy { it.id }
            if (options.isEmpty()) return@forEach
            val selected = parameter.opt("i")?.toString().orEmpty().trim()
            result[id] = ShahafGroupParameter(id, label, selected, options)
        }
    }
    return result.values.toList()
}

@Composable
fun ShahafLoginView(
    account: ShahafPlatform? = null,
    onSuccess: (ShahafPlatform) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val signInLabel = stringResource(R.string.sign_in)
    val fillFieldsMessage = stringResource(R.string.fill_all_fields)
    val draft = remember(account?.id) {
        (account?.let { ShahafPlatform.fromJson(it.toJson()) } ?: ShahafPlatform()).also { provider ->
            val legacyAutomaticName = provider.getStudentName().takeIf { it.isNotBlank() }?.let { "Shahaf · $it" }
            if (legacyAutomaticName != null && provider.getName() == legacyAutomaticName) {
                provider.setName("Shahaf")
            }
        }
    }
    val fields = remember(draft) { draft.getLoginFields().apply { loadFrom(draft) } }
    var method by remember(draft) {
        mutableStateOf(when {
            draft.loginMethod != "moe" -> "password"
            draft.getPassword().isNotBlank() -> "moe_password"
            else -> "moe_sms"
        })
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var moeCredentials by remember { mutableStateOf<Pair<String, String>?>(null) }
    var name by remember(draft) { mutableStateOf(draft.getName()) }
    var groupParameters by remember(draft.id) { mutableStateOf(emptyList<ShahafGroupParameter>()) }
    var groupLoading by remember(draft.id) { mutableStateOf(account != null) }
    var selectedGroups by remember(draft.id) { mutableStateOf(draft.getViewParameters()) }

    LaunchedEffect(account?.id) {
        if (account == null || !draft.isLoggedIn()) {
            groupLoading = false
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (draft.selectedSchool.length() == 0) {
                    val schools = draft.getSchools().jsonObjects()
                    val preferred = schools.firstOrNull { it.optString("semel") == draft.getStudentSchoolSemel() }
                        ?: schools.firstOrNull()
                    preferred?.let(draft::selectSchool)
                }
                if (draft.selectedSchool.length() > 0) {
                    val classes = draft.getClasses().jsonObjects()
                    if (classes.none { it.optString("Id") == draft.selectedClassId }) {
                        classes.firstOrNull()?.optString("Id")?.let(draft::selectClass)
                    }
                }
                draft.getAppData().shahafGroupParameters()
            }.getOrElse { emptyList() }
        }
        groupParameters = loaded
        groupLoading = false
    }

    fun complete(response: JSONObject) {
        // The SMS dialog stores the verified ID/phone in shared prefs when the
        // code is requested; keep them on the account so the next expiry only
        // asks for a fresh code, like the Inbar SMS login.
        if (draft.loginMethod == "moe") {
            if (draft.getUsername().isBlank()) {
                MoeSmsLoginDetails.lastId(context).takeIf { it.isNotBlank() }?.let { draft.setUsername(it) }
            }
            if (draft.getMoePhone().isBlank()) {
                MoeSmsLoginDetails.phone(context, draft.getUsername()).takeIf { it.isNotBlank() }?.let { draft.setMoePhone(it) }
            }
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { draft.adoptAuthResponse(response) }
                onSuccess(draft)
            } catch (e: Exception) { error = e.localizedMessage }
            finally { busy = false }
        }
    }
    moeCredentials?.let { (user, pass) ->
        HiddenShahafMoeLogin(user, pass, draft.deviceId,
            savedPhone = draft.getMoePhone().ifBlank {
                MoeSmsLoginDetails.phone(context, user.ifBlank { draft.getUsername() })
            }) { response, failure ->
            moeCredentials = null
            if (response != null) complete(response)
            else { error = failure; busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Shahaf", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        if (account != null) OutlinedTextField(
            value = name, onValueChange = { name = it; draft.setName(it) },
            label = { Text(stringResource(R.string.provider_name)) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        val moeSmsLabel = stringResource(R.string.moe_sms)
        val moePasswordLabel = stringResource(R.string.moe_password)
        val shahafPasswordLabel = stringResource(R.string.shahaf_password)
        if (!busy) ChipPicker(
            label = stringResource(R.string.sign_in_with),
            options = listOf(moeSmsLabel, moePasswordLabel, shahafPasswordLabel),
            selected = when (method) {
                "moe_password" -> moePasswordLabel
                "password" -> shahafPasswordLabel
                else -> moeSmsLabel
            },
            onSelectedChange = {
                method = when (it) {
                    moePasswordLabel -> "moe_password"
                    shahafPasswordLabel -> "password"
                    else -> "moe_sms"
                }
                error = null
            },
        )
        if (account != null && (groupLoading || groupParameters.isNotEmpty())) {
            Spacer(Modifier.height(12.dp))
            MotionCard(
                position = MotionItemPosition.Alone,
                contentPadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.shahaf_groups),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.shahaf_groups_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (groupLoading) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp))
                    } else {
                        groupParameters.forEach { parameter ->
                            val savedId = selectedGroups[parameter.id].orEmpty()
                            val selectedId = savedId.ifBlank { parameter.selectedId }
                                .takeIf { candidate -> parameter.options.any { it.id == candidate } }
                                ?: parameter.options.first().id
                            val selectedLabel = parameter.options.firstOrNull { it.id == selectedId }?.label
                                ?: parameter.options.first().label
                            ChipPicker(
                                label = parameter.label.ifBlank { stringResource(R.string.shahaf_groups) },
                                options = parameter.options.map { it.label },
                                selected = selectedLabel,
                                onSelectedChange = { label ->
                                    parameter.options.firstOrNull { it.label == label }?.let { option ->
                                        draft.setViewParameter(parameter.id, option.id)
                                        selectedGroups = draft.getViewParameters()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        if (method == "moe_sms") MoeSmsLoginButton(enabled = !busy) {
            // Keep the saved MOE ID: restoring an expired session passes it to
            // the SMS dialog so only a fresh code is needed. A blank ID means a
            // first-time sign-in, which prompts for ID + phone as before.
            draft.setPassword("")
            draft.loginMethod = "moe"
            busy = true
            error = null
            moeCredentials = draft.getUsername() to ""
        }
        if (method != "moe_sms") DynamicLoginFields(fields, onFieldsChanged = {}, isLoading = busy, errorMessage = error,
            buttonText = signInLabel, onCancel = if (account == null) onCancel else null, onSubmit = {
                val user = fields.getValue("username").orEmpty()
                val pass = fields.getValue("password").orEmpty()
                if (user.isBlank() || pass.isBlank()) {
                    error = fillFieldsMessage
                } else {
                    draft.setUsername(user)
                    draft.setPassword(pass)
                    draft.loginMethod = if (method == "password") "password" else "moe"
                    busy = true
                    error = null
                    if (method != "password") moeCredentials = user to pass
                    else scope.launch {
                        try {
                            val signedIn = withContext(Dispatchers.IO) { ShahafPlatform.login(user, pass) }
                            // Preserve account identity and selected school when signing in again.
                            val response = signedIn.toJson().getJSONObject("authResponse")
                            withContext(Dispatchers.IO) { draft.adoptAuthResponse(response) }
                            onSuccess(draft)
                        } catch (e: Exception) { error = e.localizedMessage }
                        finally { busy = false }
                    }
                }
            })
        if (account != null && !busy) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MotionButton(
                    text = stringResource(R.string.cancel),
                    onClick = onCancel,
                    sizes = MotionButtonDefaults.sizes(height = 48.dp, fontSize = 16.sp),
                    modifier = Modifier.weight(1f),
                )
                MotionButton(
                    text = stringResource(R.string.save_changes),
                    onClick = {
                        draft.setName(name)
                        onSuccess(draft)
                    },
                    sizes = MotionButtonDefaults.sizes(height = 48.dp, fontSize = 16.sp),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
