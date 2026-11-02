package com.speeduino.manager.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.speeduino.manager.desktop.ConnectionType
import com.speeduino.manager.desktop.DesktopSettingsState
import com.speeduino.manager.desktop.InitialScreen
import com.speeduino.manager.desktop.navigation.DesktopRoute

@Stable
internal class DesktopAppState(
    initialRoute: DesktopRoute,
    initialHost: String,
    initialPort: String,
    initialConnectionType: ConnectionType,
    initialSerialPort: String,
    initialBaudRate: String
) {
    private var route by mutableStateOf(initialRoute)

    /** A tela atual declarou edições não salvas (ver `ReportUnsavedChanges`). */
    var unsavedChanges by mutableStateOf(false)

    /** Destino aguardando a confirmação de descarte; `null` = nenhum diálogo. */
    var pendingRoute by mutableStateOf<DesktopRoute?>(null)
        private set

    /** Com edições não salvas, a troca de tela fica pendente até o usuário descartar. */
    var currentRoute: DesktopRoute
        get() = route
        set(value) {
            if (value == route) return
            if (unsavedChanges) pendingRoute = value else route = value
        }

    fun discardAndNavigate() {
        val target = pendingRoute ?: return
        pendingRoute = null
        unsavedChanges = false
        route = target
    }

    fun keepEditing() {
        pendingRoute = null
    }
    var host by mutableStateOf(initialHost)
    var port by mutableStateOf(initialPort)
    var connectionType by mutableStateOf(initialConnectionType)
    var serialPort by mutableStateOf(initialSerialPort)
    var baudRate by mutableStateOf(initialBaudRate)
}

@Composable
internal fun rememberDesktopAppState(desktopSettings: DesktopSettingsState): DesktopAppState {
    return remember {
        DesktopAppState(
            initialRoute = when (desktopSettings.initialScreen) {
                InitialScreen.HOME -> DesktopRoute.Home
                InitialScreen.DASHBOARD -> DesktopRoute.Dashboard
            },
            initialHost = desktopSettings.lastTcpHost ?: "127.0.0.1",
            initialPort = desktopSettings.lastTcpPort?.toString() ?: "5555",
            initialConnectionType = desktopSettings.lastConnectionType ?: ConnectionType.TCP,
            initialSerialPort = desktopSettings.lastSerialPort.orEmpty(),
            initialBaudRate = desktopSettings.lastSerialBaudRate?.toString() ?: "115200"
        )
    }
}
