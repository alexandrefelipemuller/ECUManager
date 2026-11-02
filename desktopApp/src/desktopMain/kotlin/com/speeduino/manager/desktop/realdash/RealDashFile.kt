// Parser de dashboards RealDash (.rd) — engenharia reversa (ver FORMAT.md).
// Validado contra 27 arquivos de RealDash 1.8.4 a 2.4.1 (magic 0x00034006). 1.5.x ainda não é suportado.
// Kotlin puro (JVM), sem dependência de Android.
//
// Convenções do formato:
//  - tudo little-endian
//  - string = int32 N (nº de caracteres) + N*2 bytes UTF-16LE
//  - cor = uint32 ARGB (mesmo layout do Int de cor do Android: 0xAARRGGBB)
//  - retângulos = float (left, top, right, bottom) normalizados 0..1 sobre width/height do dash
//  - ângulos de mostrador em radianos, 0 = 12 horas, sentido horário
//
// Campos com prefixo "u" ainda não têm significado confirmado; são lidos e guardados para não perder dados.
package com.speeduino.manager.desktop.realdash

import java.nio.ByteBuffer
import java.nio.ByteOrder

class RdReader(private val buf: ByteBuffer) {
    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))

    val position get() = buf.position()
    val remaining get() = buf.remaining()

    fun i32(): Int = buf.int
    fun u16(): Int = buf.short.toInt() and 0xFFFF
    fun i64(): Long = buf.long
    fun f32(): Float = buf.float
    fun color(): Int = buf.int
    fun bytes(n: Int): ByteArray = ByteArray(n).also { buf.get(it) }
    fun peekU16(at: Int): Int = buf.getShort(position + at).toInt() and 0xFFFF
    fun str(): String {
        val n = i32()
        require(n in 0..100_000) { "string inválida ($n) em 0x${(position - 4).toString(16)}" }
        return String(bytes(n * 2), Charsets.UTF_16LE)
    }

    fun i32s(n: Int) = IntArray(n) { i32() }
    fun f32s(n: Int) = FloatArray(n) { f32() }
    fun colors(n: Int) = IntArray(n) { color() }
    fun strs(n: Int) = List(n) { str() }
}

// ---------------------------------------------------------------- blocos básicos

data class RdRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun toPx(w: Int, h: Int) = floatArrayOf(left * w, top * h, right * w, bottom * h)

    companion object {
        fun read(r: RdReader) = RdRect(r.f32(), r.f32(), r.f32(), r.f32())
    }
}

/**
 * Cor por nível. Slots 0..2 = [normal, alerta, perigo]; 3..5 repetem 0..2 em todas as amostras.
 * [dynamic] = 1 → a cor interpola com o valor (gradiente) em vez de trocar por nível.
 */
data class StateColors(val ver: Int, val dynamic: Int, val values: IntArray) {
    operator fun get(level: Int) = values[level]
    companion object { fun read(r: RdReader) = StateColors(r.i32(), r.i32(), r.colors(6)) }
}

data class StateImages(val ver: Int, val values: List<String>, val uF1: Float, val uF2: Float, val uI: Int) {
    operator fun get(level: Int) = values[level]
    companion object { fun read(r: RdReader) = StateImages(r.i32(), r.strs(3), r.f32(), r.f32(), r.i32()) }
}

data class StateFonts(val ver: Int, val flag: Int, val u: Int, val values: List<String>) {
    companion object { fun read(r: RdReader) = StateFonts(r.i32(), r.i32(), r.i32(), r.strs(3)) }
}

enum class GaugeType(val id: Int) {
    NEEDLE(1), TEXT(2), BAR(3), IMAGE(5), AXIS(7), ARC(8), BUTTON(11), GRAPH(12), MAP(17), SLIDER(20),
    CONTAINER(22), UNKNOWN(-1);

    companion object { fun of(id: Int) = entries.firstOrNull { it.id == id } ?: UNKNOWN }
}

// ---------------------------------------------------------------- elemento base

