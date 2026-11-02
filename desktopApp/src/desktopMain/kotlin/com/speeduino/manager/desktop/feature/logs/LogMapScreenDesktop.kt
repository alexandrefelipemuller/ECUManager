package com.speeduino.manager.desktop.feature.logs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.speeduino.manager.desktop.LocalStrings
import io.ecucore.geolog.GeoLogCsvParser
import io.ecucore.geolog.GeoLogTrack
import io.ecucore.geolog.HeatmapPalette
import io.ecucore.geolog.HeatmapScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

private const val MAX_DRAWN_POINTS = 1500
private const val PLAY_TICK_MS = 50L
private val PLAYBACK_SPEEDS = listOf(1f, 2f, 4f, 8f)
private val CARD_SIGNAL_TOKENS = listOf("rpm", "map", "tps", "o2", "afr", "coolant", "advance", "battery", "speed")
private val HEATMAP_DEFAULT_TOKENS = listOf("gps_speed", "speed", "rpm", "tps", "map")

/**
 * Log no mapa: trajeto GPS colorido por sensor (mapa de calor), cartão de sensores e reprodução.
 * Espelha o `LogMapScreen` do app Android, mas desenhado em Canvas (sem tiles de mapa).
 */
@Composable
internal fun LogMapScreenDesktop(logPath: String?) {
    val strings = LocalStrings.current
    var track by remember { mutableStateOf<GeoLogTrack?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(logPath) {
        track = null
        loadFailed = false
        if (logPath == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        runCatching { withContext(Dispatchers.IO) { GeoLogCsvParser.parse(File(logPath).readLines().filter { it.isNotBlank() }) } }
            .onSuccess { track = it }
            .onFailure { loadFailed = true }
    }

    Column(Modifier.fillMaxSize()) {
        Text(strings["log_map_title"], style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
        val loaded = track
        when {
            loadFailed -> Message(strings["log_map_load_error"])
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loaded.isEmpty -> Message(strings["log_map_no_gps"])
            else -> LogMapContent(loaded)
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LogMapContent(track: GeoLogTrack) {
    val strings = LocalStrings.current
    val signals = remember(track) { track.signalNames }
    var heatSignal by remember(track) { mutableStateOf(pickDefaultHeatSignal(signals)) }
    var invert by remember(track) { mutableStateOf(false) }
    var palette by remember(track) { mutableStateOf(HeatmapPalette.BLUE_TO_RED) }
    var timeSec by remember(track) { mutableFloatStateOf(track.startSec.toFloat()) }
    var playing by remember(track) { mutableStateOf(false) }
    var speed by remember(track) { mutableFloatStateOf(1f) }
    val scale = remember(track, heatSignal, invert, palette) {
        heatSignal?.let { HeatmapScale.auto(track, it, palette, invert) }
    }
    val cardSignals = remember(track) { pickCardSignals(signals) }

    LaunchedEffect(playing, speed, track) {
        while (playing) {
            delay(PLAY_TICK_MS)
            val next = timeSec + PLAY_TICK_MS / 1000f * speed
            if (next >= track.endSec) {
                timeSec = track.endSec.toFloat()
                playing = false
            } else {
                timeSec = next
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TrackCanvas(track, heatSignal, scale, timeSec.toDouble(), Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var menuOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { menuOpen = true }) { Text(heatSignal ?: strings["log_map_no_heatmap"], maxLines = 1) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(strings["log_map_no_heatmap"]) }, onClick = { heatSignal = null; menuOpen = false })
                        signals.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { heatSignal = name; menuOpen = false }) }
                    }
                }
                FilterChip(selected = invert, onClick = { invert = !invert }, label = { Text(strings["log_map_invert"]) })
                FilterChip(
                    selected = palette == HeatmapPalette.VIRIDIS,
                    onClick = { palette = if (palette == HeatmapPalette.BLUE_TO_RED) HeatmapPalette.VIRIDIS else HeatmapPalette.BLUE_TO_RED },
                    label = { Text(strings["log_map_palette_viridis"]) },
                )
            }
            scale?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatValue(s.min), style = MaterialTheme.typography.labelSmall)
                    val colors = (0..8).map { Color(s.colorAt(s.min + (s.max - s.min) * it / 8.0)) }
                    Box(Modifier.weight(1f).height(10.dp).background(Brush.horizontalGradient(colors), RoundedCornerShape(5.dp)))
                    Text(formatValue(s.max), style = MaterialTheme.typography.labelSmall)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    cardSignals.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { name ->
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(track.valueAt(name, timeSec.toDouble())?.let(::formatValue) ?: "—", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (!playing && timeSec >= track.endSec) timeSec = track.startSec.toFloat()
                    playing = !playing
                }) { Text(if (playing) strings["log_map_pause"] else strings["log_map_play"]) }
                Slider(
                    value = timeSec.coerceIn(track.startSec.toFloat(), track.endSec.toFloat()),
                    onValueChange = { timeSec = it },
                    valueRange = track.startSec.toFloat()..maxOf(track.endSec.toFloat(), track.startSec.toFloat() + 0.001f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${formatClock(timeSec - track.startSec.toFloat())}/${formatClock(track.durationSec.toFloat())}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(88.dp),
                )
                OutlinedButton(onClick = { speed = PLAYBACK_SPEEDS[(PLAYBACK_SPEEDS.indexOf(speed) + 1) % PLAYBACK_SPEEDS.size] }) {
                    Text("${speed.toInt()}x")
                }
            }
        }
    }
}

