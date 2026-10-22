package com.example.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.local.DocumentEntity

/**
 * E-Ink friendly editorial thumbnail renderer for articles (including PDF articles without a custom coverUri).
 * Produces a high-contrast typographic front-page thumbnail with black rules, newspaper header,
 * serif headline, author byline, and body excerpt lines.
 */
object ArticleThumbnailRenderer {

    fun render(
        context: Context,
        doc: DocumentEntity,
        widthPx: Int = 300,
        heightPx: Int = 420
    ): Bitmap {
        val w = widthPx.coerceAtLeast(80)
        val h = heightPx.coerceAtLeast(100)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Paper background (high-contrast warm white / e-ink friendly)
        canvas.drawColor(Color.parseColor("#FAF8F2"))

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111111")
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.014f).coerceAtLeast(2f)
        }
        val thinRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111111")
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.006f).coerceAtLeast(1f)
        }
        val bodyMockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#333333")
            style = Paint.Style.STROKE
            strokeWidth = (h * 0.006f).coerceAtLeast(1.5f)
        }

        val halfStroke = borderPaint.strokeWidth / 2f
        canvas.drawRect(halfStroke, halfStroke, w - halfStroke, h - halfStroke, borderPaint)

        val pad = w * 0.08f
        val innerW = (w - pad * 2).toInt().coerceAtLeast(40)
        var y = pad * 0.9f

        // Kicker / Masthead label ("ARTICLE · PDF" or "ARTICLE")
        val kickerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111111")
            textSize = (w * 0.052f).coerceIn(9f, 20f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val formatBadge = doc.fileType.uppercase().takeIf { it.isNotBlank() } ?: "TEXT"
        val kickerText = "ARTICLE · $formatBadge"
        canvas.drawText(kickerText, pad, y + kickerPaint.textSize, kickerPaint)
        y += kickerPaint.textSize + (h * 0.025f)

        // Double editorial rule under masthead
        canvas.drawLine(pad, y, w - pad, y, borderPaint)
        y += (h * 0.012f).coerceAtLeast(2f)
        canvas.drawLine(pad, y, w - pad, y, thinRulePaint)
        y += (h * 0.04f)

        // Headline (Title) in bold serif
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111111")
            textSize = (w * 0.092f).coerceIn(13f, 36f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val cleanTitle = doc.title.trim().ifBlank { "Untitled Article" }
        val titleLayout = StaticLayout.Builder
            .obtain(cleanTitle, 0, cleanTitle.length, titlePaint, innerW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.08f)
            .setMaxLines(4)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.translate(pad, y)
        titleLayout.draw(canvas)
        canvas.restore()
        y += titleLayout.height + (h * 0.03f)

        // Author byline if present
        val author = doc.author.trim()
        if (author.isNotBlank() && !author.equals("Unknown Author", ignoreCase = true)) {
            val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#222222")
                textSize = (w * 0.055f).coerceIn(10f, 22f)
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            }
            val byline = "By $author"
            val authorLayout = StaticLayout.Builder
                .obtain(byline, 0, byline.length, authorPaint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(1)
                .setIncludePad(false)
                .build()
            canvas.save()
            canvas.translate(pad, y)
            authorLayout.draw(canvas)
            canvas.restore()
            y += authorLayout.height + (h * 0.025f)
        }

        // Thin rule separating header from excerpt / columns
        canvas.drawLine(pad, y, w - pad, y, thinRulePaint)
        y += (h * 0.035f)

        // Excerpt text if available, otherwise typographic column lines
        val snippet = doc.lastPositionSnippet?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.summary?.trim()?.takeIf { it.isNotBlank() }
        if (snippet != null && h >= 180) {
            val snippetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#222222")
                textSize = (w * 0.048f).coerceIn(8.5f, 18f)
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            }
            val remainingH = (h - y - pad).toInt()
            val maxLines = (remainingH / (snippetPaint.textSize * 1.25f)).toInt().coerceIn(2, 8)
            val snippetLayout = StaticLayout.Builder
                .obtain(snippet, 0, snippet.length, snippetPaint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.18f)
                .setMaxLines(maxLines)
                .setIncludePad(false)
                .build()
            canvas.save()
            canvas.translate(pad, y)
            snippetLayout.draw(canvas)
            canvas.restore()
            y += snippetLayout.height + (h * 0.03f)
        }

        // Fill remaining lower space with newspaper column rule lines
        val lineStep = (h * 0.038f).coerceAtLeast(6f)
        val bottomLimit = h - pad * 1.1f
        var lineIndex = 0
        while (y + lineStep < bottomLimit) {
            val lineEnd = if (lineIndex % 4 == 3) w - pad - innerW * 0.28f else w - pad
            canvas.drawLine(pad, y, lineEnd, y, bodyMockPaint)
            y += lineStep
            lineIndex++
        }

        return bmp
    }
}
