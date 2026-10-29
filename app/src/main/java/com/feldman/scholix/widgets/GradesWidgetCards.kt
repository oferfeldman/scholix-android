package com.feldman.scholix.widgets

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import androidx.graphics.shapes.toPath
import com.feldman.scholix.pages.isRtlText
import com.feldman.scholix.ui.components.subjectIconName
import com.feldman.scholix.ui.components.subjectShape
import org.json.JSONObject

private fun gradeWidgetPaint(context: Context, color: Int, size: Float, weight: Int, width: Int = 100) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, com.feldman.motion.R.font.feldman_font)
        fontVariationSettings = "'wght' $weight, 'wdth' $width, 'ROND' 200"
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, context.resources.displayMetrics)
        this.color = color
    }

internal fun gradeWidgetSummary(context: Context, label: String, value: String, widthDp: Float, background: Int, foreground: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val scale = (widthDp / 300f).coerceIn(0.65f, 1f)
    val width = (widthDp * density).toInt().coerceAtLeast(1)
    val height = (96f * scale * density).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 28f * density, 28f * density, Paint().apply { color = background; isAntiAlias = true })
    val labelPaint = gradeWidgetPaint(context, foreground, 32f * scale, 500)
    val valuePaint = gradeWidgetPaint(context, foreground, 48f * scale, 900)
    val padding = 16f * scale * density
    val rtl = isRtlText(label)
    val valueWidth = valuePaint.measureText(value)
    val labelWidth = (width - padding * 3 - valueWidth).toInt().coerceAtLeast(1)
    val title = StaticLayout.Builder.obtain(label, 0, label.length, labelPaint, labelWidth)
        .setIncludePad(false).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    canvas.save()
    canvas.translate(if (rtl) padding * 2 + valueWidth else padding, (height - title.height) / 2f)
    title.draw(canvas)
    canvas.restore()
    canvas.drawText(value, if (rtl) padding else width - padding - valueWidth,
        height / 2f - (valuePaint.ascent() + valuePaint.descent()) / 2, valuePaint)
    return bitmap
}

internal fun gradeWidgetRow(context: Context, grade: JSONObject, widthDp: Float, index: Int, count: Int,
    background: Int, foreground: Int, secondary: Int, iconBackground: Int, iconForeground: Int, gradeColor: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val scale = (widthDp / 300f).coerceIn(0.65f, 1f)
    val width = (widthDp * density).toInt().coerceAtLeast(1)
    val padding = 16f * scale * density
    val iconSize = 56f * scale * density
    val gap = 12f * scale * density
    val gradeWidth = 64f * scale * density
    val subject = grade.optString("subject")
    val name = grade.optString("name")
    val titleText = name.ifBlank { subject }
    val subtitleText = subject.takeIf { name.isNotBlank() && it != name }.orEmpty()
    val rtl = isRtlText("$titleText $subtitleText")
    val textWidth = (width - 2 * padding - iconSize - gap - gradeWidth - 8f * scale * density).toInt().coerceAtLeast(1)
    fun layout(text: String, size: Float, color: Int, lines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, gradeWidgetPaint(context, color, size * scale, 500), textWidth)
            .setIncludePad(false).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    val title = layout(titleText, 22f, foreground, 2)
    val subtitle = subtitleText.takeIf { it.isNotBlank() }?.let { layout(it, 16f, secondary, 1) }
    val textHeight = title.height + (subtitle?.height ?: 0)
    val height = (maxOf(iconSize, textHeight.toFloat()) + 24f * scale * density).toInt()
    // Keep the collection below Android's widget bitmap limit, including large grade histories.
    val renderScale = kotlin.math.sqrt((4_000_000f / count.coerceAtLeast(1) / (width.toFloat() * height * 4f)).coerceAtMost(1f))
    val bitmap = Bitmap.createBitmap((width * renderScale).toInt().coerceAtLeast(1),
        (height * renderScale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap).apply { scale(renderScale, renderScale) }
    val top = (if (index == 0) 28f else 4f) * density
    val bottom = (if (index == count - 1) 28f else 4f) * density
    val card = Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()),
        floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom), Path.Direction.CW) }
    canvas.drawPath(card, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
    val iconX = if (rtl) width - padding - iconSize else padding
    val iconY = (height - iconSize) / 2f
    val shape = subjectShape(subject).toPath()
    val bounds = RectF().also { shape.computeBounds(it, true) }
    shape.transform(Matrix().apply { setRectToRect(bounds, RectF(iconX, iconY, iconX + iconSize, iconY + iconSize), Matrix.ScaleToFit.FILL) })
    canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = iconBackground })
    val symbol = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = iconForeground
        typeface = ResourcesCompat.getFont(context, com.feldman.motion.R.font.material_symbols_rounded)
        fontVariationSettings = "'FILL' 1"
        textSize = 36f * scale * density
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(subjectIconName(subject, name), iconX + iconSize / 2,
        height / 2f - (symbol.ascent() + symbol.descent()) / 2, symbol)
    canvas.save()
    canvas.translate(if (rtl) padding + gradeWidth + 8f * scale * density else padding + iconSize + gap,
        (height - textHeight) / 2f)
    title.draw(canvas)
    if (subtitle != null) { canvas.translate(0f, title.height.toFloat()); subtitle.draw(canvas) }
    canvas.restore()
    val raw = grade.optString("grade", "-")
    val value = raw.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong().toString() else raw } ?: raw
    val number = gradeWidgetPaint(context, gradeColor, 54f * scale, 700, 50)
    while (number.measureText(value) > gradeWidth && number.textSize > 10f * density) number.textSize -= density
    number.textAlign = Paint.Align.CENTER
    canvas.drawText(value, if (rtl) padding + gradeWidth / 2 else width - padding - gradeWidth / 2,
        height / 2f - (number.ascent() + number.descent()) / 2, number)
    return bitmap
}
