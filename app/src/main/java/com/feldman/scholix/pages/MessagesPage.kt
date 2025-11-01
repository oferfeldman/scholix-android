package com.feldman.scholix.pages

import android.text.Html
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.feldman.scholix.api.PlatformStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var messages by remember { mutableStateOf(listOf<JSONObject>()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<JSONObject?>(null) }
    val pullToRefreshState = rememberPullToRefreshState()

    suspend fun loadMessages() {
        withContext(Dispatchers.IO) {
            val platform = PlatformStorage.loadPlatforms(context)[0]
            val msgs = platform.getMessages(1)
            val list = mutableListOf<JSONObject>()
            for (i in 0 until msgs.length()) list.add(msgs.getJSONObject(i))
            withContext(Dispatchers.Main) {
                messages = list
            }
        }
    }

    LaunchedEffect(Unit) {
        loadMessages()
    }

    PullToRefreshBox(
        state = pullToRefreshState,
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                loadMessages()
                isRefreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        if (selectedMessage != null) {
            MessageDetailsView(
                message = selectedMessage!!,
                onBack = { selectedMessage = null }
            )
        } else {
            MessagesList(
                messages = messages,
                onMessageClick = { msg ->
                    scope.launch(Dispatchers.IO) {
                        val platform = PlatformStorage.loadPlatforms(context)[0]
                        val details = platform.getMessageDetails(msg.optString("messageId"))
                        withContext(Dispatchers.Main) {
                            selectedMessage = details
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun MessagesList(messages: List<JSONObject>, onMessageClick: (JSONObject) -> Unit) {
    if (messages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("אין הודעות בתיבת הדואר.", color = Color.Gray)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        items(messages) { msg ->
            val subject = msg.optString("subject", "")
            val from = msg.optString("from", "")
            val date = msg.optString("date", "")
            val unread = msg.optInt("hasRead", 0) == 0

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onMessageClick(msg) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (unread) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = subject.ifEmpty { "(ללא נושא)" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = from,
                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val relativeTime = try {
                        val parsedTime = java.time.OffsetDateTime.parse(date).toInstant().toEpochMilli()
                        DateUtils.getRelativeTimeSpanString(parsedTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    } catch (_: Exception) {
                        date
                    }

                    Text(
                        text = relativeTime,
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
                    )

                }
            }
        }
    }
}
@Composable
fun MessageDetailsView(message: JSONObject, onBack: () -> Unit) {
    val context = LocalContext.current
    val subject = message.optString("subject", "")
    val from = message.optString("from", "")
    val date = message.optString("date", "")
    val content = Html.fromHtml(message.optString("contentHtml", ""), Html.FROM_HTML_MODE_LEGACY).toString()
    val attachments = message.optJSONArray("attachments") ?: JSONArray()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "חזרה",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(subject, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Text(from, style = MaterialTheme.typography.bodyLarge.copy(color = Color.Gray))
        Text(date, style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray))

        Spacer(Modifier.height(16.dp))
        Text(content, style = MaterialTheme.typography.bodyLarge)

        // 📎 Attachments section
        if (attachments.length() > 0) {
            Spacer(Modifier.height(20.dp))
            Text("קבצים מצורפים:", style = MaterialTheme.typography.titleMedium)

            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (i in 0 until attachments.length()) {
                    val att = attachments.getJSONObject(i)
                    val name = att.optString("name", "קובץ")
                    val type = att.optString("type", "")
                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val platform = PlatformStorage.loadPlatforms(context).firstOrNull()
                                val ok = platform?.downloadAttachment(context, att) ?: false
                                withContext(Dispatchers.Main) {
                                    if (ok)
                                        android.widget.Toast.makeText(context, "הקובץ ירד בהצלחה.", android.widget.Toast.LENGTH_SHORT).show()
                                    else
                                        android.widget.Toast.makeText(context, "שגיאה בהורדת הקובץ.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "📎 $name",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            if (type.isNotEmpty()) {
                                Text(
                                    text = type,
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
                                )
                            }
                        }
                    }
                }
            }
        }

    }
}
