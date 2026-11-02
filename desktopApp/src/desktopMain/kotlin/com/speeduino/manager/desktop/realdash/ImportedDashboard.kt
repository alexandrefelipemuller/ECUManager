package com.speeduino.manager.desktop.realdash

import io.ecucore.realdash.BarExt
import io.ecucore.realdash.ContainerExt
import io.ecucore.realdash.DialExt
import io.ecucore.realdash.Gauge
import io.ecucore.realdash.GaugeType
import io.ecucore.realdash.RdFile
import io.ecucore.realdash.RdRect
import io.ecucore.realdash.RealDashSignalMap
import io.ecucore.realdash.ResourceType
import io.ecucore.realdash.SliderExt
import io.ecucore.realdash.applyGaugeMath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.ecucore.EcuLiveData
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.util.Locale
import kotlin.math.min

private const val MAX_BITMAP_SIDE = 2048

/** Decodifica sob demanda as imagens e fontes embutidas no .rd. */
private class RdAssets(private val dash: RdFile, key: String) {
    private val bitmaps = HashMap<String, ImageBitmap?>()
    private val fonts = HashMap<String, org.jetbrains.skia.Typeface>()

    fun bitmap(name: String): ImageBitmap? {
        if (name.isEmpty()) return null
        return bitmaps.getOrPut(name) {
            val res = dash.resourcesByName[name]?.takeIf { it.type == ResourceType.IMAGE } ?: return@getOrPut null
            decode(res.data)
        }
    }

    /** Decodifica a imagem embutida (PNG/JPEG...) com Skia. */
    private fun decode(data: ByteArray): ImageBitmap? =
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(data).toComposeImageBitmap() }.getOrNull()

    val embeddedFontNames: Set<String> by lazy { dash.resources.filter { it.type == ResourceType.FONT }.map { it.name }.toSet() }

    fun typeface(name: String): org.jetbrains.skia.Typeface {
        if (name.isEmpty()) return defaultTypeface()
        return fonts.getOrPut(name) {
            val res = dash.resourcesByName[name]?.takeIf { it.type == ResourceType.FONT } ?: return@getOrPut defaultTypeface()
            runCatching {
                org.jetbrains.skia.FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(res.data))
                    ?: defaultTypeface()
            }.getOrDefault(defaultTypeface())
        }
    }
}

/** Valor ao vivo de um gauge (já com a fórmula aplicada), ou null se não há input/dado. */
private fun liveValue(g: Gauge, liveData: EcuLiveData?): Float? {
    val signal = RealDashSignalMap.signalFor(g.targetId) ?: return null
    // O arquivo do RealDash assume unidades métricas; não converter para imperial.
    val raw = resolveRealDashSignal(liveData, signal) ?: return null
    return applyGaugeMath(g.gaugeMath, raw).toFloat()
}

private class DrawEnv(
    val scale: Float,
    val assets: RdAssets,
    val liveData: EcuLiveData?,
    val paint: RdTextPaint,
    /** Mínimo/pico já visto por gauge nos modos "mínimo" (1) e "máximo" (2) do RealDash. */
    val extremes: java.util.IdentityHashMap<Gauge, Float>,
    val odometer: Odometer,
)

/**
 * Desenha um dashboard importado de um .rd do RealDash, com valores do [liveData].
 * Mantém a proporção do projeto original; páginas extras são trocadas pelos pontos embaixo.
 */