/** Núcleo comum a todo elemento (gauges e elementos aninhados). */
data class UiCore(
    val name: String,
    val flags: Int,
    val uA: Int,
    val image: String,              // imagem principal (face do mostrador, fundo, sprite) — "" = nenhuma
    val geomVer: Int,               // 16 (RealDash 1.x) ou 17
    val rect: RdRect,               // posição no dash, normalizada
    val rect2: RdRect,              // desconhecido (em imagens parece ser UV)
    val uB: IntArray,
    val uScale: FloatArray,
    val uC: FloatArray,
    val uD: FloatArray,             // botões: [0.25, 1, 0.25] (raio do canto?)
    val subframeIndex: Int,         // frame (1-based) da grade de sub-imagens
    val tint: Int,                  // snapshot de tintColors[nível]
    val bgColor: Int,               // snapshot de bgColors[nível]
    val uE: Int,
    val uColors: IntArray,
    val uColor77: Int,
    val uF: Int,
) {
    companion object {
        fun read(r: RdReader): UiCore {
            val name = r.str(); val flags = r.i32(); val uA = r.i32(); val image = r.str(); val geomVer = r.i32()
            return UiCore(
                name, flags, uA, image, geomVer, RdRect.read(r), RdRect.read(r),
                r.i32s(2), r.f32s(2), r.f32s(2), r.f32s(if (geomVer >= 17) 3 else 2), r.i32(),
                r.color(), r.color(), r.i32(), r.colors(4), r.color(), r.i32(),
            )
        }
    }
}

/** Componente de texto ("$#V2#$"). */
data class TextComp(
    val marker: String,
    val font: String,               // nome do recurso de fonte (ou fonte embutida do app, ex. "defaultdashfont")
    val text: String,               // texto estático (ou preview do último valor, em gauges com input)
    val textVer: Int,
    val textColor: Int,
    val shadowColor: Int,
    val textFlags: Int,             // bitfield; byte1 bit0 parece = centralizar na horizontal
    val textFlags2: Int,
    val fontVer: Int,               // 19 ou 20
    val textRect: RdRect,           // caixa de texto relativa ao rect do gauge (0..1)
    val fontSize: Float,            // em px do dash; o render ajusta à altura da caixa
    val uG: Int,
    val uH: Float,
    val textRectA: RdRect,
    val textRectB: RdRect,
    val textRectC: RdRect,
    val uI: Int?,                   // só em fontVer >= 20
) {
    companion object {
        fun read(r: RdReader): TextComp {
            val marker = r.str(); val font = r.str(); val text = r.str(); val textVer = r.i32()
            val tc = r.color(); val sc = r.color(); val f1 = r.i32(); val f2 = r.i32(); val fontVer = r.i32()
            return TextComp(
                marker, font, text, textVer, tc, sc, f1, f2, fontVer,
                RdRect.read(r), r.f32(), r.i32(), r.f32(), RdRect.read(r), RdRect.read(r), RdRect.read(r),
                if (fontVer >= 20) r.i32() else null,
            )
        }
    }
}

// ---------------------------------------------------------------- elementos aninhados

sealed interface NestedElement { val core: UiCore }

data class NestedText(override val core: UiCore, val text: TextComp, val uTail: IntArray, val pressScale: FloatArray, val uTail2: IntArray) : NestedElement

data class NestedImage(
    override val core: UiCore, val imgVer: Int, val uImg: Int, val image: String,
    val imgRectVer: Int, val imgRect: RdRect, val uImgF: FloatArray, val uImgI: IntArray,
) : NestedElement

/** Grupo de um container: gauges filhos completos (coordenadas absolutas do dash). */
data class NestedGroup(
    override val core: UiCore, val contentRect: RdRect, val uFlags: Int, val uGrp: List<Number>, val children: List<Gauge>,
) : NestedElement

fun readNested(r: RdReader): NestedElement {
    val sub = r.u16(); val magic = r.u16()
    require(magic == 0xACAC) { "elemento aninhado com magic 0x${magic.toString(16)}" }
    val core = UiCore.read(r)
    return when (sub) {
        3 -> NestedText(core, TextComp.read(r), r.i32s(2), r.f32s(2), r.i32s(2))
        5 -> NestedImage(core, r.i32(), r.i32(), r.str(), r.i32(), RdRect.read(r), r.f32s(4), r.i32s(2))
        9 -> NestedGroup(core, RdRect.read(r), r.i32(), listOf(r.i32(), r.f32(), r.i32(), r.i32()),
            List(r.i32()) { readGauge(r) })
        else -> error("subtipo aninhado desconhecido $sub em 0x${r.position.toString(16)}")
    }
}

// ---------------------------------------------------------------- extensões por tipo

