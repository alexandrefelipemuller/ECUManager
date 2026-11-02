package com.speeduino.manager.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ecucore.model.RusefiInputOutputSnapshot
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.SecondarySerialProtocol
import io.ecucore.tuning.CellRef
import com.speeduino.manager.desktop.LocalStrings
import io.ecucore.tuning.TuningStrategy
import com.speeduino.manager.desktop.ui.DropdownField
import com.speeduino.manager.desktop.ui.InfoRow
import com.speeduino.manager.desktop.ui.KioskFeatureCard
import com.speeduino.manager.desktop.ui.KioskPanelCard
import com.speeduino.manager.desktop.ui.KioskScreenScaffold
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.abs

@Composable
internal fun EcuHubScreenDesktop(
    onOpenFuel: () -> Unit,
    onOpenIgnition: () -> Unit,
    onOpenEngineSetup: () -> Unit,
    onOpenEngineOperation: () -> Unit,
) {
    val strings = LocalStrings.current
    KioskScreenScaffold(
        title = strings["route.ecu"],
    ) {
        KioskPanelCard(strings["label.fuelIgnitionSection"]) {
            KioskFeatureCard(
                strings["route.fuel"],
                strings["label.mapsTablesSubtitle"],
                icon = Icons.Default.TableChart,
                large = true,
                onClick = onOpenFuel
            )
            KioskFeatureCard(
                strings["route.ignition"],
                strings["label.ignitionHubSubtitle"],
                icon = Icons.Default.TableChart,
                large = true,
                onClick = onOpenIgnition
            )
        }
        KioskPanelCard(strings["label.engineSetupSection"]) {
            KioskFeatureCard(
                strings["route.engineSetup"],
                strings["label.configsTuningSubtitle"],
                icon = Icons.Default.Settings,
                large = true,
                onClick = onOpenEngineSetup
            )
            KioskFeatureCard(
                strings["route.engineOperation"],
                strings["label.engineOperationSubtitle"],
                icon = Icons.Default.Settings,
                large = true,
                onClick = onOpenEngineOperation
            )
        }
    }
}

@Composable
internal fun MapsTablesScreenDesktop(
    controller: DesktopSpeeduinoController,
    onOpenVeTable: () -> Unit,
    onOpenVeTable2: () -> Unit,
    onOpenAfrTable: () -> Unit,
    onOpenInjectorConfig: () -> Unit,
) {
    val strings = LocalStrings.current
    val configState by controller.configState.collectAsState()
    KioskScreenScaffold(
        title = strings["route.mapsTables"],
        subtitle = strings["label.mapsTablesSubtitle"],
    ) {
        KioskPanelCard(strings["label.fuelSection"]) {
            KioskFeatureCard(strings["label.injectorConfigTitle"], strings["label.injectorConfigSubtitle"], onClick = onOpenInjectorConfig)
            KioskFeatureCard(strings["label.veTable1"], strings["label.veTableDesc"], onClick = onOpenVeTable)
            KioskFeatureCard(strings["label.veTable2"], strings["label.veTableDesc"], onClick = onOpenVeTable2)
            KioskFeatureCard(strings["route.afrTable"], strings["label.afrTableDesc"], onClick = onOpenAfrTable)
        }
        KioskPanelCard(strings["label.workflowSection"]) {
            KioskFeatureCard(
                strings["home.openBackupSettings"],
                strings["maps_tables_backup_action_desc"],
                onClick = {
                    val source = chooseOpenFile(strings["label.backupOpenTitle"])
                    if (source != null) {
                        controller.importConfigAndRestore(source)
                    }
                },
            )
        }
    }
}

