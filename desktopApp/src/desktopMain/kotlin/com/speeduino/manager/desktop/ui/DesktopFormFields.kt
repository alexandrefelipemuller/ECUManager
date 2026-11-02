package com.speeduino.manager.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.speeduino.manager.desktop.LocalStrings
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
internal fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.clickable { expanded = true }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Faixa válida (inteiros); fora dela o campo é marcado e o erro diz qual é a faixa. */
    range: IntRange? = null,
    integerOnly: Boolean = false,
) {
    val strings = LocalStrings.current
    val notANumber = isInvalidNumber(value, integerOnly)
    val outOfRange = range != null && isOutOfRange(value, range)
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            isError = notANumber || outOfRange,
            supportingText = when {
                outOfRange -> ({ Text(strings.format("field_error_out_of_range", range!!.first, range.last)) })
                notANumber -> ({ Text(strings["field_error_invalid_number"]) })
                else -> null
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun ToggleField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun chooseSaveFile(title: String, defaultName: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val file = dialog.file ?: return null
    val directory = dialog.directory ?: return null
    return File(directory, file)
}

internal fun chooseOpenFile(title: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.file ?: return null
    val directory = dialog.directory ?: return null
    return File(directory, file)
}
