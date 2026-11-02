package com.speeduino.manager.desktop.feature.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.speeduino.manager.desktop.ConnectionState
import com.speeduino.manager.desktop.ConnectionStatus
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.LocalStrings
import com.speeduino.manager.desktop.navigation.DesktopRoute
import com.speeduino.manager.desktop.ui.KioskFeatureCard
import com.speeduino.manager.desktop.ui.KioskPanelCard
import com.speeduino.manager.desktop.ui.KioskScreenScaffold

@Composable
internal fun HomeScreenDesktop(
    controller: DesktopSpeeduinoController,
    connectionState: ConnectionState,
    onToggleConnection: () -> Unit,
    onOpenRoute: (DesktopRoute) -> Unit,
    onOpenInstitutional: () -> Unit,
) {
    val strings = LocalStrings.current
    val firmwareInfo by controller.firmwareInfo.collectAsState()
    val productString by controller.productString.collectAsState()
    val settings by controller.desktopSettings.collectAsState()
    var launchCountRecorded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!launchCountRecorded) {
            launchCountRecorded = true
            controller.saveDesktopSettings(settings.copy(appLaunchCount = settings.appLaunchCount + 1))
        }
    }

    val connectionLabel = when {
        connectionState.isConnected -> strings["status.connected"]
        connectionState.status == ConnectionStatus.Connecting -> strings["status.connecting"]
        connectionState.status == ConnectionStatus.Failed -> strings.format(
            "status.failed",
            connectionState.detail ?: strings["label.noData"]
        )
        else -> strings["status.disconnected"]
    }

    val hasFirstBootGaps = connectionState.isConnected && (
        !settings.firstBootEngineConstantsDone || !settings.firstBootInjectorsDone || !settings.firstBootIgnitionDone ||
            !settings.firstBootFuelDone || !settings.firstBootSensorsDone || !settings.firstBootOutputsDone || !settings.firstBootLivePanelDone
    )
    val firstBootChecklist = listOf(
        Triple(strings["home.firstBootEngineConstants"], settings.firstBootEngineConstantsDone, DesktopRoute.EngineConstants),
        Triple(strings["home.firstBootInjectors"], settings.firstBootInjectorsDone, DesktopRoute.InjectorConfig),
        Triple(strings["home.firstBootIgnition"], settings.firstBootIgnitionDone, DesktopRoute.Ignition),
        Triple(strings["home.firstBootFuel"], settings.firstBootFuelDone, DesktopRoute.Fuel),
        Triple(strings["home.firstBootSensors"], settings.firstBootSensorsDone, DesktopRoute.SensorsConfig),
        Triple(strings["home.firstBootOutputs"], settings.firstBootOutputsDone, DesktopRoute.InputOutputConfig),
        Triple(strings["home.firstBootLivePanel"], settings.firstBootLivePanelDone, DesktopRoute.Dashboard),
    )

    fun markFirstBootProgress(route: DesktopRoute) {
        val updated = when (route) {
            DesktopRoute.EngineConstants -> settings.copy(firstBootEngineConstantsDone = true)
            DesktopRoute.InjectorConfig -> settings.copy(firstBootInjectorsDone = true)
            DesktopRoute.Ignition -> settings.copy(firstBootIgnitionDone = true)
            DesktopRoute.Fuel -> settings.copy(firstBootFuelDone = true)
            DesktopRoute.SensorsConfig -> settings.copy(firstBootSensorsDone = true)
            DesktopRoute.InputOutputConfig -> settings.copy(firstBootOutputsDone = true)
            DesktopRoute.Dashboard -> settings.copy(firstBootLivePanelDone = true)
            else -> null
        }
        if (updated != null) {
            controller.saveDesktopSettings(updated)
        }
    }

    fun openRoute(route: DesktopRoute) {
        markFirstBootProgress(route)
        onOpenRoute(route)
    }

    val offlineSession by controller.offlineSession.collectAsState()
    var showOfflinePicker by remember { mutableStateOf(false) }
    val offlineOptions = remember(connectionState.isConnected, offlineSession) { controller.offlineSessionOptions() }

    if (showOfflinePicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showOfflinePicker = false },
            title = { Text(strings["offline_session_picker_title"]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    offlineOptions.take(12).forEach { option ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showOfflinePicker = false; controller.enterOfflineBrowse(option) },
                        ) { Text(option.label, maxLines = 1) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showOfflinePicker = false }) { Text(strings["action.cancel"]) } },
        )
    }

    KioskScreenScaffold(title = strings["route.home"], subtitle = strings["home.subtitle"]) {
        if (!connectionState.isConnected && offlineSession == null) {
            if (offlineOptions.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings["home_offline_browse_title"], style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                        Text(strings["home_offline_browse_desc"], style = MaterialTheme.typography.labelSmall)
                        OutlinedButton(onClick = { showOfflinePicker = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Text(strings["home_offline_browse_title"], style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            } else {
                Text(strings["home_offline_none"], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = connectionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = productString ?: firmwareInfo?.signature ?: strings["label.noData"],
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onToggleConnection, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(
                            if (connectionState.isConnected) strings["action.disconnect"] else strings["action.connect"],
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    OutlinedButton(onClick = { openRoute(DesktopRoute.Connection) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(strings[DesktopRoute.Connection.labelKey], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        KioskPanelCard(strings["home.exploreTitle"]) {
            KioskFeatureCard(strings[DesktopRoute.Dashboard.labelKey], strings["home.dashboardDescription"], Icons.Default.Dashboard, large = true) { openRoute(DesktopRoute.Dashboard) }
            KioskFeatureCard(strings[DesktopRoute.Ecu.labelKey], strings["home.fuelDescription"], Icons.Default.TableChart, large = true) { openRoute(DesktopRoute.Ecu) }
            KioskFeatureCard(strings[DesktopRoute.Tools.labelKey], strings["home.toolsDescription"], Icons.Default.Settings, large = true) { openRoute(DesktopRoute.Tools) }
        }

        KioskPanelCard(strings["home.setupTitle"]) {
            KioskFeatureCard(strings[DesktopRoute.Connection.labelKey], strings["home.connectionDescription"], Icons.Default.Cable, large = true) { openRoute(DesktopRoute.Connection) }
            KioskFeatureCard(strings[DesktopRoute.Settings.labelKey], strings["home.settingsDescription"], Icons.Default.Settings, large = true) { openRoute(DesktopRoute.Settings) }
        }

        if (hasFirstBootGaps) {
            KioskPanelCard(strings["home.firstBootTitle"]) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        firstBootChecklist.forEach { (label, checked, route) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(onClick = { openRoute(route) }),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { openRoute(route) },
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