@Composable
internal fun IgnitionScreenDesktop(
    onOpenIgnitionConfig: () -> Unit,
    onOpenIgnitionTable: () -> Unit,
    onOpenIgnitionTable2: () -> Unit,
    onOpenDwellTable: () -> Unit,
    onOpenTriggerSettings: () -> Unit
) {
    val strings = LocalStrings.current
    KioskScreenScaffold(
        title = strings["route.ignition"],
        subtitle = strings["label.ignitionHubSubtitle"],
    ) {
        KioskFeatureCard(strings["label.ignitionConfigTitle"], strings["label.ignitionConfigSubtitle"], onClick = onOpenIgnitionConfig)
        KioskFeatureCard(strings["label.ignitionTable1"], strings["label.ignitionTableDesc"], onClick = onOpenIgnitionTable)
        KioskFeatureCard(strings["label.ignitionTable2"], strings["label.ignitionTableDesc"], onClick = onOpenIgnitionTable2)
        KioskFeatureCard(strings["route.dwellTable"], strings["label.dwellTableDesc"], onClick = onOpenDwellTable)
        KioskFeatureCard(strings["route.triggerSettings"], strings["label.triggerSettingsDesc"], onClick = onOpenTriggerSettings)
    }
}

@Composable
internal fun ConfigsTuningScreenDesktop(
    onOpenEngineConstants: () -> Unit,
    onOpenInputOutput: () -> Unit,
    onOpenSensorCalibration: () -> Unit,
    onOpenInjectorConfig: () -> Unit,
    onOpenSecondarySerial: () -> Unit,
    onOpenIgnitionConfig: () -> Unit,
    controller: DesktopSpeeduinoController? = null,
    onOpenRoute: (com.speeduino.manager.desktop.navigation.DesktopRoute) -> Unit = {},
) {
    val strings = LocalStrings.current
    val firmware = controller?.firmwareInfo?.collectAsState()?.value
    val isMs2 = firmware?.family == io.ecucore.model.EcuFamily.MS2
    KioskScreenScaffold(
        title = strings["route.configsTuning"],
        subtitle = strings["label.configsTuningSubtitle"],
    ) {
        KioskPanelCard(strings["label.coreSetupSection"]) {
            KioskFeatureCard(strings["label.engineConstantsTitle"], strings["label.engineConstantsSubtitle"], onClick = onOpenEngineConstants)
            KioskFeatureCard(strings["label.injectorConfigTitle"], strings["label.injectorConfigSubtitle"], onClick = onOpenInjectorConfig)
            KioskFeatureCard(strings["label.ignitionConfigTitle"], strings["label.ignitionConfigSubtitle"], onClick = onOpenIgnitionConfig)
            if (isMs2) {
                KioskFeatureCard(strings["ms2_wue_title"], strings["ms2_wue_card_desc"], onClick = { onOpenRoute(com.speeduino.manager.desktop.navigation.DesktopRoute.Ms2Wue) })
                KioskFeatureCard(strings["ms2_idle_title"], strings["ms2_idle_card_desc"], onClick = { onOpenRoute(com.speeduino.manager.desktop.navigation.DesktopRoute.Ms2Idle) })
            }
        }
        KioskPanelCard(strings["label.hardwareSection"]) {
            KioskFeatureCard(strings["label.inputOutputTitle"], strings["label.inputOutputDesc"], onClick = onOpenInputOutput)
            KioskFeatureCard(strings["route.sensorsConfig"], strings["label.sensorsCalibrationSubtitle"], onClick = onOpenSensorCalibration)
            KioskFeatureCard(strings["label.secondarySerialTitle"], strings["label.secondarySerialSubtitle"], onClick = onOpenSecondarySerial)
        }
    }
}

@Composable
internal fun EngineOperationScreenDesktop(
    onOpenIdleControl: () -> Unit,
    onOpenClosedLoopCorrections: () -> Unit,
    onOpenEngineProtection: () -> Unit,
    onOpenRevLimiter: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    KioskScreenScaffold(
        title = strings["route.engineOperation"],
        subtitle = strings["label.engineOperationSubtitle"],
    ) {
        KioskFeatureCard(strings["route.idleControl"], strings["label.idleControlSubtitle"], onClick = onOpenIdleControl)
        KioskFeatureCard(strings["route.closedLoopCorrections"], strings["label.closedLoopSubtitle"], onClick = onOpenClosedLoopCorrections)
        KioskFeatureCard(strings["route.engineProtection"], strings["label.engineProtectionSubtitle"], onClick = onOpenEngineProtection)
        onOpenRevLimiter?.let { KioskFeatureCard(strings["config_rev_limiter_title"], strings["config_rev_limiter_desc"], onClick = it) }
    }
}

