package com.speeduino.manager.desktop.navigation

import androidx.compose.runtime.Composable
import io.ecucore.EcuLiveData
import com.speeduino.manager.desktop.ConnectionState
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.InstitutionalScreenDesktop
import com.speeduino.manager.desktop.feature.app.DashboardScreen
import com.speeduino.manager.desktop.feature.app.HomeScreenDesktop
import com.speeduino.manager.desktop.feature.app.SettingsScreen

@Composable
internal fun AppRouteHost(
    route: DesktopRoute,
    controller: DesktopSpeeduinoController,
    liveData: EcuLiveData?,
    connectionState: ConnectionState,
    onToggleConnection: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRoute: (DesktopRoute) -> Unit,
    onReportProblem: () -> Unit,
    onOpenInstitutional: () -> Unit,
) {
    when (route) {
        DesktopRoute.Home -> HomeScreenDesktop(
            controller = controller,
            connectionState = connectionState,
            onToggleConnection = onToggleConnection,
            onOpenRoute = onOpenRoute,
            onOpenInstitutional = onOpenInstitutional
        )
        DesktopRoute.Settings -> SettingsScreen(controller)
        DesktopRoute.Institutional -> InstitutionalScreenDesktop(onReportProblem = onReportProblem)
        DesktopRoute.Dashboard -> DashboardScreen(controller, liveData, connectionState, onOpenSettings = onOpenSettings)
        else -> Unit
    }
}
