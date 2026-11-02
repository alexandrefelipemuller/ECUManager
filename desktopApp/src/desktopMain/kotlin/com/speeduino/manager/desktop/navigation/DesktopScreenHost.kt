package com.speeduino.manager.desktop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import io.ecucore.EcuLiveData
import com.speeduino.manager.desktop.ConnectionState
import com.speeduino.manager.desktop.ConnectionType
import com.speeduino.manager.desktop.DesktopSpeeduinoController

@Composable
internal fun ScreenHost(
    route: DesktopRoute,
    controller: DesktopSpeeduinoController,
    connectionState: ConnectionState,
    liveData: EcuLiveData?,
    host: String,
    port: String,
    portIsValid: Boolean,
    connectionType: ConnectionType,
    serialPort: String,
    baudRate: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnectionTypeChange: (ConnectionType) -> Unit,
    onSerialPortChange: (String) -> Unit,
    onBaudRateChange: (String) -> Unit,
    onToggleConnection: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInstitutional: () -> Unit,
    onReportProblem: () -> Unit,
    onOpenRoute: (DesktopRoute) -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenBluetoothConnection: () -> Unit,
    onOpenUsbSerialConnection: () -> Unit,
    onOpenSerialTunnel: () -> Unit,
    onOpenVeTable: () -> Unit,
    onOpenVeTable2: () -> Unit,
    onOpenIgnitionConfig: () -> Unit,
    onOpenIgnitionTable: () -> Unit,
    onOpenIgnitionTable2: () -> Unit,
    onOpenAfrTable: () -> Unit,
    onOpenDwellTable: () -> Unit,
    onOpenEngineConstants: () -> Unit,
    onOpenTriggerSettings: () -> Unit,
    onOpenIdleControl: () -> Unit,
    onOpenInputOutputConfig: () -> Unit,
    onOpenSensorCalibration: () -> Unit,
    onOpenEngineProtection: () -> Unit,
    onOpenClosedLoopCorrections: () -> Unit,
    onOpenInjectorConfig: () -> Unit,
    onOpenSecondarySerial: () -> Unit,
    onOpenLogsEcuTools: () -> Unit,
    onOpenLogViewer: () -> Unit,
    onOpenRealTimeMonitor: () -> Unit,
    onOpenHistoricalLogViewer: (String) -> Unit
) {
    when (route) {
        DesktopRoute.Home,
        DesktopRoute.Settings,
        DesktopRoute.Institutional,
        DesktopRoute.Dashboard -> AppRouteHost(
            route = route,
            controller = controller,
            liveData = liveData,
            connectionState = connectionState,
            onToggleConnection = onToggleConnection,
            onOpenSettings = onOpenSettings,
            onOpenRoute = onOpenRoute,
            onReportProblem = onReportProblem,
            onOpenInstitutional = onOpenInstitutional
        )
        DesktopRoute.Connection,
        DesktopRoute.ConnectionSettings,
        DesktopRoute.BluetoothConnection,
        DesktopRoute.UsbSerialConnection,
        DesktopRoute.SerialTunnel -> ConnectionRouteHost(
            route = route,
            controller = controller,
            connectionState = connectionState,
            host = host,
            port = port,
            portIsValid = portIsValid,
            connectionType = connectionType,
            serialPort = serialPort,
            baudRate = baudRate,
            onHostChange = onHostChange,
            onPortChange = onPortChange,
            onConnectionTypeChange = onConnectionTypeChange,
            onSerialPortChange = onSerialPortChange,
            onBaudRateChange = onBaudRateChange,
            onToggleConnection = onToggleConnection,
            onOpenConnectionSettings = onOpenConnectionSettings,
            onOpenBluetoothConnection = onOpenBluetoothConnection,
            onOpenUsbSerialConnection = onOpenUsbSerialConnection,
            onOpenSerialTunnel = onOpenSerialTunnel,
            onOpenLogsEcuTools = onOpenLogsEcuTools,
            onOpenInstitutional = onOpenInstitutional
        )
        DesktopRoute.Ecu -> com.speeduino.manager.desktop.EcuHubScreenDesktop(
            onOpenFuel = { onOpenRoute(DesktopRoute.Fuel) },
            onOpenIgnition = { onOpenRoute(DesktopRoute.Ignition) },
            onOpenEngineSetup = { onOpenRoute(DesktopRoute.EngineSetup) },
            onOpenEngineOperation = { onOpenRoute(DesktopRoute.EngineOperation) }
        )
        DesktopRoute.Fuel,
        DesktopRoute.MapsTables,
        DesktopRoute.VeTable,
        DesktopRoute.VeTable2,
        DesktopRoute.AfrTable -> MapsRouteHost(
            route = route,
            controller = controller,
            onOpenVeTable = onOpenVeTable,
            onOpenVeTable2 = onOpenVeTable2,
            onOpenAfrTable = onOpenAfrTable,
            onOpenInjectorConfig = onOpenInjectorConfig,
        )
        DesktopRoute.Ignition,
        DesktopRoute.IgnitionConfig,
        DesktopRoute.IgnitionTable,
        DesktopRoute.IgnitionTable2,
        DesktopRoute.DwellTable,
        DesktopRoute.TriggerSettings -> ConfigsRouteHost(
            route = route,
            controller = controller,
            onOpenEngineConstants = onOpenEngineConstants,
            onOpenInputOutputConfig = onOpenInputOutputConfig,
            onOpenSensorCalibration = onOpenSensorCalibration,
            onOpenInjectorConfig = onOpenInjectorConfig,
            onOpenSecondarySerial = onOpenSecondarySerial,
            onOpenIgnitionConfig = onOpenIgnitionConfig,
            onOpenIgnitionTable = onOpenIgnitionTable,
            onOpenIgnitionTable2 = onOpenIgnitionTable2,
            onOpenDwellTable = onOpenDwellTable,
            onOpenTriggerSettings = onOpenTriggerSettings,
            onOpenIdleControl = onOpenIdleControl,
            onOpenEngineProtection = onOpenEngineProtection,
            onOpenClosedLoopCorrections = onOpenClosedLoopCorrections
        )
        DesktopRoute.EngineSetup,
        DesktopRoute.ConfigsTuning,
        DesktopRoute.InjectorConfig,
        DesktopRoute.InputOutputConfig,
        DesktopRoute.EngineConstants,
        DesktopRoute.SensorsConfig,
        DesktopRoute.SecondarySerial -> ConfigsRouteHost(
            route = route,
            controller = controller,
            onOpenEngineConstants = onOpenEngineConstants,
            onOpenInputOutputConfig = onOpenInputOutputConfig,
            onOpenSensorCalibration = onOpenSensorCalibration,
            onOpenInjectorConfig = onOpenInjectorConfig,
            onOpenSecondarySerial = onOpenSecondarySerial,
            onOpenIgnitionConfig = onOpenIgnitionConfig,
            onOpenIgnitionTable = onOpenIgnitionTable,
            onOpenIgnitionTable2 = onOpenIgnitionTable2,
            onOpenDwellTable = onOpenDwellTable,
            onOpenTriggerSettings = onOpenTriggerSettings,
            onOpenIdleControl = onOpenIdleControl,
            onOpenEngineProtection = onOpenEngineProtection,
            onOpenClosedLoopCorrections = onOpenClosedLoopCorrections,
            onOpenRoute = onOpenRoute
        )
        DesktopRoute.EngineOperation,
        DesktopRoute.IdleControl,
        DesktopRoute.ClosedLoopCorrections,
        DesktopRoute.EngineProtection -> ConfigsRouteHost(
            route = route,
            controller = controller,
            onOpenEngineConstants = onOpenEngineConstants,
            onOpenInputOutputConfig = onOpenInputOutputConfig,
            onOpenSensorCalibration = onOpenSensorCalibration,
            onOpenInjectorConfig = onOpenInjectorConfig,
            onOpenSecondarySerial = onOpenSecondarySerial,
            onOpenIgnitionConfig = onOpenIgnitionConfig,
            onOpenIgnitionTable = onOpenIgnitionTable,
            onOpenIgnitionTable2 = onOpenIgnitionTable2,
            onOpenDwellTable = onOpenDwellTable,
            onOpenTriggerSettings = onOpenTriggerSettings,
            onOpenIdleControl = onOpenIdleControl,
            onOpenEngineProtection = onOpenEngineProtection,
            onOpenClosedLoopCorrections = onOpenClosedLoopCorrections,
            onOpenRoute = onOpenRoute
        )
        DesktopRoute.RevLimiter -> com.speeduino.manager.desktop.feature.configs.RevLimiterScreenDesktop(
            controller = controller,
            onBack = { onOpenRoute(DesktopRoute.EngineOperation) },
        )
        DesktopRoute.Ms2Wue -> com.speeduino.manager.desktop.feature.configs.Ms2WueScreen(
            isConnected = connectionState.isConnected,
            backend = controller.ms2CurveBackend,
            onBack = { onOpenRoute(DesktopRoute.EngineSetup) },
        )
        DesktopRoute.Ms2Idle -> com.speeduino.manager.desktop.feature.configs.Ms2IdleScreen(
            isConnected = connectionState.isConnected,
            backend = controller.ms2CurveBackend,
            liveData = liveData,
            onBack = { onOpenRoute(DesktopRoute.EngineSetup) },
        )
        DesktopRoute.Tools,
        DesktopRoute.RestorePoints,
        DesktopRoute.LogMap,
        DesktopRoute.LogsEcuTools,
        DesktopRoute.LogViewer,
        DesktopRoute.RealTimeMonitor,
        DesktopRoute.LogAnalyzer,
        DesktopRoute.BeforeAfter,
        DesktopRoute.VirtualDyno -> LogsRouteHost(
            route = route,
            controller = controller,
            liveData = liveData,
            onOpenConnection = onOpenConnection,
            onOpenConnectionSettings = onOpenConnectionSettings,
            onOpenLogViewer = onOpenLogViewer,
            onOpenRealTimeMonitor = onOpenRealTimeMonitor,
            onOpenLogAnalyzer = { onOpenRoute(DesktopRoute.LogAnalyzer) },
            onOpenBeforeAfter = { onOpenRoute(DesktopRoute.BeforeAfter) },
            onOpenVirtualDyno = { onOpenRoute(DesktopRoute.VirtualDyno) },
            onOpenSettings = onOpenSettings,
            onOpenInstitutional = onOpenInstitutional,
            onOpenHistoricalLogViewer = onOpenHistoricalLogViewer,
            onOpenRestorePoints = { onOpenRoute(DesktopRoute.RestorePoints) },
            onOpenLogMap = { path -> controller.logMapPath.value = path; onOpenRoute(DesktopRoute.LogMap) },
            logMapPath = controller.logMapPath.collectAsState().value
        )
    }
}
