package com.speeduino.manager.desktop

import io.ecucore.model.TableChangeKind
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ecucore.model.AfrTable
import io.ecucore.model.DwellTable
import io.ecucore.model.Color as SharedColor
import io.ecucore.model.IgnitionTable
import io.ecucore.model.VeTable
import kotlin.math.roundToInt

@Composable
internal fun PlaceholderScreen(title: String, message: String) {
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun VeTableScreenDesktop(controller: DesktopSpeeduinoController, mapIndex: Int) {
    val table by controller.veTableState(mapIndex).collectAsState()
    val liveData by controller.liveData.collectAsState()
    val loadError by controller.lastError.collectAsState()
    val strings = LocalStrings.current
    MapTableScreen(
        title = if (mapIndex == 1) strings["route.veTable"] else strings["route.veTable2"],
        description = strings["label.mapVe"],
        table = table,
        loadError = loadError,
        onLoad = { controller.loadVeTable(mapIndex) },
        onSave = { controller.saveVeTable(it, mapIndex) },
        undo = rememberTableUndo(controller, UndoKey(UndoTableKind.VE, mapIndex)),
        formatValue = { it.toString() },
        parseValue = { it.toIntOrNull() },
        valueRange = 0..255,
        cellColor = { VeTable.getColorForValue(it).toComposeColor() },
        axisTypeLabel = { if (it.loadType == VeTable.LoadType.TPS) strings["label.alphaNAxis"] else strings["label.speedDensityAxis"] },
        rpmBins = { it.rpmBins },
        loadBins = { it.loadBins },
        values = { it.values },
        updateCell = { t, row, col, value -> t.setValue(row, col, value) },
        updateRpm = { t, index, value -> t.setRpmBin(index, value) },
        updateLoad = { t, index, value -> t.setLoadBin(index, value) },
        liveRpm = liveData?.rpm,
        liveLoad = { t -> if (t.loadType == VeTable.LoadType.TPS) liveData?.tps else liveData?.mapPressure }
    )
}

@Composable
internal fun IgnitionTableScreenDesktop(controller: DesktopSpeeduinoController, mapIndex: Int) {
    val table by controller.ignitionTableState(mapIndex).collectAsState()
    val liveData by controller.liveData.collectAsState()
    val loadError by controller.lastError.collectAsState()
    val strings = LocalStrings.current
    MapTableScreen(
        title = if (mapIndex == 1) strings["route.ignitionTable"] else strings["route.ignitionTable2"],
        description = strings["label.mapIgnition"],
        table = table,
        loadError = loadError,
        onLoad = { controller.loadIgnitionTable(mapIndex) },
        onSave = { controller.saveIgnitionTable(it, mapIndex) },
        undo = rememberTableUndo(controller, UndoKey(UndoTableKind.IGNITION, mapIndex)),
        formatValue = { it.toString() },
        parseValue = { it.toIntOrNull() },
        valueRange = -40..70,
        cellColor = { IgnitionTable.getColorForValue(it).toComposeColor() },
        axisTypeLabel = { if (it.loadType == IgnitionTable.LoadType.TPS) strings["label.alphaNAxis"] else strings["label.speedDensityAxis"] },
        rpmBins = { it.rpmBins },
        loadBins = { it.loadBins },
        values = { it.values },
        updateCell = { t, row, col, value -> t.setValue(row, col, value) },
        updateRpm = { t, index, value -> t.setRpmBin(index, value) },
        updateLoad = { t, index, value -> t.setLoadBin(index, value) },
        liveRpm = liveData?.rpm,
        liveLoad = { t -> if (t.loadType == IgnitionTable.LoadType.TPS) liveData?.tps else liveData?.mapPressure }
    )
}

@Composable
internal fun AfrTableScreenDesktop(controller: DesktopSpeeduinoController) {
    val table by controller.afrTable.collectAsState()
    val liveData by controller.liveData.collectAsState()
    val loadError by controller.lastError.collectAsState()
    val strings = LocalStrings.current
    MapTableScreen(
        title = strings["route.afrTable"],
        description = strings["label.mapAfr"],
        table = table,
        loadError = loadError,
        onLoad = controller::loadAfrTable,
        onSave = controller::saveAfrTable,
        undo = rememberTableUndo(controller, UndoKey(UndoTableKind.AFR)),
        formatValue = { AfrTable.formatValue(it) },
        parseValue = { parseAfrValue(it) },
        valueRange = 100..200,
        cellColor = { AfrTable.getColorForValue(it).toComposeColor() },
        axisTypeLabel = { if (it.loadType == AfrTable.LoadType.TPS) strings["label.alphaNAxis"] else strings["label.speedDensityAxis"] },
        rpmBins = { it.rpmBins },
        loadBins = { it.loadBins },
        values = { it.values },
        updateCell = { t, row, col, value -> t.setValue(row, col, value) },
        updateRpm = { t, index, value -> t.setRpmBin(index, value) },
        updateLoad = { t, index, value -> t.setLoadBin(index, value) },
        liveRpm = liveData?.rpm,
        liveLoad = { t -> if (t.loadType == AfrTable.LoadType.TPS) liveData?.tps else liveData?.mapPressure }
    )
}

@Composable
internal fun DwellTableScreenDesktop(controller: DesktopSpeeduinoController) {
    val table by controller.dwellTable.collectAsState()
    val liveData by controller.liveData.collectAsState()
    val loadError by controller.lastError.collectAsState()
    val strings = LocalStrings.current
    MapTableScreen(
        title = strings["route.dwellTable"],
        description = strings["label.dwellTableDescription"],
        table = table,
        loadError = loadError,
        onLoad = controller::loadDwellTable,
        onSave = controller::saveDwellTable,
        undo = rememberTableUndo(controller, UndoKey(UndoTableKind.DWELL)),
        formatValue = { it.toString() },
        parseValue = { it.toIntOrNull() },
        valueRange = 0..10,
        cellColor = { dwellColor(it) },
        axisTypeLabel = { if (it.loadType == IgnitionTable.LoadType.TPS) strings["label.alphaNAxis"] else strings["label.speedDensityAxis"] },
        rpmBins = { it.rpmBins },
        loadBins = { it.loadBins },
        values = { it.values },
        updateCell = { t, row, col, value -> t.setValue(row, col, value) },
        updateRpm = { t, index, value -> t.setRpmBin(index, value) },
        updateLoad = { t, index, value -> t.setLoadBin(index, value) },
        liveRpm = liveData?.rpm,
        liveLoad = { t -> if (t.loadType == IgnitionTable.LoadType.TPS) liveData?.tps else liveData?.mapPressure }
    )
}

private fun dwellColor(value: Int): Color {
    val normalized = value.coerceIn(0, 10) / 10.0f
    return Color(
        red = 0.18f + (0.70f * normalized),
        green = 0.22f + (0.35f * (1f - normalized)),
        blue = 0.55f + (0.20f * (1f - normalized)),
        alpha = 1f
    )
}

@Composable
private fun <T> MapTableScreen(
    title: String,
    description: String,
    table: T?,
    loadError: String? = null,
    onLoad: () -> Unit,
    onSave: (T) -> Unit,
    undo: TableUndoUi = TableUndoUi.None,
    formatValue: (Int) -> String,
    parseValue: (String) -> Int?,
    valueRange: IntRange,
    cellColor: (Int) -> Color,
    axisTypeLabel: (T) -> String,
    rpmBins: (T) -> List<Int>,
    loadBins: (T) -> List<Int>,
    values: (T) -> List<List<Int>>,
    updateCell: (T, Int, Int, Int) -> T,
    updateRpm: (T, Int, Int) -> T,
    updateLoad: (T, Int, Int) -> T,
    liveRpm: Int? = null,
    liveLoad: (T) -> Int? = { null }
) {
    val strings = LocalStrings.current
    var workingTable by remember(table) { mutableStateOf(table) }
    var hasChanges by remember { mutableStateOf(false) }
    ReportUnsavedChanges(hasChanges)
    var editTarget by remember { mutableStateOf<TableEditTarget?>(null) }
    var editValue by remember { mutableStateOf("") }
    var invertYAxis by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var showUndoConfirm by remember { mutableStateOf(false) }
    var liveCursorEnabled by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<TableSelection?>(null) }
    var pendingAction by remember { mutableStateOf<SelectionAction?>(null) }
    var selectionInputValue by remember { mutableStateOf("") }

    LaunchedEffect(table) {
        workingTable = table
        hasChanges = false
        selection = null
    }

    LaunchedEffect(Unit) {
        if (table == null) {
            onLoad()
        }
    }

    val rpm = workingTable?.let(rpmBins).orEmpty()
    val load = workingTable?.let(loadBins).orEmpty()
    val grid = workingTable?.let(values).orEmpty()

    val activeCurrentLoad = workingTable?.let(liveLoad)
    val activeCell: Pair<Int, Int>? = if (liveCursorEnabled && liveRpm != null && activeCurrentLoad != null && rpm.isNotEmpty() && load.isNotEmpty()) {
        nearestIndex(rpm, liveRpm) to nearestIndex(load, activeCurrentLoad)
    } else null

    val rowCount = grid.size
    val colCount = rpm.size

    fun applySelectionAction(action: SelectionAction, amount: Int?) {
        val sel = selection ?: return
        if (workingTable == null) return
        val updatedGrid = when (action) {
            SelectionAction.SET_VALUE -> applySetValue(grid, sel, amount ?: 0, valueRange)
            SelectionAction.ADD_DELTA -> applyAddDelta(grid, sel, amount ?: 0, valueRange)
            SelectionAction.INTERPOLATE -> applyInterpolate(grid, sel, valueRange)
            SelectionAction.SMOOTH -> applySmooth(grid, sel, valueRange)
        }
        for (r in sel.minRow..sel.maxRow) {
            for (c in sel.minCol..sel.maxCol) {
                val newValue = updatedGrid.getOrNull(r)?.getOrNull(c)
                val oldValue = grid.getOrNull(r)?.getOrNull(c)
                if (newValue != null && newValue != oldValue) {
                    workingTable = workingTable?.let { updateCell(it, r, c, newValue) }
                }
            }
        }
        hasChanges = true
        selection = null
        pendingAction = null
        selectionInputValue = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                text = workingTable?.let(axisTypeLabel) ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable { showInfo = !showInfo }
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (showInfo) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = strings["label.info"],
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = strings["label.liveCursor"],
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(checked = liveCursorEnabled, onCheckedChange = { liveCursorEnabled = it })
            }
            Spacer(modifier = Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = { invertYAxis = !invertYAxis }) {
                Text(
                    text = "⇅",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (invertYAxis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showInfo) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
        }

        if (workingTable == null) {
            PlaceholderScreen(
                title,
                loadError?.let { strings.format("label.loadFailed", it) } ?: strings["label.noDataLoaded"]
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                val verticalScroll = rememberScrollState()
                val horizontalScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 520.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .verticalScroll(verticalScroll)
                            .horizontalScroll(horizontalScroll),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HeaderCell(strings["label.loadRpm"])
                            rpm.forEachIndexed { index, value ->
                                HeaderCell(value.toString(), highlighted = activeCell?.first == index) {
                                    editTarget = TableEditTarget.Rpm(index)
                                    editValue = value.toString()
                                }
                            }
                        }
                        val loadOrder = if (invertYAxis) load.indices.reversed() else load.indices
                        loadOrder.forEach { dataRowIndex ->
                            val loadValue = load.getOrNull(dataRowIndex) ?: return@forEach
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                HeaderCell(loadValue.toString(), highlighted = activeCell?.second == dataRowIndex) {
                                    editTarget = TableEditTarget.Load(dataRowIndex)
                                    editValue = loadValue.toString()
                                }
                                grid.getOrNull(dataRowIndex)?.forEachIndexed { colIndex, cell ->
                                    ValueCell(
                                        value = formatValue(cell),
                                        background = cellColor(cell),
                                        isLiveCursor = activeCell == (colIndex to dataRowIndex),
                                        isSelected = selection?.contains(dataRowIndex, colIndex) == true,
                                        row = dataRowIndex,
                                        col = colIndex,
                                        rowCount = rowCount,
                                        colCount = colCount,
                                        invertRowDrag = invertYAxis,
                                        onClick = {
                                            editTarget = TableEditTarget.Cell(dataRowIndex, colIndex)
                                            editValue = formatValue(cell)
                                        },
                                        onDragStart = { r, c -> selection = TableSelection(r, c, r, c) },
                                        onDragUpdate = { r, c -> selection = selection?.copy(endRow = r, endCol = c) },
                                        onDragEnd = {
                                            val sel = selection
                                            if (sel != null && sel.minRow == sel.maxRow && sel.minCol == sel.maxCol) {
                                                selection = null
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(verticalScroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(horizontalScroll),
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    )
                }
            }

            val activeSelection = selection
            if (activeSelection != null && (activeSelection.minRow != activeSelection.maxRow || activeSelection.minCol != activeSelection.maxCol)) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    shadowElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val cellCount = (activeSelection.maxRow - activeSelection.minRow + 1) * (activeSelection.maxCol - activeSelection.minCol + 1)
                        Text(
                            text = strings.format("label.selectionCount", cellCount.toString()),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                selectionInputValue = ""
                                pendingAction = SelectionAction.SET_VALUE
                            }) { Text(strings["action.selectionSetValue"]) }
                            TextButton(onClick = {
                                selectionInputValue = ""
                                pendingAction = SelectionAction.ADD_DELTA
                            }) { Text(strings["action.selectionAddDelta"]) }
                            TextButton(onClick = { applySelectionAction(SelectionAction.INTERPOLATE, null) }) {
                                Text(strings["action.selectionInterpolate"])
                            }
                            TextButton(onClick = { applySelectionAction(SelectionAction.SMOOTH, null) }) {
                                Text(strings["action.selectionSmooth"])
                            }
                            TextButton(onClick = { selection = null }) { Text(strings["action.selectionCancel"]) }
                        }
                    }
                }
            }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                onClick = onLoad,
                enabled = workingTable == null || hasChanges,
                modifier = Modifier.weight(1f)
            ) { Text(strings["action.loadEcu"]) }
            FilledTonalButton(
                onClick = { workingTable?.let(onSave); hasChanges = false },
                enabled = workingTable != null && hasChanges,
                modifier = Modifier.weight(1f)
            ) { Text(strings["action.saveEcu"]) }
            FilledTonalButton(
                onClick = { showUndoConfirm = true },
                enabled = undo.available,
                modifier = Modifier.weight(1f)
            ) { Text(strings["action.undo"]) }
        }
    }

    if (showUndoConfirm) {
        AlertDialog(
            onDismissRequest = { showUndoConfirm = false },
            title = { Text(strings["undo.title"]) },
            text = { Text(strings["undo.body"]) },
            confirmButton = {
                TextButton(onClick = { showUndoConfirm = false; undo.onUndo() }) { Text(strings["action.undo"]) }
            },
            dismissButton = {
                TextButton(onClick = { showUndoConfirm = false }) { Text(strings["action.cancel"]) }
            }
        )
    }

    if (pendingAction == SelectionAction.SET_VALUE || pendingAction == SelectionAction.ADD_DELTA) {
        val action = pendingAction
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                Text(
                    if (action == SelectionAction.SET_VALUE) strings["action.selectionSetValue"] else strings["action.selectionAddDelta"]
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.format("label.recommendedRange", valueRange.first, valueRange.last))
                    OutlinedTextField(
                        value = selectionInputValue,
                        onValueChange = { selectionInputValue = it },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        val parsed = parseValue(selectionInputValue)
                        if (parsed != null && action != null) {
                            applySelectionAction(action, parsed)
                        } else {
                            pendingAction = null
                        }
                    }
                ) { Text(strings["action.apply"]) }
            },
            dismissButton = {
                FilledTonalButton(onClick = { pendingAction = null }) { Text(strings["action.cancel"]) }
            }
        )
    }

    if (editTarget != null) {
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(strings["action.editValue"]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.format("label.recommendedRange", valueRange.first, valueRange.last))
                    OutlinedTextField(
                        value = editValue,
                        onValueChange = { editValue = it },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        val parsed = parseValue(editValue)
                        val target = editTarget
                        if (parsed != null && target != null) {
                            workingTable = when (target) {
                                is TableEditTarget.Cell -> workingTable?.let {
                                    updateCell(it, target.row, target.col, parsed.coerceIn(valueRange))
                                }
                                is TableEditTarget.Rpm -> workingTable?.let {
                                    updateRpm(it, target.index, parsed)
                                }
                                is TableEditTarget.Load -> workingTable?.let {
                                    updateLoad(it, target.index, parsed)
                                }
                            }
                            hasChanges = true
                        }
                        editTarget = null
                    }
                ) { Text(strings["action.apply"]) }
            },
            dismissButton = {
                FilledTonalButton(onClick = { editTarget = null }) { Text(strings["action.cancel"]) }
            }
        )
    }
}