/** Bloco de 17 valores da escala (rótulos numéricos ao longo do mostrador/barra). */
class AutoScale(val raw: IntArray) {
    val ver get() = raw[0]
    val sizeScale get() = Float.fromBits(raw[1])
    val segments get() = raw[12]        // nº de rótulos, incluindo o 0
    val maxDigits get() = raw[14]       // trunca cada rótulo aos N primeiros caracteres; 0 = esconde
    val useAuto get() = raw[15] != 0

    companion object { fun read(r: RdReader) = AutoScale(r.i32s(17)) }
}

data class Layers(
    val colorsA: StateColors, val colorsB: StateColors, val colorsC: StateColors,
    val imagesA: StateImages, val colorsD: StateColors, val imagesB: StateImages,
) {
    companion object {
        fun read(r: RdReader) = Layers(StateColors.read(r), StateColors.read(r), StateColors.read(r),
            StateImages.read(r), StateColors.read(r), StateImages.read(r))
    }
}

sealed interface GaugeExt

data class BarExt(val uA: Int, val direction: Int, val scale: AutoScale, val layers: Layers, val uEnd: Int) : GaugeExt {
    val bottomToTop get() = direction == 2      // 0 = esquerda→direita
}

data class GraphExt(val u: IntArray) : GaugeExt

data class ContainerExt(val u: IntArray, val groups: List<NestedElement>) : GaugeExt {
    val children get() = groups.filterIsInstance<NestedGroup>().flatMap { it.children }
}

data class ButtonExt(
    val actions: List<String>,      // nomes das ações (RdFile.actions) disparadas no toque
    val uA: IntArray,
    val colors: List<StateColors>,
    val imagesA: StateImages, val colorsF: StateColors, val imagesB: StateImages,
    val fonts: StateFonts,
    val children: List<NestedElement>,   // rótulo do botão; tail tem escala ao pressionar (0.95)
) : GaugeExt

/** Cabeça comum da cauda 5 (ponteiro, arco, mapa, slider, axis). */
data class WidgetHead(
    val colorsA: StateColors,
    val sprite: StateImages,        // ponteiro: needle.png; arco: anel de indicação; slider: knob
    val colorsB: StateColors,
    val colorsC: StateColors,       // arco: cor do preenchimento por nível
    val uF: FloatArray,             // [0] ponteiro: tamanho do sprite relativo ao lado do rect (0.8)
)

data class DialExt(
    val head: WidgetHead, val uI: IntArray,
    val startAngle: Float,          // radianos, 0 = 12h, horário
    val sweepAngle: Float,          // radianos
    val uF: FloatArray, val scale: AutoScale, val layers: Layers, val uEnd: IntArray,
) : GaugeExt

data class MapExt(val head: WidgetHead, val uI: IntArray, val uA: Int, val uVer: Int, val uAngle: Float, val uZoom: Float,
                  val palette: List<StateColors>, val uEnd: Int) : GaugeExt

data class SliderExt(val head: WidgetHead, val uI: IntArray, val uA: IntArray, val uMax: Int,
                     val children: List<NestedElement>, val uEnd: IntArray) : GaugeExt

/** Hipótese (1 amostra): um gauge filho de rótulo. */
data class AxisExt(val head: WidgetHead, val uA: Int, val label: Gauge, val uEnd: IntArray) : GaugeExt

// ---------------------------------------------------------------- gauge

