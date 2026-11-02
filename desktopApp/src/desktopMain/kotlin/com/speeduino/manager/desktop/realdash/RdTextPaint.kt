package com.speeduino.manager.desktop.realdash

import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Typeface

/**
 * Substitui `android.graphics.Paint` no desenho de texto do dashboard RealDash: guarda tipografia,
 * cor, tamanho e alinhamento e desenha com Skia (`Canvas.drawText`).
 */
class RdTextPaint {
    enum class Align { LEFT, CENTER, RIGHT }

    var typeface: Typeface = defaultTypeface()
    var color: Int = 0xFFFFFFFF.toInt()
    var textSize: Float = 12f
    var textAlign: Align = Align.LEFT

    private val skiaPaint = SkiaPaint().apply { isAntiAlias = true }

    private fun font() = Font(typeface, textSize).apply { isSubpixel = true }

    fun measureText(text: String): Float = font().measureTextWidth(text)
    fun ascent(): Float = font().metrics.ascent
    fun descent(): Float = font().metrics.descent

    fun draw(canvas: org.jetbrains.skia.Canvas, text: String, x: Float, y: Float) {
        val f = font()
        val dx = when (textAlign) {
            Align.LEFT -> 0f
            Align.CENTER -> -f.measureTextWidth(text) / 2
            Align.RIGHT -> -f.measureTextWidth(text)
        }
        skiaPaint.color = color
        canvas.drawString(text, x + dx, y, f, skiaPaint)
    }
}

private val DEFAULT_TYPEFACE: Typeface by lazy {
    org.jetbrains.skia.FontMgr.default.matchFamilyStyle("sans-serif", org.jetbrains.skia.FontStyle.NORMAL)
        ?: org.jetbrains.skia.FontMgr.default.legacyMakeTypeface("sans-serif", org.jetbrains.skia.FontStyle.NORMAL)
        ?: Typeface.makeEmpty()
}

internal fun defaultTypeface(): Typeface = DEFAULT_TYPEFACE
