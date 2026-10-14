package com.feldman.scholix.drive

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DriveStorageDialog(repo:DriveRepository,onDismiss:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var usage by remember {mutableStateOf<DriveStorageUsage?>(null)}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var error by remember {mutableStateOf("")}
    var removeDownloads by remember {mutableStateOf(false)}
    fun run(block:suspend()->Unit) {scope.launch {
        busy=true;error=""
        try {block();usage=repo.storageUsage()}
        catch(e:Exception){if(e is CancellationException)throw e;error=DriveAuth.message(e)}finally{busy=false}
    }}
    LaunchedEffect(Unit){run {}}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("Storage")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(busy||usage==null)LinearProgressIndicator(Modifier.fillMaxWidth())
            usage?.let {size->
                Text("Downloads: ${Formatter.formatShortFileSize(context,size.downloads)}")
                Text("Temporary files: ${Formatter.formatShortFileSize(context,size.temporary)}")
                Text("Your edits and comments: ${Formatter.formatShortFileSize(context,size.edits)}")
                Text("Clearing temporary files keeps your downloads, edits and comments. Other previews may need to load from Drive again.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled=!busy&&size.temporary>0,onClick={run {
                    val freed=repo.clearTemporary();message="Freed ${Formatter.formatShortFileSize(context,freed)}"
                }}) {Text("Clear temporary files")}
                TextButton(enabled=!busy&&size.downloads>0,onClick={removeDownloads=true}){Text("Remove downloaded files")}
                Text("Note previews are kept within 128 MB after you close a note. Files you download for offline reading are kept until you remove them.",style=MaterialTheme.typography.bodySmall)
            }
            if(message.isNotBlank())Text(message)
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(enabled=!busy,onClick=onDismiss){Text("Done")}})
    if(removeDownloads)AlertDialog(onDismissRequest={removeDownloads=false},title={Text("Remove downloaded files?")},
        text={Text("This cancels folder downloads and removes their saved files from this device. Your Drive originals, followed folders, edits and comments are kept.")},
        confirmButton={TextButton(onClick={removeDownloads=false;run {repo.clearDownloads();message="Downloaded files removed."}}){Text("Remove downloads")}},
        dismissButton={TextButton(onClick={removeDownloads=false}){Text("Cancel")}})
}
