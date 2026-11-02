package com.speeduino.manager.desktop.feature.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.speeduino.manager.desktop.AppLanguage
import com.speeduino.manager.desktop.AppProtocol
import com.speeduino.manager.desktop.ConnectionType
import com.speeduino.manager.desktop.DiagnosticLoggerMode
import com.speeduino.manager.desktop.InitialScreen
import com.speeduino.manager.desktop.DesktopDashboardMode
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.IniSelectionMode
import com.speeduino.manager.desktop.IniSelectionSource
import com.speeduino.manager.desktop.LocalizationManager
import com.speeduino.manager.desktop.LocalStrings
import com.speeduino.manager.desktop.MANUAL_FIRMWARE_PROFILES
import com.speeduino.manager.desktop.ThemeManager
import com.speeduino.manager.desktop.ThemeMode
import com.speeduino.manager.desktop.ui.chooseOpenFile
import com.speeduino.manager.desktop.ui.chooseSaveFile
import com.speeduino.manager.desktop.ui.NumberField
import com.speeduino.manager.desktop.ui.DropdownField
import com.speeduino.manager.desktop.SHIFT_LIGHT_RPM_MAX
import com.speeduino.manager.desktop.SHIFT_LIGHT_RPM_MIN
import io.ecucore.units.UnitSystem
import io.ecucore.units.resolveEffectiveUnitSystem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SettingsScreen(controller: DesktopSpeeduinoController) {
    val strings = LocalStrings.current
    val language by LocalizationManager.language.collectAsState()
    val themeMode by ThemeManager.themeMode.collectAsState()
    val configState by controller.configState.collectAsState()
    val desktopSettings by controller.desktopSettings.collectAsState()
    val availableIniDefinitions by controller.availableIniDefinitions.collectAsState()
    val importedIniDefinitions by controller.importedIniDefinitions.collectAsState()
    val diagnosticSummary by controller.diagnosticSummary.collectAsState()
    val readOnlySafeMode by controller.readOnlySafeMode.collectAsState()
    val languageOptions = listOf(
        AppLanguage.EN to "English",
        AppLanguage.PT to "Português",
        AppLanguage.ES to "Español",
        AppLanguage.FR to "Français",
        AppLanguage.ID to "Bahasa Indonesia",
        AppLanguage.ZH to "中文"
    )
    var draftSettings by remember(desktopSettings) { mutableStateOf(desktopSettings) }
    var shiftLightRpmText by remember(desktopSettings.shiftLightRpm) {
        mutableStateOf(desktopSettings.shiftLightRpm.toString())
    }
    val effectiveUnitSystem = resolveEffectiveUnitSystem(draftSettings.unitSystem)
    val selectedLabel = languageOptions.firstOrNull { it.first == language }?.second
        ?: "English"

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
                    text = strings["app.settingsTitle"],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = strings["app.sectionTitle"],
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
                DropdownField(
                    label = strings["app.languageLabel"],
                    value = selectedLabel,
                    options = languageOptions.map { it.second }
                ) { label ->
                    val selected = languageOptions.firstOrNull { it.second == label }?.first
                        ?: AppLanguage.EN
                    LocalizationManager.setLanguage(selected)
                }

                DropdownField(
                    label = strings["label.themeTitle"],
                    value = when (themeMode) {
                        ThemeMode.LIGHT -> strings["label.themeLight"]
                        ThemeMode.DARK -> strings["label.themeDark"]
                    },
                    options = listOf(strings["label.themeLight"], strings["label.themeDark"])
                ) { label ->
                    ThemeManager.setThemeMode(
                        if (label == strings["label.themeDark"]) ThemeMode.DARK else ThemeMode.LIGHT
                    )
                }

                DropdownField(
                    label = strings["label.unitSystem"],
                    value = when (draftSettings.unitSystem) {
                        UnitSystem.AUTO -> strings["label.automaticDetection"]
                        UnitSystem.METRIC -> strings["label.metric"]
                        UnitSystem.IMPERIAL -> strings["label.imperial"]
                    },
                    options = listOf(strings["label.automaticDetection"], strings["label.metric"], strings["label.imperial"])
                ) { label ->
                    draftSettings = draftSettings.copy(
                        unitSystem = when (label) {
                            strings["label.metric"] -> UnitSystem.METRIC
                            strings["label.imperial"] -> UnitSystem.IMPERIAL
                            else -> UnitSystem.AUTO
                        }
                    )
                }
                Text(
                    text = strings.format("label.effectiveUnitSystemValue", when (effectiveUnitSystem) {
                        UnitSystem.AUTO -> strings["label.automaticDetection"]
                        UnitSystem.METRIC -> strings["label.metric"]
                        UnitSystem.IMPERIAL -> strings["label.imperial"]
                    }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                DropdownField(
                    label = strings["label.dashboardModeTitle"],
                    value = dashboardModeLabel(draftSettings.dashboardMode, strings),
                    options = DesktopDashboardMode.entries.map { dashboardModeLabel(it, strings) }
                ) { label ->
                    draftSettings = draftSettings.copy(
                        dashboardMode = DesktopDashboardMode.entries.first { dashboardModeLabel(it, strings) == label }
                    )
                }

                DropdownField(
                    label = strings["settings_table_density_title"],
                    value = tableDensityLabel(draftSettings.tableDensity, strings),
                    options = com.speeduino.manager.desktop.TableDensity.entries.map { tableDensityLabel(it, strings) }
                ) { label ->
                    draftSettings = draftSettings.copy(
                        tableDensity = com.speeduino.manager.desktop.TableDensity.entries.first { tableDensityLabel(it, strings) == label }
                    )
                }
                Text(strings["settings_table_density_help"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                ImportedDashboardsSection(
                    activeId = draftSettings.importedDashboardId,
                    onActiveChange = {
                        // Aplica na hora: o dashboard importado não deve depender do botão Salvar.
                        draftSettings = draftSettings.copy(importedDashboardId = it)
                        controller.saveDesktopSettings(draftSettings)
                    },
                )

                DropdownField(
                    label = strings["label.connectionType"],
                    value = connectionTypeLabel(draftSettings.lastConnectionType ?: ConnectionType.TCP, strings),
                    options = ConnectionType.entries.map { connectionTypeLabel(it, strings) }
                ) { label ->
                    draftSettings = draftSettings.copy(
                        lastConnectionType = ConnectionType.entries.first { connectionTypeLabel(it, strings) == label }
                    )
                }

                DropdownField(
                    label = strings["label.diagLoggerMode"],
                    value = diagnosticLoggerLabel(draftSettings.diagnosticLoggerMode, strings),
                    options = listOf(
                        diagnosticLoggerLabel(DiagnosticLoggerMode.OFF, strings),
                        diagnosticLoggerLabel(DiagnosticLoggerMode.TOOTH, strings),
                        diagnosticLoggerLabel(DiagnosticLoggerMode.COMPOSITE, strings)
                    )
                ) { label ->
                    draftSettings = draftSettings.copy(
                        diagnosticLoggerMode = when (label) {
                            diagnosticLoggerLabel(DiagnosticLoggerMode.TOOTH, strings) -> DiagnosticLoggerMode.TOOTH
                            diagnosticLoggerLabel(DiagnosticLoggerMode.COMPOSITE, strings) -> DiagnosticLoggerMode.COMPOSITE
                            else -> DiagnosticLoggerMode.OFF
                        }
                    )
                }

                DropdownField(
                    label = strings["label.protocol"],
                    value = when (draftSettings.protocol) {
                        AppProtocol.MS_PROTOCOL -> strings["label.speeduinoMs"]
                        AppProtocol.ELM327_OBD2 -> strings["label.elm327Obd2"]
                    },
                    options = listOf(strings["label.speeduinoMs"], strings["label.elm327Obd2"])
                ) { label ->
                    draftSettings = draftSettings.copy(
                        protocol = if (label == strings["label.elm327Obd2"]) AppProtocol.ELM327_OBD2 else AppProtocol.MS_PROTOCOL
                    )
                }

                DropdownField(
                    label = strings["label.initialScreenTitle"],
                    value = when (draftSettings.initialScreen) {
                        InitialScreen.HOME -> strings["label.initialScreenHome"]
                        InitialScreen.DASHBOARD -> strings["label.initialScreenDashboard"]
                    },
                    options = listOf(strings["label.initialScreenHome"], strings["label.initialScreenDashboard"])
                ) { label ->
                    draftSettings = draftSettings.copy(
                        initialScreen = if (label == strings["label.initialScreenDashboard"]) InitialScreen.DASHBOARD else InitialScreen.HOME
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings["label.autoConnectOnStart"],
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = strings["label.autoConnectOnStartHelp"],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = draftSettings.autoConnectOnStart,
                        onCheckedChange = { checked ->
                            draftSettings = draftSettings.copy(autoConnectOnStart = checked)
                        }
                    )
                }

                NumberField(
                    label = strings["label.shiftLightRpm"],
                    value = shiftLightRpmText,
                    onValueChange = { value ->
                        val filtered = value.filter(Char::isDigit)
                        if (filtered.isNotBlank()) {
                            shiftLightRpmText = filtered
                            filtered.toIntOrNull()?.let { parsed ->
                                draftSettings = draftSettings.copy(
                                    shiftLightRpm = parsed.coerceIn(SHIFT_LIGHT_RPM_MIN, SHIFT_LIGHT_RPM_MAX)
                                )
                            }
                        }
                    }
                )
                Text(
                    text = strings.format("label.shiftLightRpmRange", SHIFT_LIGHT_RPM_MIN, SHIFT_LIGHT_RPM_MAX),
                    style = MaterialTheme.typography.bodySmall,
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
                Text(
                    text = strings["label.iniDefinition"],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                DropdownField(
                    label = strings["label.definitionMode"],
                    value = if (draftSettings.iniSelectionMode == IniSelectionMode.AUTOMATIC) strings["label.automaticDetection"] else strings["label.manual"],
                    options = listOf(strings["label.automaticDetection"], strings["label.manual"])
                ) { label ->
                    draftSettings = draftSettings.copy(
                        iniSelectionMode = if (label == strings["label.automaticDetection"]) IniSelectionMode.AUTOMATIC else IniSelectionMode.MANUAL,
                        iniDefinitionId = if (label == strings["label.automaticDetection"]) null else draftSettings.iniDefinitionId
                    )
                }
                if (draftSettings.iniSelectionMode == IniSelectionMode.MANUAL) {
                    DropdownField(
                        label = strings["label.definitionSource"],
                        value = if (draftSettings.iniSelectionSource == IniSelectionSource.CATALOG) strings["label.remoteCatalog"] else strings["label.importedFile"],
                        options = listOf(strings["label.remoteCatalog"], strings["label.importedFile"])
                    ) { label ->
                        draftSettings = draftSettings.copy(
                            iniSelectionSource = if (label == strings["label.remoteCatalog"]) IniSelectionSource.CATALOG else IniSelectionSource.IMPORTED,
                            iniDefinitionId = null
                        )
                    }
                    val definitionOptions = if (draftSettings.iniSelectionSource == IniSelectionSource.CATALOG) {
                        availableIniDefinitions.map { "${it.id} (${it.version})" }
                    } else {
                        importedIniDefinitions.map { "${it.fileName} (${it.signature})" }
                    }
                    val selectedDefinition = when (draftSettings.iniSelectionSource) {
                        IniSelectionSource.CATALOG -> availableIniDefinitions.firstOrNull { it.id == draftSettings.iniDefinitionId }
                            ?.let { "${it.id} (${it.version})" }
                        IniSelectionSource.IMPORTED -> importedIniDefinitions.firstOrNull { it.fileName == draftSettings.iniDefinitionId }
                            ?.let { "${it.fileName} (${it.signature})" }
                    }.orEmpty()
                    DropdownField(
                        label = strings["label.definitionValue"],
                        value = selectedDefinition.ifBlank { strings["app.noOptions"] },
                        options = definitionOptions.ifEmpty { listOf(strings["app.noOptions"]) }
                    ) { label ->
                        draftSettings = draftSettings.copy(
                            iniDefinitionId = when (draftSettings.iniSelectionSource) {
                                IniSelectionSource.CATALOG -> availableIniDefinitions.firstOrNull { "${it.id} (${it.version})" == label }?.id
                                IniSelectionSource.IMPORTED -> importedIniDefinitions.firstOrNull { "${it.fileName} (${it.signature})" == label }?.fileName
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = { controller.refreshIniDefinitions(forceCatalogRefresh = true) }) {
                            Text(strings["label.refreshCatalog"])
                        }
                        FilledTonalButton(onClick = {
                            val source = chooseOpenFile(strings["label.importIniFileDialog"])
                            if (source != null) {
                                controller.importIniDefinition(source)
                            }
                        }) {
                            Text(strings["label.importIni"])
                        }
                    }
                }
                DropdownField(
                    label = strings["label.diagLoggerMode"],
                    value = when (draftSettings.diagnosticLoggerMode) {
                        DiagnosticLoggerMode.OFF -> strings["label.off"]
                        DiagnosticLoggerMode.TOOTH -> strings["label.tooth"]
                        DiagnosticLoggerMode.COMPOSITE -> strings["label.composite"]
                    },
                    options = listOf(strings["label.off"], strings["label.tooth"], strings["label.composite"])
                ) { label ->
                    draftSettings = draftSettings.copy(
                        diagnosticLoggerMode = when (label) {
                            strings["label.tooth"] -> DiagnosticLoggerMode.TOOTH
                            strings["label.composite"] -> DiagnosticLoggerMode.COMPOSITE
                            else -> DiagnosticLoggerMode.OFF
                        }
                    )
                }

                DropdownField(
                    label = strings["label.manualFirmwareProfile"],
                    value = MANUAL_FIRMWARE_PROFILES.firstOrNull { it.signature == draftSettings.manualFirmwareProfile }?.label
                        ?: strings["label.automaticDetection"],
                    options = listOf(strings["label.automaticDetection"]) + MANUAL_FIRMWARE_PROFILES.map { it.label }
                ) { label ->
                    draftSettings = draftSettings.copy(
                        manualFirmwareProfile = MANUAL_FIRMWARE_PROFILES.firstOrNull { it.label == label }?.signature
                    )
                }
                Text(
                    text = strings["label.manualFirmwareHelp"],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = strings.format("label.currentLoggerValue", diagnosticSummary.diagnosticLoggerMode.name.lowercase().replace('_', ' ')),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (readOnlySafeMode || diagnosticSummary.readOnlySafeMode) {
                    Text(
                        text = strings["label.readOnlySafeModeActive"],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                FilledTonalButton(
                    onClick = { controller.saveDesktopSettings(draftSettings) },
                    enabled = draftSettings != desktopSettings &&
                        (draftSettings.iniSelectionMode != IniSelectionMode.MANUAL || draftSettings.iniDefinitionId != null)
                ) {
                    Text(strings["label.saveDefinitionSettings"])
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
                Text(
                    text = strings["label.backupTitle"],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = strings["label.backupSubtitle"],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { controller.downloadAllConfigs() },
                        enabled = !configState.isBusy
                    ) {
                        Text(strings["action.downloadConfig"])
                    }
                    FilledTonalButton(
                        onClick = {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val target = chooseSaveFile(
                                title = strings["label.backupSaveTitle"],
                                defaultName = "speeduino_backup_$timestamp.zip"
                            )
                            if (target != null) {
                                controller.exportLatestConfig(target)
                            }
                        },
                        enabled = !configState.isBusy
                    ) {
                        Text(strings["action.exportConfig"])
                    }
                    FilledTonalButton(
                        onClick = {
                            val source = chooseOpenFile(strings["label.backupOpenTitle"])
                            if (source != null) {
                                controller.importConfigAndRestore(source)
                            }
                        },
                        enabled = !configState.isBusy
                    ) {
                        Text(strings["action.importConfig"])
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val target = chooseSaveFile(
                                title = strings["label.msqSaveTitle"],
                                defaultName = "speeduino_tune_$timestamp.msq"
                            )
                            if (target != null) {
                                controller.exportLatestConfigMsq(target)
                            }
                        },
                        enabled = !configState.isBusy
                    ) {
                        Text(strings["action.exportConfigMsq"])
                    }
                    FilledTonalButton(
                        onClick = {
                            val source = chooseOpenFile(strings["label.msqOpenTitle"])
                            if (source != null) {
                                controller.importConfigFromMsq(source)
                            }
                        },
                        enabled = !configState.isBusy
                    ) {
                        Text(strings["action.importConfigMsq"])
                    }
                }
                if (configState.isBusy) {
                    Text(
                        text = strings.format("label.configProgress", configState.progressPercent),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { configState.progressPercent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                val message = configState.message
                if (!message.isNullOrBlank()) {
                    Text(
                        text = strings.format("label.configStatus", message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


private fun dashboardModeLabel(mode: DesktopDashboardMode, strings: com.speeduino.manager.desktop.Strings): String {
    return when (mode) {
        DesktopDashboardMode.DEFAULT -> strings["label.dashboardModeDefault"]
        DesktopDashboardMode.PETROL -> strings["label.dashboardModePetrol"]
        DesktopDashboardMode.FUTURE -> strings["label.dashboardModeFuture"]
        DesktopDashboardMode.APEX -> strings["label.dashboardModeApex"]
    }
}


private fun connectionTypeLabel(type: ConnectionType, strings: com.speeduino.manager.desktop.Strings): String {
    return type.label(strings)
}


private fun diagnosticLoggerLabel(mode: DiagnosticLoggerMode, strings: com.speeduino.manager.desktop.Strings): String {
    return when (mode) {
        DiagnosticLoggerMode.OFF -> strings["label.off"]
        DiagnosticLoggerMode.TOOTH -> strings["label.tooth"]
        DiagnosticLoggerMode.COMPOSITE -> strings["label.composite"]
    }
}

/** Gerencia os dashboards RealDash (.rd) importados: escolher o ativo, importar e excluir. */
@Composable
private fun ImportedDashboardsSection(activeId: String?, onActiveChange: (String?) -> Unit) {
    val strings = LocalStrings.current
    val store = remember { com.speeduino.manager.desktop.realdash.ImportedDashboardStore() }
    var items by remember { mutableStateOf(store.list()) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings["dashboard_import_dialog_title"], style = MaterialTheme.typography.titleSmall)
        Text(strings["dashboard_import_dialog_message"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { info ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = info.id == activeId, onClick = { onActiveChange(if (info.id == activeId) null else info.id) })
                Text(info.name, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = {
                    store.delete(info.id)
                    items = store.list()
                    if (activeId == info.id) onActiveChange(null)
                }) { Text(strings["dashboard_import_delete"]) }
            }
        }
        FilledTonalButton(onClick = {
            val file = chooseOpenFile(strings["dashboard_import_dialog_title"]) ?: return@FilledTonalButton
            runCatching { store.import(file.readBytes(), file.name) }
                .onSuccess { info ->
                    items = store.list()
                    onActiveChange(info.id)
                    message = strings.format("dashboard_import_success", info.name)
                }
                .onFailure { message = strings["dashboard_import_failed"] + ": " + (it.message ?: "") }
        }) { Text(strings["dashboard_import_dialog_confirm"]) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun tableDensityLabel(density: com.speeduino.manager.desktop.TableDensity, strings: com.speeduino.manager.desktop.Strings): String = when (density) {
    com.speeduino.manager.desktop.TableDensity.DEFAULT -> strings["settings_table_density_default"]
    com.speeduino.manager.desktop.TableDensity.LARGE -> strings["settings_table_density_large"]
    com.speeduino.manager.desktop.TableDensity.EXTRA_LARGE -> strings["settings_table_density_extra_large"]
}