private sealed class TableEditTarget {
    data class Cell(val row: Int, val col: Int) : TableEditTarget()
    data class Rpm(val index: Int) : TableEditTarget()
    data class Load(val index: Int) : TableEditTarget()
}

private fun nearestIndex(bins: List<Int>, value: Int): Int {
    var best = 0
    var bestDelta = Int.MAX_VALUE
    bins.forEachIndexed { index, bin ->
        val delta = kotlin.math.abs(bin - value)
        if (delta < bestDelta) {
            bestDelta = delta
            best = index
        }
    }
    return best
}

@Composable
private fun HeaderCell(text: String, highlighted: Boolean = false, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        val modifier = Modifier
            .width(BASE_TABLE_CELL_WIDTH * com.speeduino.manager.desktop.ui.LocalTableCellScale.current)
            .padding(vertical = 6.dp, horizontal = 8.dp)
        val clickable = if (onClick != null) modifier.clickable { onClick() } else modifier
        Box(
            modifier = clickable,
            contentAlignment = Alignment.Center
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge, fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

private val BASE_TABLE_CELL_WIDTH = 68.dp
private val BASE_TABLE_CELL_HEIGHT = 36.dp
private val TABLE_CELL_SPACING = 6.dp

@Composable
private fun ValueCell(
    value: String,
    background: Color,
    isLiveCursor: Boolean = false,
    isSelected: Boolean = false,
    row: Int = 0,
    col: Int = 0,
    rowCount: Int = 1,
    colCount: Int = 1,
    invertRowDrag: Boolean = false,
    onClick: () -> Unit,
    onDragStart: (Int, Int) -> Unit = { _, _ -> },
    onDragUpdate: (Int, Int) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {}
) {
    val bg = background.copy(alpha = 0.78f)
    val contentColor = if (bg.luminance() < 0.45f) Color(0xFFF8F6F2) else Color(0xFF1C1B1A)
    val density = LocalDensity.current
    val cellScale = com.speeduino.manager.desktop.ui.LocalTableCellScale.current
    val TABLE_CELL_WIDTH = BASE_TABLE_CELL_WIDTH * cellScale
    val TABLE_CELL_HEIGHT = BASE_TABLE_CELL_HEIGHT * cellScale
    val borderModifier = when {
        isSelected -> Modifier.border(3.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp))
        isLiveCursor -> Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
        else -> Modifier
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
        modifier = borderModifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Box(
            modifier = Modifier
                .width(TABLE_CELL_WIDTH)
                .height(TABLE_CELL_HEIGHT)
                .clickable { onClick() }
                .pointerInput(row, col, rowCount, colCount, invertRowDrag) {
                    var dragOffset = Offset.Zero
                    val cellWidthPx = with(density) { (TABLE_CELL_WIDTH + TABLE_CELL_SPACING).toPx() }
                    val cellHeightPx = with(density) { (TABLE_CELL_HEIGHT + TABLE_CELL_SPACING).toPx() }
                    val rowSign = if (invertRowDrag) -1 else 1
                    detectDragGestures(
                        onDragStart = {
                            dragOffset = Offset.Zero
                            onDragStart(row, col)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                            val targetCol = (col + (dragOffset.x / cellWidthPx).roundToInt()).coerceIn(0, colCount - 1)
                            val targetRow = (row + rowSign * (dragOffset.y / cellHeightPx).roundToInt()).coerceIn(0, rowCount - 1)
                            onDragUpdate(targetRow, targetCol)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd
                    )
                }
                .padding(vertical = 6.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
        }
    }
}

private data class TableSelection(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int) {
    val minRow get() = minOf(startRow, endRow)
    val maxRow get() = maxOf(startRow, endRow)
    val minCol get() = minOf(startCol, endCol)
    val maxCol get() = maxOf(startCol, endCol)
    fun contains(row: Int, col: Int) = row in minRow..maxRow && col in minCol..maxCol
}

private enum class SelectionAction { SET_VALUE, ADD_DELTA, INTERPOLATE, SMOOTH }

private fun applySetValue(grid: List<List<Int>>, sel: TableSelection, value: Int, range: IntRange): List<List<Int>> {
    val clamped = value.coerceIn(range)
    return grid.mapIndexed { r, row ->
        if (r < sel.minRow || r > sel.maxRow) row
        else row.mapIndexed { c, v -> if (c in sel.minCol..sel.maxCol) clamped else v }
    }
}

private fun applyAddDelta(grid: List<List<Int>>, sel: TableSelection, delta: Int, range: IntRange): List<List<Int>> {
    return grid.mapIndexed { r, row ->
        if (r < sel.minRow || r > sel.maxRow) row
        else row.mapIndexed { c, v -> if (c in sel.minCol..sel.maxCol) (v + delta).coerceIn(range) else v }
    }
}

private fun applyInterpolate(grid: List<List<Int>>, sel: TableSelection, range: IntRange): List<List<Int>> {
    val startValue = grid.getOrNull(sel.minRow)?.getOrNull(sel.minCol) ?: return grid
    val endValue = grid.getOrNull(sel.maxRow)?.getOrNull(sel.maxCol) ?: return grid
    val rowSpan = (sel.maxRow - sel.minRow).coerceAtLeast(1)
    val colSpan = (sel.maxCol - sel.minCol).coerceAtLeast(1)
    return grid.mapIndexed { r, row ->
        if (r < sel.minRow || r > sel.maxRow) row
        else row.mapIndexed { c, v ->
            if (c !in sel.minCol..sel.maxCol) v
            else {
                val rowProgress = (r - sel.minRow).toFloat() / rowSpan
                val colProgress = (c - sel.minCol).toFloat() / colSpan
                val t = (rowProgress + colProgress) / 2f
                (startValue + (endValue - startValue) * t).roundToInt().coerceIn(range)
            }
        }
    }
}

private fun applySmooth(grid: List<List<Int>>, sel: TableSelection, range: IntRange): List<List<Int>> {
    val rows = grid.size
    val cols = grid.firstOrNull()?.size ?: 0
    return grid.mapIndexed { r, row ->
        row.mapIndexed { c, v ->
            if (r < sel.minRow || r > sel.maxRow || c < sel.minCol || c > sel.maxCol) v
            else {
                var sum = v
                var count = 1
                if (r > 0) { sum += grid[r - 1][c]; count++ }
                if (r < rows - 1) { sum += grid[r + 1][c]; count++ }
                if (c > 0) { sum += grid[r][c - 1]; count++ }
                if (c < cols - 1) { sum += grid[r][c + 1]; count++ }
                (sum.toFloat() / count).roundToInt().coerceIn(range)
            }
        }
    }
}

private fun SharedColor.toComposeColor(): Color {
    return Color(argb)
}

private fun parseAfrValue(text: String): Int? {
    val normalized = text.replace(',', '.')
    return when {
        normalized.contains('.') -> normalized.toFloatOrNull()?.let { (it * 10).toInt() }
        else -> normalized.toIntOrNull()
    }
}

/** O que a tela de tabela precisa para mostrar e acionar o "Desfazer". */
internal data class TableUndoUi(val available: Boolean = false, val onUndo: () -> Unit = {}) {
    companion object {
        val None = TableUndoUi()
    }
}

@Composable
private fun rememberTableUndo(controller: DesktopSpeeduinoController, key: UndoKey): TableUndoUi {
    val available by controller.tableUndoStore.available.collectAsState()
    return TableUndoUi(key in available) { controller.undoTableWrite(key) }
}

@Composable
internal fun TableChangeConfirmationDialog(confirmation: TableChangeConfirmation) {
    val assessment by confirmation.pending.collectAsState()
    val current = assessment ?: return
    val strings = LocalStrings.current
    val tableName = when (current.kind) {
        TableChangeKind.VE -> strings["tableChange.nameVe"]
        TableChangeKind.IGNITION -> strings["tableChange.nameIgnition"]
        TableChangeKind.AFR -> strings["tableChange.nameAfr"]
    }
    val detail = if (current.shapeChanged) {
        strings["tableChange.detailShape"]
    } else {
        strings.format("tableChange.detail", current.largeChangeCells, current.totalCells, current.changedCells)
    }
    AlertDialog(
        onDismissRequest = { confirmation.resolve(false) },
        title = { Text(strings.format("tableChange.title", tableName)) },
        text = {
            Text(
                buildString {
                    appendLine(detail)
                    if (current.bulkReplacement && !current.shapeChanged) {
                        appendLine()
                        appendLine(strings["tableChange.bulk"])
                    }
                    appendLine()
                    append(strings["tableChange.warning"])
                }
            )
        },
        confirmButton = { TextButton(onClick = { confirmation.resolve(true) }) { Text(strings["tableChange.confirm"]) } },
        dismissButton = { TextButton(onClick = { confirmation.resolve(false) }) { Text(strings["action.cancel"]) } }
    )
}

@Composable
internal fun WriteSafetyDialog(gate: WriteSafetyGate) {
    val request by gate.pending.collectAsState()
    val current = request ?: return
    val strings = LocalStrings.current
    val (title, body, confirm) = when (current) {
        WriteSafetyRequest.FirstUseNotice -> Triple(
            strings["writeSafety.noticeTitle"],
            strings["writeSafety.noticeBody"],
            strings["writeSafety.noticeConfirm"],
        )
        is WriteSafetyRequest.VehicleMoving -> Triple(
            strings["writeSafety.movingTitle"],
            strings.format("writeSafety.movingBody", current.speedKmh.toInt()),
            strings["writeSafety.movingConfirm"],
        )
    }
    AlertDialog(
        onDismissRequest = { gate.resolve(false) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = { gate.resolve(true) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = { gate.resolve(false) }) { Text(strings["action.cancel"]) } },
    )
}
