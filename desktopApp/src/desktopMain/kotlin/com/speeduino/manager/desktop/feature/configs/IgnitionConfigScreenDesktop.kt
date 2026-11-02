package com.speeduino.manager.desktop.feature.configs

import com.speeduino.manager.desktop.ui.isOutOfRange
import com.speeduino.manager.desktop.ui.invalidNumbers
import com.speeduino.manager.desktop.ui.SaveDisabledReason
import androidx.compose.runtime.mutableIntStateOf
import com.speeduino.manager.desktop.ReportUnsavedChanges
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.LocalStrings
import com.speeduino.manager.desktop.ui.DropdownField
import com.speeduino.manager.desktop.ui.NumberField
import com.speeduino.manager.desktop.ui.ToggleField
import io.ecucore.model.Algorithm
import io.ecucore.model.TriggerSettings

private val sparkModeLabels = listOf("Wasted Spark", "Distributor", "Wasted COP", "Sequential", "Rotary")

@Composable
internal fun IgnitionConfigScreenDesktop(controller: DesktopSpeeduinoController) {
    val strings = LocalStrings.current
    val constants by controller.engineConstants.collectAsState()
    val trigger by controller.triggerSettings.collectAsState()

    var restoreKey by remember { mutableIntStateOf(0) }
    var ignitionAlgorithm by remember(constants, restoreKey) { mutableStateOf(constants?.ignitionAlgorithm ?: Algorithm.SPEED_DENSITY) }
    var fixedTimingEnabled by remember(constants, restoreKey) { mutableStateOf(constants?.ignitionFixedTimingEnabled ?: false) }
    var perToothEnabled by remember(constants, restoreKey) { mutableStateOf(constants?.ignitionPerToothEnabled ?: false) }
    var sparkMode by remember(trigger) { mutableStateOf(trigger?.sparkMode ?: 0) }
    var crankingAdvance by remember(trigger) { mutableStateOf(trigger?.crankingAdvanceDeg?.toString() ?: "10") }
    var fixedTimingAngle by remember(trigger) { mutableStateOf(trigger?.fixedTimingAngleDeg?.toString() ?: "10") }
    var coilSignalMode by remember(trigger) { mutableStateOf(trigger?.coilSignalMode ?: TriggerSettings.CoilSignalMode.GOING_LOW) }
    var dwellCorrectionEnabled by remember(trigger) { mutableStateOf(trigger?.dwellErrorCorrectionEnabled ?: false) }
    var triggerAngle by remember(trigger) { mutableStateOf(trigger?.triggerAngleDeg?.toString() ?: "0") }
    var triggerMultiplier by remember(trigger) { mutableStateOf(trigger?.triggerAngleMultiplier?.toString() ?: "1") }
    var baseTeeth by remember(trigger) { mutableStateOf(trigger?.primaryBaseTeeth?.toString() ?: "36") }
    var missingTeeth by remember(trigger) { mutableStateOf(trigger?.missingTeeth?.toString() ?: "1") }
    var triggerEdge by remember(trigger) { mutableStateOf(trigger?.triggerEdge ?: TriggerSettings.SignalEdge.RISING) }
    var primarySpeed by remember(trigger) { mutableStateOf(trigger?.primaryTriggerSpeed ?: TriggerSettings.TriggerSpeed.CRANK) }
    var filter by remember(trigger) { mutableStateOf(trigger?.triggerFilter ?: TriggerSettings.TriggerFilter.OFF) }
    var hasChanges by remember { mutableStateOf(false) }
    val hasInvalidFields = invalidNumbers(crankingAdvance, fixedTimingAngle, triggerAngle, triggerMultiplier, baseTeeth, missingTeeth)
    ReportUnsavedChanges(hasChanges)

    LaunchedEffect(constants, trigger) { hasChanges = false }

    LaunchedEffect(Unit) {
        if (constants == null) {
            controller.loadEngineConstants()
        }
        if (trigger == null) {
            controller.loadTriggerSettings()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings["label.ignitionConfigTitle"], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(strings["label.ignitionConfigSubtitle"], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { controller.loadEngineConstants(); controller.loadTriggerSettings() }, enabled = constants == null || trigger == null || hasChanges) { Text(strings["action.loadEcu"]) }
                    FilledTonalButton(
                        enabled = hasChanges && constants != null && trigger != null && !hasInvalidFields,
                        onClick = {
                            constants?.let { current ->
                                controller.saveEngineConstants(current.copy(
                                    ignitionAlgorithm = ignitionAlgorithm,
                                    ignitionFixedTimingEnabled = fixedTimingEnabled,
                                    ignitionPerToothEnabled = perToothEnabled,
                                ))
                            }
                            trigger?.let { current ->
                                controller.saveTriggerSettings(current.copy(
                                    sparkMode = sparkMode,
                                    crankingAdvanceDeg = crankingAdvance.toIntOrNull() ?: current.crankingAdvanceDeg,
                                    fixedTimingAngleDeg = fixedTimingAngle.toIntOrNull() ?: current.fixedTimingAngleDeg,
                                    coilSignalMode = coilSignalMode,
                                    dwellErrorCorrectionEnabled = dwellCorrectionEnabled,
                                    triggerAngleDeg = triggerAngle.toIntOrNull() ?: current.triggerAngleDeg,
                                    triggerAngleMultiplier = triggerMultiplier.toIntOrNull() ?: current.triggerAngleMultiplier,
                                    primaryBaseTeeth = baseTeeth.toIntOrNull() ?: current.primaryBaseTeeth,
                                    missingTeeth = missingTeeth.toIntOrNull() ?: current.missingTeeth,
                                    triggerEdge = triggerEdge,
                                    primaryTriggerSpeed = primarySpeed,
                                    triggerFilter = filter,
                                ))
                            }
                            hasChanges = false
                        }
                    ) { Text(strings["action.saveEcu"]) }
                    FilledTonalButton(onClick = { restoreKey++; hasChanges = false }, enabled = hasChanges) { Text(strings["btn_restore_ecu_values"]) }
                }
                SaveDisabledReason(hasChanges = hasChanges, dataAvailable = constants != null, hasInvalidFields = hasInvalidFields)
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings["label.ignitionStrategy"], style = MaterialTheme.typography.titleMedium)
                DropdownField(strings["label.ignitionLoadReference"], ignitionAlgorithm.displayName, Algorithm.values().map { it.displayName }) { value ->
                    ignitionAlgorithm = Algorithm.values().first { it.displayName == value }
                    hasChanges = true
                }
                DropdownField(strings["label.ignitionType"], sparkModeLabels.getOrElse(sparkMode) { "Mode $sparkMode" }, sparkModeLabels) { value ->
                    sparkMode = sparkModeLabels.indexOf(value).coerceAtLeast(0)
                    hasChanges = true
                }
                DropdownField(strings["label.coilSignal"], coilSignalMode.name, TriggerSettings.CoilSignalMode.values().map { it.name }) { value ->
                    coilSignalMode = TriggerSettings.CoilSignalMode.valueOf(value)
                    hasChanges = true
                }
                ToggleField(strings["label.fixedTimingEnabled"], fixedTimingEnabled) { fixedTimingEnabled = it; hasChanges = true }
                ToggleField(strings["label.perToothPrecisionEnabled"], perToothEnabled) { perToothEnabled = it; hasChanges = true }
                ToggleField(strings["label.dwellCorrectionEnabled"], dwellCorrectionEnabled) { dwellCorrectionEnabled = it; hasChanges = true }

                HorizontalDivider()

                Text(strings["label.timing"], style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.crankingAdvance"], crankingAdvance, { crankingAdvance = it; hasChanges = true }, Modifier.weight(1f))
                    NumberField(strings["label.fixedAngle"], fixedTimingAngle, { fixedTimingAngle = it; hasChanges = true }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.triggerAngle"], triggerAngle, { triggerAngle = it; hasChanges = true }, Modifier.weight(1f))
                    NumberField(strings["label.multiplier"], triggerMultiplier, { triggerMultiplier = it; hasChanges = true }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.baseTeeth"], baseTeeth, { baseTeeth = it; hasChanges = true }, Modifier.weight(1f))
                    NumberField(strings["label.missingTeeth"], missingTeeth, { missingTeeth = it; hasChanges = true }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField(strings["label.triggerEdge"], triggerEdge.name, TriggerSettings.SignalEdge.values().map { it.name }) { value ->
                        triggerEdge = TriggerSettings.SignalEdge.valueOf(value)
                        hasChanges = true
                    }
                    DropdownField(strings["label.primarySpeed"], primarySpeed.name, TriggerSettings.TriggerSpeed.values().map { it.name }) { value ->
                        primarySpeed = TriggerSettings.TriggerSpeed.valueOf(value)
                        hasChanges = true
                    }
                }
                DropdownField(strings["label.noiseFilter"], filter.name, TriggerSettings.TriggerFilter.values().map { it.name }) { value ->
                    filter = TriggerSettings.TriggerFilter.valueOf(value)
                    hasChanges = true
                }
            }
        }
    }
}
