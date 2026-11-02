package com.speeduino.manager.desktop.feature.configs

import io.ecucore.io.ActuatorConfig
import io.ecucore.io.ActuatorControlMode
import io.ecucore.io.ActuatorType
import io.ecucore.io.CompareOp
import io.ecucore.io.Conversion
import io.ecucore.io.ExpressionConversion
import io.ecucore.io.IoChannel
import io.ecucore.io.IoChannelType
import io.ecucore.io.IoConfigEngine
import io.ecucore.io.IoStatus
import io.ecucore.io.LinearConversion
import io.ecucore.io.SensorConfig
import io.ecucore.io.TableConversion
import io.ecucore.io.TablePoint
import io.ecucore.io.VirtualBitConfig
import io.ecucore.io.VirtualCompareConfig
import io.ecucore.io.VirtualConfig
import io.ecucore.io.VirtualMode
import io.ecucore.io.VirtualOutputFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.speeduino.manager.desktop.LocalStrings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ecucore.EcuLiveData
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.ui.DropdownField
import com.speeduino.manager.desktop.ui.NumberField
import com.speeduino.manager.io.*
import io.ecucore.model.EcuOutputChannels

@Composable
internal fun InputOutputEditorScreenDesktop(
    controller: DesktopSpeeduinoController,
    onOpenSecondarySerial: () -> Unit,
) {
    val strings = LocalStrings.current
    val tuningState by controller.tuningConfigState.collectAsState()
    val liveData by controller.liveData.collectAsState()
    val snapshot = tuningState.rusefiSnapshot
    val repo = remember { IoConfigRepository() }
    val engine = remember { IoConfigEngine() }
    var channels by remember { mutableStateOf(repo.load()) }
    var selectedTab by remember { mutableStateOf(IoChannelType.SENSOR) }
    var editing by remember { mutableStateOf<IoChannel?>(null) }
    var previewing by remember { mutableStateOf<IoChannel?>(null) }
    var deleting by remember { mutableStateOf<IoChannel?>(null) }

    fun persist(updated: List<IoChannel>) {
        channels = updated
        repo.save(updated)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings["label.inputOutputCardTitle"], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(strings["label.ioLoadInstructions"], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onOpenSecondarySerial) { Text(strings["label.secondarySerialTitle"]) }
                    FilledTonalButton(onClick = controller::loadRusefiInputOutputSnapshot) { Text(strings["label.rusefiSnapshotAction"]) }
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab.ordinal) {
            IoChannelType.values().forEach { tab ->
                Tab(selected = selectedTab == tab, onClick = { selectedTab = tab }, text = { Text(when (tab) {
                    IoChannelType.SENSOR -> strings["label.ioTabSensors"]
                    IoChannelType.ACTUATOR -> strings["label.ioTabActuators"]
                    IoChannelType.VIRTUAL -> strings["label.ioTabVirtual"]
                }) })
            }
        }

        val filtered = channels.filter { it.type == selectedTab }
        if (filtered.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.format("label.ioNoChannels", selectedTab.name.lowercase()))
                    Button(onClick = { editing = defaultChannel(selectedTab) }) {
                        androidx.compose.material3.Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(strings["label.ioAddChannel"])
                    }
                }
            }
        } else {
            filtered.forEach { channel ->
                val status = engine.validate(channel)
                val computed = engine.evaluate(channel, liveData)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(status.name, color = when (status) {
                                IoStatus.OK -> MaterialTheme.colorScheme.primary
                                IoStatus.ERROR -> MaterialTheme.colorScheme.error
                                IoStatus.LOCAL_ONLY -> MaterialTheme.colorScheme.tertiary
                            })
                        }
                        Text(buildChannelSummary(channel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(strings.format("label.ioPreviewPrefix", "${computed.displayValue}${computed.unit?.let { " $it" } ?: ""}"), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { previewing = channel }) {
                                androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Text(strings["label.ioTest"])
                            }
                            TextButton(onClick = { editing = channel }) { Text(strings["label.ioEdit"]) }
                            TextButton(onClick = { deleting = channel }) {
                                androidx.compose.material3.Icon(Icons.Default.Delete, contentDescription = null)
                                Text(strings["label.ioRemoveChannel"])
                            }
                        }
                    }
                }
            }
        }

        Button(onClick = { editing = defaultChannel(selectedTab) }) {
            androidx.compose.material3.Icon(Icons.Default.Add, contentDescription = null)
            Text(strings["label.ioAddChannel"])
        }

        snapshot?.let {
            SnapshotSection(strings["label.rusefiInputs"], it.inputs.map { entry -> "${entry.label}: ${entry.value}" })
            SnapshotSection(strings["label.rusefiFuelOutputs"], it.fuelOutputs.map { entry -> "${entry.label}: ${entry.value}" })
            SnapshotSection(strings["label.rusefiIgnitionOutputs"], it.ignitionOutputs.map { entry -> "${entry.label}: ${entry.value}" })
            SnapshotSection(strings["label.rusefiAuxOutputs"], it.auxiliaryOutputs.map { entry -> "${entry.label}: ${entry.value}" })
        }
    }

    editing?.let { channel ->
        IoChannelEditorDialog(
            initial = channel,
            onDismiss = { editing = null },
            onSave = { updated ->
                val existingIndex = channels.indexOfFirst { it.id == updated.id }
                val next = if (existingIndex >= 0) channels.map { if (it.id == updated.id) updated else it } else channels + updated
                persist(next)
                editing = null
            }
        )
    }

    previewing?.let { channel ->
        val computed = engine.evaluate(channel, liveData)
        AlertDialog(
            onDismissRequest = { previewing = null },
            title = { Text(strings["label.ioChannelPreview"]) },
            text = { Text(strings.format("label.ioPreviewValue", channel.name, computed.displayValue, computed.unit ?: "")) },
            confirmButton = { TextButton(onClick = { previewing = null }) { Text(strings["label.ioClose"]) } }
        )
    }

    deleting?.let { channel ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(strings["label.ioRemoveChannel"]) },
            text = { Text(strings.format("label.ioRemoveChannelBody", channel.name)) },
            confirmButton = {
                TextButton(onClick = {
                    persist(channels.filter { it.id != channel.id })
                    deleting = null
                }) { Text(strings["label.ioRemoveChannel"]) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(strings["label.ioCancel"]) } }
        )
    }
}

