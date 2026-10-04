package com.feldman.scholix.drive

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import coil.compose.AsyncImage

@Composable
fun StarNotePage(drive:DriveRepository, query:String, modifier:Modifier=Modifier, onReader:(Boolean)->Unit={}) {
    val context=LocalContext.current
    val repository=remember { StarNoteRepository(context,drive) }
    val scope=rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<DriveItem>>(emptyList()) }
    var opened by remember { mutableStateOf<StarOpen?>(null) }
    var edits by remember { mutableStateOf<StarEdits?>(null) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("read") }
    var dialog by remember { mutableStateOf<StarAnnotation?>(null) }
    var text by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf(false) }
    var enableBackup by remember { mutableStateOf(false) }
    fun run(block:suspend ()->Unit) { if(busy)return;scope.launch {busy=true;error="";try{block()}
        catch(e:Exception){if(e is CancellationException)throw e;error=DriveAuth.message(e)}finally{busy=false}} }
    fun refresh()=run {notes=repository.notes()}
    fun backup()=run {
        val current=edits ?: return@run; val account=opened?.account ?: return@run
        repository.saveLocal(account,current)
        val saved=repository.backup(account,current)
        repository.saveLocal(account,saved,acknowledge=true)
        if(edits?.revision==saved.revision) edits=saved
        status="Backed up in Google Drive";enableBackup=false
    }
    val authorization=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            DriveAuth.complete(result.resultCode,result.data!=null) {
                Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data).accessToken
            }
            backup()
        } catch(e:Exception) {error=DriveAuth.message(e)}
    }
    fun enable()=run {
        val account=opened?.account ?: return@run
        val result=DriveAuth.authorize(context,account,edits=true)
        if(result.hasResolution()) authorization.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent).intentSender).build())
        else {
            val current=edits ?: return@run
            repository.saveLocal(account,current)
            val saved=repository.backup(account,current);repository.saveLocal(account,saved,acknowledge=true)
            if(edits?.revision==saved.revision)edits=saved
            status="Backed up in Google Drive";enableBackup=false
        }
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(opened) { onReader(opened!=null) }
    DisposableEffect(Unit) { onDispose { onReader(false) } }
    // Persist locally before a cloud request. Unsent revisions survive process death and network loss.
    LaunchedEffect(edits?.revision,edits?.backedUp) {
        val current=edits ?: return@LaunchedEffect;val account=opened?.account ?: return@LaunchedEffect
        try {
            withContext(NonCancellable) { repository.saveLocal(account,current) }
            if(!current.backedUp) {
                status="Saved on this device · waiting for Drive backup"
                delay(1500)
                val saved=repository.backup(account,current)
                repository.saveLocal(account,saved,acknowledge=true)
                if(edits?.revision==saved.revision)edits=saved
                status="Backed up in Google Drive";enableBackup=false
            } else status=if(current.annotations.isEmpty() && current.title.isBlank()) "No Scholix edits yet" else "Backed up in Google Drive"
        } catch(e:Exception) {
            if(e is CancellationException)throw e
            enableBackup=e is DriveNeedsConsent
            error=if(enableBackup) "Enable Drive backups to save your Scholix edits online." else DriveAuth.message(e)
        }
    }
    fun close() {
        val current=edits;val account=opened?.account
        run { if(current!=null && account!=null)repository.saveLocal(account,current);opened=null;edits=null }
    }
    BackHandler(opened!=null) { close() }
    Column(modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        val note=opened
        if(note==null) {
            Text("StarNote",style=MaterialTheme.typography.titleMedium)
            Text("Open notes from StarNote’s Drive sync folder. Scholix handwriting, text and comments are backed up separately.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={refresh()},enabled=!busy){Text("Refresh StarNote notes")}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(notes.isEmpty()&&!busy)item {Text("No sync/v1 notes found. Turn on Google Drive sync in StarNote.")}
                items(notes.filter {it.name.contains(query,true)},key={it.id}) {item ->
                    var cover by remember(item.id) {mutableStateOf<File?>(null)}
                    LaunchedEffect(item.id) {try{cover=repository.cover(item)}catch(e:Exception){if(e is CancellationException)throw e}}
                    ElevatedCard(onClick={run {val value=repository.open(item);opened=value;edits=value.edits;pageIndex=0;mode="read";status=""}},modifier=Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            cover?.let {AsyncImage(it,"Note cover",Modifier.size(64.dp),contentScale=ContentScale.Fit)}
                        Column {
                            Text(if(Regex("[a-f0-9-]{36}").matches(item.name)) "Note ${item.name.take(8)}" else item.name,style=MaterialTheme.typography.titleSmall)
                            Text(item.name,style=MaterialTheme.typography.labelSmall)
                            Text("Open · rename in Scholix",style=MaterialTheme.typography.bodySmall)
                        }
                        }
                    }
                }
            }
        } else {
            val current=edits ?: return@Column
            val page=note.document.pages[pageIndex]
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={close()},enabled=!busy){Text("Back")}
                TextButton(onClick={text=current.title;rename=true}){Text(current.title.ifBlank {"StarNote note"})}
            }
            Text("Edits made here appear in Scholix. They don’t change the original StarNote backup.",style=MaterialTheme.typography.labelSmall)
            Text("StarNote preview · covers, pen pressure and some formatting may differ.",style=MaterialTheme.typography.labelSmall)
            if(note.document.warnings.isNotEmpty()) Text(note.document.warnings.joinToString(" "),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
            Text(status,style=MaterialTheme.typography.labelSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                listOf("read" to "Read","ink" to "Pen","erase" to "Erase ink","text" to "Text","comment" to "Comment").forEach {(id,label)->
                    FilterChip(mode==id,onClick={mode=id},label={Text(label)})
                }
                TextButton(onClick={enable()},enabled=!busy){Text(if(enableBackup)"Enable backups" else "Back up now")}
                TextButton(onClick={edits=current.changed(annotations=current.annotations.dropLast(1))},enabled=current.annotations.isNotEmpty()) {Text("Undo last addition")}
            }
            if(mode in listOf("text","comment")) Text("Tap the page to place ${if(mode=="text")"text" else "a comment"}.",style=MaterialTheme.typography.labelSmall)
            StarNoteCanvas(page,note.resources[page.resource],note.templates[page.template],current.annotations.filter {it.page==page.id},mode,
                onInk={points->edits=edits!!.changed(annotations=edits!!.annotations+StarAnnotation(page=page.id,kind="ink",points=points))},
                onTap={point->
                    if(mode=="erase") {
                        val visible=page.strokes.filterNot {s->current.annotations.any {it.page==page.id&&it.kind=="hide"&&it.text==s.id}}
                        fun distance(points:List<StarPoint>)=points.minOfOrNull {p->
                            val dx=p.x-point.x;val dy=(p.y-point.y)*page.height/page.width;dx*dx+dy*dy
                        } ?: Float.MAX_VALUE
                        val original=visible.minByOrNull {s->distance(s.points.map {p->
                            val m=s.transform;val d=m[6]*p.x+m[7]*p.y+m[8]
                            StarPoint((m[0]*p.x+m[1]*p.y+m[2])/d/page.width,(m[3]*p.x+m[4]*p.y+m[5])/d/page.height)
                        })}
                        val added=current.annotations.filter {it.page==page.id&&it.kind=="ink"}.minByOrNull {distance(it.points)}
                        val originalDistance=original?.let {s->distance(s.points.map {p->
                            val m=s.transform;val d=m[6]*p.x+m[7]*p.y+m[8]
                            StarPoint((m[0]*p.x+m[1]*p.y+m[2])/d/page.width,(m[3]*p.x+m[4]*p.y+m[5])/d/page.height)
                        })} ?: Float.MAX_VALUE
                        if(added!=null && distance(added.points)<.0025f && distance(added.points)<=originalDistance)
                            edits=current.changed(annotations=current.annotations.filterNot {it.id==added.id})
                        else if(original!=null && originalDistance<.0025f)
                            edits=current.changed(annotations=current.annotations+StarAnnotation(page=page.id,kind="hide",text=original.id))
                    } else {dialog=StarAnnotation(page=page.id,kind=mode,points=listOf(point));text=""}
                },modifier=Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={pageIndex--},enabled=pageIndex>0){Text("Previous")}
                Text("${pageIndex+1} / ${note.document.pages.size}",Modifier.padding(top=12.dp))
                TextButton(onClick={pageIndex++},enabled=pageIndex+1<note.document.pages.size){Text("Next")}
            }
            val comments=current.annotations.filter {it.page==page.id && it.kind in listOf("comment","text")}
            if(comments.isNotEmpty()) LazyColumn(Modifier.heightIn(max=150.dp)) {
                items(comments,key={it.id}) {a->Row(verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={dialog=a;text=a.text},modifier=Modifier.weight(1f)) {Text("${if(a.kind=="comment")"Comment" else "Text"}: ${a.text}",maxLines=2)}
                    TextButton(onClick={edits=current.changed(annotations=current.annotations.filterNot {it.id==a.id})}) {Text("Delete")}
                }}
            }
        }
    }
    if(dialog!=null||rename) AlertDialog(onDismissRequest={dialog=null;rename=false},title={Text(if(rename)"Name this note" else if(dialog!!.kind=="comment")"Comment" else "Text")},
        text={OutlinedTextField(text,{text=it.take(10000)},modifier=Modifier.fillMaxWidth())},
        confirmButton={TextButton(onClick={
            val current=edits!!
            edits=if(rename)current.changed(title=text.trim()) else current.changed(annotations=current.annotations.filterNot {it.id==dialog!!.id}+dialog!!.copy(text=text.trim()))
            dialog=null;rename=false
        },enabled=text.isNotBlank()){Text("Save")}},dismissButton={TextButton(onClick={dialog=null;rename=false}){Text("Cancel")}})
}