@Composable
internal fun InjectorConfigScreenDesktop(controller: DesktopSpeeduinoController) {
    com.speeduino.manager.desktop.feature.configs.InjectorConfigScreenDesktop(controller)
}

@Composable
internal fun InputOutputConfigScreenDesktop(
    controller: DesktopSpeeduinoController,
    onOpenSecondarySerial: () -> Unit
) {
    val strings = LocalStrings.current
    val tuningState by controller.tuningConfigState.collectAsState()
    val snapshot = tuningState.rusefiSnapshot
    KioskScreenScaffold(
        title = strings["label.inputOutputTitle"],
        subtitle = strings["label.ioLoadInstructions"],
    ) {
        ActionCard(strings["label.secondarySerialTitle"], strings["label.secondarySerialTitle"]) {
            RowButtons(onOpenSecondarySerial, null, strings["action.open"], null)
        }
        ActionCard(strings["label.rusefiSnapshotAction"], strings["label.ioLoadInstructions"]) {
            RowButtons({ controller.loadRusefiInputOutputSnapshot() }, null, strings["action.loadEcu"], null)
        }
        if (snapshot != null) {
            SnapshotGroup(strings["label.rusefiInputs"], snapshot.inputs)
            SnapshotGroup(strings["label.rusefiFuelOutputs"], snapshot.fuelOutputs)
            SnapshotGroup(strings["label.rusefiIgnitionOutputs"], snapshot.ignitionOutputs)
            SnapshotGroup(strings["label.rusefiAuxOutputs"], snapshot.auxiliaryOutputs)
        } else {
            PlaceholderScreen(strings["label.rusefiSnapshotAction"], strings["label.ioLoadInstructions"])
        }
    }
}

@Composable
internal fun SecondarySerialScreenDesktop(controller: DesktopSpeeduinoController) {
    val strings = LocalStrings.current
    val tuningState by controller.tuningConfigState.collectAsState()
    val config = tuningState.secondarySerialConfig ?: SecondarySerialConfig(
        enabled = false,
        protocol = SecondarySerialProtocol.TUNERSTUDIO,
        protocolRaw = SecondarySerialProtocol.TUNERSTUDIO.rawValue
    )
    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var protocol by remember(config) { mutableStateOf(config.protocol) }
    var protocolRaw by remember(config) { mutableStateOf(config.protocolRaw.toString()) }
    var hasChanges by remember { mutableStateOf(false) }
    ReportUnsavedChanges(hasChanges)

    KioskScreenScaffold(
        title = strings["label.secondarySerialTitle"],
        subtitle = strings["label.secondarySerialTitle"],
    ) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings["label.enabled"], style = MaterialTheme.typography.bodyMedium)
                        Text(strings["label.secondarySerialHelp"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        hasChanges = true
                    })
                }
                DropdownField(
                    label = strings["label.protocol"],
                    value = protocol.displayName(),
                    options = SecondarySerialProtocol.values().filter { it != SecondarySerialProtocol.UNKNOWN }.map { it.displayName() }
                ) { value ->
                    protocol = SecondarySerialProtocol.values().first { it.displayName() == value }
                    protocolRaw = protocol.rawValue.toString()
                    hasChanges = true
                }
                OutlinedTextField(
                    value = protocolRaw,
                    onValueChange = {
                        protocolRaw = it.filter(Char::isDigit)
                        protocol = SecondarySerialProtocol.UNKNOWN
                        hasChanges = true
                    },
                    label = { Text(strings["label.secondarySerialRawProtocol"]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = controller::loadSecondarySerialConfig) { Text(strings["action.reload"]) }
                    FilledTonalButton(
                        onClick = {
                            controller.saveSecondarySerialConfig(
                                SecondarySerialConfig(
                                    enabled = enabled,
                                    protocol = protocol,
                                    protocolRaw = protocolRaw.toIntOrNull() ?: protocol.rawValue
                                )
                            )
                            hasChanges = false
                        },
                        enabled = hasChanges
                    ) { Text(strings["action.saveEcu"]) }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun RowButtons(
    primary: () -> Unit,
    secondary: (() -> Unit)?,
    primaryLabel: String,
    secondaryLabel: String?
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = primary) { Text(primaryLabel) }
        if (secondary != null && secondaryLabel != null) {
            FilledTonalButton(onClick = secondary) { Text(secondaryLabel) }
        }
    }
}