@Composable
private fun SnapshotSection(title: String, lines: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            lines.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun buildChannelSummary(channel: IoChannel): String {
    return when (val config = channel.config) {
        is SensorConfig -> "${config.sourceChannel} • ${config.conversion::class.simpleName}"
        is VirtualConfig -> if (config.mode == VirtualMode.EXPRESSION) "Expression" else "${config.sourceChannel} • ${config.operation?.opType}"
        is ActuatorConfig -> "${config.outputName} • ${config.outputType}"
    }
}

private fun defaultChannel(type: IoChannelType): IoChannel {
    val catalog = EcuOutputChannels.getCatalog()
    return when (type) {
        IoChannelType.SENSOR -> IoChannel(name = "New Sensor", type = type, config = SensorConfig(catalog.firstOrNull()?.name ?: "rpm", LinearConversion(0.0, 0.0, 1023.0, 5.0, "V")))
        IoChannelType.VIRTUAL -> IoChannel(name = "New Virtual", type = type, config = VirtualConfig(mode = VirtualMode.SIMPLE, sourceChannel = catalog.firstOrNull()?.name ?: "rpm", operation = VirtualCompareConfig(CompareOp.GT, 1000.0)))
        IoChannelType.ACTUATOR -> IoChannel(name = "New Actuator", type = type, config = ActuatorConfig(outputName = "fan", outputType = ActuatorType.DIGITAL, controlMode = ActuatorControlMode.MANUAL, manualValue = 0.0))
    }
}

@Composable
private fun IoChannelEditorDialog(
    initial: IoChannel,
    onDismiss: () -> Unit,
    onSave: (IoChannel) -> Unit,
) {
    val strings = LocalStrings.current
    val catalog = remember { EcuOutputChannels.getCatalog().map { it.name } }
    var name by remember(initial) { mutableStateOf(initial.name) }
    var type by remember(initial) { mutableStateOf(initial.type) }
    var sourceChannel by remember(initial) { mutableStateOf((initial.config as? SensorConfig)?.sourceChannel ?: (initial.config as? VirtualConfig)?.sourceChannel ?: catalog.firstOrNull().orEmpty()) }
    var conversionType by remember(initial) { mutableStateOf(if ((initial.config as? SensorConfig)?.conversion is TableConversion) "TABLE" else if ((initial.config as? SensorConfig)?.conversion is ExpressionConversion) "EXPRESSION" else "LINEAR") }
    var unit by remember(initial) { mutableStateOf(((initial.config as? SensorConfig)?.conversion?.unit).orEmpty()) }
    var x1 by remember(initial) { mutableStateOf(((initial.config as? SensorConfig)?.conversion as? LinearConversion)?.x1?.toString() ?: "0") }
    var y1 by remember(initial) { mutableStateOf(((initial.config as? SensorConfig)?.conversion as? LinearConversion)?.y1?.toString() ?: "0") }
    var x2 by remember(initial) { mutableStateOf(((initial.config as? SensorConfig)?.conversion as? LinearConversion)?.x2?.toString() ?: "1") }
    var y2 by remember(initial) { mutableStateOf(((initial.config as? SensorConfig)?.conversion as? LinearConversion)?.y2?.toString() ?: "1") }
    var pointsText by remember(initial) { mutableStateOf(((initial.config as? SensorConfig)?.conversion as? TableConversion)?.points?.joinToString(",") { "${it.x}:${it.y}" } ?: "") }
    var expression by remember(initial) { mutableStateOf(((initial.config as? SensorConfig)?.conversion as? ExpressionConversion)?.expression ?: (initial.config as? VirtualConfig)?.expression ?: (initial.config as? ActuatorConfig)?.expression.orEmpty()) }
    var virtualMode by remember(initial) { mutableStateOf((initial.config as? VirtualConfig)?.mode ?: VirtualMode.SIMPLE) }
    var virtualFormat by remember(initial) { mutableStateOf((initial.config as? VirtualConfig)?.outputFormat ?: VirtualOutputFormat.ZERO_ONE) }
    var compareOp by remember(initial) { mutableStateOf(((initial.config as? VirtualConfig)?.operation as? VirtualCompareConfig)?.compareOp ?: CompareOp.GT) }
    var compareValue by remember(initial) { mutableStateOf(((initial.config as? VirtualConfig)?.operation as? VirtualCompareConfig)?.compareValue?.toString() ?: "1000") }
    var bit by remember(initial) { mutableStateOf(((initial.config as? VirtualConfig)?.operation as? VirtualBitConfig)?.bit?.toString() ?: "0") }
    var virtualOpType by remember(initial) { mutableStateOf(if ((initial.config as? VirtualConfig)?.operation is VirtualBitConfig) "BIT" else "COMPARE") }
    var actuatorOutput by remember(initial) { mutableStateOf((initial.config as? ActuatorConfig)?.outputName ?: "fan") }
    var actuatorType by remember(initial) { mutableStateOf((initial.config as? ActuatorConfig)?.outputType ?: ActuatorType.DIGITAL) }
    var actuatorControl by remember(initial) { mutableStateOf((initial.config as? ActuatorConfig)?.controlMode ?: ActuatorControlMode.MANUAL) }
    var manualValue by remember(initial) { mutableStateOf((initial.config as? ActuatorConfig)?.manualValue?.toString() ?: "0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings["label.ioEdit"]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(strings["label.ioName"]) }, modifier = Modifier.fillMaxWidth())
                DropdownField(strings["label.ioType"], type.name, IoChannelType.values().map { it.name }) { value -> type = IoChannelType.valueOf(value) }
                when (type) {
                    IoChannelType.SENSOR -> {
                        DropdownField(strings["label.ioSource"], sourceChannel, catalog) { sourceChannel = it }
                        DropdownField(strings["label.ioConversion"], conversionType, listOf(strings["label.ioLinear"], strings["label.ioTable"], strings["label.ioExpression"])) { conversionType = when (it) { strings["label.ioTable"] -> "TABLE"; strings["label.ioExpression"] -> "EXPRESSION"; else -> "LINEAR" } }
                        if (conversionType == "LINEAR") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField(strings["label.ioLinearX1"], x1, { x1 = it }, Modifier.weight(1f))
                                NumberField(strings["label.ioLinearY1"], y1, { y1 = it }, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField(strings["label.ioLinearX2"], x2, { x2 = it }, Modifier.weight(1f))
                                NumberField(strings["label.ioLinearY2"], y2, { y2 = it }, Modifier.weight(1f))
                            }
                        } else if (conversionType == "TABLE") {
                            OutlinedTextField(value = pointsText, onValueChange = { pointsText = it }, label = { Text(strings["label.ioPoints"]) }, modifier = Modifier.fillMaxWidth())
                        } else {
                            OutlinedTextField(value = expression, onValueChange = { expression = it }, label = { Text(strings["label.ioExpression"]) }, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text(strings["label.ioUnit"]) }, modifier = Modifier.fillMaxWidth())
                    }
                    IoChannelType.VIRTUAL -> {
                        DropdownField(strings["label.ioMode"], virtualMode.name, VirtualMode.values().map { it.name }) { value -> virtualMode = VirtualMode.valueOf(value) }
                        if (virtualMode == VirtualMode.SIMPLE) {
                            DropdownField(strings["label.ioSource"], sourceChannel, catalog) { sourceChannel = it }
                            DropdownField(strings["label.ioOperation"], virtualOpType, listOf(strings["label.ioBit"], strings["label.ioCompare"])) { virtualOpType = when (it) { strings["label.ioBit"] -> "BIT"; else -> "COMPARE" } }
                            if (virtualOpType == "BIT") {
                                NumberField(strings["label.ioBit"], bit, { bit = it }, Modifier.fillMaxWidth())
                            } else {
                                DropdownField(strings["label.ioCompare"], compareOp.name, CompareOp.values().map { it.name }) { value -> compareOp = CompareOp.valueOf(value) }
                                NumberField(strings["label.ioCompareValue"], compareValue, { compareValue = it }, Modifier.fillMaxWidth())
                            }
                            DropdownField(strings["label.ioOutputFormat"], virtualFormat.name, VirtualOutputFormat.values().map { it.name }) { value -> virtualFormat = VirtualOutputFormat.valueOf(value) }
                        } else {
                            OutlinedTextField(value = expression, onValueChange = { expression = it }, label = { Text(strings["label.ioExpression"]) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    IoChannelType.ACTUATOR -> {
                        OutlinedTextField(value = actuatorOutput, onValueChange = { actuatorOutput = it }, label = { Text(strings["label.ioOutput"]) }, modifier = Modifier.fillMaxWidth())
                        DropdownField(strings["label.ioActuatorType"], actuatorType.name, ActuatorType.values().map { it.name }) { value -> actuatorType = ActuatorType.valueOf(value) }
                        DropdownField(strings["label.ioControl"], actuatorControl.name, ActuatorControlMode.values().map { it.name }) { value -> actuatorControl = ActuatorControlMode.valueOf(value) }
                        if (actuatorControl == ActuatorControlMode.MANUAL) {
                            NumberField(strings["label.ioManualValue"], manualValue, { manualValue = it }, Modifier.fillMaxWidth())
                        } else {
                            OutlinedTextField(value = expression, onValueChange = { expression = it }, label = { Text(strings["label.ioExpression"]) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = when (type) {
                    IoChannelType.SENSOR -> {
                        val conversion: Conversion = when (conversionType) {
                            "TABLE" -> TableConversion(
                                points = pointsText.split(',').mapNotNull { pair ->
                                    val parts = pair.split(':')
                                    if (parts.size != 2) null else TablePoint(parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null, parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null)
                                },
                                unit = unit,
                            )
                            "EXPRESSION" -> ExpressionConversion(expression, unit)
                            else -> LinearConversion(x1.toDoubleOrNull() ?: 0.0, y1.toDoubleOrNull() ?: 0.0, x2.toDoubleOrNull() ?: 1.0, y2.toDoubleOrNull() ?: 1.0, unit)
                        }
                        initial.copy(name = name, type = type, config = SensorConfig(sourceChannel, conversion))
                    }
                    IoChannelType.VIRTUAL -> {
                        val operation = if (virtualOpType == "BIT") {
                            VirtualBitConfig(bit.toIntOrNull() ?: 0)
                        } else {
                            VirtualCompareConfig(compareOp, compareValue.toDoubleOrNull() ?: 0.0)
                        }
                        initial.copy(name = name, type = type, config = VirtualConfig(mode = virtualMode, sourceChannel = if (virtualMode == VirtualMode.SIMPLE) sourceChannel else null, operation = if (virtualMode == VirtualMode.SIMPLE) operation else null, outputFormat = virtualFormat, expression = if (virtualMode == VirtualMode.EXPRESSION) expression else null))
                    }
                    IoChannelType.ACTUATOR -> initial.copy(name = name, type = type, config = ActuatorConfig(outputName = actuatorOutput, outputType = actuatorType, controlMode = actuatorControl, expression = if (actuatorControl == ActuatorControlMode.EXPRESSION) expression else null, manualValue = if (actuatorControl == ActuatorControlMode.MANUAL) manualValue.toDoubleOrNull() else null))
                }
                onSave(updated)
            }) { Text(strings["label.ioSave"]) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings["label.ioCancel"]) } }
    )
}
