package com.example.sudoku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import org.json.JSONArray

data class RubyPart(val text: String, val reading: String?)
data class Haiku(val id: Int, val russian: String, val lines: List<List<RubyPart>>)

fun loadHaikus(context: Context): List<Haiku> {
    val data = context.assets.open("basho_haiku_furigana.json").bufferedReader(Charsets.UTF_8).use { JSONArray(it.readText()) }
    return (0 until data.length()).map { index ->
        val item = data.getJSONObject(index)
        val lines = item.getJSONArray("lines")
        Haiku(item.getInt("id"), item.getString("russian"), (0 until lines.length()).map { row ->
            val parts = lines.getJSONArray(row)
            (0 until parts.length()).map { column ->
                val part = parts.getJSONObject(column)
                RubyPart(part.getString("text"), if (part.has("reading")) part.getString("reading") else null)
            }
        })
    }
}

/** Native Views adaptation of the supplied Compose example, with aligned furigana. */
class HaikuView(context: Context, private val haiku: Haiku, private val ink: Int, private val muted: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    init {
        contentDescription = haiku.lines.joinToString("。") { line -> line.joinToString("") { it.text } }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        val scaled = resources.displayMetrics.scaledDensity
        val rowHeight = height.toFloat() / haiku.lines.size
        haiku.lines.forEachIndexed { row, parts ->
            val base = minOf(25f * scaled, rowHeight / 1.65f)
            fun widths(size: Float) = parts.map { part ->
                paint.textSize = size
                val textWidth = paint.measureText(part.text)
                paint.textSize = size * .42f
                maxOf(textWidth, paint.measureText(part.reading ?: ""))
            }
            val natural = widths(base).sum()
            val size = base * minOf(1f, (width - paddingLeft - paddingRight).coerceAtLeast(1) / natural.coerceAtLeast(1f))
            val partWidths = widths(size)
            var x = (width - partWidths.sum()) / 2f
            parts.forEachIndexed { column, part ->
                paint.textAlign = Paint.Align.CENTER
                paint.color = muted; paint.textSize = size * .42f
                canvas.drawText(part.reading ?: "", x + partWidths[column] / 2, row * rowHeight + size * .44f, paint)
                paint.color = ink; paint.textSize = size
                canvas.drawText(part.text, x + partWidths[column] / 2, row * rowHeight + size * 1.5f, paint)
                x += partWidths[column]
            }
        }
    }
}
