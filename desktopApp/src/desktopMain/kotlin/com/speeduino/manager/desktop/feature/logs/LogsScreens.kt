package com.speeduino.manager.desktop.feature.logs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ecucore.EcuLiveData
import io.ecucore.compare.LogCompareResult
import io.ecucore.compare.LogHeatCellState
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.DesktopLogExportFormat
import com.speeduino.manager.desktop.DefaultSelectedLogSignals
import com.speeduino.manager.desktop.LocalStrings
import com.speeduino.manager.desktop.LogExportSignals
import com.speeduino.manager.desktop.ui.InfoRow
import com.speeduino.manager.desktop.ui.NumberField
import com.speeduino.manager.desktop.ui.ToggleField
import com.speeduino.manager.desktop.ui.chooseOpenFile
import io.ecucore.model.logging.LiveLogEntry
import io.ecucore.model.logging.LiveLogSnapshot
import io.ecucore.tuning.CellRef
import io.ecucore.tuning.CellSuggestion
import io.ecucore.tuning.TuningStrategy
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun RealTimeMonitorScreenDesktop(
    controller: DesktopSpeeduinoController,
    liveData: EcuLiveData?
) {
    val strings = LocalStrings.current
    val logState by controller.logState.collectAsState()
    val logSaveStatus by controller.logSaveStatus.collectAsState()
    val lastSavedLogPath by controller.lastSavedLogPath.collectAsState()
    val intervalMs by controller.streamIntervalMs.collectAsState()
    var selectedInterval by remember(intervalMs) { mutableStateOf(intervalMs.toString()) }
    var fileName by remember(strings) { mutableStateOf(strings["label.logFilenamePrefix"]) }
    var selectedSignals by remember { mutableStateOf(DefaultSelectedLogSignals) }
    var showSavedLogsDialog by remember { mutableStateOf(false) }
    val savedLogFiles = remember(lastSavedLogPath) { collectSavedLogFiles(lastSavedLogPath) }

    if (showSavedLogsDialog) {
        SavedLogsDialogDesktop(
            files = savedLogFiles,
            onDismiss = { showSavedLogsDialog = false },
            onSelect = { path ->
                showSavedLogsDialog = false
                controller.loadLogSnapshotFromCsv(path)
            }
        )
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    strings["label.monitorTitle"],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(strings["label.monitorSubtitle"], style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(strings["label.intervalMs"], selectedInterval, { value ->
                        selectedInterval = value
                        value.toLongOrNull()?.let(controller::updateStreamInterval)
                    }, Modifier.width(160.dp))
                    FilledTonalButton(
                        onClick = {
                            if (logState.isRecording) {
                                controller.stopLogCapture()
                            } else {
                                controller.startLogCapture(intervalMs)
                            }
                        }
                    ) {
                        Text(
                            if (logState.isRecording) {
                                strings["action.stopCapture"]
                            } else {
                                strings["action.startCapture"]
                            }
                        )
                    }
                }
                Text(
                    text = strings.format("label.captureCount", logState.samplesCaptured),
                    style = MaterialTheme.typography.bodyMedium
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(strings["label.currentStatus"], style = MaterialTheme.typography.titleMedium)
                InfoRow(strings["label.rpm"], liveData?.rpm?.toString() ?: strings["label.noData"])
                InfoRow(strings["label.map"], liveData?.mapPressure?.toString() ?: strings["label.noData"])
                InfoRow(strings["label.tps"], liveData?.tps?.toString() ?: strings["label.noData"])
                InfoRow(strings["label.coolant"], liveData?.coolantTemp?.toString() ?: strings["label.noData"])
                InfoRow(strings["label.iat"], liveData?.intakeTemp?.toString() ?: strings["label.noData"])
                InfoRow(
                    strings["label.battery"],
                    liveData?.batteryVoltage?.let { String.format(Locale.US, "%.1f", it) } ?: strings["label.noData"]
                )
            }
        }

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
                Text(strings["action.exportLog"], style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(strings["label.fileName"]) },
                    singleLine = true,
                    modifier = Modifier.width(280.dp)
                )
                Text(
                    text = strings["label.logSignalsSelect"],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LogExportSignals.forEach { signal ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = selectedSignals.contains(signal.key),
                                onCheckedChange = { checked ->
                                    selectedSignals = if (checked) {
                                        selectedSignals + signal.key
                                    } else {
                                        selectedSignals - signal.key
                                    }
                                }
                            )
                            Text(strings[signal.labelKey], style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = {
                            controller.saveLogSnapshot(
                                fileName = fileName,
                                selectedSignalKeys = selectedSignals,
                                exportFormat = DesktopLogExportFormat.CSV
                            )
                        }
                    ) {
                        Text(strings["action.saveCsv"])
                    }
                    FilledTonalButton(
                        onClick = {
                            controller.saveLogSnapshot(
                                fileName = fileName,
                                selectedSignalKeys = selectedSignals,
                                exportFormat = DesktopLogExportFormat.MSL
                            )
                        }
                    ) {
                        Text(strings["action.saveMsl"])
                    }
                }
                if (!logSaveStatus.isNullOrBlank()) {
                    Text(
                        text = logSaveStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (logSaveStatus!!.contains("Error", ignoreCase = true) || logSaveStatus!!.contains("erro", ignoreCase = true)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (!lastSavedLogPath.isNullOrBlank()) {
                    Text(
                        text = strings.format("label.lastSavedLogPath", lastSavedLogPath!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun LogViewerScreenDesktop(controller: DesktopSpeeduinoController) {
    val strings = LocalStrings.current
    val snapshot by controller.logSnapshot.collectAsState()
    val snapshotSourcePath by controller.logSnapshotSourcePath.collectAsState()
    val logViewerError by controller.logViewerError.collectAsState()
    val lastSavedLogPath by controller.lastSavedLogPath.collectAsState()
    val entries = snapshot?.entries.orEmpty()
    val parsedCsvLog = remember(snapshotSourcePath) {
        snapshotSourcePath?.let { path ->
            runCatching { parseDesktopCsvLog(path) }
        }
    }
    val csvLog = parsedCsvLog?.getOrNull()
    val effectiveLogViewerError = logViewerError ?: parsedCsvLog?.exceptionOrNull()?.message


    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    strings["label.snapshotTitle"],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (snapshotSourcePath.isNullOrBlank()) {
                        strings["label.snapshotSubtitle"]
                    } else {
                        strings["label.logViewerLoadedFileSubtitle"]
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!snapshotSourcePath.isNullOrBlank()) {
                    Text(
                        text = snapshotSourcePath!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                var showSavedLogsDialog by remember { mutableStateOf(false) }
                val savedLogFiles = remember(lastSavedLogPath) { collectSavedLogFiles(lastSavedLogPath) }
                if (showSavedLogsDialog) {
                    SavedLogsDialogDesktop(
                        files = savedLogFiles,
                        onDismiss = { showSavedLogsDialog = false },
                        onSelect = { path ->
                            showSavedLogsDialog = false
                            controller.loadLogSnapshotFromCsv(path)
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = controller::captureSnapshot) {
                        Text(strings["action.refreshSnapshot"])
                    }
                    FilledTonalButton(
                        onClick = {
                            chooseOpenFile(strings["label.logViewerOpenCsvTitle"])?.absolutePath?.let(controller::loadLogSnapshotFromCsv)
                        }
                    ) {
                        Text(strings["label.logViewerChooseCsv"])
                    }
                    OutlinedButton(onClick = { showSavedLogsDialog = true }, enabled = savedLogFiles.isNotEmpty()) {
                        Text(strings["label.logViewerSavedLogsTitle"])
                    }
                }
                effectiveLogViewerError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
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
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (snapshotSourcePath != null && csvLog != null) {
                    val orderedSignals = remember(csvLog) { sortSignalsByPriority(csvLog.series.map { it.name }) }
                    var selected by remember(csvLog) {
                        mutableStateOf(defaultSelectedSignals(orderedSignals).toSet())
                    }

                    LogViewerFiltersRow(
                        signals = orderedSignals,
                        signalColors = csvLog.series.associate { it.name to it.color },
                        selectedSignals = selected,
                        onSelectedSignalsChange = { selected = it },
                        onToggle = { name ->
                            selected = if (selected.contains(name)) selected - name else selected + name
                        }
                    )

                    LogViewerChart(
                        series = csvLog.series.filter { selected.contains(it.name) },
                        totalSamples = csvLog.sampleCount
                    )

                    LogMetadataSummary(
                        startedAtMs = csvLog.startedAtMs,
                        endedAtMs = csvLog.endedAtMs,
                        sampleCount = csvLog.sampleCount
                    )
                } else if (entries.isEmpty()) {
                    Text(strings["label.noLogCaptured"], style = MaterialTheme.typography.bodyMedium)
                } else {
                    val series = remember(entries, strings) { buildLogSeries(entries, strings) }
                    val orderedSignals = remember(series) { sortSignalsByPriority(series.map { it.name }) }
                    var selected by remember(series) {
                        mutableStateOf(defaultSelectedSignals(orderedSignals).toSet())
                    }

                    LogViewerFiltersRow(
                        signals = orderedSignals,
                        signalColors = series.associate { it.name to it.color },
                        selectedSignals = selected,
                        onSelectedSignalsChange = { selected = it },
                        onToggle = { name ->
                            selected = if (selected.contains(name)) {
                                selected - name
                            } else {
                                selected + name
                            }
                        }
                    )

                    LogViewerChart(
                        series = series.filter { selected.contains(it.name) },
                        totalSamples = entries.size
                    )

                    LogMetadataSummary(entries = entries)
                }
            }
        }
    }
}

@Composable
internal fun LogAnalyzerScreenDesktop(controller: DesktopSpeeduinoController) {
    val strings = LocalStrings.current
    val currentVe by controller.veTable.collectAsState()
    val currentAfr by controller.afrTable.collectAsState()
    val analyzerResult by controller.analyzerResult.collectAsState()
    val analyzerBusy by controller.analyzerBusy.collectAsState()
    val analyzerError by controller.analyzerError.collectAsState()
    val analyzerLogFile by controller.analyzerLogFile.collectAsState()
    var strategy by remember { mutableStateOf(TuningStrategy.CONSERVATIVE) }
    var selectedCell by remember(analyzerResult) { mutableStateOf<CellRef?>(null) }
    var selectedClusterId by remember(analyzerResult) { mutableStateOf<String?>(null) }
    var includedClusters by remember(analyzerResult) {
        mutableStateOf(analyzerResult?.clusters?.map { it.id }?.toSet().orEmpty())
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    strings["label.logAnalyzerTitle"],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(strings["label.logAnalyzerSubtitle"], style = MaterialTheme.typography.bodyMedium)
                Text(
                    analyzerLogFile ?: strings["label.logAnalyzerNoFile"],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = {
                            val file = chooseOpenFile(strings["label.logAnalyzerOpenTitle"])
                            controller.selectAnalyzerLogFile(file?.absolutePath)
                        }
                    ) { Text(strings["action.open"]) }
                    FilledTonalButton(
                        onClick = { controller.analyzeLogFile(strategy) },
                        enabled = !analyzerBusy && !analyzerLogFile.isNullOrBlank() && currentVe != null && currentAfr != null
                    ) { Text(strings["action.analyze"]) }
                    FilledTonalButton(
                        onClick = { controller.applyAnalyzerToVe(strategy, includedClusters) },
                        enabled = !analyzerBusy && analyzerResult != null && currentVe != null
                    ) { Text(strings["action.applyToVe"]) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings["label.strategy"], style = MaterialTheme.typography.bodyMedium)
                    StrategyChip(strings["label.tuningAssistantConservative"], strategy == TuningStrategy.CONSERVATIVE) {
                        strategy = TuningStrategy.CONSERVATIVE
                    }
                    StrategyChip(strings["label.tuningAssistantStandard"], strategy == TuningStrategy.STANDARD) {
                        strategy = TuningStrategy.STANDARD
                    }
                    StrategyChip(strings["label.tuningAssistantAggressive"], strategy == TuningStrategy.AGGRESSIVE) {
                        strategy = TuningStrategy.AGGRESSIVE
                    }
                }
                analyzerError?.let {
                    Text(
                        strings.format("label.errorWithValue", it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (analyzerBusy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            strings["label.analyzerLoading"],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        val signals = analyzerResult?.signalStatus
        if (signals != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(strings["label.analyzerSignals"], style = MaterialTheme.typography.titleMedium)
                    AnalyzerSignalRow(strings["label.rpm"], signals.hasRpm)
                    AnalyzerSignalRow(strings["label.load"], signals.hasLoad)
                    AnalyzerSignalRow(strings["label.afr"], signals.hasAfr)
                    AnalyzerSignalRow(strings["label.afrTarget"], signals.hasAfrTarget)
                }
            }
        }

        analyzerResult?.let { result ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(strings["label.logAnalyzerSummary"], style = MaterialTheme.typography.titleMedium)
                    Text(strings.format("label.samplesUsed", result.summary.usedSamples, result.summary.totalSamples))
                    Text(strings.format("label.clustersCount", result.clusters.size))
                    Text(strings.format("label.durationSeconds", result.summary.durationSeconds))
                    result.summary.rpmRange?.let {
                        Text(strings.format("label.rpmRange", it.first, it.last))
                    }
                    result.summary.loadRange?.let {
                        Text(strings.format("label.loadRange", it.first, it.last, result.summary.loadLabel))
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(strings["label.heatmapTitle"], style = MaterialTheme.typography.titleMedium)
                    val highlighted = result.clusters.firstOrNull { it.id == selectedClusterId }?.cells?.toSet().orEmpty()
                    AnalyzerHeatmapGrid(
                        suggestions = result.cellSuggestions,
                        maxChangePct = strategy.maxChangePct,
                        highlightedCells = highlighted,
                        onCellSelected = { selectedCell = it }
                    )
                    selectedCell?.let { cell ->
                        val suggestion = result.cellSuggestions.getOrNull(cell.row)?.getOrNull(cell.col)
                        if (suggestion != null) {
                            val deltaPct = (suggestion.deltaPct * 100).toInt()
                            Text(
                                strings.format(
                                    "label.heatmapCellInfo",
                                    result.suggestedVeTable.rpmBins[cell.col],
                                    result.suggestedVeTable.loadBins[cell.row],
                                    deltaPct,
                                    suggestion.hitCount
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(strings["label.clusterSuggestions"], style = MaterialTheme.typography.titleMedium)
                    if (result.clusters.isEmpty()) {
                        Text(strings["label.noSuggestions"], style = MaterialTheme.typography.bodySmall)
                    } else {
                        result.clusters.forEach { cluster ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(cluster.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(cluster.reason, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        strings.format("label.clusterDelta", (cluster.avgDeltaPct * 100).toInt(), cluster.avgHits),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        FilledTonalButton(
                                            onClick = {
                                                selectedClusterId = if (selectedClusterId == cluster.id) null else cluster.id
                                            }
                                        ) { Text(strings["label.preview"]) }
                                        Switch(
                                            checked = includedClusters.contains(cluster.id),
                                            onCheckedChange = {
                                                includedClusters = if (includedClusters.contains(cluster.id)) {
                                                    includedClusters - cluster.id
                                                } else {
                                                    includedClusters + cluster.id
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BeforeAfterScreenDesktop(controller: DesktopSpeeduinoController) {
    val strings = LocalStrings.current
    val beforePath by controller.beforeAfterBeforeLogPath.collectAsState()
    val afterPath by controller.beforeAfterAfterLogPath.collectAsState()
    val busy by controller.beforeAfterBusy.collectAsState()
    val error by controller.beforeAfterError.collectAsState()
    val result by controller.beforeAfterResult.collectAsState()
    var selectedCell by remember(result) { mutableStateOf<Pair<Int, Int>?>(null) }


    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                Text(strings["label.beforeAfterTitle"], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(strings["label.beforeAfterSubtitle"], style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = {
                            val file = chooseOpenFile(strings["label.beforeAfterPickBefore"])
                            controller.setBeforeAfterBeforeLogPath(file?.absolutePath)
                        }
                    ) { Text(strings["label.beforeAfterBefore"]) }
                    Text(beforePath ?: strings["label.logAnalyzerNoFile"], style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = {
                            val file = chooseOpenFile(strings["label.beforeAfterPickAfter"])
                            controller.setBeforeAfterAfterLogPath(file?.absolutePath)
                        }
                    ) { Text(strings["label.beforeAfterAfter"]) }
                    Text(afterPath ?: strings["label.logAnalyzerNoFile"], style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(
                    onClick = { controller.compareBeforeAfterLogs() },
                    enabled = !busy && !beforePath.isNullOrBlank() && !afterPath.isNullOrBlank()
                ) { Text(strings["action.compare"]) }
                error?.let {
                    Text(strings.format("label.errorWithValue", it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(strings["label.analyzerLoading"], style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        result?.let { compare ->
            BeforeAfterSummary(strings, compare)
            BeforeAfterHeatmap(compare, selectedCell) { row, col -> selectedCell = row to col }
            selectedCell?.let { (row, col) ->
                val cell = compare.cells.getOrNull(row)?.getOrNull(col)
                if (cell != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(strings.format("label.beforeAfterCell", row + 1, col + 1), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(strings.format("label.beforeAfterCellBefore", cell.beforeAvgAbsError ?: 0.0, cell.beforeSamples), style = MaterialTheme.typography.bodySmall)
                            Text(strings.format("label.beforeAfterCellAfter", cell.afterAvgAbsError ?: 0.0, cell.afterSamples), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeforeAfterSummary(strings: com.speeduino.manager.desktop.Strings, result: LogCompareResult) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings["label.beforeAfterSummary"], style = MaterialTheme.typography.titleMedium)
            Text(strings.format("label.beforeAfterMetricAfrError", result.beforeSummary.avgAbsError, result.afterSummary.avgAbsError))
            Text(strings.format("label.beforeAfterMetricLean", result.beforeSummary.leanTimeRatio * 100.0, result.afterSummary.leanTimeRatio * 100.0))
            Text(strings.format("label.beforeAfterMetricRich", result.beforeSummary.richTimeRatio * 100.0, result.afterSummary.richTimeRatio * 100.0))
            Text(
                strings.format(
                    "label.beforeAfterMetricCoverage",
                    result.beforeSummary.coverageCells,
                    result.beforeSummary.totalCells,
                    result.afterSummary.coverageCells,
                    result.afterSummary.totalCells
                )
            )
        }
    }
}

@Composable
private fun BeforeAfterHeatmap(
    result: LogCompareResult,
    selectedCell: Pair<Int, Int>?,
    onSelectCell: (Int, Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(LocalStrings.current["label.beforeAfterHeatmap"], style = MaterialTheme.typography.titleMedium)
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                result.cells.forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEachIndexed { colIndex, cell ->
                            val color = when (cell.state) {
                                LogHeatCellState.IMPROVED -> Color(0xFF16A34A)
                                LogHeatCellState.WORSE -> Color(0xFFDC2626)
                                LogHeatCellState.UNCHANGED -> Color(0xFF9CA3AF)
                                LogHeatCellState.NOT_ENOUGH -> Color(0xFFE5E7EB)
                            }
                            val border = if (selectedCell?.first == rowIndex && selectedCell.second == colIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            }
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .border(2.dp, border, RoundedCornerShape(2.dp))
                                    .background(color, RoundedCornerShape(2.dp))
                                    .clickable { onSelectCell(rowIndex, colIndex) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}


@Composable
private fun StrategyChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Text(
            text = label,
            modifier = Modifier.clickable { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun AnalyzerSignalRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            if (ok) "OK" else "Missing",
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun AnalyzerHeatmapGrid(
    suggestions: List<List<CellSuggestion?>>,
    maxChangePct: Double,
    highlightedCells: Set<CellRef>,
    onCellSelected: (CellRef) -> Unit
) {
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        suggestions.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { colIndex, cell ->
                    val color = when {
                        cell == null -> Color(0xFFE5E7EB)
                        cell.deltaPct >= 0 -> analyzerHeatColor(Color(0xFFDC2626), abs(cell.deltaPct) / maxChangePct)
                        else -> analyzerHeatColor(Color(0xFF2563EB), abs(cell.deltaPct) / maxChangePct)
                    }
                    val border = if (highlightedCells.contains(CellRef(rowIndex, colIndex))) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    }
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .border(2.dp, border, shape = RoundedCornerShape(2.dp))
                            .background(color, shape = RoundedCornerShape(2.dp))
                            .clickable { onCellSelected(CellRef(rowIndex, colIndex)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

private fun analyzerHeatColor(base: Color, intensity: Double): Color {
    val alpha = intensity.coerceIn(0.2, 1.0).toFloat()
    return base.copy(alpha = alpha)
}

private data class LogSeries(
    val name: String,
    val color: Color,
    val points: List<LogPoint>
)

private data class LogPoint(
    val x: Float,
    val y: Float
)

private data class SeriesScale(
    val min: Float,
    val max: Float
) {
    private val range: Float = max - min

    fun normalize(value: Float): Float {
        if (range == 0f) return 0.5f
        return ((value - min) / range).coerceIn(0f, 1f)
    }
}

private data class ParsedCsvLog(
    val series: List<LogSeries>,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val sampleCount: Int
)

private data class SelectedSignalValue(
    val name: String,
    val y: Float
)

private fun buildLogSeries(
    entries: List<LiveLogEntry>,
    strings: com.speeduino.manager.desktop.Strings
): List<LogSeries> {
    if (entries.isEmpty()) return emptyList()
    val anchorTimestamp = entries.first().timestampMs

    fun build(
        name: String,
        color: Color,
        extractor: (LiveLogEntry) -> Float
    ): LogSeries {
        return LogSeries(
            name = name,
            color = color,
            points = entries.map { entry ->
                LogPoint(
                    x = ((entry.timestampMs - anchorTimestamp).coerceAtLeast(0L)) / 1000f,
                    y = extractor(entry)
                )
            }
        )
    }

    return listOf(
        build(strings["label.rpm"], Color(0xFF2F6B5F)) { it.rpm.toFloat() },
        build(strings["label.map"], Color(0xFFC37B2C)) { it.mapKpa.toFloat() },
        build(strings["label.tps"], Color(0xFF5C6BC0)) { it.tps.toFloat() },
        build(strings["label.coolantShort"], Color(0xFFB04A3B)) { it.coolantTempC.toFloat() },
        build(strings["label.iat"], Color(0xFF8D6E63)) { it.intakeTempC.toFloat() },
        build(strings["label.batt"], Color(0xFF388E3C)) { it.batteryDeciVolt.toFloat() / 10f },
        build(strings["label.advance"], Color(0xFF6D4C41)) { it.advanceDeg.toFloat() },
        build(strings["label.o2"], Color(0xFF00897B)) { it.o2.toFloat() }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogViewerFiltersRow(
    signals: List<String>,
    signalColors: Map<String, Color>,
    selectedSignals: Set<String>,
    onSelectedSignalsChange: (Set<String>) -> Unit,
    onToggle: (String) -> Unit
) {
    val strings = LocalStrings.current
    var query by remember(signals) { mutableStateOf("") }
    val filterFocusRequester = remember { FocusRequester() }
    val filteredSignals = remember(signals, query) {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            signals
        } else {
            signals.filter { it.lowercase().contains(normalizedQuery) }
        }
    }
    LaunchedEffect(signals) {
        filterFocusRequester.requestFocus()
    }
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            filterFocusRequester.requestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(strings["label.filterChannels"]) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(filterFocusRequester)
            )
            FilledTonalButton(
                onClick = { onSelectedSignalsChange(selectedSignals + filteredSignals) },
                enabled = filteredSignals.isNotEmpty()
            ) {
                Text(strings["label.selectShown"])
            }
            FilledTonalButton(
                onClick = { onSelectedSignalsChange(selectedSignals - filteredSignals.toSet()) },
                enabled = filteredSignals.any { selectedSignals.contains(it) }
            ) {
                Text(strings["label.clearShown"])
            }
        }

        Text(
            text = strings.format("label.channelsSelected", selectedSignals.size, signals.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filteredSignals.forEach { name ->
                LogSignalChip(
                    name = name,
                    color = signalColors[name] ?: MaterialTheme.colorScheme.outline,
                    selected = selectedSignals.contains(name),
                    onToggle = { onToggle(name) }
                )
            }
        }
    }
}

@Composable
private fun LogSignalChip(
    name: String,
    color: Color,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        androidx.compose.material3.Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() }
        )
        Box(
            modifier = Modifier
                .size(width = 22.dp, height = 4.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LogViewerChart(series: List<LogSeries>, totalSamples: Int) {
    val strings = LocalStrings.current
    if (series.isEmpty()) {
        Text(strings["label.selectSignals"], style = MaterialTheme.typography.bodyMedium)
        return
    }

    var zoomFactor by remember(totalSamples) { mutableStateOf(1f) }
    var highlightedRange by remember(totalSamples) { mutableStateOf<Pair<Float, Float>?>(null) }
    var pendingCenterX by remember(totalSamples) { mutableStateOf<Float?>(null) }
    val maxPoints = (800 * zoomFactor).roundToInt().coerceIn(800, 6_000)
    val downsampled = series.map { it.copy(points = downsamplePoints(it.points, maxPoints)) }
    val basePointCount = downsampled.maxOfOrNull { it.points.size } ?: 1
    val chartWidth = maxOf(720.dp, (basePointCount * 4 * zoomFactor).dp)
    val chartHeight = 320.dp
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val markerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val zoomHighlightColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
    val zoomHighlightStroke = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
    val xMax = downsampled.maxOfOrNull { it.points.lastOrNull()?.x ?: 0f }?.coerceAtLeast(1f) ?: 1f
    val scales = remember(downsampled) {
        downsampled.associate { logSeries ->
            val values = logSeries.points.map { it.y }
            logSeries.name to SeriesScale(
                min = values.minOrNull() ?: 0f,
                max = values.maxOrNull() ?: 1f
            )
        }
    }
    var selectedX by remember(totalSamples) { mutableStateOf<Float?>(null) }
    val selectedSignalValues = remember(downsampled, selectedX) {
        val x = selectedX ?: return@remember emptyList()
        downsampled.mapNotNull { logSeries ->
            interpolateYAtX(logSeries, x)?.let { value ->
                SelectedSignalValue(name = logSeries.name, y = value)
            }
        }
    }
    fun visibleCenterX(): Float {
        val contentWidthPx = with(density) { chartWidth.toPx() }
        if (contentWidthPx <= 0f || horizontal.maxValue <= 0) {
            return selectedX ?: (xMax / 2f)
        }
        val viewportWidthPx = (contentWidthPx - horizontal.maxValue).coerceAtLeast(1f)
        val centerPx = horizontal.value + viewportWidthPx / 2f
        return ((centerPx / contentWidthPx).coerceIn(0f, 1f) * xMax)
    }

    LaunchedEffect(pendingCenterX, zoomFactor, chartWidth, horizontal.maxValue, xMax) {
        val centerX = pendingCenterX ?: return@LaunchedEffect
        if (horizontal.maxValue <= 0) return@LaunchedEffect

        val contentWidthPx = with(density) { chartWidth.toPx() }
        val viewportWidthPx = (contentWidthPx - horizontal.maxValue).coerceAtLeast(1f)
        val centerRatio = (centerX / xMax).coerceIn(0f, 1f)
        val targetScroll = (contentWidthPx * centerRatio - viewportWidthPx / 2f)
            .roundToInt()
            .coerceIn(0, horizontal.maxValue)
        horizontal.animateScrollTo(targetScroll)
        pendingCenterX = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val centerX = selectedX ?: visibleCenterX()
                            val nextZoom = (zoomFactor / 1.6f).coerceAtLeast(1f)
                            zoomFactor = nextZoom
                            pendingCenterX = centerX
                            if (nextZoom == 1f) highlightedRange = null
                        },
                        enabled = zoomFactor > 1f
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomOut,
                            contentDescription = "Zoom out"
                        )
                    }
                    Text(
                        text = "${String.format(Locale.US, "%.1f", zoomFactor)}x",
                        style = MaterialTheme.typography.labelLarge
                    )
                    IconButton(
                        onClick = {
                            pendingCenterX = selectedX ?: visibleCenterX()
                            zoomFactor = (zoomFactor * 1.6f).coerceAtMost(16f)
                        },
                        enabled = zoomFactor < 16f
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Zoom in"
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight + 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
                    .horizontalScroll(horizontal)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(chartWidth)
                        .height(chartHeight)
                        .pointerInput(zoomFactor) {
                            detectDragGestures { _, dragAmount ->
                                val target = (horizontal.value - dragAmount.x)
                                    .roundToInt()
                                    .coerceIn(0, horizontal.maxValue)
                                scope.launch { horizontal.scrollTo(target) }
                            }
                        }
                        .pointerInput(downsampled, zoomFactor) {
                            fun xFromOffset(offset: Offset): Float {
                                val padding = 24f
                                val widthPx = this.size.width.toFloat() - padding * 2f
                                if (widthPx <= 0f) return selectedX ?: 0f
                                val normalizedX = ((offset.x - padding) / widthPx).coerceIn(0f, 1f)
                                return normalizedX * xMax
                            }

                            detectTapGestures(
                                onTap = { offset ->
                                    selectedX = xFromOffset(offset)
                                },
                                onDoubleTap = { offset ->
                                    val tappedX = xFromOffset(offset)
                                    val nextZoom = (zoomFactor * 2f).coerceAtMost(16f)
                                    selectedX = tappedX
                                    zoomFactor = nextZoom
                                    val window = (xMax / nextZoom).coerceAtLeast(0.5f)
                                    val start = (tappedX - window / 2f).coerceAtLeast(0f)
                                    val end = (start + window).coerceAtMost(xMax)
                                    highlightedRange = start to end
                                    pendingCenterX = tappedX
                                }
                            )
                        }
                ) {
                    val padding = 24f
                    val width = size.width - padding * 2
                    val height = size.height - padding * 2

                    highlightedRange?.let { (start, end) ->
                        val startX = padding + (start / xMax) * width
                        val endX = padding + (end / xMax) * width
                        drawRect(
                            color = zoomHighlightColor,
                            topLeft = Offset(startX, padding),
                            size = androidx.compose.ui.geometry.Size(
                                width = (endX - startX).coerceAtLeast(1f),
                                height = height
                            )
                        )
                        drawLine(
                            color = zoomHighlightStroke,
                            start = Offset(startX, padding),
                            end = Offset(startX, padding + height),
                            strokeWidth = 1.4f
                        )
                        drawLine(
                            color = zoomHighlightStroke,
                            start = Offset(endX, padding),
                            end = Offset(endX, padding + height),
                            strokeWidth = 1.4f
                        )
                    }

                    drawLine(
                        color = axisColor,
                        start = Offset(padding, padding),
                        end = Offset(padding, padding + height),
                        strokeWidth = 1.5f
                    )
                    drawLine(
                        color = axisColor,
                        start = Offset(padding, padding + height),
                        end = Offset(padding + width, padding + height),
                        strokeWidth = 1.5f
                    )

                    downsampled.forEach { s ->
                        val scale = scales.getValue(s.name)
                        val path = Path()
                        s.points.forEachIndexed { index, point ->
                            val normalized = scale.normalize(point.y)
                            val x = padding + (point.x / xMax) * width
                            val y = padding + height - (normalized * height)
                            if (index == 0) {
                                path.moveTo(x, y)
                            } else {
                                path.lineTo(x, y)
                            }
                        }
                        drawPath(
                            path = path,
                            color = s.color,
                            style = Stroke(width = 2.2f, cap = StrokeCap.Round)
                        )

                        val markerX = selectedX
                        if (markerX != null) {
                            val markerY = interpolateYAtX(s, markerX)
                            if (markerY != null) {
                                val normalized = scale.normalize(markerY)
                                drawCircle(
                                    color = s.color,
                                    radius = 4f,
                                    center = Offset(
                                        x = padding + (markerX / xMax) * width,
                                        y = padding + height - (normalized * height)
                                    )
                                )
                            }
                        }
                    }

                    val markerX = selectedX
                    if (markerX != null) {
                        val canvasX = padding + (markerX / xMax) * width
                        drawLine(
                            color = markerColor,
                            start = Offset(canvasX, padding),
                            end = Offset(canvasX, padding + height),
                            strokeWidth = 1.5f
                        )
                    }
                }
            }
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontal),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
            )
            AxisRangeLabels(
                series = downsampled,
                scales = scales,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 30.dp, top = 8.dp)
            )
            AxisRangeLabels(
                series = downsampled,
                scales = scales,
                showMax = false,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 30.dp, bottom = 28.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(strings.format("label.samples", totalSamples), style = MaterialTheme.typography.labelLarge)
            downsampled.forEach { s ->
                val values = s.points.map { it.y }
                val min = values.minOrNull() ?: 0f
                val max = values.maxOrNull() ?: 0f
                val amplitude = max - min
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(s.color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(s.color, RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "${s.name} ${formatRange(min, max)} amp ${formatAxisValue(amplitude)}",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        if (selectedSignalValues.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                selectedX?.let { tappedX ->
                    Text(strings.format("label.timeSeconds", tappedX), style = MaterialTheme.typography.labelLarge)
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    selectedSignalValues.forEach { reading ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = formatSignalReading(reading.name, reading.y),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AxisRangeLabels(
    series: List<LogSeries>,
    scales: Map<String, SeriesScale>,
    modifier: Modifier = Modifier,
    showMax: Boolean = true
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        series.forEach { logSeries ->
            val scale = scales[logSeries.name] ?: return@forEach
            val value = if (showMax) scale.max else scale.min
            Text(
                text = "${logSeries.name} ${if (showMax) "max" else "min"} ${formatAxisValue(value)}",
                style = MaterialTheme.typography.labelSmall,
                color = logSeries.color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LogMetadataSummary(entries: List<LiveLogEntry>) {
    if (entries.isEmpty()) return
    LogMetadataSummary(
        startedAtMs = entries.first().timestampMs,
        endedAtMs = entries.last().timestampMs,
        sampleCount = entries.size
    )
}

@Composable
private fun LogMetadataSummary(
    startedAtMs: Long,
    endedAtMs: Long,
    sampleCount: Int
) {
    val strings = LocalStrings.current
    val durationSec = (endedAtMs - startedAtMs).coerceAtLeast(0) / 1000

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(strings.format("label.duration", durationSec), style = MaterialTheme.typography.labelLarge)
        Text(strings.format("label.start", startedAtMs), style = MaterialTheme.typography.labelLarge)
        Text(strings.format("label.end", endedAtMs), style = MaterialTheme.typography.labelLarge)
        Text(strings.format("label.samples", sampleCount), style = MaterialTheme.typography.labelLarge)
    }
}

private fun downsamplePoints(points: List<LogPoint>, maxPoints: Int): List<LogPoint> {
    if (points.size <= maxPoints) return points
    val step = points.size.toFloat() / maxPoints
    return List(maxPoints) { index ->
        val idx = (index * step).toInt().coerceIn(0, points.size - 1)
        points[idx]
    }
}

private fun interpolateYAtX(series: LogSeries, x: Float): Float? {
    val points = series.points
    if (points.isEmpty()) return null
    if (points.size == 1) return points.first().y

    val first = points.first()
    val last = points.last()
    if (x <= first.x) return first.y
    if (x >= last.x) return last.y

    var low = 0
    var high = points.lastIndex
    while (low <= high) {
        val mid = (low + high) / 2
        val midX = points[mid].x
        when {
            abs(midX - x) < 0.0001f -> return points[mid].y
            midX < x -> low = mid + 1
            else -> high = mid - 1
        }
    }

    val rightIndex = low.coerceIn(1, points.lastIndex)
    val leftIndex = rightIndex - 1
    val left = points[leftIndex]
    val right = points[rightIndex]
    val span = right.x - left.x
    if (span <= 0f) return left.y
    val ratio = ((x - left.x) / span).coerceIn(0f, 1f)
    return left.y + (right.y - left.y) * ratio
}

private fun formatSignalReading(signalName: String, value: Float): String {
    return "$signalName ${String.format(Locale.US, "%.2f", value)}"
}

private fun formatAxisValue(value: Float): String {
    val absValue = abs(value)
    return when {
        absValue >= 100f -> String.format(Locale.US, "%.0f", value)
        absValue >= 10f -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.2f", value)
    }
}

private fun formatRange(min: Float, max: Float): String {
    return if (min == max) {
        String.format(Locale.US, "%.1f", min)
    } else {
        String.format(Locale.US, "%.1f-%.1f", min, max)
    }
}

private fun parseDesktopCsvLog(path: String): ParsedCsvLog {
    val file = File(path)
    require(file.exists()) { "CSV file not found: ${file.name}" }

    val lines = file.readLines().filter { it.isNotBlank() }
    require(lines.size > 1) { "CSV has no samples." }

    val headers = splitCsvLine(lines.first()).map { it.trim() }
    val timestampIndex = headers.indexOfFirst { header ->
        val normalized = header.trim().lowercase()
        normalized == "timestamp_ms" || normalized == "timestamp" || normalized == "time_ms" || normalized == "time"
    }
    require(timestampIndex >= 0) { "Missing timestamp column." }

    val valueIndices = headers.indices.filter { it != timestampIndex }
    val headerUnits = valueIndices.associateWith { columnIndex ->
        detectUnitFromHeader(headers[columnIndex])
    }
    val buffers = valueIndices.map { mutableListOf<LogPoint>() }
    var baseTimestamp: Long? = null
    var lastTimestamp: Long? = null

    lines.drop(1).forEach { line ->
        val cols = splitCsvLine(line)
        val timestamp = cols.getOrNull(timestampIndex)?.trim()?.toLongOrNull() ?: return@forEach
        val anchor = baseTimestamp ?: timestamp.also { baseTimestamp = it }
        lastTimestamp = timestamp
        val xSeconds = (timestamp - anchor) / 1000f

        valueIndices.forEachIndexed { bufferIndex, colIndex ->
            val value = cols.getOrNull(colIndex)?.trim()?.replace(',', '.')?.toFloatOrNull()
            if (value != null) {
                buffers[bufferIndex].add(LogPoint(x = xSeconds, y = value))
            }
        }
    }

    val orderedColors = listOf(
        Color(0xFF2F6B5F),
        Color(0xFFC37B2C),
        Color(0xFF5C6BC0),
        Color(0xFFB04A3B),
        Color(0xFF8D6E63),
        Color(0xFF388E3C),
        Color(0xFF6D4C41),
        Color(0xFF00897B),
        Color(0xFFD81B60),
        Color(0xFF3949AB),
    )

    val series = valueIndices.mapIndexedNotNull { index, columnIndex ->
        val originalName = headers[columnIndex]
        val detectedUnit = headerUnits[columnIndex]
        val displayName = decorateSeriesName(originalName, detectedUnit)
        val points = buffers[index]
        if (points.isEmpty()) {
            null
        } else {
            LogSeries(
                name = displayName,
                color = orderedColors[index % orderedColors.size],
                points = points
            )
        }
    }
    require(series.isNotEmpty()) { "CSV has no numeric columns." }

    return ParsedCsvLog(
        series = series,
        startedAtMs = baseTimestamp ?: 0L,
        endedAtMs = lastTimestamp ?: baseTimestamp ?: 0L,
        sampleCount = series.maxOfOrNull { it.points.size } ?: 0
    )
}

private fun detectUnitFromHeader(header: String): String? {
    val normalized = header.trim().lowercase()
    return when {
        normalized.contains("kpa") -> "kPa"
        normalized.contains("bar") -> "bar"
        normalized.endsWith("_c") || normalized.contains("coolant_c") || normalized.contains("iat_c") || normalized.contains("temp_c") -> "°C"
        normalized.contains("km/h") || normalized.contains("kph") || normalized.contains("kmh") || normalized.endsWith("_kmh") -> "km/h"
        normalized.contains("pct") || normalized.endsWith("_pct") -> "%"
        normalized.contains("deg") -> "deg"
        normalized.endsWith("_v") || normalized.contains("battery_v") -> "V"
        normalized.endsWith("_ms") || normalized.contains("time_ms") -> "ms"
        normalized.contains("nm") -> "Nm"
        normalized.contains("whp") -> "whp"
        else -> null
    }
}

private fun decorateSeriesName(originalName: String, unit: String?): String {
    if (unit.isNullOrBlank()) return originalName
    return "$originalName [$unit]"
}

private fun sortSignalsByPriority(signals: List<String>): List<String> {
    return signals.sortedWith(compareBy<String> { signalPriority(it) }.thenBy { it.lowercase() })
}

private fun defaultSelectedSignals(orderedSignals: List<String>): List<String> {
    val telemetryTokens = listOf(
        listOf("rpm"),
        listOf("tps", "throttle"),
        listOf("lambda", "afr", "o2"),
        listOf("map_kpa", "mapkpa", "map"),
        listOf("speed"),
        listOf("engine_temp", "coolant", "clt"),
        listOf("air_temp", "iat"),
        listOf("inj", "injection"),
        listOf("ignition", "advance", "dwell"),
        listOf("battery"),
        listOf("torq", "torque"),
        listOf("power")
    )
    val prioritized = telemetryTokens.mapNotNull { tokens ->
        orderedSignals.firstOrNull { signalMatchesAny(it, tokens) }
    }.distinct()
    if (prioritized.isNotEmpty()) return prioritized.take(8)
    return orderedSignals.take(8)
}

private fun splitCsvLine(line: String): List<String> {
    val values = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var index = 0
    while (index < line.length) {
        val ch = line[index]
        when {
            ch == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index++
            }
            ch == '"' -> inQuotes = !inQuotes
            ch == ',' && !inQuotes -> {
                values += current.toString()
                current.clear()
            }
            else -> current.append(ch)
        }
        index++
    }
    values += current.toString()
    return values
}

private fun signalPriority(signal: String): Int {
    return when {
        signalMatchesAny(signal, listOf("rpm")) -> 0
        signalMatchesAny(signal, listOf("map", "map_kpa", "mapkpa")) -> 1
        signalMatchesAny(signal, listOf("tps", "throttle")) -> 2
        signalMatchesAny(signal, listOf("lambda", "afr", "o2")) -> 3
        signalMatchesAny(signal, listOf("oil_pressure", "fuel_pressure")) -> 4
        signalMatchesAny(signal, listOf("speed", "drag")) -> 5
        signalMatchesAny(signal, listOf("coolant", "clt")) -> 6
        signalMatchesAny(signal, listOf("air_temp", "iat")) -> 7
        signalMatchesAny(signal, listOf("ignition", "advance", "dwell")) -> 8
        signalMatchesAny(signal, listOf("inj", "injection")) -> 9
        else -> 100
    }
}

private fun signalMatchesAny(signal: String, tokens: List<String>): Boolean {
    val normalized = signal.lowercase()
    return tokens.any { token -> normalized == token || normalized.contains(token) }
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
            OutlinedButton(onClick = onDismiss) {
                Text("Close")
            }
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