@Composable
private fun SnapshotGroup(title: String, items: List<io.ecucore.model.RusefiIoEntry>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            items.forEach { item ->
                InfoRow(item.label, item.value)
            }
        }
    }
}

private fun SecondarySerialProtocol.displayName(): String {
    return when (this) {
        SecondarySerialProtocol.TUNERSTUDIO -> "TunerStudio"
        SecondarySerialProtocol.REALDASH -> "RealDash"
        SecondarySerialProtocol.MSDROID -> "MSDroid"
        SecondarySerialProtocol.CAN -> "CAN"
        SecondarySerialProtocol.GENERIC_FIXED -> "Generic (Fixed List)"
        SecondarySerialProtocol.GENERIC_INI -> "Generic (ini File)"
        SecondarySerialProtocol.UNKNOWN -> "Unknown"
    }
}

@Composable
private fun SignalReadinessCard(signalStatus: io.ecucore.tuning.AnalyzerSignalStatus, afrAvailable: Boolean) {
    val strings = LocalStrings.current
    val rows = listOf(
        "RPM" to signalStatus.hasRpm,
        "Load" to signalStatus.hasLoad,
        "AFR" to signalStatus.hasAfr,
        "AFR Target" to (signalStatus.hasAfrTarget && afrAvailable)
    )
    ActionCard(
        title = strings["label.tuningAssistantSignalsTitle"],
        description = strings["label.tuningAssistantSignalsDesc"]
    ) {
        rows.forEach { (label, ok) ->
            InfoRow(label, if (ok) "OK" else "Missing")
        }
    }
}

