package com.speeduino.manager.desktop.feature.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.speeduino.manager.desktop.LocalStrings
import io.ecucore.model.ConfigValidator
import io.ecucore.model.TpsCalibration

/** Calibração de TPS da MS2/Extra com captura do ADC ao vivo (espelha o diálogo do app Android). */
@Composable
internal fun Ms2TpsCalibrationDialog(
    liveAdc: Int?,
    initialClosed: Int,
    initialOpen: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    val strings = LocalStrings.current
    var closedText by remember { mutableStateOf(initialClosed.toString()) }
    var openText by remember { mutableStateOf(initialOpen.toString()) }
    val closed = closedText.toIntOrNull()
    val open = openText.toIntOrNull()
    val valid = closed != null && open != null &&
        ConfigValidator.validateTpsCalibration(TpsCalibration(closed, open, TpsCalibration.ADC_MAX_10BIT)).isValid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings["sensor_tps_ms2_title"]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings["sensor_tps_ms2_instructions"])
                Text(
                    text = liveAdc?.let { strings.format("sensor_tps_ms2_live_adc", it) } ?: "-",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(
                    onClick = { liveAdc?.let { closedText = it.toString() } },
                    enabled = liveAdc != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings["sensor_tps_ms2_capture_closed"]) }
                OutlinedTextField(
                    value = closedText,
                    onValueChange = { closedText = it.filter(Char::isDigit).take(4) },
                    label = { Text(strings["sensor_tps_ms2_closed_label"]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { liveAdc?.let { openText = it.toString() } },
                    enabled = liveAdc != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings["sensor_tps_ms2_capture_wot"]) }
                OutlinedTextField(
                    value = openText,
                    onValueChange = { openText = it.filter(Char::isDigit).take(4) },
                    label = { Text(strings["sensor_tps_ms2_wot_label"]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!valid) {
                    Text(
                        text = strings["sensor_tps_ms2_invalid"],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (closed != null && open != null) onSave(closed, open) }, enabled = valid) {
                Text(strings["sensor_tps_ms2_save"])
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings["btn_cancel"]) } },
    )
}