@Composable
fun ImportedDashboardView(
    dash: RdFile,
    assetKey: String,
    liveData: EcuLiveData?,
    modifier: Modifier = Modifier,
) {
    val assets = remember(dash, assetKey) { RdAssets(dash, assetKey) }
    val paint = remember { RdTextPaint() }
    val extremes = remember(dash) { java.util.IdentityHashMap<Gauge, Float>() }
    val odometer = remember { Odometer() }
    androidx.compose.runtime.LaunchedEffect(liveData) {
        odometer.update(resolveRealDashSignal(liveData, "vss"))
    }
    androidx.compose.runtime.DisposableEffect(odometer) { onDispose { odometer.save() } }
    var pageIndex by remember(dash) { mutableIntStateOf(0) }
    val page = dash.pages[pageIndex.coerceIn(0, dash.pages.lastIndex)]
    val aspect = dash.width.toFloat() / dash.height.toFloat().coerceAtLeast(1f)

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val fitWidth = maxWidth.value / maxHeight.value < aspect
        val frame = if (fitWidth) Modifier.fillMaxWidth().aspectRatio(aspect) else Modifier.fillMaxHeight().aspectRatio(aspect)
        Box(modifier = frame) {
            Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
                val env = DrawEnv(size.width / dash.width, assets, liveData, paint, extremes, odometer)
                val bg = Color(page.bgColor)
                drawRect(if (bg.alpha > 0f) bg else Color.Black)
                page.gauges.forEach { drawGauge(it, env) }
            }
            if (dash.pages.size > 1) {
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dash.pages.indices.forEach { i ->
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(if (i == pageIndex) Color.White else Color(0x55FFFFFF), CircleShape)
                                .clickable { pageIndex = i },
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.boxOf(r: RdRect) =
    Rect(r.left * size.width, r.top * size.height, r.right * size.width, r.bottom * size.height)

private fun DrawScope.drawGauge(g: Gauge, env: DrawEnv) {
    val box = boxOf(g.rect)
    if (box.width <= 0f || box.height <= 0f) return
    val odometerKm = when (g.targetId) {
        Odometer.ODOMETER_TARGET_ID -> env.odometer.odometerKm
        Odometer.TRIPMETER_A_TARGET_ID -> env.odometer.tripKm
        else -> null
    }
    val value = (odometerKm?.toFloat() ?: liveValue(g, env.liveData))?.let { live ->
        when (g.valueMode) {
            1 -> min(env.extremes[g] ?: live, live).also { env.extremes[g] = it }
            2 -> maxOf(env.extremes[g] ?: live, live).also { env.extremes[g] = it }
            else -> live
        }
    }
    val hasInput = g.targetId != -1
    val v = value ?: g.valueRange[0]
    val level = if (hasInput && value != null) g.levelFor(v) else 0
    val tint = Color(g.tintColors[level])
    val bg = Color(g.bgColors[level])

    when (g.type) {
        GaugeType.TEXT -> {
            if (bg.alpha > 0f) drawRect(bg, box.topLeft, box.size)
            val text = when {
                !hasInput -> g.text.text
                odometerKm == null && RealDashSignalMap.signalFor(g.targetId) == null -> ""   // input que o app não conhece: não mostrar valor falso
                value == null -> "--"
                g.targetId == RealDashSignalMap.GEAR_ID -> if (v.toInt() == 0) "N" else v.toInt().toString()
                else -> formatValue(v, g.decimals)
            }
            drawRdText(env, g, text, Color(g.textColors[level]), box, level)
        }
        GaugeType.IMAGE -> {
            if (bg.alpha > 0f) drawRect(bg, box.topLeft, box.size)
            env.assets.bitmap(g.imageFor(level))?.let { drawStretched(it, box, tint) }
        }
        GaugeType.BAR -> {
            val bmp = env.assets.bitmap(g.imageFor(level))
            val frac = g.fraction(v)
            val bottomToTop = (g.ext as? BarExt)?.bottomToTop == true
            if (bmp != null) {
                val src = if (bottomToTop) {
                    IntOffset(0, (bmp.height * (1 - frac)).toInt()) to IntSize(bmp.width, (bmp.height * frac).toInt().coerceAtLeast(1))
                } else {
                    IntOffset(0, 0) to IntSize((bmp.width * frac).toInt().coerceAtLeast(1), bmp.height)
                }
                val dst = if (bottomToTop) {
                    Rect(box.left, box.top + box.height * (1 - frac), box.right, box.bottom)
                } else {
                    Rect(box.left, box.top, box.left + box.width * frac, box.bottom)
                }
                drawImage(bmp, src.first, src.second, IntOffset(dst.left.toInt(), dst.top.toInt()),
                    IntSize(dst.width.toInt().coerceAtLeast(1), dst.height.toInt().coerceAtLeast(1)),
                    colorFilter = tintFilter(tint))
            } else {
                val fill = if (bottomToTop) Rect(box.left, box.top + box.height * (1 - frac), box.right, box.bottom)
                else Rect(box.left, box.top, box.left + box.width * frac, box.bottom)
                drawRect(if (bg.alpha > 0f) bg else Color(0xFF46FF64), fill.topLeft, fill.size)
            }
            if (!bottomToTop) drawBarScale(g, env, box)
        }
        GaugeType.NEEDLE, GaugeType.ARC -> drawDial(g, env, box, v, level, tint)
        GaugeType.BUTTON -> {
            val bmp = env.assets.bitmap(g.core.image)
            if (bmp != null) drawStretched(bmp, box, tint) else {
                val radius = g.core.uD[0] * min(box.width, box.height)
                drawRoundRect(bg, box.topLeft, box.size, CornerRadius(radius))
            }
            val label = g.stateTexts[level].ifEmpty { g.text.text }
            drawRdText(env, g, label, Color(g.textColors[level]), box, level)
        }
        GaugeType.CONTAINER -> (g.ext as? ContainerExt)?.children?.forEach { drawGauge(it, env) }
        GaugeType.SLIDER -> {
            val cx = box.center.x
            drawLine(Color(0xFF787878), Offset(cx, box.top), Offset(cx, box.bottom), strokeWidth = 3f)
            val knob = (g.ext as? SliderExt)?.head?.sprite?.get(0)?.let(env.assets::bitmap)
            if (knob != null) {
                val k = box.width * 1.6f
                val y = box.bottom - g.fraction(v) * box.height
                drawStretched(knob, Rect(cx - k / 2, y - k / 2, cx + k / 2, y + k / 2), Color.White)
            }
        }
        // Mapa, gráfico e axis ainda não têm renderização própria: só a área, para manter o layout.
        GaugeType.MAP, GaugeType.GRAPH, GaugeType.AXIS ->
            drawRect(Color(0x33FFFFFF), box.topLeft, box.size, style = Stroke(width = 1.5f))
        GaugeType.UNKNOWN -> Unit
    }
}

private fun tintFilter(tint: Color): ColorFilter? =
    if (tint == Color.White) null else ColorFilter.tint(tint, BlendMode.Modulate)

private fun DrawScope.drawStretched(bmp: ImageBitmap, box: Rect, tint: Color) {
    drawImage(
        bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height),
        IntOffset(box.left.toInt(), box.top.toInt()),
        IntSize(box.width.toInt().coerceAtLeast(1), box.height.toInt().coerceAtLeast(1)),
        colorFilter = tintFilter(tint),
    )
}

private fun DrawScope.drawDial(g: Gauge, env: DrawEnv, box: Rect, v: Float, level: Int, tint: Color) {
    val ext = g.ext as? DialExt ?: return
    env.assets.bitmap(g.core.image)?.let { drawStretched(it, box, tint) }
    // 0 = 12 horas, sentido horário (radianos); o Canvas do Android mede a partir das 3 horas.
    val angle = ext.startAngle + g.fraction(v) * ext.sweepAngle
    val sprite = env.assets.bitmap(ext.head.sprite[level])
    if (g.type == GaugeType.ARC && sprite != null) {
        val sector = Path().apply {
            moveTo(box.center.x, box.center.y)
            arcTo(box, Math.toDegrees(ext.startAngle.toDouble()).toFloat() - 90f,
                Math.toDegrees((angle - ext.startAngle).toDouble()).toFloat(), false)
            close()
        }
        clipPath(sector) { drawStretched(sprite, box, Color(ext.head.colorsC[level])) }
    }
    // Arcos não desenham rótulos automáticos: os dashes usam gauges de texto separados para a escala
    // (confirmado no gm.rd, onde o RealDash não mostra rótulos nos arcos mesmo com useAuto/maxDigits > 0).
    if (g.type != GaugeType.ARC) drawScale(g, ext, env, box)
    if (g.type == GaugeType.NEEDLE && sprite != null) {
        val s = min(box.width, box.height) * ext.head.uF[0] / sprite.height
        val w = sprite.width * s
        val h = sprite.height * s
        rotate(Math.toDegrees(angle.toDouble()).toFloat(), pivot = box.center) {
            drawImage(sprite, IntOffset.Zero, IntSize(sprite.width, sprite.height),
                IntOffset((box.center.x - w / 2).toInt(), (box.center.y - h / 2).toInt()),
                IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)))
        }
    }
}