@Composable
private fun HeatmapCard(
    result: io.ecucore.tuning.AnalyzerResult,
    highlightedCells: Set<CellRef>,
    selectedCell: CellRef?,
    onCellSelected: (CellRef) -> Unit
) {
    val strings = LocalStrings.current
    val maxChangePct = when {
        result.clusters.isNotEmpty() -> result.clusters.maxOfOrNull { abs(it.avgDeltaPct) }?.coerceAtLeast(0.01) ?: 0.08
        else -> 0.08
    }
    ActionCard(
        title = strings["label.tuningAssistantHeatmapTitle"],
        description = strings["label.tuningAssistantHeatmapDesc"]
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            result.cellSuggestions.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { colIndex, cell ->
                        val ref = CellRef(rowIndex, colIndex)
                        val color = when {
                            cell == null -> Color(0xFFE5E7EB)
                            cell.deltaPct >= 0 -> heatColor(Color(0xFFDC2626), abs(cell.deltaPct) / maxChangePct)
                            else -> heatColor(Color(0xFF2563EB), abs(cell.deltaPct) / maxChangePct)
                        }
                        val borderColor = when {
                            selectedCell == ref -> MaterialTheme.colorScheme.primary
                            highlightedCells.contains(ref) -> MaterialTheme.colorScheme.secondary
                            else -> Color.Transparent
                        }
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .background(color, RoundedCornerShape(3.dp))
                                .clickable { onCellSelected(ref) }
                                .padding(0.dp)
                                .border(1.dp, borderColor, RoundedCornerShape(3.dp))
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
        selectedCell?.let {
            val suggestion = result.cellSuggestions.getOrNull(it.row)?.getOrNull(it.col)
            if (suggestion != null) {
                HorizontalDivider()
                InfoRow(strings["label.cell"], strings.format("label.cellCoords", suggestion.row, suggestion.col))
                InfoRow(strings["label.delta"], strings.format("label.deltaPct", suggestion.deltaPct * 100.0))
                InfoRow(strings["label.hits"], suggestion.hitCount.toString())
                InfoRow(strings["label.afrPair"], strings.format("label.afrPairValue", suggestion.meanAfrMeasured, suggestion.meanAfrTarget))
            }
        }
    }
}

@Composable
private fun ClusterSuggestionsCard(
    clusters: List<io.ecucore.tuning.SuggestionCluster>,
    includedClusterIds: Set<String>,
    onToggleInclude: (String) -> Unit,
    onPreview: (String) -> Unit
) {
    val strings = LocalStrings.current
    ActionCard(
        title = strings["label.tuningAssistantSuggestionsTitle"],
        description = if (clusters.isEmpty()) strings["label.noSuggestions"] else "Choose which suggestion clusters to apply."
    ) {
        if (clusters.isEmpty()) {
            Text(strings["label.noSuggestions"])
        } else {
            clusters.forEach { cluster ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (includedClusterIds.contains(cluster.id)) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(cluster.label, fontWeight = FontWeight.Medium)
                            Text(
                                text = cluster.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = strings.format("label.clusterSummary", cluster.avgDeltaPct * 100.0, cluster.avgHits, cluster.rpmRange.first, cluster.rpmRange.last),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        FilledTonalButton(onClick = { onPreview(cluster.id) }) {
                            Text(strings["label.preview"])
                        }
                        Switch(
                            checked = includedClusterIds.contains(cluster.id),
                            onCheckedChange = { onToggleInclude(cluster.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyCard(
    strategy: TuningStrategy,
    onChange: (TuningStrategy) -> Unit
) {
    val strings = LocalStrings.current
    ActionCard(
        title = strings["label.tuningAssistantStrategy"],
        description = strings["label.tuningAssistantStrategyDesc"]
    ) {
        DropdownField(
            label = strings["label.tuningAssistantStrategy"],
            value = strategy.displayName(strings),
            options = TuningStrategy.values().map { it.displayName(strings) }
        ) { value ->
            onChange(TuningStrategy.values().first { it.displayName(strings) == value })
        }
    }
}

@Composable
private fun ActionsCard(
    hasUndo: Boolean,
    isBusy: Boolean,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onReload: () -> Unit,
    onCompare: () -> Unit
) {
    val strings = LocalStrings.current
    ActionCard(
        title = strings["label.tuningAssistantActions"],
        description = strings["label.tuningAssistantActionsDesc"]
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onApply, enabled = !isBusy) { Text(strings["label.tuningAssistantApply"]) }
            FilledTonalButton(onClick = onUndo, enabled = hasUndo && !isBusy) { Text(strings["label.tuningAssistantUndo"]) }
            FilledTonalButton(onClick = onReload, enabled = !isBusy) { Text(strings["label.tuningAssistantReload"]) }
            FilledTonalButton(onClick = onCompare) { Text(strings["label.tuningAssistantCompareAfterApply"]) }
        }
    }
}

private fun TuningStrategy.displayName(strings: Strings): String {
    return when (this) {
        TuningStrategy.CONSERVATIVE -> strings["label.tuningAssistantConservative"]
        TuningStrategy.STANDARD -> strings["label.tuningAssistantStandard"]
        TuningStrategy.AGGRESSIVE -> strings["label.tuningAssistantAggressive"]
    }
}

private fun heatColor(base: Color, intensity: Double): Color {
    val alpha = intensity.coerceIn(0.2, 1.0).toFloat()
    return base.copy(alpha = alpha)
}

private fun chooseOpenFile(title: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isVisible = true
    val fileName = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return File(dir, fileName)
}


@Composable
private fun SavedLogsDialogDesktop(
    files: List<File>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val sortedFiles = remember(files) { files.sortedByDescending { it.lastModified() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Saved logs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sortedFiles.isEmpty()) {
                    Text("No saved logs found.")
                } else {
                    sortedFiles.forEach { file ->
                        Text(
                            text = file.name,
                            modifier = Modifier.clickable { onSelect(file.absolutePath) }
                        )
                    }
                }
            }
        }
    )
}

private fun collectSavedLogFiles(lastSavedLogPath: String?): List<File> {
    val parent = lastSavedLogPath?.let { File(it).parentFile } ?: return emptyList()
    if (!parent.exists()) return emptyList()
    return parent.listFiles { file -> file.isFile && file.name.endsWith(".csv", ignoreCase = true) }?.toList().orEmpty()
}
