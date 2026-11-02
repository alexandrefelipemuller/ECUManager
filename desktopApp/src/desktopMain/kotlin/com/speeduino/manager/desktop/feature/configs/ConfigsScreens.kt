package com.speeduino.manager.desktop.feature.configs

import com.speeduino.manager.desktop.ui.rememberEcuLoadState
import com.speeduino.manager.desktop.ui.EcuLoadProgress
import com.speeduino.manager.desktop.ui.isOutOfRange
import com.speeduino.manager.desktop.ui.invalidNumbers
import com.speeduino.manager.desktop.ui.SaveDisabledReason
import androidx.compose.runtime.mutableIntStateOf
import com.speeduino.manager.desktop.ReportUnsavedChanges
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ecucore.EcuLiveData
import io.ecucore.compare.LogCompareResult
import io.ecucore.compare.LogHeatCellState
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.LocalStrings
import com.speeduino.manager.desktop.ui.DropdownField
import com.speeduino.manager.desktop.ui.InfoRow
import com.speeduino.manager.desktop.ui.NumberField
import com.speeduino.manager.desktop.ui.ToggleField
import io.ecucore.model.Algorithm
import io.ecucore.model.EngineConstants
import io.ecucore.model.EngineStroke
import io.ecucore.model.EngineType
import io.ecucore.model.InjectorLayout
import io.ecucore.model.InjectorPortType
import io.ecucore.model.InjectorStaging
import io.ecucore.model.MapSampleMethod
import io.ecucore.model.TriggerSettings
import io.ecucore.tuning.CellRef
import kotlinx.coroutines.isActive
import kotlin.math.abs

