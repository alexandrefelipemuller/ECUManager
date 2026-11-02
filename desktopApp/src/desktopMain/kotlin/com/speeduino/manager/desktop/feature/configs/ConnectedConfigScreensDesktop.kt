package com.speeduino.manager.desktop.feature.configs

import com.speeduino.manager.desktop.ReportUnsavedChanges
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.LocalStrings
import com.speeduino.manager.desktop.ui.DropdownField
import com.speeduino.manager.desktop.ui.NumberField
import com.speeduino.manager.desktop.ui.ToggleField
import io.ecucore.model.CutMethod
import io.ecucore.model.EngineProtectionConfig
import io.ecucore.model.PressureCalibration
import io.ecucore.model.ProtectionCut
import io.ecucore.model.TpsCalibration
import kotlinx.coroutines.launch

@Composable
internal fun SensorsCalibrationScreenDesktop(controller: DesktopSpeeduinoController) {
    val strings = LocalStrings.current
    val connectionState by controller.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    var mapMin by remember { mutableStateOf("10") }
    var mapMax by remember { mutableStateOf("260") }
    var baroMin by remember { mutableStateOf("10") }
    var baroMax by remember { mutableStateOf("260") }
    var emapMin by remember { mutableStateOf("10") }
    var emapMax by remember { mutableStateOf("260") }
    var tpsMin by remember { mutableStateOf("0") }
    var tpsMax by remember { mutableStateOf("255") }
    var status by remember { mutableStateOf(strings["label.calibrationLoadHint"]) }

    fun loadFromEcu() {
        scope.launch {
            val pressure = controller.readPressureCalibration()
            val tps = controller.readTpsCalibration()
            if (pressure != null) {
                mapMin = pressure.mapMin.toString()
                mapMax = pressure.mapMax.toString()
                baroMin = pressure.baroMin.toString()
                baroMax = pressure.baroMax.toString()
                emapMin = pressure.emapMin.toString()
                emapMax = pressure.emapMax.toString()
            }
            if (tps != null) {
                tpsMin = tps.tpsMin.toString()
                tpsMax = tps.tpsMax.toString()
            }
            status = if (pressure != null || tps != null) strings["label.calibrationLoaded"] else strings["label.calibrationReadFailed"]
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
                Text(strings["label.sensorsCalibrationTitle"], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(strings["label.sensorsCalibrationSubtitle"], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = ::loadFromEcu, enabled = connectionState.isConnected) { Text(strings["action.loadEcu"]) }
                    FilledTonalButton(
                        enabled = connectionState.isConnected,
                        onClick = {
                            scope.launch {
                                val pressureResult = controller.writePressureCalibration(
                                    PressureCalibration(
                                        mapMin = mapMin.toIntOrNull() ?: 10,
                                        mapMax = mapMax.toIntOrNull() ?: 260,
                                        baroMin = baroMin.toIntOrNull() ?: 10,
                                        baroMax = baroMax.toIntOrNull() ?: 260,
                                        emapMin = emapMin.toIntOrNull() ?: 10,
                                        emapMax = emapMax.toIntOrNull() ?: 260,
                                    )
                                )
                                val tpsResult = controller.writeTpsCalibration(
                                    TpsCalibration(
                                        tpsMin = tpsMin.toIntOrNull() ?: 0,
                                        tpsMax = tpsMax.toIntOrNull() ?: 255,
                                    )
                                )
                                status = if (pressureResult.isSuccess && tpsResult.isSuccess) strings["label.calibrationSaveSuccess"] else strings["label.calibrationSaveFailed"]
                            }
                        }
                    ) { Text(strings["action.saveEcu"]) }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings["label.pressureSensors"], style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.mapMin"], mapMin, { mapMin = it }, Modifier.weight(1f))
                    NumberField(strings["label.mapMax"], mapMax, { mapMax = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.baroMin"], baroMin, { baroMin = it }, Modifier.weight(1f))
                    NumberField(strings["label.baroMax"], baroMax, { baroMax = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.emapMin"], emapMin, { emapMin = it }, Modifier.weight(1f))
                    NumberField(strings["label.emapMax"], emapMax, { emapMax = it }, Modifier.weight(1f))
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings["label.tpsCalibration"], style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.closedAdc"], tpsMin, { tpsMin = it }, Modifier.weight(1f))
                    NumberField(strings["label.openAdc"], tpsMax, { tpsMax = it }, Modifier.weight(1f))
                }
            }
        }
    }

    LaunchedEffect(connectionState.isConnected) {
        if (connectionState.isConnected) loadFromEcu()
    }
}

@Composable
internal fun EngineProtectionEditorScreenDesktop(controller: DesktopSpeeduinoController) {
    val strings = LocalStrings.current
    val config by controller.engineProtectionConfig.collectAsState()
    var protectionCut by remember(config) { mutableStateOf(config?.protectionCut ?: ProtectionCut.BOTH) }
    var cutMethod by remember(config) { mutableStateOf(config?.cutMethod ?: CutMethod.FULL) }
    var rpmMin by remember(config) { mutableStateOf(config?.engineProtectionRpmMin?.toString() ?: "1500") }
    var engineProtectEnabled by remember(config) { mutableStateOf(config?.engineProtectEnabled ?: false) }
    var revLimiterEnabled by remember(config) { mutableStateOf(config?.revLimiterEnabled ?: false) }
    var boostLimitEnabled by remember(config) { mutableStateOf(config?.boostLimitEnabled ?: false) }
    var oilEnabled by remember(config) { mutableStateOf(config?.oilPressureProtectionEnabled ?: false) }
    var afrEnabled by remember(config) { mutableStateOf(config?.afrProtectionEnabled ?: false) }
    var coolantEnabled by remember(config) { mutableStateOf(config?.coolantProtectionEnabled ?: false) }
    var hasChanges by remember { mutableStateOf(false) }
    ReportUnsavedChanges(hasChanges)
    var status by remember { mutableStateOf(strings["label.calibrationLoadHint"]) }

    LaunchedEffect(config) {
        hasChanges = false
        status = if (config != null) strings["label.calibrationLoaded"] else strings["label.calibrationLoadHint"]
    }

    LaunchedEffect(Unit) {
        if (config == null) {
            controller.loadEngineProtectionConfig()
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
                Text(strings["label.engineProtectionLimitersTitle"], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(strings["label.engineProtectionLimitersSubtitle"], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = controller::loadEngineProtectionConfig, enabled = config == null || hasChanges) { Text(strings["action.loadEcu"]) }
                    FilledTonalButton(
                        enabled = hasChanges,
                        onClick = {
                            controller.saveEngineProtectionConfig(
                                EngineProtectionConfig(
                                    protectionCut = protectionCut,
                                    cutMethod = cutMethod,
                                    engineProtectionRpmMin = rpmMin.toIntOrNull() ?: 1500,
                                    engineProtectEnabled = engineProtectEnabled,
                                    revLimiterEnabled = revLimiterEnabled,
                                    boostLimitEnabled = boostLimitEnabled,
                                    oilPressureProtectionEnabled = oilEnabled,
                                    afrProtectionEnabled = afrEnabled,
                                    coolantProtectionEnabled = coolantEnabled,
                                )
                            )
                            hasChanges = false
                            status = strings["label.calibrationLoaded"]
                        }
                    ) { Text(strings["action.saveEcu"]) }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownField(
                    strings["label.protectionCut"],
                    protectionCutLabel(strings, protectionCut),
                    ProtectionCut.values().filter { it != ProtectionCut.OFF }.map { protectionCutLabel(strings, it) }
                ) { label ->
                    protectionCut = protectionCutFromLabel(strings, label)
                    hasChanges = true
                }
                DropdownField(
                    strings["label.cutMethod"],
                    cutMethodLabel(strings, cutMethod),
                    CutMethod.values().map { cutMethodLabel(strings, it) }
                ) { label ->
                    cutMethod = cutMethodFromLabel(strings, label)
                    hasChanges = true
                }
                NumberField(strings["label.minimumRpm"], rpmMin, { rpmMin = it; hasChanges = true })
                ToggleField(strings["label.engineProtectionEnabled"], engineProtectEnabled) { engineProtectEnabled = it; hasChanges = true }
                ToggleField(strings["label.revLimiterEnabled"], revLimiterEnabled) { revLimiterEnabled = it; hasChanges = true }
                ToggleField(strings["label.boostLimitEnabled"], boostLimitEnabled) { boostLimitEnabled = it; hasChanges = true }
                ToggleField(strings["label.oilPressureProtection"], oilEnabled) { oilEnabled = it; hasChanges = true }
                ToggleField(strings["label.afrProtection"], afrEnabled) { afrEnabled = it; hasChanges = true }
                ToggleField(strings["label.coolantProtection"], coolantEnabled) { coolantEnabled = it; hasChanges = true }
            }
        }
    }
}

private fun protectionCutLabel(strings: com.speeduino.manager.desktop.Strings, value: ProtectionCut): String {
    return when (value) {
        ProtectionCut.OFF -> strings["label.off"]
        ProtectionCut.SPARK_ONLY -> strings["label.cutIgnition"]
        ProtectionCut.FUEL_ONLY -> strings["label.cutFuel"]
        ProtectionCut.BOTH -> strings["label.cutBoth"]
    }
}

private fun protectionCutFromLabel(strings: com.speeduino.manager.desktop.Strings, label: String): ProtectionCut {
    return ProtectionCut.values().firstOrNull { protectionCutLabel(strings, it) == label } ?: ProtectionCut.BOTH
}

private fun cutMethodLabel(strings: com.speeduino.manager.desktop.Strings, value: CutMethod): String {
    return when (value) {
        CutMethod.FULL -> strings["label.cutMethodFull"]
        CutMethod.ROLLING -> strings["label.cutMethodRolling"]
    }
}

private fun cutMethodFromLabel(strings: com.speeduino.manager.desktop.Strings, label: String): CutMethod {
    return CutMethod.values().firstOrNull { cutMethodLabel(strings, it) == label } ?: CutMethod.FULL
}