/** Rótulos numéricos abaixo de uma barra horizontal (mesma escala `segments`/`maxDigits` dos mostradores). */
private fun DrawScope.drawBarScale(g: Gauge, env: DrawEnv, box: Rect) {
    val sc = (g.ext as? BarExt)?.scale ?: return
    // raw[16] = 0 nas barras sem rótulos (fundos e barras verticais de temperatura).
    if (!sc.useAuto || sc.maxDigits == 0 || sc.segments < 2 || sc.raw[16] == 0) return
    val paint = env.paint
    paint.typeface = env.assets.typeface(g.fonts.values[0].ifEmpty { g.text.font })
    paint.color = g.textColors[0]
    // fontSize do gauge = altura da barra; limitado a 7% da altura do dash (calibrado contra o RealDash).
    paint.textSize = min(g.text.fontSize * env.scale * sc.sizeScale, size.height * 0.07f)
    paint.textAlign = RdTextPaint.Align.CENTER
    val (lo, hi) = g.valueRange[0] to g.valueRange[1]
    val centerY = box.bottom + paint.textSize * 0.45f
    drawIntoCanvas { canvas ->
        for (i in 0 until sc.segments) {
            val k = i / (sc.segments - 1f)
            val label = formatValue(lo + k * (hi - lo), g.decimals).take(sc.maxDigits)
            paint.draw(canvas.nativeCanvas, label, box.left + k * box.width, centerY - (paint.ascent() + paint.descent()) / 2)
        }
    }
}