/** Trajeto em projeção equiretangular local (longitude corrigida por cos(latitude)), sem mapa de fundo. */
@Composable
private fun TrackCanvas(track: GeoLogTrack, heatSignal: String?, scale: HeatmapScale?, timeSec: Double, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val bounds = remember(track) { track.bounds() }
    val indices = remember(track) { track.decimatedIndices(MAX_DRAWN_POINTS) }
    Canvas(modifier.background(bg)) {
        val b = bounds ?: return@Canvas
        val midLat = (b.minLatitude + b.maxLatitude) / 2
        val kx = cos(Math.toRadians(midLat))
        val spanX = ((b.maxLongitude - b.minLongitude) * kx).coerceAtLeast(1e-6)
        val spanY = (b.maxLatitude - b.minLatitude).coerceAtLeast(1e-6)
        val pad = 24f
        val s = minOf((size.width - 2 * pad) / spanX.toFloat(), (size.height - 2 * pad) / spanY.toFloat())
        val offX = (size.width - spanX.toFloat() * s) / 2
        val offY = (size.height - spanY.toFloat() * s) / 2
        fun px(lon: Double) = offX + ((lon - b.minLongitude) * kx).toFloat() * s
        fun py(lat: Double) = size.height - offY - ((lat - b.minLatitude)).toFloat() * s
        for (k in 1 until indices.size) {
            val a = track.points[indices[k - 1]]
            val c = track.points[indices[k]]
            val color = if (heatSignal != null && scale != null) {
                track.valueAt(heatSignal, c.timeSec)?.let { Color(scale.colorAt(it)) } ?: base
            } else base
            drawLine(color, Offset(px(a.longitude), py(a.latitude)), Offset(px(c.longitude), py(c.latitude)), strokeWidth = 6f, cap = StrokeCap.Round)
        }
        track.positionAt(timeSec)?.let { pos ->
            val center = Offset(px(pos.longitude), py(pos.latitude))
            drawCircle(Color.White, 12f, center)
            drawCircle(Color.Black, 12f, center, style = Stroke(3f))
        }
    }
}

private fun pickDefaultHeatSignal(signals: List<String>): String? {
    for (token in HEATMAP_DEFAULT_TOKENS) signals.firstOrNull { it.contains(token, ignoreCase = true) }?.let { return it }
    return signals.firstOrNull()
}

private fun pickCardSignals(signals: List<String>): List<String> {
    val picked = CARD_SIGNAL_TOKENS.mapNotNull { token -> signals.firstOrNull { it.contains(token, ignoreCase = true) } }.distinct()
    return (picked + signals.filter { it !in picked }).take(6)
}

private fun formatValue(v: Double): String =
    if (abs(v) >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

private fun formatClock(seconds: Float): String {
    val s = seconds.coerceAtLeast(0f).toInt()
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}
