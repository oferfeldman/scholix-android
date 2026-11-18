package com.feldman.scholix.lemida

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.feldman.scholix.ui.components.SettingsTopBar
import kotlinx.coroutines.launch

/** A downloaded Lemida PDF, readable in Scholix and savable to Downloads/Scholix. */
@Composable
internal fun LemidaPdfScreen(file: java.io.File, name: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val messages = remember(file) { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var saving by remember(file) { mutableStateOf(false) }
    Scaffold(snackbarHost = { SnackbarHost(messages) }, topBar = {
        SettingsTopBar(name, onBack = onBack, actions = {
            TextButton(enabled = !saving, onClick = {
                scope.launch {
                    saving = true
                    val message = try {
                        LemidaPdfDownloads.save(context, file, name)
                        "Saved to Downloads/Scholix"
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { e.message ?: "Could not save the PDF. Try again." }
                    finally { saving = false }
                    messages.showSnackbar(message)
                }
            }) { Text(if (saving) "Saving…" else "Download") }
        })
    }) { padding ->
        com.feldman.scholix.drive.DrivePdfReader(file,
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp).padding(bottom = 80.dp),
            errorMessage = "This PDF could not be displayed. Go back and try opening it again.")
    }
}
