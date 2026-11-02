package com.speeduino.manager.desktop.navigation

import androidx.compose.runtime.Composable
import io.ecucore.EcuLiveData
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.LogsEcuToolsScreenDesktop
import com.speeduino.manager.desktop.feature.logs.BeforeAfterScreenDesktop
import com.speeduino.manager.desktop.feature.logs.LogAnalyzerScreenDesktop
import com.speeduino.manager.desktop.feature.logs.LogViewerScreenDesktop
import com.speeduino.manager.desktop.feature.logs.RealTimeMonitorScreenDesktop
import com.speeduino.manager.desktop.feature.logs.VirtualDynoScreenDesktop

@Composable
internal fun LogsRouteHost(
    route: DesktopRoute,
    controller: DesktopSpeeduinoController,
    liveData: EcuLiveData?,
    onOpenConnection: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onOpenLogViewer: () -> Unit,
    onOpenRealTimeMonitor: () -> Unit,
    onOpenLogAnalyzer: () -> Unit,
    onOpenBeforeAfter: () -> Unit,
    onOpenVirtualDyno: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInstitutional: () -> Unit,
    onOpenHistoricalLogViewer: (String) -> Unit,
    onOpenRestorePoints: () -> Unit = {},
    onOpenLogMap: (String) -> Unit = {},
    logMapPath: String? = null
) {
    when (route) {
        DesktopRoute.Tools,
        DesktopRoute.LogsEcuTools -> LogsEcuToolsScreenDesktop(
            onOpenConnection = onOpenConnection,
            onOpenConnectionSettings = onOpenConnectionSettings,
            onOpenLogViewer = onOpenLogViewer,
            onOpenRealTimeMonitor = onOpenRealTimeMonitor,
            onOpenLogAnalyzer = onOpenLogAnalyzer,
            onOpenBeforeAfter = onOpenBeforeAfter,
            onOpenVirtualDyno = onOpenVirtualDyno,
            onOpenSettings = onOpenSettings,
            onOpenInstitutional = onOpenInstitutional,
            onOpenHistoricalLogViewer = onOpenHistoricalLogViewer,
            onOpenRestorePoints = onOpenRestorePoints,
            onOpenLogMap = onOpenLogMap
        )
        DesktopRoute.LogMap -> com.speeduino.manager.desktop.feature.logs.LogMapScreenDesktop(logMapPath)
        DesktopRoute.RestorePoints -> com.speeduino.manager.desktop.feature.app.RestorePointsScreenDesktop(controller)
        DesktopRoute.RealTimeMonitor -> RealTimeMonitorScreenDesktop(controller, liveData)
        DesktopRoute.LogViewer -> LogViewerScreenDesktop(controller)
        DesktopRoute.LogAnalyzer -> LogAnalyzerScreenDesktop(controller)
        DesktopRoute.BeforeAfter -> BeforeAfterScreenDesktop(controller)
        DesktopRoute.VirtualDyno -> VirtualDynoScreenDesktop(controller)
        else -> Unit
    }
}