data class Gauge(
    val offset: Int,
    val typeId: Int,
    val core: UiCore,
    val text: TextComp,
    val subframeGrid: IntArray,     // colunas, linhas da folha de sub-imagens (padrão 9x6)
    val uJ: String,                 // string ("" nos arquivos antigos; 2.5+: ex. "[POSTFIX= °C]")
    val gaugeMath: String,          // ex. "=V/1000"; alerta/perigo comparam o valor DEPOIS da fórmula
    val stateTexts: List<String>,   // texto por nível [normal, alerta, perigo]
    val uK: Int?,
    val rangeVer: Int,              // 14 (1.x) ou 16
    val dangerRange: FloatArray,    // (low, high): perigo se valor < low ou > high
    val warningRange: FloatArray,   // (low, high): alerta se valor < low ou > high
    val valueRange: FloatArray,     // (min, max) do input
    val currentValue: Float,        // valor no momento do save
    val uM: FloatArray,
    val blink: FloatArray,          // velocidade de piscar por nível; 0 = não pisca
    val uO: Float, val uP: Int, val uQ: FloatArray,
    val uVer: Int,                  // 12 ou 14
    val gaugeFlags: Int,            // bit 0x20000000 = texto estático
    val uR: IntArray, val uS: IntArray, val uT: IntArray,
    val uU: IntArray,               // uU[1]: 0 = valor atual, 1 = mínimo, 2 = máximo (pico)
    val inputVer: Int,
    val targetId: Int,              // target ID do RealDash (-1 = sem input; inputs CAN customizados = hash)
    val decimals: Int,
    val uV: Int,
    val uMagic: Int,                // 0x04030201 (antigos: 0xFEFDFCxx)
    val bgColors: StateColors,      // grupo 0: fundo (face do botão, "pílula" de texto)
    val tintColors: StateColors,    // grupo 1: tint da imagem
    val textColors: StateColors,    // grupo 2: texto (e rótulos da escala dos mostradores)
    val shadowColors: StateColors,
    val fonts: StateFonts,
    val uColorsE: StateColors,
    val images: StateImages,        // imagem por nível
    val uColorsF: StateColors,
    val images2: StateImages,
    val uTrailing: IntArray?,       // 11 ints, só em rangeVer >= 16
    val tailKind: Int,
    val ext: GaugeExt? = null,
    val embeddedSlots: AssetSlots? = null,
) {
    val type get() = GaugeType.of(typeId)
    val name get() = core.name
    val rect get() = core.rect
    val valueMode get() = uU[1]
    val isStaticText get() = gaugeFlags and 0x20000000 != 0

    /** 0 = normal, 1 = alerta, 2 = perigo. */
    fun levelFor(value: Float): Int {
        fun out(r: FloatArray) = r[0] != r[1] && (value < r[0] || value > r[1])
        return when {
            out(dangerRange) -> 2
            out(warningRange) -> 1
            else -> 0
        }
    }

    fun fraction(value: Float): Float {
        val (lo, hi) = valueRange[0] to valueRange[1]
        return if (hi == lo) 0f else ((value - lo) / (hi - lo)).coerceIn(0f, 1f)
    }

    fun imageFor(level: Int) = images[level].ifEmpty { core.image }
}

fun readGauge(r: RdReader): Gauge {
    val off = r.position
    val typeId = r.u16(); val magic = r.u16()
    require(magic == 0x0CBD) { "gauge com magic 0x${magic.toString(16)} em 0x${off.toString(16)}" }
    val core = UiCore.read(r)
    val text = TextComp.read(r)
    val grid = r.i32s(2); val uJ = r.str(); val math = r.str(); val states = r.strs(3)
    val uK = if (core.geomVer >= 17) r.i32() else null
    val rangeVer = r.i32()
    val ranges = r.f32s(6)
    val current = r.f32(); val uM = r.f32s(2); val blink = r.f32s(3); val uO = r.f32(); val uP = r.i32()
    val uQ = if (rangeVer >= 16) r.f32s(2) else FloatArray(0)
    val uVer = r.i32(); val flags = r.i32()
    val uR = r.i32s(4); val uS = r.i32s(2); val uT = r.i32s(3); val uU = r.i32s(if (uVer >= 15) 5 else if (uVer >= 14) 4 else 2)
    val g = Gauge(
        off, typeId, core, text, grid, uJ, math, states, uK, rangeVer,
        ranges.copyOfRange(0, 2), ranges.copyOfRange(2, 4), ranges.copyOfRange(4, 6),
        current, uM, blink, uO, uP, uQ, uVer, flags, uR, uS, uT, uU,
        r.i32(), r.i32(), r.i32(), r.i32(), r.i32(),
        StateColors.read(r), StateColors.read(r), StateColors.read(r), StateColors.read(r),
        StateFonts.read(r), StateColors.read(r), StateImages.read(r), StateColors.read(r), StateImages.read(r),
        if (rangeVer >= 16) r.i32s(11) else null,
        r.i32(),
    )
    return g.copy(ext = readTail(r, g))
}

