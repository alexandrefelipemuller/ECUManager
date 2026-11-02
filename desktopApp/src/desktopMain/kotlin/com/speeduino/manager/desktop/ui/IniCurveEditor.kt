package com.speeduino.manager.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Interpreta decimal com vírgula ou ponto; null se inválido. */
fun parseCurveNumber(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

private fun formatCurveNumber(v: Double, decimals: Int): String = String.format(Locale.getDefault(), "%.${decimals}f", v)

/**
 * Editor genérico de curva 1D (X/Y): gráfico simples + lista editável de pontos + botão de gravar.
 * Reutilizável para qualquer curva do .ini (WUE, marcha lenta...). [initialX]/[initialY] reiniciam o
 * estado de edição quando mudam (ex.: após ler da ECU). [validate] recebe os valores atuais e devolve
 * erros (vazio = ok); com erros, o botão de gravar fica desabilitado e os erros são exibidos.
 */
@Composable
fun IniCurveEditor(
    xLabel: String,
    yLabel: String,
    initialX: List<Double>,
    initialY: List<Double>,
    saveLabel: String,
    modifier: Modifier = Modifier,
    xDecimals: Int = 1,
    yDecimals: Int = 0,
    enabled: Boolean = true,
    validate: (xs: List<Double>, ys: List<Double>) -> List<String> = { _, _ -> emptyList() },
    invalidNumberMessage: String = "Invalid number",
    onSave: (xs: List<Double>, ys: List<Double>) -> Unit,
) {
    var xText by remember(initialX) { mutableStateOf(initialX.map { formatCurveNumber(it, xDecimals) }) }
    var yText by remember(initialY) { mutableStateOf(initialY.map { formatCurveNumber(it, yDecimals) }) }
    val xs = xText.map(::parseCurveNumber)
    val ys = yText.map(::parseCurveNumber)
    val allParsed = xs.none { it == null } && ys.none { it == null }
    val errors = if (!allParsed) listOf(invalidNumberMessage)
    else validate(xs.map { it!! }, ys.map { it!! })
    val changed = xText != initialX.map { formatCurveNumber(it, xDecimals) } ||
        yText != initialY.map { formatCurveNumber(it, yDecimals) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CurveChart(
            xs = xs.map { it ?: Double.NaN },
            ys = ys.map { it ?: Double.NaN },
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(xLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(yLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        }
        xText.indices.forEach { i ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = xText[i],
                    onValueChange = { v -> xText = xText.toMutableList().also { it[i] = v } },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    singleLine = true,
                    isError = xs[i] == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = yText[i],
                    onValueChange = { v -> yText = yText.toMutableList().also { it[i] = v } },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    singleLine = true,
                    isError = ys[i] == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        }
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = { onSave(xs.map { it!! }, ys.map { it!! }) },
            enabled = enabled && changed && errors.isEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) { Text(saveLabel) }
    }
}

@Composable
private fun CurveChart(xs: List<Double>, ys: List<Double>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val pts = xs.zip(ys).filter { !it.first.isNaN() && !it.second.isNaN() }
        drawRect(grid, style = Stroke(1f))
        if (pts.size < 2) return@Canvas
        val minX = pts.minOf { it.first }
        val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }
        val maxY = pts.maxOf { it.second }
        val dx = (maxX - minX).takeIf { it > 0 } ?: 1.0
        val dy = (maxY - minY).takeIf { it > 0 } ?: 1.0
        val pad = 12f
        fun px(x: Double) = pad + ((x - minX) / dx).toFloat() * (size.width - 2 * pad)
        fun py(y: Double) = size.height - pad - ((y - minY) / dy).toFloat() * (size.height - 2 * pad)
        val path = Path()
        pts.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y))
        }
        drawPath(path, line, style = Stroke(3f))
        pts.forEach { (x, y) -> drawCircle(line, 5f, Offset(px(x), py(y))) }
    }
}
