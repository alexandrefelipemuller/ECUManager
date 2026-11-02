package com.speeduino.manager.desktop

import com.speeduino.manager.desktop.ui.isOutOfRange
import com.speeduino.manager.desktop.ui.invalidNumbers
import com.speeduino.manager.desktop.ui.SaveDisabledReason
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ecucore.model.ClosedLoopCorrectionConfig
import io.ecucore.model.ClosedLoopCorrectionMapper
import io.ecucore.model.ClosedLoopSensorType
import io.ecucore.model.ClosedLoopStrategy
import io.ecucore.model.IdleControlMode
import io.ecucore.model.IdleControlSettings
import com.speeduino.manager.desktop.ui.DropdownField
import com.speeduino.manager.desktop.ui.NumberField
import com.speeduino.manager.desktop.ui.ToggleField

@Composable
internal fun IdleControlScreenDesktop(controller: DesktopSpeeduinoController) {
    val settings by controller.idleControlSettings.collectAsState()
    val strings = LocalStrings.current
    var restoreKey by remember { mutableIntStateOf(0) }
    var controlMode by remember(settings, restoreKey) { mutableStateOf(settings?.controlMode ?: IdleControlMode.NONE) }
    var idleTargetRpm by remember(settings, restoreKey) { mutableStateOf(settings?.idleTargetRpm?.toString() ?: "900") }
    var fastIdleTempC by remember(settings, restoreKey) { mutableStateOf(settings?.fastIdleTempC?.toString() ?: "40") }
    var pwmChannels by remember(settings, restoreKey) { mutableStateOf(settings?.pwmChannels?.toString() ?: "1") }
    var idleValveFrequencyHz by remember(settings, restoreKey) { mutableStateOf(settings?.idleValveFrequencyHz?.toString() ?: "120") }
    var pwmDirectionReverse by remember(settings, restoreKey) { mutableStateOf(settings?.pwmDirectionReverse ?: false) }
    var runBeforeStart by remember(settings, restoreKey) { mutableStateOf(settings?.runBeforeStart ?: false) }
    var stepTimeMs by remember(settings, restoreKey) { mutableStateOf(settings?.stepTimeMs?.toString() ?: "3") }
    var stepHomeSteps by remember(settings, restoreKey) { mutableStateOf(settings?.stepHomeSteps?.toString() ?: "100") }
    var stepMinimumSteps by remember(settings, restoreKey) { mutableStateOf(settings?.stepMinimumSteps?.toString() ?: "3") }
    var hasChanges by remember { mutableStateOf(false) }
    val hasInvalidFields = (invalidNumbers(idleTargetRpm, fastIdleTempC, pwmChannels, idleValveFrequencyHz, stepTimeMs, stepHomeSteps, stepMinimumSteps) || isOutOfRange(idleTargetRpm, 0..2550) || isOutOfRange(pwmChannels, 1..2))
    ReportUnsavedChanges(hasChanges)

    LaunchedEffect(Unit) {
        if (settings == null) {
            controller.loadIdleControlSettings()
        }
    }

    LaunchedEffect(settings) {
        hasChanges = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ConfigHeaderCard(
            title = strings["route.idleControl"],
            subtitle = strings["label.idleControlSubtitle"],
            onLoad = controller::loadIdleControlSettings,
            onSave = {
                val updated = IdleControlSettings(
                    controlMode = controlMode,
                    idleTargetRpm = idleTargetRpm.toIntOrNull() ?: 900,
                    fastIdleTempC = fastIdleTempC.toIntOrNull() ?: 40,
                    pwmChannels = (pwmChannels.toIntOrNull() ?: 1).coerceIn(1, 2),
                    idleValveFrequencyHz = idleValveFrequencyHz.toIntOrNull() ?: 120,
                    pwmDirectionReverse = pwmDirectionReverse,
                    runBeforeStart = runBeforeStart,
                    stepTimeMs = (stepTimeMs.toIntOrNull() ?: 3).coerceAtLeast(1),
                    stepHomeSteps = (stepHomeSteps.toIntOrNull() ?: 100).coerceAtLeast(0),
                    stepMinimumSteps = (stepMinimumSteps.toIntOrNull() ?: 3).coerceAtLeast(0),
                )
                controller.saveIdleControlSettings(updated)
            },
            saveEnabled = hasChanges && !hasInvalidFields,
            onRestore = { restoreKey++; hasChanges = false },
            restoreEnabled = hasChanges,
            footer = { SaveDisabledReason(hasChanges = hasChanges, dataAvailable = settings != null, hasInvalidFields = hasInvalidFields) },
            loadEnabled = settings == null || hasChanges
        )

        ConfigSectionCard(
            title = "Basico",
            subtitle = "Modo de controle e alvo de marcha lenta."
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownField(
                    label = "Modo",
                    value = controlMode.displayLabel(),
                    options = IdleControlMode.values().map { it.displayLabel() },
                    modifier = Modifier.weight(1f)
                ) { label ->
                    controlMode = IdleControlMode.values().first { it.displayLabel() == label }
                    hasChanges = true
                }
                NumberField("Alvo RPM", idleTargetRpm, { idleTargetRpm = it; hasChanges = true }, Modifier.weight(1f), range = 0..2550, integerOnly = true)
                NumberField("Fast idle °C", fastIdleTempC, { fastIdleTempC = it; hasChanges = true }, Modifier.weight(1f))
            }
        }

        if (controlMode.isPwmMode()) {
            ConfigSectionCard(
                title = "PWM",
                subtitle = "Saídas e frequência da válvula de marcha lenta."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("Saídas", pwmChannels, { pwmChannels = it; hasChanges = true }, Modifier.weight(1f), range = 1..2, integerOnly = true)
                    NumberField("Frequencia Hz", idleValveFrequencyHz, { idleValveFrequencyHz = it; hasChanges = true }, Modifier.weight(1f))
                }
                ToggleField("Inverter direcao", pwmDirectionReverse) {
                    pwmDirectionReverse = it
                    hasChanges = true
                }
                ToggleField("Ativar antes da partida", runBeforeStart) {
                    runBeforeStart = it
                    hasChanges = true
                }
            }
        }

        if (controlMode.isStepperMode()) {
            ConfigSectionCard(
                title = "Stepper",
                subtitle = "Parametros basicos do motor de passo."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("Tempo de passo ms", stepTimeMs, { stepTimeMs = it; hasChanges = true }, Modifier.weight(1f))
                    NumberField("Home steps", stepHomeSteps, { stepHomeSteps = it; hasChanges = true }, Modifier.weight(1f))
                    NumberField("Minimo de steps", stepMinimumSteps, { stepMinimumSteps = it; hasChanges = true }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun ClosedLoopCorrectionsScreenDesktop(controller: DesktopSpeeduinoController) {
    val config by controller.closedLoopCorrections.collectAsState()
    val strings = LocalStrings.current
    var restoreKey by remember { mutableIntStateOf(0) }
    var sensorType by remember(config, restoreKey) { mutableStateOf(config?.sensorType ?: ClosedLoopSensorType.WIDE_BAND) }
    var strategy by remember(config, restoreKey) { mutableStateOf(config?.strategy ?: ClosedLoopStrategy.SIMPLE) }
    var ignitionEventsPerStep by remember(config, restoreKey) { mutableStateOf(config?.ignitionEventsPerStep?.toString() ?: "0") }
    var authorityPercent by remember(config, restoreKey) { mutableStateOf(config?.authorityPercent?.toString() ?: "0") }
    var minAfr by remember(config, restoreKey) { mutableStateOf(config?.minAfr?.toString() ?: "14.0") }
    var maxAfr by remember(config, restoreKey) { mutableStateOf(config?.maxAfr?.toString() ?: "15.0") }
    var activeAboveCoolantF by remember(config, restoreKey) { mutableStateOf(config?.activeAboveCoolantF?.toString() ?: "70") }
    var activeAboveRpm by remember(config, restoreKey) { mutableStateOf(config?.activeAboveRpm?.toString() ?: "1200") }
    var activeBelowTpsPercent by remember(config, restoreKey) { mutableStateOf(config?.activeBelowTpsPercent?.toString() ?: "35.0") }
    var activeBelowMapKpa by remember(config, restoreKey) { mutableStateOf(config?.activeBelowMapKpa?.toString() ?: "100") }
    var activeAboveMapKpa by remember(config, restoreKey) { mutableStateOf(config?.activeAboveMapKpa?.toString() ?: "20") }
    var delayAfterStartSeconds by remember(config, restoreKey) { mutableStateOf(config?.delayAfterStartSeconds?.toString() ?: "5") }
    var pidProportional by remember(config, restoreKey) { mutableStateOf(config?.pidProportional?.toString() ?: "0") }
    var pidIntegral by remember(config, restoreKey) { mutableStateOf(config?.pidIntegral?.toString() ?: "0") }
    var pidDerivative by remember(config, restoreKey) { mutableStateOf(config?.pidDerivative?.toString() ?: "0") }
    var hasChanges by remember { mutableStateOf(false) }
    val hasInvalidFields = invalidNumbers(ignitionEventsPerStep, authorityPercent, minAfr, maxAfr, activeAboveCoolantF, activeAboveRpm, activeBelowTpsPercent, activeBelowMapKpa, activeAboveMapKpa, delayAfterStartSeconds, pidProportional, pidIntegral, pidDerivative)
    ReportUnsavedChanges(hasChanges)

    LaunchedEffect(Unit) {
        if (config == null) {
            controller.loadClosedLoopCorrections()
        }
    }

    LaunchedEffect(config) {
        hasChanges = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ConfigHeaderCard(
            title = strings["route.closedLoopCorrections"],
            subtitle = strings["label.closedLoopSubtitle"],
            onLoad = controller::loadClosedLoopCorrections,
            onSave = {
                val updated = ClosedLoopCorrectionMapper.syncFromAfr(
                    ClosedLoopCorrectionConfig(
                        sensorType = sensorType,
                        strategy = strategy,
                        ignitionEventsPerStep = ignitionEventsPerStep.toIntOrNull() ?: 0,
                        authorityPercent = authorityPercent.toIntOrNull() ?: 0,
                        minAfr = minAfr.toDoubleOrNull() ?: 14.0,
                        maxAfr = maxAfr.toDoubleOrNull() ?: 15.0,
                        minLambda = 0.0,
                        maxLambda = 0.0,
                        activeAboveCoolantF = activeAboveCoolantF.toIntOrNull() ?: 70,
                        activeAboveRpm = activeAboveRpm.toIntOrNull() ?: 1200,
                        activeBelowTpsPercent = activeBelowTpsPercent.toDoubleOrNull() ?: 35.0,
                        activeBelowMapKpa = activeBelowMapKpa.toIntOrNull() ?: 100,
                        activeAboveMapKpa = activeAboveMapKpa.toIntOrNull() ?: 20,
                        delayAfterStartSeconds = delayAfterStartSeconds.toIntOrNull() ?: 5,
                        pidProportional = pidProportional.toIntOrNull() ?: 0,
                        pidIntegral = pidIntegral.toIntOrNull() ?: 0,
                        pidDerivative = pidDerivative.toIntOrNull() ?: 0,
                    )
                )
                controller.saveClosedLoopCorrections(updated)
            },
            saveEnabled = hasChanges && !hasInvalidFields,
            onRestore = { restoreKey++; hasChanges = false },
            restoreEnabled = hasChanges,
            footer = { SaveDisabledReason(hasChanges = hasChanges, dataAvailable = config != null, hasInvalidFields = hasInvalidFields) },
            loadEnabled = config == null || hasChanges
        )

        ConfigSectionCard(
            title = "Modo",
            subtitle = "Tipo de sensor e estrategia de correcao."
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownField(
                    label = "Sensor",
                    value = sensorType.displayLabel(),
                    options = ClosedLoopSensorType.values().map { it.displayLabel() },
                    modifier = Modifier.weight(1f)
                ) { label ->
                    sensorType = ClosedLoopSensorType.values().first { it.displayLabel() == label }
                    hasChanges = true
                }
                DropdownField(
                    label = "Estrategia",
                    value = strategy.displayLabel(),
                    options = ClosedLoopStrategy.values().map { it.displayLabel() },
                    modifier = Modifier.weight(1f)
                ) { label ->
                    strategy = ClosedLoopStrategy.values().first { it.displayLabel() == label }
                    hasChanges = true
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("Eventos por passo", ignitionEventsPerStep, { ignitionEventsPerStep = it; hasChanges = true }, Modifier.weight(1f))
                NumberField("Autoridade %", authorityPercent, { authorityPercent = it; hasChanges = true }, Modifier.weight(1f))
                NumberField("Delay pos-partida s", delayAfterStartSeconds, { delayAfterStartSeconds = it; hasChanges = true }, Modifier.weight(1f))
            }
        }

        ConfigSectionCard(
            title = "Faixa AFR",
            subtitle = "Janela de correcao usada pela malha fechada."
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("AFR minimo", minAfr, { minAfr = it; hasChanges = true }, Modifier.weight(1f))
                NumberField("AFR maximo", maxAfr, { maxAfr = it; hasChanges = true }, Modifier.weight(1f))
            }
        }

        ConfigSectionCard(
            title = "Ativacao",
            subtitle = "Limites de temperatura, RPM, TPS e MAP. O limite de TPS e particularmente importante em Alpha-N."
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("Coolant F >", activeAboveCoolantF, { activeAboveCoolantF = it; hasChanges = true }, Modifier.weight(1f))
                NumberField("RPM >", activeAboveRpm, { activeAboveRpm = it; hasChanges = true }, Modifier.weight(1f))
                NumberField("TPS % <", activeBelowTpsPercent, { activeBelowTpsPercent = it; hasChanges = true }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("MAP kPa <", activeBelowMapKpa, { activeBelowMapKpa = it; hasChanges = true }, Modifier.weight(1f))
                NumberField("MAP kPa >", activeAboveMapKpa, { activeAboveMapKpa = it; hasChanges = true }, Modifier.weight(1f))
            }
        }

        if (strategy == ClosedLoopStrategy.PID) {
            ConfigSectionCard(
                title = "PID",
                subtitle = "Ganhos usados no modo PID."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("Kp", pidProportional, { pidProportional = it; hasChanges = true }, Modifier.weight(1f))
                    NumberField("Ki", pidIntegral, { pidIntegral = it; hasChanges = true }, Modifier.weight(1f))
                    NumberField("Kd", pidDerivative, { pidDerivative = it; hasChanges = true }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ConfigHeaderCard(
    title: String,
    subtitle: String,
    onLoad: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    loadEnabled: Boolean = saveEnabled,
    onRestore: (() -> Unit)? = null,
    restoreEnabled: Boolean = false,
    footer: @Composable () -> Unit = {},
) {
    val strings = LocalStrings.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onLoad, enabled = loadEnabled) { Text(strings["action.loadEcu"]) }
                FilledTonalButton(onClick = onSave, enabled = saveEnabled) { Text(strings["action.saveEcu"]) }
                onRestore?.let { FilledTonalButton(onClick = it, enabled = restoreEnabled) { Text(strings["btn_restore_ecu_values"]) } }
            }
            footer()
        }
    }
}

@Composable
private fun ConfigSectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

private fun IdleControlMode.displayLabel(): String = when (this) {
    IdleControlMode.NONE -> "None"
    IdleControlMode.ON_OFF -> "On/Off"
    IdleControlMode.PWM_OPEN_LOOP -> "PWM Open Loop"
    IdleControlMode.PWM_CLOSED_LOOP -> "PWM Closed Loop"
    IdleControlMode.STEPPER_OPEN_LOOP -> "Stepper Open Loop"
    IdleControlMode.STEPPER_CLOSED_LOOP -> "Stepper Closed Loop"
    IdleControlMode.PWM_CLOSED_PLUS_OPEN_LOOP -> "PWM Closed + Open Loop"
    IdleControlMode.STEPPER_CLOSED_PLUS_OPEN_LOOP -> "Stepper Closed + Open Loop"
}

private fun IdleControlMode.isPwmMode(): Boolean = this == IdleControlMode.ON_OFF ||
    this == IdleControlMode.PWM_OPEN_LOOP ||
    this == IdleControlMode.PWM_CLOSED_LOOP ||
    this == IdleControlMode.PWM_CLOSED_PLUS_OPEN_LOOP

private fun IdleControlMode.isStepperMode(): Boolean = this == IdleControlMode.STEPPER_OPEN_LOOP ||
    this == IdleControlMode.STEPPER_CLOSED_LOOP ||
    this == IdleControlMode.STEPPER_CLOSED_PLUS_OPEN_LOOP

private fun ClosedLoopSensorType.displayLabel(): String = when (this) {
    ClosedLoopSensorType.DISABLED -> "Disabled"
    ClosedLoopSensorType.NARROW_BAND -> "Narrow Band"
    ClosedLoopSensorType.WIDE_BAND -> "Wide Band"
}

private fun ClosedLoopStrategy.displayLabel(): String = when (this) {
    ClosedLoopStrategy.SIMPLE -> "Simple"
    ClosedLoopStrategy.PID -> "PID"
    ClosedLoopStrategy.NO_CORRECTION -> "No Correction"
}