@Composable
private fun StarNoteCanvas(page:StarPage,resource:File?,template:org.json.JSONObject?,annotations:List<StarAnnotation>,mode:String,
    onInk:(List<StarPoint>)->Unit,onTap:(StarPoint)->Unit,modifier:Modifier=Modifier) {
    var bitmap by remember(page.id,resource) {mutableStateOf<Bitmap?>(null)}
    var backgroundError by remember(page.id,resource) {mutableStateOf("")}
    var drawing by remember(page.id) {mutableStateOf<List<StarPoint>>(emptyList())}
    var zoom by remember(page.id) {mutableFloatStateOf(1f)}
    var pan by remember(page.id) {mutableStateOf(Offset.Zero)}
    val transform=rememberTransformableState {scale,offset,_->
        zoom=(zoom*scale).coerceIn(1f,5f)
        pan=if(zoom==1f)Offset.Zero else pan+offset
    }
    LaunchedEffect(mode) {zoom=1f;pan=Offset.Zero}
    val inkCallback by rememberUpdatedState(onInk);val tapCallback by rememberUpdatedState(onTap)
    LaunchedEffect(page.id,resource) {
        if(page.kind=="import_pdf") {
            try {
                require(resource!=null){"The original PDF is missing from this backup."}
                bitmap=withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(resource,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->PdfRenderer(fd).use {pdf->
                        pdf.openPage(page.resourcePage).use {p->
                            val scale=1800f/maxOf(p.width,p.height)
                            Bitmap.createBitmap((p.width*scale).toInt().coerceAtLeast(1),(p.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(android.graphics.Color.WHITE);p.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }}
                }
            }catch(e:Exception){if(e is CancellationException)throw e;backgroundError=e.message ?: "PDF background unavailable"}
        }
    }
    DisposableEffect(bitmap){val b=bitmap;onDispose{b?.recycle()}}
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds(),contentAlignment=Alignment.Center) {
        val ratio=page.width/page.height
        val w=minOf(maxWidth,maxHeight*ratio)
        Box(Modifier.width(w).aspectRatio(ratio).then(if(mode=="read")Modifier.transformable(transform) else Modifier)
            .graphicsLayer {scaleX=zoom;scaleY=zoom;translationX=pan.x;translationY=pan.y}) {
            bitmap?.let {Image(it.asImageBitmap(),"StarNote page",Modifier.fillMaxSize())}
            Canvas(Modifier.fillMaxSize().pointerInput(page.id,mode) {
                fun point(p:Offset)=StarPoint((p.x/size.width).coerceIn(0f,1f),(p.y/size.height).coerceIn(0f,1f))
                if(mode=="ink") detectDragGestures(onDragStart={drawing=listOf(point(it))},onDragCancel={drawing=emptyList()},
                    onDragEnd={if(drawing.size>1)inkCallback(drawing);drawing=emptyList()},onDrag={change,_->
                        change.consume();if(drawing.size<100000)drawing=drawing+point(change.position)
                    })
                else if(mode in listOf("text","comment","erase")) detectTapGestures {tapCallback(point(it))}
            }) {
                if(page.kind!="import_pdf") {
                    drawRect(Color.White)
                    val props=template?.optJSONObject("properties")
                    val spacing=(props?.optJSONObject("pageMargins")?.optDouble("spacing",86.81) ?: 86.81).toFloat()*size.height/page.height
                    if(template?.optString("universalShapeType")?.contains("line")==true && spacing>2f) {
                        var y=spacing;while(y<size.height){drawLine(Color(0xFFE2E5EA),Offset(0f,y),Offset(size.width,y),1f);y+=spacing}
                    }
                }
                fun path(points:List<StarPoint>,normalized:Boolean,color:Color,width:Float,m:List<Float> = listOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)) {
                    if(points.isEmpty())return
                    val path=Path()
                    points.forEachIndexed {i,p->
                        val denominator=m[6]*p.x+m[7]*p.y+m[8]
                        if(denominator==0f)return@forEachIndexed
                        val x=if(normalized)p.x*size.width else ((m[0]*p.x+m[1]*p.y+m[2])/denominator)*size.width/page.width
                        val y=if(normalized)p.y*size.height else ((m[3]*p.x+m[4]*p.y+m[5])/denominator)*size.height/page.height
                        if(i==0)path.moveTo(x,y) else path.lineTo(x,y)
                    }
                    drawPath(path,color,style=Stroke(width.coerceAtLeast(1f),cap=androidx.compose.ui.graphics.StrokeCap.Round))
                }
                page.strokes.filterNot {s->annotations.any {it.kind=="hide"&&it.text==s.id}}.forEach {path(it.points,false,Color(it.color),it.width*size.width/page.width,it.transform)}
                annotations.filter {it.kind=="ink"}.forEach {path(it.points,true,Color(0xFF2158BE),3f)}
                path(drawing,true,Color(0xFF2158BE),3f)
                annotations.filter {it.kind=="comment"}.forEach {a->a.points.firstOrNull()?.let {
                    drawCircle(Color(0xFFE3A008),6.dp.toPx(),Offset(it.x*size.width,it.y*size.height))
                }}
            }
            annotations.filter {it.kind=="text"}.forEach {a->a.points.firstOrNull()?.let {p->
                Text(a.text,Modifier.offset(x=w*p.x,y=(w/ratio)*p.y).widthIn(max=w*(1f-p.x)),color=Color(0xFF2158BE),style=MaterialTheme.typography.bodySmall)
            }}
            if(backgroundError.isNotEmpty())Text(backgroundError,color=MaterialTheme.colorScheme.error)
        }
    }
}
