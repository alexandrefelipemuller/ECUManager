package com.speeduino.manager.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.speeduino.manager.desktop.TableChangeConfirmationDialog
import com.speeduino.manager.desktop.WriteSafetyDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import com.speeduino.manager.desktop.ConnectionType
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.InitialScreen
import com.speeduino.manager.desktop.LocalStrings
import com.speeduino.manager.desktop.navigation.DesktopRoute
import com.speeduino.manager.desktop.navigation.KioskNavigationRail
import com.speeduino.manager.desktop.navigation.ScreenHost
import com.speeduino.manager.desktop.navigation.parentRoute
import com.speeduino.manager.desktop.ui.DisconnectedBanner
import com.speeduino.manager.desktop.ui.HeaderBar
import com.speeduino.manager.desktop.ui.chooseSaveFile

@Composable
internal fun DesktopAppShell() {
    val scope = rememberCoroutineScope()
    val controller = remember { DesktopSpeeduinoController(scope) }
    val desktopSettings by controller.desktopSettings.collectAsState()
    val appState = rememberDesktopAppState(desktopSettings)
    if (appState.currentRoute == DesktopRoute.Home) {
        when (desktopSettings.initialScreen) {
            InitialScreen.HOME -> Unit
            InitialScreen.DASHBOARD -> appState.currentRoute = DesktopRoute.Dashboard
        }
    }
    val strings = LocalStrings.current
    var showReportProblemDialog by remember { mutableStateOf(false) }
    var reportProblemText by remember { mutableStateOf("") }

    val connectionState by controller.connectionState.collectAsState()
    val liveData by controller.liveData.collectAsState()
    val configState by controller.configState.collectAsState()
    val serialTunnelState by controller.serialTunnelState.collectAsState()
    val isTunnelActive = serialTunnelState.status != com.speeduino.manager.desktop.SerialTunnelStatus.IDLE

    val portIsValid by remember {
        derivedStateOf { appState.port.toIntOrNull()?.let { it in 1..65535 } == true }
    }
    val onToggleConnection = {
        if (!connectionState.isConnected) {
            when (appState.connectionType) {
                ConnectionType.TCP -> if (portIsValid) {
                    controller.connectTcp(appState.host, appState.port.toInt())
                }
                ConnectionType.USB,
                ConnectionType.BLUETOOTH -> {
                    val baud = appState.baudRate.toIntOrNull() ?: 115200
                    if (appState.serialPort.isNotBlank()) {
                        controller.connectSerial(appState.serialPort, baud, appState.connectionType)
                    }
                }
            }
        } else {
            controller.disconnect()
        }
    }
    val onOpenRoute: (DesktopRoute) -> Unit = { route ->
        if (!isTunnelActive) {
            appState.currentRoute = route
        }
    }
    val onReportProblem = {
        reportProblemText = buildString {
            appendLine("Desktop report summary")
            appendLine("App version: ${com.speeduino.manager.desktop.APP_VERSION}")
            appendLine("Timestamp: ${java.time.ZonedDateTime.now()}")
            appendLine("Route: ${appState.currentRoute}")
            appendLine("Connection: ${connectionState.status}")
            appendLine("Firmware: ${controller.diagnosticSummary.value.firmwareSignature ?: "unknown"}")
            appendLine("Product: ${controller.diagnosticSummary.value.productString ?: "unknown"}")
            appendLine("INI: ${controller.diagnosticSummary.value.activeIniSource ?: "unknown"}")
            appendLine("Logger: ${controller.diagnosticSummary.value.diagnosticLoggerMode}")
            appendLine("Last error: ${controller.lastError.value ?: "none"}")
        }
        showReportProblemDialog = true
    }
    val issueUrl = "https://github.com/alexandrefelipemuller/SpeeduinoManagerDesktop/issues/new"
    val copyReportToClipboard = {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(reportProblemText), null)
        }
    }
    val openIssueTracker = {
        runCatching {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(issueUrl))
            }
        }
    }
    val exportReport = {
        chooseSaveFile("Export report", "speeduino_desktop_report.txt")?.let { file ->
            runCatching {
                file.writeText(reportProblemText)
            }
        }
    }

    val themeMode by com.speeduino.manager.desktop.ThemeManager.themeMode.collectAsState()
    val backgroundBrush = if (themeMode == com.speeduino.manager.desktop.ThemeMode.DARK) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF000000),
                Color(0xFF101312),
                Color(0xFF000000)
            ),
            start = Offset.Zero,
            end = Offset(0f, 1400f)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFF6F1E8),
                Color(0xFFE7EEF0),
                Color(0xFFF6F1E8)
            ),
            start = Offset.Zero,
            end = Offset(0f, 1400f)
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(backgroundBrush)) {
        Column(modifier = Modifier.fillMaxSize()) {
            DisconnectedBanner(connectionState)
            Row(
                modifier = Modifier.weight(1f).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
            KioskNavigationRail(
                currentRoute = appState.currentRoute,
                onRouteSelected = if (isTunnelActive) ({}) else onOpenRoute
            )
            Box(modifier = Modifier.fillMaxSize()) {
                val contentScroll = rememberScrollState()
                val isDashboard = appState.currentRoute == DesktopRoute.Dashboard
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HeaderBar(
                        title = strings[appState.currentRoute.titleKey],
                        connectionState = connectionState,
                        onBackClick = if (isTunnelActive) {
                            null
                        } else {
                            parentRoute(appState.currentRoute)?.let { parent ->
                                { appState.currentRoute = parent }
                            }
                        }
                    )

                    val screenContentModifier = if (isDashboard) {
                        Modifier.weight(1f).fillMaxWidth()
                    } else {
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll)
                    }

                    Box(modifier = screenContentModifier) {
                    ScreenHost(
                                    route = appState.currentRoute,
                                    controller = controller,
                                    connectionState = connectionState,
                                    liveData = liveData,
                                    host = appState.host,
                                    port = appState.port,
                                    portIsValid = portIsValid,
                                    connectionType = appState.connectionType,
                                    serialPort = appState.serialPort,
                                    baudRate = appState.baudRate,
                                    onHostChange = { appState.host = it },
                                    onPortChange = { appState.port = it.filter { ch -> ch.isDigit() } },
                                    onConnectionTypeChange = { appState.connectionType = it },
                                    onSerialPortChange = { appState.serialPort = it },
                                    onBaudRateChange = { appState.baudRate = it.filter { ch -> ch.isDigit() } },
                                    onToggleConnection = onToggleConnection,
                                    onOpenSettings = { appState.currentRoute = DesktopRoute.Settings },
                                    onOpenInstitutional = { appState.currentRoute = DesktopRoute.Institutional },
                                    onReportProblem = onReportProblem,
                                    onOpenRoute = onOpenRoute,
                                    onOpenConnectionSettings = { appState.currentRoute = DesktopRoute.ConnectionSettings },
                                    onOpenConnection = { appState.currentRoute = DesktopRoute.Connection },
                                    onOpenBluetoothConnection = { appState.currentRoute = DesktopRoute.BluetoothConnection },
                                    onOpenUsbSerialConnection = { appState.currentRoute = DesktopRoute.UsbSerialConnection },
                                    onOpenSerialTunnel = { appState.currentRoute = DesktopRoute.SerialTunnel },
                                    onOpenVeTable = { appState.currentRoute = DesktopRoute.VeTable },
                                    onOpenVeTable2 = { appState.currentRoute = DesktopRoute.VeTable2 },
                                    onOpenIgnitionConfig = { appState.currentRoute = DesktopRoute.IgnitionConfig },
                                    onOpenIgnitionTable = { appState.currentRoute = DesktopRoute.IgnitionTable },
                                    onOpenIgnitionTable2 = { appState.currentRoute = DesktopRoute.IgnitionTable2 },
                                    onOpenAfrTable = { appState.currentRoute = DesktopRoute.AfrTable },
                                    onOpenDwellTable = { appState.currentRoute = DesktopRoute.DwellTable },
                                    onOpenEngineConstants = { appState.currentRoute = DesktopRoute.EngineConstants },
                                    onOpenTriggerSettings = { appState.currentRoute = DesktopRoute.TriggerSettings },
                                    onOpenIdleControl = { appState.currentRoute = DesktopRoute.IdleControl },
                                    onOpenInputOutputConfig = { appState.currentRoute = DesktopRoute.InputOutputConfig },
                                    onOpenSensorCalibration = { appState.currentRoute = DesktopRoute.SensorsConfig },
                                    onOpenEngineProtection = { appState.currentRoute = DesktopRoute.EngineProtection },
                                    onOpenClosedLoopCorrections = { appState.currentRoute = DesktopRoute.ClosedLoopCorrections },
                                    onOpenInjectorConfig = { appState.currentRoute = DesktopRoute.InjectorConfig },
                                    onOpenSecondarySerial = { appState.currentRoute = DesktopRoute.SecondarySerial },
                                    onOpenLogsEcuTools = { appState.currentRoute = DesktopRoute.LogsEcuTools },
                                    onOpenLogViewer = { appState.currentRoute = DesktopRoute.LogViewer },
                                    onOpenRealTimeMonitor = { appState.currentRoute = DesktopRoute.RealTimeMonitor },
                                    onOpenHistoricalLogViewer = { path ->
                                        controller.loadLogSnapshotFromCsv(path)
                                        appState.currentRoute = DesktopRoute.LogViewer
                                    }
                                )
                    }
                }
                if (configState.isBusy) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 3.dp,
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = strings.format("label.configProgress", configState.progressPercent),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = configState.message ?: strings["label.noData"],
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        TableChangeConfirmationDialog(controller.tableChangeConfirmation)
        WriteSafetyDialog(controller.writeSafetyGate)

        if (showReportProblemDialog) {
            AlertDialog(
                onDismissRequest = { showReportProblemDialog = false },
                title = { Text(strings["route.institutional"]) },
                text = { Text(reportProblemText) },
                confirmButton = {
                    FilledTonalButton(onClick = { showReportProblemDialog = false }) {
                        Text(strings["action.cancel"])
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            copyReportToClipboard()
                        }) {
                            Text(strings["label.copy"])
                        }
                        OutlinedButton(onClick = {
                            exportReport()
                        }) {
                            Text(strings["label.export"])
                        }
                        FilledTonalButton(onClick = {
                            openIssueTracker()
                        }) {
                            Text(strings["label.openIssue"])
                        }
                    }
                }
            )
        }
    }

    val syncPrompt by controller.syncPrompt.collectAsState()
    if (syncPrompt != null) {
        AlertDialog(
            onDismissRequest = { controller.dismissSyncPrompt() },
            title = { Text(strings["label.syncDialogTitle"]) },
            text = { Text(strings["label.syncDialogMessage"]) },
            confirmButton = {
                FilledTonalButton(onClick = { controller.chooseSyncSource(useLocal = true) }) {
                    Text(strings["action.useLocal"])
                }
            },
            dismissButton = {
                FilledTonalButton(onClick = { controller.chooseSyncSource(useLocal = false) }) {
                    Text(strings["action.useEcu"])
                }
            }
        )
    }
}
