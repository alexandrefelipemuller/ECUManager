package com.speeduino.manager.desktop.navigation

import androidx.compose.runtime.Composable
import com.speeduino.manager.desktop.DesktopSpeeduinoController
import com.speeduino.manager.desktop.ConfigsTuningScreenDesktop
import com.speeduino.manager.desktop.ClosedLoopCorrectionsScreenDesktop
import com.speeduino.manager.desktop.DwellTableScreenDesktop
import com.speeduino.manager.desktop.EngineOperationScreenDesktop
import com.speeduino.manager.desktop.IgnitionScreenDesktop
import com.speeduino.manager.desktop.IgnitionTableScreenDesktop
import com.speeduino.manager.desktop.IdleControlScreenDesktop
import com.speeduino.manager.desktop.SecondarySerialScreenDesktop
import com.speeduino.manager.desktop.feature.configs.EngineConstantsScreenDesktop
import com.speeduino.manager.desktop.feature.configs.InputOutputEditorScreenDesktop
import com.speeduino.manager.desktop.feature.configs.InjectorConfigScreenDesktop
import com.speeduino.manager.desktop.feature.configs.EngineProtectionEditorScreenDesktop
import com.speeduino.manager.desktop.feature.configs.IgnitionConfigScreenDesktop
import com.speeduino.manager.desktop.feature.configs.SensorsCalibrationScreenDesktop
import com.speeduino.manager.desktop.feature.configs.TriggerSettingsScreenDesktop

@Composable
internal fun ConfigsRouteHost(
    route: DesktopRoute,
    controller: DesktopSpeeduinoController,
    onOpenEngineConstants: () -> Unit,
    onOpenInputOutputConfig: () -> Unit,
    onOpenSensorCalibration: () -> Unit,
    onOpenInjectorConfig: () -> Unit,
    onOpenSecondarySerial: () -> Unit,
    onOpenIgnitionConfig: () -> Unit,
    onOpenIgnitionTable: () -> Unit,
    onOpenIgnitionTable2: () -> Unit,
    onOpenDwellTable: () -> Unit,
    onOpenTriggerSettings: () -> Unit,
    onOpenIdleControl: () -> Unit,
    onOpenEngineProtection: () -> Unit,
    onOpenClosedLoopCorrections: () -> Unit,
    onOpenRoute: (DesktopRoute) -> Unit = {}
) {
    when (route) {
        DesktopRoute.Ignition -> IgnitionScreenDesktop(
            onOpenIgnitionConfig = onOpenIgnitionConfig,
            onOpenIgnitionTable = onOpenIgnitionTable,
            onOpenIgnitionTable2 = onOpenIgnitionTable2,
            onOpenDwellTable = onOpenDwellTable,
            onOpenTriggerSettings = onOpenTriggerSettings
        )
        DesktopRoute.EngineSetup,
        DesktopRoute.ConfigsTuning -> ConfigsTuningScreenDesktop(
            onOpenEngineConstants = onOpenEngineConstants,
            onOpenInputOutput = onOpenInputOutputConfig,
            onOpenSensorCalibration = onOpenSensorCalibration,
            onOpenInjectorConfig = onOpenInjectorConfig,
            onOpenSecondarySerial = onOpenSecondarySerial,
            onOpenIgnitionConfig = onOpenIgnitionConfig,
            controller = controller,
            onOpenRoute = onOpenRoute
        )
        DesktopRoute.EngineOperation -> EngineOperationScreenDesktop(
            onOpenIdleControl = onOpenIdleControl,
            onOpenClosedLoopCorrections = onOpenClosedLoopCorrections,
            onOpenEngineProtection = onOpenEngineProtection
        )
        DesktopRoute.InjectorConfig -> InjectorConfigScreenDesktop(controller)
        DesktopRoute.InputOutputConfig -> InputOutputEditorScreenDesktop(
            controller = controller,
            onOpenSecondarySerial = onOpenSecondarySerial
        )
        DesktopRoute.SecondarySerial -> SecondarySerialScreenDesktop(controller)
        DesktopRoute.EngineConstants -> EngineConstantsScreenDesktop(controller)
        DesktopRoute.TriggerSettings -> TriggerSettingsScreenDesktop(controller)
        DesktopRoute.IgnitionConfig -> IgnitionConfigScreenDesktop(controller)
        DesktopRoute.IgnitionTable -> IgnitionTableScreenDesktop(controller, mapIndex = 1)
        DesktopRoute.IgnitionTable2 -> IgnitionTableScreenDesktop(controller, mapIndex = 2)
        DesktopRoute.DwellTable -> DwellTableScreenDesktop(controller)
        DesktopRoute.IdleControl -> IdleControlScreenDesktop(controller)
        DesktopRoute.ClosedLoopCorrections -> ClosedLoopCorrectionsScreenDesktop(controller)
        DesktopRoute.SensorsConfig -> SensorsCalibrationScreenDesktop(controller)
        DesktopRoute.EngineProtection -> EngineProtectionEditorScreenDesktop(controller)
        else -> Unit
    }
}
