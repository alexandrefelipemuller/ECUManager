package com.speeduino.manager.desktop.feature.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.speeduino.manager.desktop.RestorePoint
import com.speeduino.manager.desktop.ui.chooseSaveFile
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pontos de restauração: salvar a configuração atual, restaurar na ECU, exportar ZIP/MSQ, renomear e excluir. */
@Composable
internal fun RestorePointsScreenDesktop(controller: DesktopSpeeduinoController) {
    val strings = LocalStrings.current
    val points by controller.restorePoints.collectAsState()
    val configState by controller.configState.collectAsState()
    val connectionState by controller.connectionState.collectAsState()
    var pendingRestore by remember { mutableStateOf<RestorePoint?>(null) }
    var pendingDelete by remember { mutableStateOf<RestorePoint?>(null) }
    var pendingRename by remember { mutableStateOf<RestorePoint?>(null) }
    var creating by remember { mutableStateOf(false) }
    val canRestore = connectionState.isConnected && !configState.isBusy

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings["restore_points_title"], style = MaterialTheme.typography.headlineSmall)
        Text(strings["restore_points_description"], style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { creating = true }, enabled = !configState.isBusy, modifier = Modifier.fillMaxWidth()) {
            Text(strings["restore_points_create"])
        }
        if (!connectionState.isConnected) {
            Text(strings["restore_points_connect_hint"], style = MaterialTheme.typography.bodySmall)
        }
        configState.message?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (points.isEmpty()) {
            Text(strings["restore_points_empty"], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
        }
        points.forEach { point ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pointTitle(point, strings.format("restore_points_default_name", point.ecuFamily ?: "ECU")), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(formatDate(point.createdAtEpochMs), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(point.ecuFamily, point.firmware, strings.format("restore_points_pages", point.pageCount), formatSize(point.sizeBytes)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pendingRestore = point }, enabled = canRestore) { Text(strings["title_restore_configs"]) }
                        OutlinedButton(onClick = {
                            chooseSaveFile(strings["btn_save_configs_zip"], exportFileName(point, "zip"))?.let { controller.exportLatestConfig(it, point.dir) }
                        }, enabled = !configState.isBusy) { Text(strings["btn_save_configs_zip"]) }
                        OutlinedButton(onClick = {
                            chooseSaveFile(strings["btn_save_configs_msq"], exportFileName(point, "msq"))?.let { controller.exportLatestConfigMsq(it, point.dir) }
                        }, enabled = !configState.isBusy) { Text(strings["btn_save_configs_msq"]) }
                        OutlinedButton(onClick = { pendingRename = point }) { Text(strings["restore_points_rename"]) }
                        OutlinedButton(onClick = { pendingDelete = point }) { Text(strings["restore_points_delete"]) }
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(strings["restore_points_create"], "", strings["restore_points_save_name"], onDismiss = { creating = false }) {
            creating = false
            controller.createRestorePoint(it.ifBlank { null })
        }
    }
    pendingRename?.let { point ->
        NameDialog(strings["restore_points_rename"], point.name.orEmpty(), strings["restore_points_save_name"], onDismiss = { pendingRename = null }) {
            pendingRename = null
            controller.renameRestorePoint(point, it)
        }
    }
    pendingRestore?.let { point ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(strings["title_restore_configs"]) },
            text = {
                Text(strings.format("restore_points_restore_confirm", pointTitle(point, strings.format("restore_points_default_name", point.ecuFamily ?: "ECU")), formatDate(point.createdAtEpochMs)))
            },
            confirmButton = { Button(onClick = { pendingRestore = null; controller.restoreFromRestorePoint(point) }) { Text(strings["btn_confirm"]) } },
            dismissButton = { OutlinedButton(onClick = { pendingRestore = null }) { Text(strings["action.cancel"]) } },
        )
    }
    pendingDelete?.let { point ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(strings["restore_points_delete_title"]) },
            text = { Text(strings.format("restore_points_delete_confirm", pointTitle(point, strings.format("restore_points_default_name", point.ecuFamily ?: "ECU")))) },
            confirmButton = { Button(onClick = { pendingDelete = null; controller.deleteRestorePoint(point) }) { Text(strings["restore_points_delete"]) } },
            dismissButton = { OutlinedButton(onClick = { pendingDelete = null }) { Text(strings["action.cancel"]) } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val strings = LocalStrings.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                singleLine = true,
                label = { Text(strings["restore_points_name_label"]) },
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text(confirmLabel) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(strings["action.cancel"]) } },
    )
}

private fun pointTitle(point: RestorePoint, fallback: String): String = point.name ?: fallback

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f MB".format(Locale.US, bytes / 1048576.0) else "${(bytes + 512) / 1024} KB"

private fun exportFileName(point: RestorePoint, extension: String): String {
    val prefix = (point.ecuFamily ?: "ecu").lowercase(Locale.US)
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(point.createdAtEpochMs))
    return "${prefix}_${if (extension == "msq") "tune" else "backup"}_$stamp.$extension"
}