/** Rótulos numéricos ao longo da escala (segments / maxDigits do RealDash). */
private fun DrawScope.drawScale(g: Gauge, ext: DialExt, env: DrawEnv, box: Rect) {
    val sc = ext.scale
    if (!sc.useAuto || sc.maxDigits == 0 || sc.segments < 2) return
    val radius = min(box.width, box.height) / 2
    val paint = env.paint
    paint.typeface = env.assets.typeface(g.fonts.values[0].ifEmpty { g.text.font })
    paint.color = g.textColors[0]
    paint.textSize = radius * 0.16f * sc.sizeScale / 0.8f
    paint.textAlign = RdTextPaint.Align.CENTER
    val (lo, hi) = g.valueRange[0] to g.valueRange[1]
    drawIntoCanvas { canvas ->
        for (i in 0 until sc.segments) {
            val k = i / (sc.segments - 1f)
            val a = ext.startAngle + k * ext.sweepAngle
            val value = lo + k * (hi - lo)
            val label = (if (kotlin.math.abs(value) >= 10 || value == value.toInt().toFloat()) "%.0f".format(Locale.US, value) else "%.1f".format(Locale.US, value))
                .take(sc.maxDigits)
            val x = box.center.x + kotlin.math.sin(a) * radius * 0.68f
            val y = box.center.y - kotlin.math.cos(a) * radius * 0.68f
            paint.draw(canvas.nativeCanvas, label, x, y - (paint.ascent() + paint.descent()) / 2)
        }
    }
}

private fun formatValue(v: Float, decimals: Int): String =
    if (decimals <= 0) v.toInt().toString() else "%.${decimals}f".format(Locale.US, v)

private fun DrawScope.drawRdText(env: DrawEnv, g: Gauge, text: String, color: Color, box: Rect, level: Int) {
    if (text.isEmpty()) return
    val tr = g.text.textRect
    val tx0 = box.left + tr.left * box.width
    val tx1 = box.left + tr.right * box.width
    val ty0 = box.top + tr.top * box.height
    val ty1 = box.top + tr.bottom * box.height
    val paint = env.paint
    paint.typeface = env.assets.typeface(g.fonts.values[level].ifEmpty { g.text.font })
    paint.color = color.toArgb()
    // fontSize vem em px do dash original; a caixa de texto já é uma fração do gauge.
    paint.textSize = (g.text.fontSize * env.scale * tr.height).coerceAtLeast(4f)
    // O RealDash encolhe o texto que não cabe na largura (hipótese confirmada visualmente).
    val maxWidth = tx1 - tx0
    val measured = paint.measureText(text)
    // gaugeFlags 0x1000 (visto no "km/h" do gm.rd): o texto transborda a caixa em vez de encolher.
    val shrink = g.gaugeFlags and 0x1000 == 0
    if (shrink && measured > maxWidth && maxWidth > 0f) paint.textSize *= maxWidth / measured
    val centered = (g.text.textFlags shr 8) and 1 == 1
    // textFlags 0x00040000 = alinhado à direita (visto no gm.rd: o RealDash termina o texto na borda direita da caixa).
    val rightAligned = !centered && g.text.textFlags and 0x00040000 != 0
    paint.textAlign = when { centered -> RdTextPaint.Align.CENTER; rightAligned -> RdTextPaint.Align.RIGHT; else -> RdTextPaint.Align.LEFT }
    val x = when { centered -> (tx0 + tx1) / 2; rightAligned -> tx1; else -> tx0 }
    // Fontes embutidas no .rd (ex. 888.otf do gm.rd) saem ~14 px (do dash original) abaixo do RealDash: sobe o texto.
    val embeddedFontShift = if (g.fonts.values[level].ifEmpty { g.text.font } in env.assets.embeddedFontNames) 14f * env.scale else 0f
    val y = (ty0 + ty1) / 2 - (paint.ascent() + paint.descent()) / 2 - embeddedFontShift
    drawIntoCanvas { paint.draw(it.nativeCanvas, text, x, y) }
}
