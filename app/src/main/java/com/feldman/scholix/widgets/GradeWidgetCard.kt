package com.feldman.scholix.widgets

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.core.content.FileProvider
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import com.feldman.scholix.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

@Composable
internal fun GradeWidgetCard(context: Context, bitmap: Bitmap, description: String, color: Int, shape: Int, summary: Boolean, gap: Dp) {
    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    bitmap.recycle()
    val name = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val file = File(context.cacheDir, "grades-widget/$name.png")
    if (!file.exists()) { file.parentFile!!.mkdirs(); file.writeBytes(bytes) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    // URI-backed artwork is loaded by the host instead of consuming the RemoteViews bitmap budget.
    val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    if (home != null) context.grantUriPermission(home, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val views = RemoteViews(context.packageName, if (summary) R.layout.grade_widget_summary else R.layout.grade_widget_row).apply {
        setInt(R.id.grade_widget_card, "setBackgroundResource", shape)
        if (Build.VERSION.SDK_INT >= 31) setColorStateList(R.id.grade_widget_card, "setBackgroundTintList", ColorStateList.valueOf(color))
        setImageViewUri(R.id.grade_widget_artwork, uri)
        setContentDescription(R.id.grade_widget_artwork, description)
    }
    Column(GlanceModifier.fillMaxWidth().padding(bottom = gap)) { AndroidRemoteViews(views, GlanceModifier.fillMaxWidth()) }
}
