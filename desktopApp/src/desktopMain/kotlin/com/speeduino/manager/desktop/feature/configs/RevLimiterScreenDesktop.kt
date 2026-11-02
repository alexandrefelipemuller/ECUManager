package com.speeduino.manager.desktop.feature.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.LocalStrings
import com.speeduino.manager.desktop.ReportUnsavedChanges
import io.ecucore.model.ConfigValidator

internal val REV_LIMITER_RPM_RANGE = ConfigValidator.PROTECTION_RPM_MIN..ConfigValidator.PROTECTION_RPM_MAX
internal const val REV_LIMITER_RPM_STEP = ConfigValidator.PROTECTION_RPM_STEP

/** Problemas que impedem salvar o limitador (espelha `RevLimiterIssue` do app Android). */
internal enum class RevLimiterIssue { HARD_OUT_OF_RANGE, SOFT_OUT_OF_RANGE, HARD_NOT_STEP, SOFT_NOT_STEP, SOFT_ABOVE_HARD }

/**
 * Valida os dois limites digitados. A ECU guarda RPM/100 em um byte, então valores que não são
 * múltiplos do passo seriam truncados em silêncio ao gravar; aqui viram erro.
 */
internal fun revLimiterIssues(hardText: String, softText: String): Set<RevLimiterIssue> {
    val hard = hardText.trim().toIntOrNull()
    val soft = softText.trim().toIntOrNull()
    val issues = mutableSetOf<RevLimiterIssue>()
    if (hard == null || hard !in REV_LIMITER_RPM_RANGE) issues += RevLimiterIssue.HARD_OUT_OF_RANGE
    if (soft == null || soft !in REV_LIMITER_RPM_RANGE) issues += RevLimiterIssue.SOFT_OUT_OF_RANGE
    if (hard != null && hard in REV_LIMITER_RPM_RANGE && hard % REV_LIMITER_RPM_STEP != 0) issues += RevLimiterIssue.HARD_NOT_STEP
    if (soft != null && soft in REV_LIMITER_RPM_RANGE && soft % REV_LIMITER_RPM_STEP != 0) issues += RevLimiterIssue.SOFT_NOT_STEP
    if (hard != null && soft != null && soft > hard) issues += RevLimiterIssue.SOFT_ABOVE_HARD
    return issues
}

/** Limitador de giros: limite duro (corte) e suave, lidos e gravados via `EngineProtectionConfig`. */
@Composable
internal fun RevLimiterScreenDesktop(controller: DesktopSpeeduinoController, onBack: () -> Unit) {
    val strings = LocalStrings.current
    val config by controller.engineProtectionConfig.collectAsState()
    val connectionState by controller.connectionState.collectAsState()
    val limiterRead = config != null && config!!.hardRevLimitRpm > 0

    var hardEnabled by remember { mutableStateOf(false) }
    var hardText by remember { mutableStateOf("") }
    var softText by remember { mutableStateOf("") }

    fun restoreFromEcu() {
        val loaded = config ?: return
        hardEnabled = loaded.hardRevLimitEnabled
        hardText = loaded.hardRevLimitRpm.toString()
        softText = loaded.softRevLimitRpm.toString()
    }
    LaunchedEffect(config) { restoreFromEcu() }
    LaunchedEffect(connectionState.isConnected) {
        if (connectionState.isConnected) controller.loadEngineProtectionConfig()
    }

    val loaded = config
    val hasChanges = loaded != null && limiterRead && (
        hardEnabled != loaded.hardRevLimitEnabled ||
            hardText != loaded.hardRevLimitRpm.toString() ||
            softText != loaded.softRevLimitRpm.toString()
        )
    ReportUnsavedChanges(hasChanges)
    val issues = if (limiterRead) revLimiterIssues(hardText, softText) else emptySet()

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(strings["config_rev_limiter_title"], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(strings["config_rev_limiter_desc"], style = MaterialTheme.typography.bodyMedium)
        when {
            loaded == null -> Text(strings["rev_limiter_not_loaded"])
            !limiterRead -> Text(strings["rev_limiter_unsupported"])
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(strings["rev_limiter_hard_enabled_label"], fontWeight = FontWeight.Medium)
                        Text(strings["rev_limiter_hard_enabled_desc"], style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = hardEnabled, onCheckedChange = { hardEnabled = it })
                }
                RpmField(
                    label = strings["rev_limiter_hard_rpm_label"],
                    description = strings["rev_limiter_hard_rpm_desc"],
                    value = hardText,
                    outOfRange = RevLimiterIssue.HARD_OUT_OF_RANGE in issues,
                    extraError = if (RevLimiterIssue.HARD_NOT_STEP in issues) strings.format("rev_limiter_error_step", REV_LIMITER_RPM_STEP) else null,
                    onValueChange = { hardText = it },
                )
                RpmField(
                    label = strings["rev_limiter_soft_rpm_label"],
                    description = strings["rev_limiter_soft_rpm_desc"],
                    value = softText,
                    outOfRange = RevLimiterIssue.SOFT_OUT_OF_RANGE in issues,
                    extraError = when {
                        RevLimiterIssue.SOFT_NOT_STEP in issues -> strings.format("rev_limiter_error_step", REV_LIMITER_RPM_STEP)
                        RevLimiterIssue.SOFT_ABOVE_HARD in issues -> strings["rev_limiter_error_soft_above_hard"]
                        else -> null
                    },
                    onValueChange = { softText = it },
                )
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = hasChanges && connectionState.isConnected && issues.isEmpty(),
            onClick = {
                val base = config ?: return@Button
                controller.saveEngineProtectionConfig(
                    base.copy(
                        hardRevLimitEnabled = hardEnabled,
                        hardRevLimitRpm = hardText.trim().toInt(),
                        softRevLimitRpm = softText.trim().toInt(),
                    )
                )
            },
        ) { Text(strings["btn_save"]) }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = hasChanges, onClick = { restoreFromEcu() }) {
            Text(strings["btn_restore_ecu_values"])
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = connectionState.isConnected,
            onClick = { controller.loadEngineProtectionConfig() },
        ) { Text(strings["btn_load"]) }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onBack) { Text(strings["btn_back"]) }
    }
}

@Composable
private fun RpmField(
    label: String,
    description: String,
    value: String,
    outOfRange: Boolean,
    extraError: String?,
    onValueChange: (String) -> Unit,
) {
    val notANumber = value.trim().toIntOrNull() == null
    val rangeError = outOfRange && !notANumber
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = notANumber || rangeError || extraError != null,
        supportingText = {
            Text(
                when {
                    extraError != null && !notANumber && !rangeError -> extraError
                    rangeError -> "$description ${REV_LIMITER_RPM_RANGE.first}..${REV_LIMITER_RPM_RANGE.last}"
                    else -> description
                }
            )
        },
    )
}
