package com.speeduino.manager.desktop.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class DesktopRoute(
    val labelKey: String,
    val titleKey: String,
    val icon: ImageVector,
    val topLevel: Boolean = false
) {
    Home("route.home", "route.home", Icons.Default.Home, topLevel = true),
    Dashboard("route.dashboard", "route.dashboard", Icons.Default.Dashboard, topLevel = true),
    Ecu("route.ecu", "route.ecu", Icons.Default.TableChart, topLevel = true),
    Tools("route.tools", "route.tools", Icons.Default.Build, topLevel = true),
    Settings("app.settingsLabel", "app.settingsTitle", Icons.Default.Settings),
    Institutional("route.institutional", "label.institutionalTitle", Icons.Default.Info),
    Connection("route.connection", "route.connection", Icons.Default.Cable),
    Fuel("route.fuel", "route.fuel", Icons.Default.TableChart),
    Ignition("route.ignition", "route.ignition", Icons.Default.TableChart),
    EngineSetup("route.engineSetup", "route.engineSetup", Icons.Default.Settings),
    EngineOperation("route.engineOperation", "route.engineOperation", Icons.Default.Settings),
    ConnectionSettings("label.wifiTcp", "label.wifiTcp", Icons.Default.Cable),
    BluetoothConnection("label.bluetooth", "label.bluetooth", Icons.Default.Cable),
    UsbSerialConnection("label.usbSerial", "label.usbSerial", Icons.Default.Cable),
    SerialTunnel("label.serialTunnel", "label.serialTunnel", Icons.Default.Cable),
    MapsTables("route.mapsTables", "route.mapsTables", Icons.Default.TableChart),
    ConfigsTuning("route.configsTuning", "route.configsTuning", Icons.Default.Settings),
    InjectorConfig("label.injectors", "label.injectors", Icons.Default.Settings),
    InputOutputConfig("label.inputOutputTitle", "label.inputOutputTitle", Icons.Default.Settings),
    SecondarySerial("label.secondarySerialTitle", "label.secondarySerialTitle", Icons.Default.Settings),
    VeTable("route.veTable", "route.veTable", Icons.Default.TableChart),
    VeTable2("route.veTable2", "route.veTable2", Icons.Default.TableChart),
    IgnitionTable("route.ignitionTable", "route.ignitionTable", Icons.Default.TableChart),
    IgnitionTable2("route.ignitionTable2", "route.ignitionTable2", Icons.Default.TableChart),
    AfrTable("route.afrTable", "route.afrTable", Icons.Default.TableChart),
    DwellTable("route.dwellTable", "route.dwellTable", Icons.Default.TableChart),
    EngineConstants("route.engineConstants", "route.engineConstants", Icons.Default.Settings),
    TriggerSettings("route.triggerSettings", "route.triggerSettings", Icons.Default.Settings),
    IgnitionConfig("route.ignition", "route.ignition", Icons.Default.Settings),
    IdleControl("route.idleControl", "route.idleControl", Icons.Default.Settings),
    ClosedLoopCorrections("route.closedLoopCorrections", "route.closedLoopCorrections", Icons.Default.Settings),
    SensorsConfig("route.sensorsConfig", "route.sensorsConfig", Icons.Default.Settings),
    EngineProtection("route.engineProtection", "route.engineProtection", Icons.Default.Settings),
    RealTimeMonitor("route.realTimeMonitor", "route.realTimeMonitor", Icons.Default.Build),
    LogViewer("route.logViewer", "route.logViewer", Icons.Default.Build),
    LogAnalyzer("route.logAnalyzer", "route.logAnalyzer", Icons.Default.Build),
    BeforeAfter("route.beforeAfter", "route.beforeAfter", Icons.Default.Build),
    VirtualDyno("route.virtualDyno", "route.virtualDyno", Icons.Default.Build),
    LogsEcuTools("route.logsEcuTools", "label.logsEcuToolsTitle", Icons.Default.Build),
    LogMap("log_map_title", "log_map_title", Icons.Default.Build),
    RestorePoints("restore_points_title", "restore_points_title", Icons.Default.Build),
    RevLimiter("config_rev_limiter_title", "config_rev_limiter_title", Icons.Default.Settings),
    Ms2Wue("ms2_wue_title", "ms2_wue_title", Icons.Default.Settings),
    Ms2Idle("ms2_idle_title", "ms2_idle_title", Icons.Default.Settings)
}

internal fun selectedNavRoute(route: DesktopRoute): DesktopRoute {
    return generateSequence(route) { current -> parentRoute(current) }
        .firstOrNull { it.topLevel }
        ?: route
}

internal fun parentRoute(route: DesktopRoute): DesktopRoute? {
    return when (route) {
        DesktopRoute.ConnectionSettings,
        DesktopRoute.BluetoothConnection,
        DesktopRoute.UsbSerialConnection,
        DesktopRoute.SerialTunnel -> DesktopRoute.Connection
        DesktopRoute.VeTable,
        DesktopRoute.VeTable2,
        DesktopRoute.AfrTable -> DesktopRoute.Fuel
        DesktopRoute.IgnitionTable,
        DesktopRoute.IgnitionTable2,
        DesktopRoute.DwellTable,
        DesktopRoute.TriggerSettings,
        DesktopRoute.IgnitionConfig -> DesktopRoute.Ignition
        DesktopRoute.InjectorConfig,
        DesktopRoute.InputOutputConfig,
        DesktopRoute.EngineConstants,
        DesktopRoute.SensorsConfig,
        DesktopRoute.SecondarySerial,
        DesktopRoute.Ms2Wue,
        DesktopRoute.Ms2Idle -> DesktopRoute.EngineSetup
        DesktopRoute.IdleControl,
        DesktopRoute.ClosedLoopCorrections,
        DesktopRoute.EngineProtection,
        DesktopRoute.RevLimiter -> DesktopRoute.EngineOperation
        DesktopRoute.Fuel,
        DesktopRoute.Ignition,
        DesktopRoute.EngineSetup,
        DesktopRoute.EngineOperation -> DesktopRoute.Ecu
        DesktopRoute.LogViewer,
        DesktopRoute.RealTimeMonitor,
        DesktopRoute.LogAnalyzer,
        DesktopRoute.BeforeAfter,
        DesktopRoute.VirtualDyno,
        DesktopRoute.Connection,
        DesktopRoute.Settings,
        DesktopRoute.Institutional -> DesktopRoute.Tools
        DesktopRoute.MapsTables -> DesktopRoute.Fuel
        DesktopRoute.ConfigsTuning -> DesktopRoute.EngineSetup
        DesktopRoute.LogsEcuTools,
        DesktopRoute.RestorePoints,
        DesktopRoute.LogMap -> DesktopRoute.Tools
        else -> null
    }
}