private fun readTail(r: RdReader, g: Gauge): GaugeExt? {
    val where = "0x${g.offset.toString(16)}"
    return when (g.tailKind) {
        0, 1 -> null
        2 -> when (g.type) {
            GaugeType.BAR -> BarExt(r.i32(), r.i32(), AutoScale.read(r), Layers.read(r), r.i32())
            GaugeType.CONTAINER -> ContainerExt(r.i32s(4), List(r.i32()) { readNested(r) })
            else -> error("cauda 2 em tipo ${g.typeId} ($where)")
        }
        3 -> if (g.type == GaugeType.GRAPH) GraphExt(r.i32s(5)) else error("cauda 3 em tipo ${g.typeId} ($where)")
        6 -> ButtonExt(
            r.strs(r.i32()), r.i32s(5), List(5) { StateColors.read(r) },
            StateImages.read(r), StateColors.read(r), StateImages.read(r), StateFonts.read(r),
            List(r.i32()) { readNested(r) },
        )
        5 -> {
            val head = WidgetHead(StateColors.read(r), StateImages.read(r), StateColors.read(r), StateColors.read(r), r.f32s(4))
            if (g.type == GaugeType.AXIS) return AxisExt(head, r.i32(), readGauge(r), r.i32s(3))
            val uI = r.i32s(2)
            when (g.type) {
                GaugeType.NEEDLE, GaugeType.ARC -> DialExt(
                    head, uI, r.f32(), r.f32(), r.f32s(4), AutoScale.read(r), Layers.read(r),
                    r.i32s(if (g.type == GaugeType.ARC) 2 else 1),
                )
                GaugeType.MAP -> MapExt(head, uI, r.i32(), r.i32(), r.f32(), r.f32(), List(8) { StateColors.read(r) }, r.i32())
                GaugeType.SLIDER -> {
                    val uA = r.i32s(2); val max = r.i32()
                    SliderExt(head, uI, uA, max, List(r.i32()) { readNested(r) }, r.i32s(2))
                }
                else -> error("cauda 5 em tipo ${g.typeId} ($where)")
            }
        }
        else -> error("tailKind ${g.tailKind} desconhecido em $where")
    }
}

// ---------------------------------------------------------------- ações, triggers, recursos, páginas

data class AnimKey(val value: FloatArray, val speed: Float, val delay: Float, val easing: Int, val u: IntArray) {
    val duration get() = if (speed > 0) 1f / speed else 0f
}

data class RdAction(
    val ver: Int,
    val actionType: Int,            // 1 datalogger, 7 ir p/ página?, 8 tocar som, 13 animação morph/position,
                                    // 20/21/22 música, 25 setar valor, 28 alternar valor, 35 animação fade, ...
    val uA: Int,
    val name: String,
    val param: String,              // nome do som / input alvo / gauge alvo da animação
    val values: FloatArray,
    val uB: IntArray,
    val targetId: Int,
    val anim: List<AnimKey>,        // 4 keyframes em ações de animação; vazio nas demais
    val uExtra: Int? = null,        // RealDash 2.5+: int extra antes de `values` nas ações tipo 28
)

data class RdCondition(
    val uA: Int, val op: Int, val targetId: Int, val uMul: Float, val uB: Int,
    val value: Float, val targetId2: Int, val uMul2: Float, val uC: Int, val uD: Int,
) {
    companion object {
        fun read(r: RdReader) = RdCondition(r.i32(), r.i32(), r.i32(), r.f32(), r.i32(), r.f32(), r.i32(), r.f32(), r.i32(), r.i32())
    }
}

data class RdTrigger(val ver: Int, val name: String, val uA: Int, val on: List<RdCondition>, val off: List<RdCondition>, val actions: List<String>)

enum class ResourceType { IMAGE, SOUND, FONT, UNKNOWN }

class RdResource(val typeId: Int, val name: String, val data: ByteArray, val fontParams: List<Number>?) {
    val type get() = when (typeId) { 0 -> ResourceType.IMAGE; 2 -> ResourceType.SOUND; 5 -> ResourceType.FONT; else -> ResourceType.UNKNOWN }
}

data class AssetSlots(val u: Int, val slots: Map<Int, String>) {
    companion object {
        fun read(r: RdReader): AssetSlots {
            val u = r.i32()
            return AssetSlots(u, (0 until r.i32()).associate { r.i32() to r.str() })
        }
    }
}

data class RdPage(
    val ver: Int,                   // 50001 (antigo) ou 50003
    val uA: IntArray,
    val subVer: Int?,
    val bgColor: Int,               // ARGB; 0 = transparente
    val uColor: Int,
    val uB: IntArray,
    val gauges: List<Gauge>,        // ordem = ordem de desenho (z-order)
)