@Composable
internal fun EngineConstantsScreenDesktop(controller: DesktopSpeeduinoController) {
    val constants by controller.engineConstants.collectAsState()
    val strings = LocalStrings.current
    var restoreKey by remember { mutableIntStateOf(0) }
    val loadState = rememberEcuLoadState(constants)
    var reqFuel by remember(constants, restoreKey) { mutableStateOf(constants?.reqFuel?.toString() ?: "6.0") }
    var batteryVoltage by remember(constants, restoreKey) { mutableStateOf(constants?.batteryVoltage?.toString() ?: "12.0") }
    var algorithm by remember(constants, restoreKey) { mutableStateOf(constants?.algorithm ?: Algorithm.SPEED_DENSITY) }
    var squirtsPerCycle by remember(constants, restoreKey) { mutableStateOf(constants?.squirtsPerCycle?.toString() ?: "1") }
    var injectorStaging by remember(constants, restoreKey) { mutableStateOf(constants?.injectorStaging ?: InjectorStaging.ALTERNATING) }
    var engineStroke by remember(constants, restoreKey) { mutableStateOf(constants?.engineStroke ?: EngineStroke.FOUR_STROKE) }
    var numberOfCylinders by remember(constants, restoreKey) { mutableStateOf(constants?.numberOfCylinders?.toString() ?: "4") }
    var injectorPortType by remember(constants, restoreKey) { mutableStateOf(constants?.injectorPortType ?: InjectorPortType.PORT) }
    var numberOfInjectors by remember(constants, restoreKey) { mutableStateOf(constants?.numberOfInjectors?.toString() ?: "4") }
    var engineType by remember(constants, restoreKey) { mutableStateOf(constants?.engineType ?: EngineType.EVEN_FIRE) }
    var stoich by remember(constants, restoreKey) { mutableStateOf(constants?.stoichiometricRatio?.toString() ?: "14.7") }
    var injectorLayout by remember(constants, restoreKey) { mutableStateOf(constants?.injectorLayout ?: InjectorLayout.SEQUENTIAL) }
    var mapSampleMethod by remember(constants, restoreKey) { mutableStateOf(constants?.mapSampleMethod ?: MapSampleMethod.CYCLE_AVERAGE) }
    var mapSwitchPoint by remember(constants, restoreKey) { mutableStateOf(constants?.mapSwitchPoint?.toString() ?: "4000") }
    var channel2Angle by remember(constants, restoreKey) { mutableStateOf(constants?.channel2Angle?.toString() ?: "180") }
    var channel3Angle by remember(constants, restoreKey) { mutableStateOf(constants?.channel3Angle?.toString() ?: "270") }
    var channel4Angle by remember(constants, restoreKey) { mutableStateOf(constants?.channel4Angle?.toString() ?: "360") }
    var hasChanges by remember { mutableStateOf(false) }
    val hasInvalidFields = invalidNumbers(reqFuel, batteryVoltage, squirtsPerCycle, numberOfCylinders, numberOfInjectors, stoich, mapSwitchPoint, channel2Angle, channel3Angle, channel4Angle)
    ReportUnsavedChanges(hasChanges)

    LaunchedEffect(constants) {
        hasChanges = false
    }

    LaunchedEffect(Unit) {
        if (constants == null) {
            controller.loadEngineConstants()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    strings["label.engineConstantsTitle"],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(strings["label.engineConstantsSubtitle"], style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { loadState.start(); controller.loadEngineConstants() }, enabled = constants == null || hasChanges) { Text(strings["action.loadEcu"]) }
                    FilledTonalButton(
                        onClick = {
                            val updated = EngineConstants(
                                reqFuel = reqFuel.toFloatOrNull() ?: 6.0f,
                                batteryVoltage = batteryVoltage.toFloatOrNull() ?: 12.0f,
                                algorithm = algorithm,
                                squirtsPerCycle = squirtsPerCycle.toIntOrNull() ?: 1,
                                injectorStaging = injectorStaging,
                                engineStroke = engineStroke,
                                numberOfCylinders = numberOfCylinders.toIntOrNull() ?: 4,
                                injectorPortType = injectorPortType,
                                numberOfInjectors = numberOfInjectors.toIntOrNull() ?: 4,
                                engineType = engineType,
                                stoichiometricRatio = stoich.toFloatOrNull() ?: 14.7f,
                                injectorLayout = injectorLayout,
                                mapSampleMethod = mapSampleMethod,
                                mapSwitchPoint = mapSwitchPoint.toIntOrNull() ?: 4000,
                                channel2Angle = channel2Angle.toIntOrNull() ?: 180,
                                channel3Angle = channel3Angle.toIntOrNull() ?: 270,
                                channel4Angle = channel4Angle.toIntOrNull() ?: 360
                            )
                            controller.saveEngineConstants(updated)
                        },
                        enabled = hasChanges && !hasInvalidFields
                    ) { Text(strings["action.saveEcu"]) }
                    FilledTonalButton(onClick = { restoreKey++; hasChanges = false }, enabled = hasChanges) { Text(strings["btn_restore_ecu_values"]) }
                }
                EcuLoadProgress(loadState)
                SaveDisabledReason(hasChanges = hasChanges, dataAvailable = constants != null, hasInvalidFields = hasInvalidFields)
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(strings["label.fuelAndReference"], style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.reqFuelMs"], reqFuel, {
                        reqFuel = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                    NumberField(strings["label.voltage"], batteryVoltage, {
                        batteryVoltage = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                }

                HorizontalDivider()

                Text(strings["label.algorithmAndInjection"], style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField(strings["label.algorithm"], algorithm.displayName, Algorithm.values().map { it.displayName }) { label ->
                        algorithm = Algorithm.values().first { it.displayName == label }
                        hasChanges = true
                    }
                    DropdownField(strings["label.injectorStaging"], injectorStaging.displayName, InjectorStaging.values().map { it.displayName }) { label ->
                        injectorStaging = InjectorStaging.values().first { it.displayName == label }
                        hasChanges = true
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField(strings["label.stroke"], engineStroke.displayName, EngineStroke.values().map { it.displayName }) { label ->
                        engineStroke = EngineStroke.values().first { it.displayName == label }
                        hasChanges = true
                    }
                    DropdownField(strings["label.portLabel"], injectorPortType.displayName, InjectorPortType.values().map { it.displayName }) { label ->
                        injectorPortType = InjectorPortType.values().first { it.displayName == label }
                        hasChanges = true
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField(strings["label.engineType"], engineType.displayName, EngineType.values().map { it.displayName }) { label ->
                        engineType = EngineType.values().first { it.displayName == label }
                        hasChanges = true
                    }
                    DropdownField(strings["label.layout"], injectorLayout.displayName, InjectorLayout.values().map { it.displayName }) { label ->
                        injectorLayout = InjectorLayout.values().first { it.displayName == label }
                        hasChanges = true
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.squirtsPerCycle"], squirtsPerCycle, {
                        squirtsPerCycle = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                    NumberField(strings["label.cylinders"], numberOfCylinders, {
                        numberOfCylinders = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                    NumberField(strings["label.injectors"], numberOfInjectors, {
                        numberOfInjectors = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                }

                HorizontalDivider()

                Text(strings["label.mapAndStoich"], style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField(strings["label.mapSample"], mapSampleMethod.displayName, MapSampleMethod.values().map { it.displayName }) { label ->
                        mapSampleMethod = MapSampleMethod.values().first { it.displayName == label }
                        hasChanges = true
                    }
                    NumberField(strings["label.stoichAfr"], stoich, {
                        stoich = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                    NumberField(strings["label.mapSwitch"], mapSwitchPoint, {
                        mapSwitchPoint = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                }

                HorizontalDivider()

                Text(strings["label.oddfireAngles"], style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.channel2"], channel2Angle, {
                        channel2Angle = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                    NumberField(strings["label.channel3"], channel3Angle, {
                        channel3Angle = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                    NumberField(strings["label.channel4"], channel4Angle, {
                        channel4Angle = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun TriggerSettingsScreenDesktop(controller: DesktopSpeeduinoController) {
    val settings by controller.triggerSettings.collectAsState()
    val strings = LocalStrings.current
    var restoreKey by remember { mutableIntStateOf(0) }
    val loadState = rememberEcuLoadState(settings)
    var triggerAngle by remember(settings, restoreKey) { mutableStateOf(settings?.triggerAngleDeg?.toString() ?: "0") }
    var triggerMultiplier by remember(settings, restoreKey) { mutableStateOf(settings?.triggerAngleMultiplier?.toString() ?: "1") }
    var triggerPattern by remember(settings, restoreKey) { mutableStateOf(settings?.triggerPattern ?: 0) }
    var baseTeeth by remember(settings, restoreKey) { mutableStateOf(settings?.primaryBaseTeeth?.toString() ?: "36") }
    var missingTeeth by remember(settings, restoreKey) { mutableStateOf(settings?.missingTeeth?.toString() ?: "1") }
    var primarySpeed by remember(settings, restoreKey) { mutableStateOf(settings?.primaryTriggerSpeed ?: TriggerSettings.TriggerSpeed.CRANK) }
    var triggerEdge by remember(settings, restoreKey) { mutableStateOf(settings?.triggerEdge ?: TriggerSettings.SignalEdge.RISING) }
    var secondaryEdge by remember(settings, restoreKey) { mutableStateOf(settings?.secondaryTriggerEdge ?: TriggerSettings.SignalEdge.RISING) }
    var secondaryType by remember(settings, restoreKey) { mutableStateOf(settings?.secondaryTriggerType ?: 0) }
    var phaseHigh by remember(settings, restoreKey) { mutableStateOf(settings?.levelForFirstPhaseHigh ?: false) }
    var skipRevs by remember(settings, restoreKey) { mutableStateOf(settings?.skipRevolutions?.toString() ?: "0") }
    var filter by remember(settings, restoreKey) { mutableStateOf(settings?.triggerFilter ?: TriggerSettings.TriggerFilter.OFF) }
    var reSyncEveryCycle by remember(settings, restoreKey) { mutableStateOf(settings?.reSyncEveryCycle ?: false) }
    var hasChanges by remember { mutableStateOf(false) }
    val hasInvalidFields = invalidNumbers(triggerAngle, triggerMultiplier, baseTeeth, missingTeeth, skipRevs)
    ReportUnsavedChanges(hasChanges)

    LaunchedEffect(settings) {
        hasChanges = false
    }

    LaunchedEffect(Unit) {
        if (settings == null) {
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
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(strings["label.triggerTitle"], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(strings["label.triggerSubtitle"], style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { loadState.start(); controller.loadTriggerSettings() }, enabled = settings == null || hasChanges) { Text(strings["action.loadEcu"]) }
                    FilledTonalButton(
                        onClick = {
                            val updated = TriggerSettings(
                                triggerAngleDeg = triggerAngle.toIntOrNull() ?: 0,
                                triggerAngleMultiplier = triggerMultiplier.toIntOrNull() ?: 1,
                                triggerPattern = triggerPattern,
                                primaryBaseTeeth = baseTeeth.toIntOrNull() ?: 36,
                                missingTeeth = missingTeeth.toIntOrNull() ?: 1,
                                primaryTriggerSpeed = primarySpeed,
                                triggerEdge = triggerEdge,
                                secondaryTriggerEdge = secondaryEdge,
                                secondaryTriggerType = secondaryType,
                                levelForFirstPhaseHigh = phaseHigh,
                                skipRevolutions = skipRevs.toIntOrNull() ?: 0,
                                triggerFilter = filter,
                                reSyncEveryCycle = reSyncEveryCycle
                            )
                            controller.saveTriggerSettings(updated)
                        },
                        enabled = hasChanges && !hasInvalidFields
                    ) { Text(strings["action.saveEcu"]) }
                    FilledTonalButton(onClick = { restoreKey++; hasChanges = false }, enabled = hasChanges) { Text(strings["btn_restore_ecu_values"]) }
                }
                EcuLoadProgress(loadState)
                SaveDisabledReason(hasChanges = hasChanges, dataAvailable = settings != null, hasInvalidFields = hasInvalidFields)
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.triggerAngle"], triggerAngle, {
                        triggerAngle = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                    NumberField(strings["label.angleMultiplier"], triggerMultiplier, {
                        triggerMultiplier = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                }
                DropdownField(
                    strings["label.triggerPattern"],
                    triggerPatternLabel(strings, triggerPattern),
                    triggerPatternOptions(strings)
                ) { label ->
                    triggerPattern = triggerPatternFromLabel(strings, label)
                    hasChanges = true
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.baseTeeth"], baseTeeth, {
                        baseTeeth = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                    NumberField(strings["label.missingTeeth"], missingTeeth, {
                        missingTeeth = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                }
                DropdownField(
                    strings["label.primarySpeed"],
                    triggerSpeedLabel(strings, primarySpeed),
                    TriggerSettings.TriggerSpeed.values().map { triggerSpeedLabel(strings, it) }
                ) { label ->
                    primarySpeed = triggerSpeedFromLabel(strings, label)
                    hasChanges = true
                }
                DropdownField(
                    strings["label.triggerEdge"],
                    signalEdgeLabel(strings, triggerEdge),
                    TriggerSettings.SignalEdge.values().map { signalEdgeLabel(strings, it) }
                ) { label ->
                    triggerEdge = signalEdgeFromLabel(strings, label)
                    hasChanges = true
                }
                TriggerVisualizationCardDesktop(
                    totalTeeth = baseTeeth.toIntOrNull() ?: 0,
                    missingTeeth = missingTeeth.toIntOrNull() ?: 0,
                    triggerAngleDeg = triggerAngle.toIntOrNull() ?: 0,
                    triggerAngleMultiplier = triggerMultiplier.toIntOrNull() ?: 0,
                    triggerEdge = triggerEdge,
                    primaryTriggerSpeed = primarySpeed,
                    isMissingToothPattern = triggerPattern == 0
                )
                DropdownField(
                    strings["label.secondaryEdge"],
                    signalEdgeLabel(strings, secondaryEdge),
                    TriggerSettings.SignalEdge.values().map { signalEdgeLabel(strings, it) }
                ) { label ->
                    secondaryEdge = signalEdgeFromLabel(strings, label)
                    hasChanges = true
                }
                DropdownField(
                    strings["label.secondaryPattern"],
                    secondaryPatternLabel(strings, secondaryType),
                    secondaryPatternOptions(strings)
                ) { label ->
                    secondaryType = secondaryPatternFromLabel(strings, label)
                    hasChanges = true
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.skipRevolutions"], skipRevs, {
                        skipRevs = it
                        hasChanges = true
                    }, Modifier.weight(1f))
                    DropdownField(
                        strings["label.triggerFilter"],
                        triggerFilterLabel(strings, filter),
                        TriggerSettings.TriggerFilter.values().map { triggerFilterLabel(strings, it) }
                    ) { label ->
                        filter = triggerFilterFromLabel(strings, label)
                        hasChanges = true
                    }
                }
                ToggleField(strings["label.primaryPhaseHigh"], phaseHigh) {
                    phaseHigh = it
                    hasChanges = true
                }
                ToggleField(strings["label.resyncEveryCycle"], reSyncEveryCycle) {
                    reSyncEveryCycle = it
                    hasChanges = true
                }
            }
        }
    }
}

@Composable
private fun TriggerVisualizationCardDesktop(
    totalTeeth: Int,
    missingTeeth: Int,
    triggerAngleDeg: Int,
    triggerAngleMultiplier: Int,
    triggerEdge: TriggerSettings.SignalEdge,
    primaryTriggerSpeed: TriggerSettings.TriggerSpeed,
    isMissingToothPattern: Boolean
) {
    val strings = LocalStrings.current
    val preview = remember(
        totalTeeth,
        missingTeeth,
        triggerAngleDeg,
        triggerAngleMultiplier,
        triggerEdge,
        primaryTriggerSpeed
    ) {
        TriggerPreviewModelDesktop.create(
            totalTeeth = totalTeeth,
            missingTeeth = missingTeeth,
            triggerAngleDeg = triggerAngleDeg,
            triggerAngleMultiplier = triggerAngleMultiplier,
            triggerEdge = triggerEdge,
            primaryTriggerSpeed = primaryTriggerSpeed
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(strings["label.triggerVisualPreview"], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!isMissingToothPattern) {
                Text(strings["label.triggerVisualApproximation"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (preview == null) {
                Text(strings["label.triggerVisualInvalidInput"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                return@Column
            }

            Text(strings["label.triggerWheelPreview"], style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            TriggerWheelCanvasDesktop(preview)
            Text(strings["label.triggerSignalPreview"], style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            TriggerSignalCanvasDesktop(preview)

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                LegendItemDesktop(MaterialTheme.colorScheme.primary, strings["label.syncMarker"])
                LegendItemDesktop(MaterialTheme.colorScheme.tertiary, strings["label.tdcMarker"])
                LegendItemDesktop(MaterialTheme.colorScheme.error, strings["label.missingWindow"])
            }
            Text(strings.format("label.syncToothFormat", preview.syncToothSlot + 1), style = MaterialTheme.typography.bodySmall)
            Text(strings.format("label.effectiveTriggerAngleFormat", preview.effectiveTriggerAngleDeg), style = MaterialTheme.typography.bodySmall)
            Text(strings.format("label.cycleScopeFormat", preview.cycleDegrees), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TriggerWheelCanvasDesktop(preview: TriggerPreviewModelDesktop) {
    val ringColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val toothColor = MaterialTheme.colorScheme.onSurface
    val syncColor = MaterialTheme.colorScheme.primary
    val tdcColor = MaterialTheme.colorScheme.tertiary
    val missingColor = MaterialTheme.colorScheme.error

    Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.29f
        val toothInnerRadius = radius * 1.04f
        val toothOuterRadius = radius * 1.27f
        val markerOuterRadius = toothOuterRadius * 1.1f
        val toothStroke = (360f / preview.totalTeeth).coerceIn(1.2f, 8f)

        drawCircle(color = ringColor, radius = radius, center = center, style = Stroke(width = 6.dp.toPx()))

        if (preview.missingTeeth > 0) {
            val gapSweep = preview.slotAngleDeg * preview.missingTeeth
            val arcRadius = toothOuterRadius * 1.01f
            drawArc(
                color = missingColor.copy(alpha = 0.28f),
                startAngle = -90f,
                sweepAngle = gapSweep,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = androidx.compose.ui.geometry.Size(arcRadius * 2f, arcRadius * 2f),
                style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        for (slot in 0 until preview.totalTeeth) {
            if (slot < preview.missingTeeth) continue
            val angle = -90f + (slot * preview.slotAngleDeg)
            val start = polarDesktop(center, toothInnerRadius, angle)
            val end = polarDesktop(center, toothOuterRadius, angle)
            val tint = if (slot == preview.syncToothSlot) syncColor else toothColor
            drawLine(color = tint, start = start, end = end, strokeWidth = toothStroke, cap = StrokeCap.Round)
        }

        val syncAngle = -90f + (preview.syncToothSlot * preview.slotAngleDeg)
        val tdcAngle = -90f + (preview.tdcSlotPosition * preview.slotAngleDeg)
        drawMarkerDesktop(center, radius * 0.7f, markerOuterRadius, syncAngle, syncColor, 4f)
        drawMarkerDesktop(center, radius * 0.5f, markerOuterRadius * 0.95f, tdcAngle, tdcColor, 3f)
    }
}

@Composable
private fun TriggerSignalCanvasDesktop(preview: TriggerPreviewModelDesktop) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    val syncColor = MaterialTheme.colorScheme.primary
    val tdcColor = MaterialTheme.colorScheme.tertiary
    val signalColor = MaterialTheme.colorScheme.onSurface
    val missingColor = MaterialTheme.colorScheme.error

    Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
        val left = 14.dp.toPx()
        val right = size.width - 14.dp.toPx()
        val top = 18.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val contentWidth = right - left
        val toothWidth = contentWidth / preview.totalTeeth
        val pulseLeadRatio = 0.12f
        val pulseDutyRatio = 0.44f
        val centerY = (top + bottom) / 2f
        val halfAmplitude = (bottom - top) * 0.25f
        val baselineY = if (preview.triggerEdge == TriggerSettings.SignalEdge.RISING) centerY + halfAmplitude else centerY - halfAmplitude
        val pulseY = if (preview.triggerEdge == TriggerSettings.SignalEdge.RISING) centerY - halfAmplitude else centerY + halfAmplitude

        drawRect(
            color = missingColor.copy(alpha = 0.11f),
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(toothWidth * preview.missingTeeth, bottom - top)
        )
        drawLine(color = axisColor, start = Offset(left, top), end = Offset(left, bottom), strokeWidth = 1.5f)
        drawLine(color = axisColor, start = Offset(left, baselineY), end = Offset(right, baselineY), strokeWidth = 1.5f)

        val path = Path().apply { moveTo(left, baselineY) }
        for (slot in 0 until preview.totalTeeth) {
            val x0 = left + (slot * toothWidth)
            val x1 = x0 + toothWidth
            val hasTooth = slot >= preview.missingTeeth
            val pulseStart = x0 + (toothWidth * pulseLeadRatio)
            val pulseEnd = pulseStart + (toothWidth * pulseDutyRatio)
            if (!hasTooth) {
                path.lineTo(x1, baselineY)
                continue
            }
            path.lineTo(pulseStart, baselineY)
            path.lineTo(pulseStart, pulseY)
            path.lineTo(pulseEnd, pulseY)
            path.lineTo(pulseEnd, baselineY)
            path.lineTo(x1, baselineY)
        }
        drawPath(path = path, color = signalColor, style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        val syncSlotStart = left + (preview.syncToothSlot * toothWidth)
        val syncPulseStart = syncSlotStart + (toothWidth * pulseLeadRatio)
        val syncPulseEnd = syncPulseStart + (toothWidth * pulseDutyRatio)
        val syncX = if (preview.triggerEdge == TriggerSettings.SignalEdge.RISING) syncPulseStart else syncPulseEnd
        val tdcX = left + (preview.tdcSlotPosition / preview.totalTeeth) * contentWidth

        drawLine(color = syncColor, start = Offset(syncX, top), end = Offset(syncX, bottom), strokeWidth = 3f)
        drawLine(color = tdcColor, start = Offset(tdcX, top), end = Offset(tdcX, bottom), strokeWidth = 2.4f)
    }
}

@Composable
private fun LegendItemDesktop(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(color = color, shape = MaterialTheme.shapes.extraSmall))
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

private data class TriggerPreviewModelDesktop(
    val totalTeeth: Int,
    val missingTeeth: Int,
    val syncToothSlot: Int,
    val tdcSlotPosition: Float,
    val triggerEdge: TriggerSettings.SignalEdge,
    val slotAngleDeg: Float,
    val effectiveTriggerAngleDeg: Int,
    val cycleDegrees: Float
) {
    companion object {
        fun create(
            totalTeeth: Int,
            missingTeeth: Int,
            triggerAngleDeg: Int,
            triggerAngleMultiplier: Int,
            triggerEdge: TriggerSettings.SignalEdge,
            primaryTriggerSpeed: TriggerSettings.TriggerSpeed
        ): TriggerPreviewModelDesktop? {
            if (totalTeeth < 2) return null
            val normalizedTotal = totalTeeth.coerceAtMost(255)
            val normalizedMissing = missingTeeth.coerceIn(0, normalizedTotal - 1)
            val syncToothSlot = normalizedMissing % normalizedTotal
            val cycleDegrees = if (primaryTriggerSpeed == TriggerSettings.TriggerSpeed.CAM) 720f else 360f
            val degreesPerSlot = cycleDegrees / normalizedTotal
            val effectiveAngle = triggerAngleDeg * triggerAngleMultiplier
            val tdcSlotPosition = wrapSlotDesktop(syncToothSlot - (effectiveAngle / degreesPerSlot), normalizedTotal.toFloat())
            return TriggerPreviewModelDesktop(
                totalTeeth = normalizedTotal,
                missingTeeth = normalizedMissing,
                syncToothSlot = syncToothSlot,
                tdcSlotPosition = tdcSlotPosition,
                triggerEdge = triggerEdge,
                slotAngleDeg = 360f / normalizedTotal,
                effectiveTriggerAngleDeg = effectiveAngle,
                cycleDegrees = cycleDegrees
            )
        }
    }
}

private fun wrapSlotDesktop(value: Float, total: Float): Float {
    val wrapped = value % total
    return if (wrapped < 0f) wrapped + total else wrapped
}

private fun polarDesktop(center: Offset, radius: Float, angleDeg: Float): Offset {
    val radians = (angleDeg * (kotlin.math.PI / 180f)).toFloat()
    return Offset(
        x = center.x + kotlin.math.cos(radians) * radius,
        y = center.y + kotlin.math.sin(radians) * radius
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarkerDesktop(
    center: Offset,
    radiusStart: Float,
    radiusEnd: Float,
    angleDeg: Float,
    color: Color,
    strokeWidth: Float
) {
    val start = polarDesktop(center, radiusStart, angleDeg)
    val end = polarDesktop(center, radiusEnd, angleDeg)
    drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
}

@Composable
internal fun SensorsConfigScreenDesktop(controller: DesktopSpeeduinoController) {
    SensorsCalibrationScreenDesktop(controller)
}

@Composable
internal fun EngineProtectionScreenDesktop() {
    val strings = LocalStrings.current
    var protectionCut by remember { mutableStateOf(ProtectionCutOption.BOTH) }
    var engineProtectionRpmMin by remember { mutableStateOf("1500") }
    var cutMethod by remember { mutableStateOf(CutMethodOption.FULL) }
    var engineProtectEnabled by remember { mutableStateOf(false) }
    var revLimiterEnabled by remember { mutableStateOf(false) }
    var boostLimitEnabled by remember { mutableStateOf(false) }
    var oilPressureProtectEnabled by remember { mutableStateOf(false) }
    var afrProtectEnabled by remember { mutableStateOf(false) }
    var coolantProtectEnabled by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }
    ReportUnsavedChanges(hasChanges)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = strings["label.engineProtectionTitle"],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = strings["label.engineProtectionSubtitle"],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ProtectionSectionHeader(strings["label.mainSettings"])
                DropdownField(
                    label = strings["label.engineProtectionCut"],
                    value = protectionCut.label(strings),
                    options = ProtectionCutOption.values().map { it.label(strings) }
                ) { value ->
                    protectionCut = ProtectionCutOption.values().first { it.label(strings) == value }
                    hasChanges = true
                }
                NumberField(
                    label = strings["label.engineProtectionRpmMin"],
                    value = engineProtectionRpmMin,
                    onValueChange = {
                        engineProtectionRpmMin = it
                        hasChanges = true
                    }
                )
                DropdownField(
                    label = strings["label.cutMethod"],
                    value = cutMethod.label(strings),
                    options = CutMethodOption.values().map { it.label(strings) }
                ) { value ->
                    cutMethod = CutMethodOption.values().first { it.label(strings) == value }
                    hasChanges = true
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ProtectionSectionHeader(strings["label.activeProtections"])
                ToggleField(strings["label.engineProtect"], engineProtectEnabled) {
                    engineProtectEnabled = it
                    hasChanges = true
                }
                ToggleField(strings["label.revLimiter"], revLimiterEnabled) {
                    revLimiterEnabled = it
                    hasChanges = true
                }
                ToggleField(strings["label.boostLimit"], boostLimitEnabled) {
                    boostLimitEnabled = it
                    hasChanges = true
                }
                ToggleField(strings["label.oilPressureProtect"], oilPressureProtectEnabled) {
                    oilPressureProtectEnabled = it
                    hasChanges = true
                }
                ToggleField(strings["label.afrProtect"], afrProtectEnabled) {
                    afrProtectEnabled = it
                    hasChanges = true
                }
                ToggleField(strings["label.coolantProtect"], coolantProtectEnabled) {
                    coolantProtectEnabled = it
                    hasChanges = true
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { hasChanges = false },
                enabled = hasChanges
            ) { Text(strings["action.save"]) }
            FilledTonalButton(onClick = { hasChanges = false }) { Text(strings["action.loadEcu"]) }
        }
    }
}

private enum class ProtectionCutOption {
    FUEL,
    IGNITION,
    BOTH;

    fun label(strings: com.speeduino.manager.desktop.Strings): String {
        return when (this) {
            FUEL -> strings["label.cutFuel"]
            IGNITION -> strings["label.cutIgnition"]
            BOTH -> strings["label.cutBoth"]
        }
    }
}

private enum class CutMethodOption {
    FULL,
    PROGRESSIVE;

    fun label(strings: com.speeduino.manager.desktop.Strings): String {
        return when (this) {
            FULL -> strings["label.cutFull"]
            PROGRESSIVE -> strings["label.cutProgressive"]
        }
    }
}

@Composable
private fun ProtectionSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

private fun triggerPatternOptions(strings: com.speeduino.manager.desktop.Strings): List<String> {
    return listOf(
        strings["label.patternMissingTooth"],
        strings["label.patternCamSync"],
        strings["label.patternWheel"],
        strings["label.patternDistributor"],
        strings["label.patternOddFire"],
        strings["label.patternCustom"]
    )
}

private fun triggerPatternLabel(strings: com.speeduino.manager.desktop.Strings, value: Int): String {
    return triggerPatternOptions(strings).getOrNull(value) ?: strings.format("label.pattern", value)
}

private fun triggerPatternFromLabel(strings: com.speeduino.manager.desktop.Strings, label: String): Int {
    val index = triggerPatternOptions(strings).indexOf(label)
    return if (index >= 0) index else 0
}

private fun secondaryPatternOptions(strings: com.speeduino.manager.desktop.Strings): List<String> {
    return listOf(
        strings["label.secondaryPatternNone"],
        strings["label.secondaryPatternCam"],
        strings["label.secondaryPatternDistributor"],
        strings["label.secondaryPatternHall"],
        strings["label.secondaryPatternCrank"],
        strings["label.secondaryPatternCustom"]
    )
}

private fun secondaryPatternLabel(strings: com.speeduino.manager.desktop.Strings, value: Int): String {
    return secondaryPatternOptions(strings).getOrNull(value) ?: strings.format("label.typeWithValue", value)
}

private fun secondaryPatternFromLabel(strings: com.speeduino.manager.desktop.Strings, label: String): Int {
    val index = secondaryPatternOptions(strings).indexOf(label)
    return if (index >= 0) index else 0
}

private fun triggerSpeedLabel(strings: com.speeduino.manager.desktop.Strings, value: TriggerSettings.TriggerSpeed): String {
    return when (value) {
        TriggerSettings.TriggerSpeed.CRANK -> strings["label.speedCrank"]
        TriggerSettings.TriggerSpeed.CAM -> strings["label.speedCam"]
    }
}

private fun triggerSpeedFromLabel(strings: com.speeduino.manager.desktop.Strings, label: String): TriggerSettings.TriggerSpeed {
    return TriggerSettings.TriggerSpeed.values().firstOrNull { triggerSpeedLabel(strings, it) == label } ?: TriggerSettings.TriggerSpeed.CRANK
}

private fun signalEdgeLabel(strings: com.speeduino.manager.desktop.Strings, value: TriggerSettings.SignalEdge): String {
    return when (value) {
        TriggerSettings.SignalEdge.RISING -> strings["label.edgeRising"]
        TriggerSettings.SignalEdge.FALLING -> strings["label.edgeFalling"]
    }
}

private fun signalEdgeFromLabel(strings: com.speeduino.manager.desktop.Strings, label: String): TriggerSettings.SignalEdge {
    return TriggerSettings.SignalEdge.values().firstOrNull { signalEdgeLabel(strings, it) == label } ?: TriggerSettings.SignalEdge.RISING
}

private fun triggerFilterLabel(strings: com.speeduino.manager.desktop.Strings, value: TriggerSettings.TriggerFilter): String {
    return when (value) {
        TriggerSettings.TriggerFilter.OFF -> strings["label.off"]
        TriggerSettings.TriggerFilter.WEAK -> strings["label.filterWeak"]
        TriggerSettings.TriggerFilter.MEDIUM -> strings["label.filterMedium"]
        TriggerSettings.TriggerFilter.AGGRESSIVE -> strings["label.filterAggressive"]
    }
}

private fun triggerFilterFromLabel(strings: com.speeduino.manager.desktop.Strings, label: String): TriggerSettings.TriggerFilter {
    return TriggerSettings.TriggerFilter.values().firstOrNull { triggerFilterLabel(strings, it) == label } ?: TriggerSettings.TriggerFilter.OFF
}