class RdFile(
    val magic: Int,
    val uVer: IntArray,
    val name: String,
    val description: String,
    val width: Int,
    val height: Int,
    val aspect: Float,
    val appVersion: String,
    val uHeader: IntArray,
    val actions: List<RdAction>,
    val triggers: List<RdTrigger>,
    val resources: List<RdResource>,
    val pages: List<RdPage>,
    val defaultSlots: AssetSlots?,
    val sets: List<String>,
    val trailing: ByteArray,
) {
    val resourcesByName by lazy { resources.associateBy { it.name } }

    companion object {
        /**
         * RealDash 2.5+ acrescenta um int antes dos valores das ações tipo 28. Tenta primeiro o layout indicado
         * pela versão do app; se falhar, tenta o outro (cobre versões de fronteira desconhecidas).
         */
        fun parse(bytes: ByteArray): RdFile {
            val preferred = runCatching { parse(bytes, extra28 = null) }
            return preferred.getOrElse { first ->
                val guess = runCatching { parseVersionGuess(bytes) }.getOrDefault(false)
                runCatching { parse(bytes, extra28 = !guess) }.getOrElse { throw first }
            }
        }

        private fun parseVersionGuess(bytes: ByteArray): Boolean {
            val r = RdReader(bytes)
            r.i32(); r.i32s(2); r.str(); r.str(); r.i32(); r.i32(); r.f32()
            return appHasExtra28(r.str())
        }

        private fun appHasExtra28(appVersion: String): Boolean {
            val p = appVersion.split('.').mapNotNull { it.toIntOrNull() }
            val major = p.getOrElse(0) { 0 }; val minor = p.getOrElse(1) { 0 }
            return major > 2 || (major == 2 && minor >= 5)
        }

        private fun parse(bytes: ByteArray, extra28: Boolean?): RdFile {
            val r = RdReader(bytes)
            val magic = r.i32(); val uVer = r.i32s(2)
            val name = r.str(); val description = r.str()
            val width = r.i32(); val height = r.i32(); val aspect = r.f32()
            val appVersion = r.str(); val uHeader = r.i32s(7)

            val hasExtra28 = extra28 ?: appHasExtra28(appVersion)
            val actions = List(r.i32()) {
                val ver = r.i32(); val type = r.i32(); val uA = r.i32(); val n = r.str(); val p = r.str()
                val uExtra = if (type == 28 && hasExtra28) r.i32() else null
                val values = r.f32s(r.i32()); val uB = r.i32s(2); val target = r.i32()
                val anim = List(r.i32()) { AnimKey(r.f32s(4), r.f32(), r.f32(), r.i32(), r.i32s(2)) }
                RdAction(ver, type, uA, n, p, values, uB, target, anim, uExtra)
            }
            val triggers = List(r.i32()) {
                RdTrigger(r.i32(), r.str(), r.i32(), List(r.i32()) { RdCondition.read(r) },
                    List(r.i32()) { RdCondition.read(r) }, r.strs(r.i32()))
            }
            val resources = List(r.i32()) {
                val type = r.i32(); val n = r.str(); val size = r.i64().toInt()
                val fp = if (type == 5) listOf(r.i32(), r.i32(), r.i32(), r.f32(), r.i32()) else null
                RdResource(type, n, r.bytes(size), fp)
            }
            var slotsSeen = false
            val pages = List(r.i32()) {
                val ver = r.i32(); val uA = r.i32s(2)
                val subVer = if (ver >= 50003) r.i32() else null
                val bg = r.color(); val uColor = r.color()
                val uB = r.i32s(if (subVer != null) subVer - 2 else 3)
                val gauges = ArrayList<Gauge>()
                repeat(r.i32()) {
                    if (gauges.isNotEmpty() && r.peekU16(2) != 0x0CBD) {
                        // arquivos editados fora do RealDash podem trazer o mapa de assets logo após um registro
                        gauges[gauges.lastIndex] = gauges.last().copy(embeddedSlots = AssetSlots.read(r))
                        slotsSeen = true
                    }
                    gauges += readGauge(r)
                }
                RdPage(ver, uA, subVer, bg, uColor, uB, gauges)
            }
            val slots = if (slotsSeen) null else AssetSlots.read(r)
            val sets = r.strs(r.i32())
            return RdFile(magic, uVer, name, description, width, height, aspect, appVersion, uHeader,
                actions, triggers, resources, pages, slots, sets, r.bytes(r.remaining))
        }
    }
}
