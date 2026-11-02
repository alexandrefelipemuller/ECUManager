package com.speeduino.manager.desktop

import io.ecucore.ConfigManager
import io.ecucore.EcuClient
import io.ecucore.EcuLiveData
import io.ecucore.definition.IniCatalogEntry
import io.ecucore.definition.IniDefinition
import io.ecucore.shared.Logger
import io.ecucore.compare.BeforeAfterLogComparator
import io.ecucore.compare.LogCompareException
import io.ecucore.compare.LogCompareReason
import io.ecucore.compare.LogCompareResult
import io.ecucore.connection.AutoReconnectCoordinator
import io.ecucore.connection.ConnectionRetryPolicy
import io.ecucore.connection.IEcuConnection
import io.ecucore.connection.EcuSerialConnection
import io.ecucore.connection.EcuTcpConnection
import io.ecucore.definition.IniCatalogErrorCategory
import io.ecucore.definition.IniCatalogErrorClassifier
import io.ecucore.definition.isAlreadyActiveDefinition
import io.ecucore.ecu.FirmwareInfo
import io.ecucore.model.AfrTable
import io.ecucore.model.DwellTable
import io.ecucore.model.ClosedLoopCorrectionConfig
import io.ecucore.model.ClosedLoopCorrectionMapper
import io.ecucore.model.EngineConstants
import io.ecucore.model.EngineProtectionConfig
import io.ecucore.model.FirmwareEra
import io.ecucore.model.IdleControlSettings
import io.ecucore.model.IgnitionTable
import io.ecucore.model.OutputField
import io.ecucore.model.PressureCalibration
import io.ecucore.model.TpsCalibration
import io.ecucore.model.TriggerSettings
import io.ecucore.model.VeTable
import io.ecucore.model.RusefiInputOutputSnapshot
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.EcuOutputChannels
import io.ecucore.model.basemap.GeneratedBaseMap
import io.ecucore.model.logging.LiveLogEntry
import io.ecucore.model.logging.LiveLogRecorder
import io.ecucore.model.logging.LiveLogSnapshot
import io.ecucore.sync.ConfigSyncService
import io.ecucore.sync.SessionSyncPrompt
import io.ecucore.tuning.AnalyzerResult
import io.ecucore.tuning.TuningAssistantAnalyzer
import io.ecucore.tuning.TuningStrategy
import com.speeduino.manager.telemetry.ConnectionDiagnosticsLogger
import com.speeduino.manager.telemetry.DiagnosticsFlags
import com.speeduino.manager.telemetry.Obd2InvestigationRecorder
import com.speeduino.manager.transport.AutoDetectEcuTransport
import com.speeduino.manager.transport.Obd2OptimizationProfileStore
import com.speeduino.manager.transport.Obd2Transport
import com.speeduino.manager.transport.PromotingObd2Transport
import com.speeduino.manager.transport.PsaConnectionSessionStore
import com.speeduino.manager.transport.PsaTransport
import com.speeduino.manager.transport.RenaultTransport
import com.speeduino.manager.transport.VagTransport
import io.ecucore.transport.EcuTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class DesktopDiagnosticSummary(
    val firmwareSignature: String? = null,
    val productString: String? = null,
    val connectionInfo: String? = null,
    val activeIniSource: String? = null,
    val manualFirmwareProfile: String? = null,
    val diagnosticLoggerMode: DiagnosticLoggerMode = DiagnosticLoggerMode.OFF,
    val readOnlySafeMode: Boolean = false,
    val lastError: String? = null,
)

internal class DesktopSpeeduinoController(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DesktopController"
        private const val CONNECT_RETRY_ATTEMPTS = 3
        private const val CONNECT_RETRY_DELAY_MS = 1000L
        private const val RECONNECT_DELAY_MS = 2000L
    }

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState = _connectionState.asStateFlow()

    private val _liveData = MutableStateFlow<EcuLiveData?>(null)
    val liveData = _liveData.asStateFlow()

    private val _engineConstants = MutableStateFlow<EngineConstants?>(null)
    val engineConstants = _engineConstants.asStateFlow()

    private val _triggerSettings = MutableStateFlow<TriggerSettings?>(null)
    val triggerSettings = _triggerSettings.asStateFlow()

    private val _veTable = MutableStateFlow<VeTable?>(null)
    val veTable = _veTable.asStateFlow()
    private val _veTable2 = MutableStateFlow<VeTable?>(null)
    val veTable2 = _veTable2.asStateFlow()

    private val _ignitionTable = MutableStateFlow<IgnitionTable?>(null)
    val ignitionTable = _ignitionTable.asStateFlow()
    private val _ignitionTable2 = MutableStateFlow<IgnitionTable?>(null)
    val ignitionTable2 = _ignitionTable2.asStateFlow()

    private val _afrTable = MutableStateFlow<AfrTable?>(null)
    val afrTable = _afrTable.asStateFlow()

    private val _dwellTable = MutableStateFlow<DwellTable?>(null)
    val dwellTable = _dwellTable.asStateFlow()

    private val _idleControlSettings = MutableStateFlow<IdleControlSettings?>(null)
    val idleControlSettings = _idleControlSettings.asStateFlow()

    private val _engineProtectionConfig = MutableStateFlow<EngineProtectionConfig?>(null)
    val engineProtectionConfig = _engineProtectionConfig.asStateFlow()

    private val _closedLoopCorrections = MutableStateFlow<ClosedLoopCorrectionConfig?>(null)
    val closedLoopCorrections = _closedLoopCorrections.asStateFlow()

    private val _tuningConfigState = MutableStateFlow(TuningConfigState())
    val tuningConfigState = _tuningConfigState.asStateFlow()

    private val _firmwareInfo = MutableStateFlow<FirmwareInfo?>(null)
    val firmwareInfo = _firmwareInfo.asStateFlow()

    private val _productString = MutableStateFlow<String?>(null)
    val productString = _productString.asStateFlow()

    private val _connectionInfo = MutableStateFlow<String?>(null)
    val connectionInfo = _connectionInfo.asStateFlow()

    private val _diagnosticSummary = MutableStateFlow(DesktopDiagnosticSummary())
    val diagnosticSummary = _diagnosticSummary.asStateFlow()

    val tableUndoStore = TableUndoStore()
    val tableChangeConfirmation = TableChangeConfirmation()

    /** Aviso de uso e alerta de veículo em movimento antes de qualquer gravação (HARA H9). */
    internal val writeSafetyGate = WriteSafetyGate(
        isNoticeAccepted = { _desktopSettings.value.writeSafetyNoticeAccepted },
        markNoticeAccepted = { saveDesktopSettings(_desktopSettings.value.copy(writeSafetyNoticeAccepted = true)) },
        currentSpeedKmh = {
            _liveData.value?.let { VehicleMotion.speedKmh(it.candidateSpeedKph, it.gpsSpeedKph) }
        },
    )

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    private val _desktopSettings = MutableStateFlow(DesktopSettingsStore.loadSettings())
    val desktopSettings = _desktopSettings.asStateFlow()

    private val _availableIniDefinitions = MutableStateFlow<List<IniCatalogEntry>>(emptyList())
    val availableIniDefinitions = _availableIniDefinitions.asStateFlow()

    private val _importedIniDefinitions = MutableStateFlow<List<ImportedIniDefinition>>(emptyList())
    val importedIniDefinitions = _importedIniDefinitions.asStateFlow()

    private val _activeIniDefinition = MutableStateFlow<IniDefinition?>(null)
    val activeIniDefinition = _activeIniDefinition.asStateFlow()

    private val _activeIniCatalogEntry = MutableStateFlow<IniCatalogEntry?>(null)
    val activeIniCatalogEntry = _activeIniCatalogEntry.asStateFlow()

    private val _readOnlySafeMode = MutableStateFlow(false)
    val readOnlySafeMode = _readOnlySafeMode.asStateFlow()

    private val _serialPorts = MutableStateFlow<List<SerialPortInfo>>(emptyList())
    val serialPorts = _serialPorts.asStateFlow()

    private val _streamIntervalMs = MutableStateFlow(120L)
    val streamIntervalMs = _streamIntervalMs.asStateFlow()

    private val logRecorder = LiveLogRecorder()
    val logState = logRecorder.state
    private val _logSnapshot = MutableStateFlow<LiveLogSnapshot?>(null)
    val logSnapshot = _logSnapshot.asStateFlow()
    private val _logSnapshotSourcePath = MutableStateFlow<String?>(null)
    val logSnapshotSourcePath = _logSnapshotSourcePath.asStateFlow()
    private val _logViewerError = MutableStateFlow<String?>(null)
    val logViewerError = _logViewerError.asStateFlow()
    private val _lastSavedLogPath = MutableStateFlow<String?>(null)
    val lastSavedLogPath = _lastSavedLogPath.asStateFlow()
    private val _logSaveStatus = MutableStateFlow<String?>(null)
    val logSaveStatus = _logSaveStatus.asStateFlow()
    private val _analyzerLogFile = MutableStateFlow<String?>(null)
    val analyzerLogFile = _analyzerLogFile.asStateFlow()
    private val _analyzerResult = MutableStateFlow<AnalyzerResult?>(null)
    val analyzerResult = _analyzerResult.asStateFlow()
    private val _analyzerBusy = MutableStateFlow(false)
    val analyzerBusy = _analyzerBusy.asStateFlow()
    private val _analyzerError = MutableStateFlow<String?>(null)
    val analyzerError = _analyzerError.asStateFlow()
    private val _analyzerUndoVeTable = MutableStateFlow<VeTable?>(null)
    val analyzerUndoAvailable = _analyzerUndoVeTable.asStateFlow()
    private val _beforeAfterBeforeLogPath = MutableStateFlow<String?>(null)
    val beforeAfterBeforeLogPath = _beforeAfterBeforeLogPath.asStateFlow()
    private val _beforeAfterAfterLogPath = MutableStateFlow<String?>(null)
    val beforeAfterAfterLogPath = _beforeAfterAfterLogPath.asStateFlow()
    private val _beforeAfterResult = MutableStateFlow<LogCompareResult?>(null)
    val beforeAfterResult = _beforeAfterResult.asStateFlow()
    private val _beforeAfterBusy = MutableStateFlow(false)
    val beforeAfterBusy = _beforeAfterBusy.asStateFlow()
    private val _beforeAfterError = MutableStateFlow<String?>(null)
    val beforeAfterError = _beforeAfterError.asStateFlow()

    private val configManager = ConfigManager()
    private val _configState = MutableStateFlow(ConfigSyncState())
    val configState = _configState.asStateFlow()
    private val _syncPrompt = MutableStateFlow<SyncPrompt?>(null)
    val syncPrompt = _syncPrompt.asStateFlow()

    private var pollingJob: Job? = null
    private var connection: IEcuConnection? = null
    private var client: EcuTransport? = null
    private var localSessionDir: File? = null
    private var ecuSessionDir: File? = null
    private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private var lastIniDefinitionSignature: String? = null
    private val connectRetryPolicy = ConnectionRetryPolicy(maxAttempts = CONNECT_RETRY_ATTEMPTS, delayMs = CONNECT_RETRY_DELAY_MS)
    private val autoReconnectCoordinator = AutoReconnectCoordinator(reconnectDelayMs = RECONNECT_DELAY_MS)
    private val beforeAfterComparator = BeforeAfterLogComparator()
    private val syncService = ConfigSyncService(configManager)
    private val definitionRepository = DesktopDefinitionRepository()
    private val ecuCommandMutex = Mutex()
    private val serialTunnelService = SerialTunnelService(scope)
    val serialTunnelState = serialTunnelService.state

    init {
        refreshIniDefinitions()
        maybeAutoConnect()
    }

    fun connectTcp(host: String, port: Int) {
        saveConnectionProfile(
            connectionType = ConnectionType.TCP,
            tcpHost = host,
            tcpPort = port
        )
        connectInternal(EcuTcpConnection(host, port))
    }

    fun connectSerial(portDescriptor: String, baudRate: Int, connectionType: ConnectionType = ConnectionType.USB) {
        saveConnectionProfile(
            connectionType = connectionType,
            serialPort = portDescriptor,
            serialBaudRate = baudRate
        )
        connectInternal(EcuSerialConnection(portDescriptor, baudRate))
    }

    fun saveConnectionProfile(
        connectionType: ConnectionType,
        tcpHost: String? = null,
        tcpPort: Int? = null,
        serialPort: String? = null,
        serialBaudRate: Int? = null,
    ) {
        val current = _desktopSettings.value
        saveDesktopSettings(
            current.copy(
                lastConnectionType = connectionType,
                lastTcpHost = tcpHost ?: current.lastTcpHost,
                lastTcpPort = tcpPort ?: current.lastTcpPort,
                lastSerialPort = serialPort ?: current.lastSerialPort,
                lastSerialBaudRate = serialBaudRate ?: current.lastSerialBaudRate,
            )
        )
    }

    private fun maybeAutoConnect() {
        val settings = _desktopSettings.value
        if (!settings.autoConnectOnStart || _connectionState.value.isConnected) {
            return
        }
        connectUsingLastProfile()
    }

    private fun connectUsingLastProfile() {
        val settings = _desktopSettings.value
        when (settings.lastConnectionType) {
            ConnectionType.TCP -> {
                val host = settings.lastTcpHost
                val port = settings.lastTcpPort
                if (!host.isNullOrBlank() && port != null) {
                    connectTcp(host, port)
                }
            }
            ConnectionType.USB,
            ConnectionType.BLUETOOTH -> {
                val portDescriptor = settings.lastSerialPort
                val baudRate = settings.lastSerialBaudRate
                if (!portDescriptor.isNullOrBlank() && baudRate != null) {
                    connectSerial(
                        portDescriptor = portDescriptor,
                        baudRate = baudRate,
                        connectionType = settings.lastConnectionType
                    )
                }
            }
            null -> Unit
        }
    }

    /**
     * Reconecta automaticamente apos uma queda inesperada de conexao (nunca apos
     * desconexao manual do usuario), usando o ultimo perfil de conexao salvo.
     */
    private fun scheduleAutomaticReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            autoReconnectCoordinator.awaitReconnectDelay()
            if (manualDisconnect || _connectionState.value.isConnected) {
                return@launch
            }
            ConnectionDiagnosticsLogger.log("desktop", "reconnect", "attempting automatic reconnect")
            connectUsingLastProfile()
        }
    }

    private fun connectInternal(newConnection: IEcuConnection) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        teardownConnection()

        connection = newConnection
        client = createTransport(checkNotNull(connection))
        _connectionState.value = ConnectionState(ConnectionStatus.Connecting)

        pollingJob = scope.launch(Dispatchers.IO) {
            try {
                val activeClient = client ?: return@launch
                val settings = _desktopSettings.value
                if (!settings.manualFirmwareProfile.isNullOrBlank()) {
                    activeClient.setManualFirmwareProfile(settings.manualFirmwareProfile, readOnly = true)
                } else {
                    activeClient.clearManualFirmwareProfile()
                }

                connectRetryPolicy.connect(
                    onAttemptFailed = { attempt, error ->
                        ConnectionDiagnosticsLogger.log(
                            "desktop",
                            "connect",
                            "attempt $attempt failed: ${error.message ?: "unknown"}, retrying"
                        )
                    }
                ) {
                    activeClient.connect()
                }
                ConnectionDiagnosticsLogger.log("desktop", "connect", "handshake complete ${activeClient.getConnectionInfo()}")
                _firmwareInfo.value = activeClient.getFirmwareInfoCached()
                _productString.value = activeClient.getProductString()
                _connectionInfo.value = activeClient.getConnectionInfo()
                _readOnlySafeMode.value = activeClient.isReadOnlySafeMode()
                runCatching {
                    applyConfiguredIniDefinition(activeClient)
                    refreshDiagnosticSummary()
                }.onFailure { error ->
                    val message = "Falha ao aplicar definicao INI: ${error.message ?: "unknown"}"
                    Logger.w(TAG, message)
                    _lastError.value = message
                }
                val configsDownloaded = runCatching { downloadAllConfigs(autoRestartStream = true) }
                    .onFailure { error ->
                        val message = "Falha no download inicial das configuracoes: ${error.message ?: "unknown"}"
                        Logger.w(TAG, message)
                        _lastError.value = message
                    }
                    .getOrDefault(false)
                // Live data must start regardless of config download outcome: the dashboard
                // is the app's core feature and shouldn't stay blank just because table sync failed.
                if (_connectionState.value.isConnected) {
                    runCatching {
                        activeClient.startLiveDataStream(_streamIntervalMs.value)
                        ConnectionDiagnosticsLogger.log("desktop", "stream", "started (configsDownloaded=$configsDownloaded)")
                    }.onFailure { error ->
                        Logger.w(TAG, "Falha ao iniciar live data: ${error.message ?: "unknown"}")
                    }
                }
            } catch (e: Exception) {
                ConnectionDiagnosticsLogger.logError("desktop", "connect", "failed: ${e.message ?: "unknown"}", e)
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
                _connectionState.value = ConnectionState(
                    status = ConnectionStatus.Failed,
                    detail = e.message
                )
                disconnect()
                }
        }
    }

    private fun createTransport(connection: IEcuConnection): EcuTransport {
        val callbacks = transportCallbacks()
        val profileStore = Obd2OptimizationProfileStore()
        val sessionStore = PsaConnectionSessionStore()
        val investigationRecorder = Obd2InvestigationRecorder()
        return when (_desktopSettings.value.protocol) {
            AppProtocol.ELM327_OBD2 -> {
                val obd2Transport = Obd2Transport(
                    connection = connection,
                    onDataReceived = callbacks.onDataReceived,
                    onConnectionStateChanged = callbacks.onConnectionStateChanged,
                    onError = callbacks.onError,
                    profileStore = profileStore,
                    investigationRecorder = investigationRecorder,
                )
                val psaTransport = PsaTransport(
                    connection = connection,
                    onDataReceived = callbacks.onDataReceived,
                    onConnectionStateChanged = callbacks.onConnectionStateChanged,
                    onError = callbacks.onError,
                    obd2ProfileStore = profileStore,
                    sessionStore = sessionStore,
                    investigationRecorder = investigationRecorder,
                    enableInvestigationCampaign = DiagnosticsFlags.ENABLE_ECU_INVESTIGATION,
                )
                val renaultTransport = RenaultTransport(
                    connection = connection,
                    onDataReceived = callbacks.onDataReceived,
                    onConnectionStateChanged = callbacks.onConnectionStateChanged,
                    onError = callbacks.onError,
                    obd2ProfileStore = profileStore,
                    investigationRecorder = investigationRecorder,
                )
                val vagTransport = VagTransport(
                    connection = connection,
                    onDataReceived = callbacks.onDataReceived,
                    onConnectionStateChanged = callbacks.onConnectionStateChanged,
                    onError = callbacks.onError,
                    investigationRecorder = investigationRecorder,
                )
                val promotingObd2Transport = PromotingObd2Transport(
                    genericTransport = obd2Transport,
                    psaTransport = psaTransport,
                )
                val renaultPsaTransport = AutoDetectEcuTransport(
                    primaryTransport = renaultTransport,
                    obd2FallbackTransport = psaTransport,
                )
                val vagRenaultPsaTransport = AutoDetectEcuTransport(
                    primaryTransport = vagTransport,
                    obd2FallbackTransport = renaultPsaTransport,
                )
                AutoDetectEcuTransport(
                    primaryTransport = promotingObd2Transport,
                    obd2FallbackTransport = vagRenaultPsaTransport,
                )
            }
            AppProtocol.MS_PROTOCOL -> EcuClient(
                connection = connection,
                onDataReceived = callbacks.onDataReceived,
                onConnectionStateChanged = callbacks.onConnectionStateChanged,
                onError = callbacks.onError,
            ).apply {
                tableChangeGuard = { assessment -> tableChangeConfirmation.confirm(assessment) }
                writeGuard = { writeSafetyGate.authorize() }
            }
        }
    }

    private fun transportCallbacks(): TransportCallbacks {
        return TransportCallbacks(
            onDataReceived = { data ->
                _liveData.value = data
                if (logRecorder.state.value.isRecording) {
                    logRecorder.record(data)
                }
            },
            onConnectionStateChanged = { isConnected ->
                val wasConnected = _connectionState.value.isConnected
                _connectionState.value = if (isConnected) {
                    ConnectionState(ConnectionStatus.Connected)
                } else {
                    ConnectionState(ConnectionStatus.Disconnected)
                }
                if (autoReconnectCoordinator.shouldReconnect(wasConnected, isConnected, manualDisconnect)) {
                    ConnectionDiagnosticsLogger.log("desktop", "connect", "unexpected drop, scheduling reconnect")
                    scheduleAutomaticReconnect()
                }
            },
            onError = { error ->
                _lastError.value = error
            }
        )
    }

    private suspend fun <T> withPausedLiveDataStream(
        restartStream: Boolean = true,
        block: suspend (EcuTransport) -> T
    ): T? {
        return ecuCommandMutex.withLock {
            val activeClient = client
            if (activeClient == null || !_connectionState.value.isConnected) {
                return@withLock null
            }

            val wasStreaming = activeClient.isStreaming()
            if (wasStreaming) {
                activeClient.pauseLiveDataStream()
            }
            try {
                block(activeClient)
            } finally {
                if (restartStream && wasStreaming && client === activeClient && _connectionState.value.isConnected) {
                    activeClient.startLiveDataStream(_streamIntervalMs.value)
                }
            }
        }
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        teardownConnection()
    }

    private fun teardownConnection() {
        pollingJob?.cancel()
        pollingJob = null
        client?.stopLiveDataStream()
        client?.disconnect()
        client = null
        connection?.disconnect()
        connection = null
        _liveData.value = null
        tableUndoStore.clear()
        tableChangeConfirmation.resolve(false)
        writeSafetyGate.resolve(false)
        if (localSessionDir == null) {
            _engineConstants.value = null
            _triggerSettings.value = null
            _veTable.value = null
            _veTable2.value = null
            _ignitionTable.value = null
            _ignitionTable2.value = null
            _afrTable.value = null
            _dwellTable.value = null
            _idleControlSettings.value = null
            _closedLoopCorrections.value = null
        }
        _syncPrompt.value = null
        _readOnlySafeMode.value = false
        _activeIniDefinition.value = null
        _activeIniCatalogEntry.value = null
        if (_connectionState.value.isConnected) {
            _connectionState.value = ConnectionState(ConnectionStatus.Disconnected)
        }
    }

    fun dismissSyncPrompt() {
        _syncPrompt.value = null
    }

    fun chooseSyncSource(useLocal: Boolean) {
        scope.launch(Dispatchers.IO) {
            val prompt = _syncPrompt.value ?: return@launch
            _syncPrompt.value = null
            if (useLocal) {
                _configState.value = _configState.value.copy(
                    isBusy = true,
                    message = "Restaurando sessão local..."
                )
                try {
                    syncService.restoreConfigToEcu(
                        client = client ?: throw IllegalStateException("ECU não conectada"),
                        sessionDir = prompt.localSessionDir,
                        stopOnRangeErr = true,
                        restartStreamIntervalMs = _streamIntervalMs.value,
                        safetyBackup = true,
                    )
                    ecuSessionDir = prompt.localSessionDir
                    localSessionDir = prompt.localSessionDir
                    loadTablesFromSession(prompt.localSessionDir)
                    _configState.value = _configState.value.copy(
                        isBusy = false,
                        message = "Sessão local aplicada na ECU."
                    )
                } catch (e: Exception) {
                    _configState.value = _configState.value.copy(
                        isBusy = false,
                        message = "Falha ao restaurar sessão local: ${e.message}"
                    )
                }
            } else {
                ecuSessionDir = prompt.ecuSessionDir
                localSessionDir = prompt.ecuSessionDir
                loadTablesFromSession(prompt.ecuSessionDir)
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Sessão da ECU carregada."
                )
            }
        }
    }

    fun updateStreamInterval(intervalMs: Long) {
        _streamIntervalMs.value = intervalMs
        if (connectionState.value.isConnected) {
            scope.launch(Dispatchers.IO) {
                client?.stopLiveDataStream()
                client?.startLiveDataStream(intervalMs)
            }
        }
    }

    fun refreshSerialPorts() {
        val ports = EcuSerialConnection.listPorts()
        _serialPorts.value = ports.map { port ->
            SerialPortInfo(port, port)
        }
    }

    fun startSerialTunnel(serialPortDescriptor: String, baudRate: Int, tcpPort: Int) {
        if (connectionState.value.isConnected) {
            disconnect()
        }
        serialTunnelService.start(serialPortDescriptor, baudRate, tcpPort)
    }

    fun stopSerialTunnel() {
        serialTunnelService.stop()
    }

    fun saveDesktopSettings(settings: DesktopSettingsState) {
        _desktopSettings.value = settings
        DesktopSettingsStore.saveSettings(settings)
        refreshDiagnosticSummary()
    }

    private fun refreshDiagnosticSummary(lastError: String? = _lastError.value) {
        _diagnosticSummary.value = DesktopDiagnosticSummary(
            firmwareSignature = _firmwareInfo.value?.signature,
            productString = _productString.value,
            connectionInfo = _connectionInfo.value,
            activeIniSource = _activeIniDefinition.value?.sourceName,
            manualFirmwareProfile = _desktopSettings.value.manualFirmwareProfile,
            diagnosticLoggerMode = _desktopSettings.value.diagnosticLoggerMode,
            readOnlySafeMode = _readOnlySafeMode.value,
            lastError = lastError,
        )
    }

    fun refreshIniDefinitions(forceCatalogRefresh: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val catalog = runCatching {
                definitionRepository.loadCatalog(forceRefresh = forceCatalogRefresh)
            }.getOrDefault(emptyList())
            val imported = runCatching {
                definitionRepository.listImportedDefinitions()
            }.getOrDefault(emptyList())
            _availableIniDefinitions.value = catalog
            _importedIniDefinitions.value = imported
        }
    }

    fun importIniDefinition(sourceFile: File) {
        scope.launch(Dispatchers.IO) {
            try {
                val definition = definitionRepository.importDefinition(sourceFile)
                _importedIniDefinitions.value = definitionRepository.listImportedDefinitions()
                val updatedSettings = _desktopSettings.value.copy(
                    iniSelectionMode = IniSelectionMode.MANUAL,
                    iniSelectionSource = IniSelectionSource.IMPORTED,
                    iniDefinitionId = definition.sourceName,
                )
                saveDesktopSettings(updatedSettings)
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun loadEngineConstants() {
        scope.launch(Dispatchers.IO) {
            try {
                _engineConstants.value = withPausedLiveDataStream { it.readEngineConstants() }
                    ?: run {
                    localSessionDir?.let { configManager.loadEngineConstants(it) }
                }
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun saveEngineConstants(constants: EngineConstants) {
        scope.launch(Dispatchers.IO) {
            try {
                withPausedLiveDataStream { it.writeEngineConstants(constants) }
                updateLocalEngineConstants(constants)
                _engineConstants.value = constants
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun loadTriggerSettings() {
        scope.launch(Dispatchers.IO) {
            try {
                _triggerSettings.value = withPausedLiveDataStream { it.readTriggerSettings() }
                    ?: run {
                    localSessionDir?.let { configManager.loadTriggerSettings(it) }
                }
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun saveTriggerSettings(settings: TriggerSettings) {
        scope.launch(Dispatchers.IO) {
            try {
                withPausedLiveDataStream { it.writeTriggerSettings(settings) }
                updateLocalTriggerSettings(settings)
                _triggerSettings.value = settings
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun loadEngineProtectionConfig() {
        scope.launch(Dispatchers.IO) {
            try {
                _engineProtectionConfig.value = withPausedLiveDataStream { it.readEngineProtectionConfig() }
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun saveEngineProtectionConfig(config: EngineProtectionConfig) {
        scope.launch(Dispatchers.IO) {
            try {
                withPausedLiveDataStream { it.writeEngineProtectionConfig(config, burn = true) }
                _engineProtectionConfig.value = config
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    suspend fun readPressureCalibration(): PressureCalibration? = withContext(Dispatchers.IO) {
        try {
            withPausedLiveDataStream { it.readPressureCalibration() }
        } catch (e: Exception) {
            _lastError.value = e.message
            null
        }
    }

    suspend fun writePressureCalibration(calibration: PressureCalibration): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withPausedLiveDataStream { it.writePressureCalibration(calibration, burn = true) }
            Unit
        }.onFailure { _lastError.value = it.message }
    }

    /** Curvas .ini e campos escalares da MS2 (WUE, marcha lenta com motor de passo). */
    internal val ms2CurveBackend = com.speeduino.manager.desktop.feature.configs.Ms2CurveBackend(
        read = { name -> runCatching { requireNotNull(withPausedLiveDataStream { it.readIniCurve(name) }) { "ECU não conectada." } } },
        write = { name, values -> runCatching { requireNotNull(withPausedLiveDataStream { it.writeIniCurve(name, values) }) { "ECU não conectada." }; Unit } },
        readFields = { names -> runCatching { requireNotNull(withPausedLiveDataStream { it.readMegaSpeedFieldValues(names) }) { "ECU não conectada." } } },
        writeField = { name, value -> runCatching { requireNotNull(withPausedLiveDataStream { it.writeMegaSpeedConfigField(name, value) ; true }) { "ECU não conectada." }; Unit } },
    )

    suspend fun readTpsCalibration(): TpsCalibration? = withContext(Dispatchers.IO) {
        try {
            withPausedLiveDataStream { it.readTpsCalibration() }
        } catch (e: Exception) {
            _lastError.value = e.message
            null
        }
    }

    suspend fun writeTpsCalibration(calibration: TpsCalibration): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withPausedLiveDataStream { it.writeTpsCalibration(calibration, burn = true) }
            Unit
        }.onFailure { _lastError.value = it.message }
    }

    fun veTableState(mapIndex: Int): StateFlow<VeTable?> = if (mapIndex == 2) veTable2 else veTable

    fun ignitionTableState(mapIndex: Int): StateFlow<IgnitionTable?> = if (mapIndex == 2) ignitionTable2 else ignitionTable

    fun loadVeTable(mapIndex: Int = 1) {
        scope.launch(Dispatchers.IO) {
            _lastError.value = null
            try {
                val table = withPausedLiveDataStream { it.readVeTable(mapIndex) }
                    ?: run {
                    localSessionDir?.let { configManager.loadVeTable(it, mapIndex) }
                }
                if (mapIndex == 2) _veTable2.value = table else _veTable.value = table
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    /**
     * MS3 firmware has historically been more sensitive to partial/rejected page writes than
     * Speeduino, so back up the last known-good session before writing to it. No-op for any
     * other ECU family. Shared with the Android app's equivalent safety check via ecu-core.
     */
    private suspend fun backupBeforeMs3Write() {
        val activeClient = client ?: return
        val sessionDir = localSessionDir ?: return
        runCatching {
            syncService.ensureMs3WriteSafetyBackup(sessionDir, activeClient.getEcuFamily())
        }.onFailure { e -> Logger.w(TAG, "Falha no backup de seguranca MS3: ${e.message}") }
    }

    fun saveVeTable(table: VeTable, mapIndex: Int = 1) {
        scope.launch(Dispatchers.IO) {
            try {
                backupBeforeMs3Write()
                veTableState(mapIndex).value?.let { tableUndoStore.push(UndoKey(UndoTableKind.VE, mapIndex), it) }
                withPausedLiveDataStream { it.writeVeTable(table, mapIndex) }
                updateLocalVeTable(table, mapIndex)
                if (mapIndex == 2) _veTable2.value = table else _veTable.value = table
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun loadIgnitionTable(mapIndex: Int = 1) {
        scope.launch(Dispatchers.IO) {
            _lastError.value = null
            try {
                val table = withPausedLiveDataStream { it.readIgnitionTable(mapIndex) }
                    ?: run {
                    localSessionDir?.let { configManager.loadIgnitionTable(it, mapIndex) }
                }
                if (mapIndex == 2) _ignitionTable2.value = table else _ignitionTable.value = table
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun saveIgnitionTable(table: IgnitionTable, mapIndex: Int = 1) {
        scope.launch(Dispatchers.IO) {
            try {
                backupBeforeMs3Write()
                ignitionTableState(mapIndex).value?.let { tableUndoStore.push(UndoKey(UndoTableKind.IGNITION, mapIndex), it) }
                withPausedLiveDataStream { it.writeIgnitionTable(table, mapIndex) }
                updateLocalIgnitionTable(table, mapIndex)
                if (mapIndex == 2) _ignitionTable2.value = table else _ignitionTable.value = table
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun loadAfrTable() {
        scope.launch(Dispatchers.IO) {
            _lastError.value = null
            try {
                _afrTable.value = withPausedLiveDataStream { it.readAfrTable() }
                    ?: run {
                    localSessionDir?.let { configManager.loadAfrTable(it) }
                }
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun saveAfrTable(table: AfrTable) {
        scope.launch(Dispatchers.IO) {
            try {
                backupBeforeMs3Write()
                _afrTable.value?.let { tableUndoStore.push(UndoKey(UndoTableKind.AFR), it) }
                withPausedLiveDataStream { it.writeAfrTable(table) }
                updateLocalAfrTable(table)
                _afrTable.value = table
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    /**
     * Regrava a tabela que estava na ECU antes da última gravação. A mudança é intencional, então
     * não passa pelo diálogo de mudança grande. O topo da pilha só sai depois da gravação ter dado certo.
     */
    fun undoTableWrite(key: UndoKey) {
        scope.launch(Dispatchers.IO) {
            try {
                backupBeforeMs3Write()
                tableChangeConfirmation.withoutConfirmation {
                    when (key.kind) {
                        UndoTableKind.VE -> tableUndoStore.peek<VeTable>(key)?.let { previous ->
                            withPausedLiveDataStream { it.writeVeTable(previous, key.mapIndex) }
                            updateLocalVeTable(previous, key.mapIndex)
                            if (key.mapIndex == 2) _veTable2.value = previous else _veTable.value = previous
                        }
                        UndoTableKind.IGNITION -> tableUndoStore.peek<IgnitionTable>(key)?.let { previous ->
                            withPausedLiveDataStream { it.writeIgnitionTable(previous, key.mapIndex) }
                            updateLocalIgnitionTable(previous, key.mapIndex)
                            if (key.mapIndex == 2) _ignitionTable2.value = previous else _ignitionTable.value = previous
                        }
                        UndoTableKind.AFR -> tableUndoStore.peek<AfrTable>(key)?.let { previous ->
                            withPausedLiveDataStream { it.writeAfrTable(previous) }
                            updateLocalAfrTable(previous)
                            _afrTable.value = previous
                        }
                        UndoTableKind.DWELL -> tableUndoStore.peek<DwellTable>(key)?.let { previous ->
                            withPausedLiveDataStream { it.writeDwellTable(previous) }
                            _dwellTable.value = previous
                        }
                    }
                }
                tableUndoStore.pop(key)
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun loadDwellTable() {
        scope.launch(Dispatchers.IO) {
            _lastError.value = null
            try {
                val table = withPausedLiveDataStream { it.readDwellTable() }
                    ?: DwellTable.createDefault()
                _dwellTable.value = table
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
                _dwellTable.value = DwellTable.createDefault()
            }
        }
    }

    fun saveDwellTable(table: DwellTable) {
        scope.launch(Dispatchers.IO) {
            try {
                _dwellTable.value?.let { tableUndoStore.push(UndoKey(UndoTableKind.DWELL), it) }
                withPausedLiveDataStream { it.writeDwellTable(table) }
                _dwellTable.value = table
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun loadIdleControlSettings() {
        scope.launch(Dispatchers.IO) {
            try {
                _idleControlSettings.value = withPausedLiveDataStream { it.readIdleControlSettings() }
                    ?: run {
                    loadIdleControlSettingsFromSession(localSessionDir)
                }
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun saveIdleControlSettings(settings: IdleControlSettings) {
        scope.launch(Dispatchers.IO) {
            try {
                withPausedLiveDataStream { it.writeIdleControlSettings(settings) }
                updateLocalIdleControlSettings(settings)
                _idleControlSettings.value = settings
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun loadClosedLoopCorrections() {
        scope.launch(Dispatchers.IO) {
            try {
                _closedLoopCorrections.value = withPausedLiveDataStream { it.readClosedLoopCorrectionConfig() }
                    ?: run {
                    loadClosedLoopCorrectionsFromSession(localSessionDir)
                }
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun saveClosedLoopCorrections(config: ClosedLoopCorrectionConfig) {
        scope.launch(Dispatchers.IO) {
            try {
                val normalized = ClosedLoopCorrectionMapper.syncFromAfr(config)
                withPausedLiveDataStream { it.writeClosedLoopCorrectionConfig(normalized) }
                updateLocalClosedLoopCorrections(normalized)
                _closedLoopCorrections.value = normalized
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun loadRusefiInputOutputSnapshot() {
        scope.launch(Dispatchers.IO) {
            try {
                val snapshot = withPausedLiveDataStream { it.readRusefiInputOutputSnapshot() }
                if (snapshot != null) {
                    _tuningConfigState.value = _tuningConfigState.value.copy(
                        rusefiSnapshot = snapshot
                    )
                } else {
                    _lastError.value = "RuseFI input/output snapshot requires an active connection."
                }
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun loadSecondarySerialConfig() {
        scope.launch(Dispatchers.IO) {
            try {
                val config = withPausedLiveDataStream { it.readSecondarySerialConfig() }
                if (config != null) {
                    _tuningConfigState.value = _tuningConfigState.value.copy(
                        secondarySerialConfig = config
                    )
                } else {
                    _lastError.value = "Secondary serial config requires an active connection."
                }
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    fun saveSecondarySerialConfig(config: SecondarySerialConfig) {
        scope.launch(Dispatchers.IO) {
            try {
                withPausedLiveDataStream { it.writeSecondarySerialConfig(config, burn = true) }
                _tuningConfigState.value = _tuningConfigState.value.copy(secondarySerialConfig = config)
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    private val restorePointStore = RestorePointStore()
    private val _restorePoints = MutableStateFlow(restorePointStore.list())
    val restorePoints = _restorePoints.asStateFlow()

    private fun refreshRestorePoints() {
        _restorePoints.value = restorePointStore.list()
    }

    /** Baixa a configuração atual da ECU (se conectada) e a guarda como ponto de restauração. */
    internal fun createRestorePoint(name: String?) {
        scope.launch(Dispatchers.IO) {
            if (_connectionState.value.isConnected) {
                if (!downloadAllConfigs(autoRestartStream = true)) return@launch
            }
            val source = localSessionDir ?: configManager.latestSavedConfig()
            if (source == null) {
                _configState.value = _configState.value.copy(isBusy = false, message = LocalizationManager.currentStrings()["restore_points_create_failed"])
                return@launch
            }
            val info = _firmwareInfo.value
            runCatching {
                restorePointStore.create(source, name, info?.family?.name, info?.productString ?: info?.signature)
            }.onSuccess {
                refreshRestorePoints()
                _configState.value = _configState.value.copy(isBusy = false, message = LocalizationManager.currentStrings()["restore_points_created"])
            }.onFailure {
                _configState.value = _configState.value.copy(isBusy = false, message = LocalizationManager.currentStrings()["restore_points_create_failed"] + " " + (it.message ?: ""))
            }
        }
    }

    /** Grava o ponto de restauração na ECU, com backup de segurança e checagem de CRC32 (ecu-core). */
    internal fun restoreFromRestorePoint(point: RestorePoint) {
        scope.launch(Dispatchers.IO) {
            val activeClient = client
            if (activeClient == null || !_connectionState.value.isConnected) {
                _configState.value = _configState.value.copy(isBusy = false, message = "ECU não conectada.")
                return@launch
            }
            _configState.value = _configState.value.copy(isBusy = true, message = "Restaurando ponto de restauração...")
            try {
                syncService.restoreConfigToEcu(
                    client = activeClient,
                    sessionDir = point.dir,
                    stopOnRangeErr = true,
                    restartStreamIntervalMs = _streamIntervalMs.value,
                    safetyBackup = true,
                )
                ecuSessionDir = point.dir
                localSessionDir = point.dir
                loadTablesFromSession(point.dir)
                _configState.value = _configState.value.copy(isBusy = false, message = "Ponto de restauração aplicado na ECU.")
            } catch (e: Exception) {
                _configState.value = _configState.value.copy(isBusy = false, message = "Falha ao restaurar: ${e.message}")
            }
        }
    }

    internal fun renameRestorePoint(point: RestorePoint, name: String) {
        restorePointStore.rename(point.id, name)
        refreshRestorePoints()
    }

    internal fun deleteRestorePoint(point: RestorePoint) {
        restorePointStore.delete(point.id)
        refreshRestorePoints()
    }

    fun downloadAllConfigs() {
        scope.launch(Dispatchers.IO) {
            downloadAllConfigs(autoRestartStream = true)
        }
    }

    fun exportLatestConfig(targetFile: File, sessionDirOverride: File? = null) {
        scope.launch(Dispatchers.IO) {
            val sessionDir = sessionDirOverride ?: localSessionDir ?: configManager.latestSavedConfig()
            if (sessionDir == null) {
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Nenhuma sessão salva para exportar."
                )
                return@launch
            }
            _configState.value = _configState.value.copy(
                isBusy = true,
                message = "Exportando backup..."
            )
            try {
                FileOutputStream(targetFile).use { output ->
                    configManager.exportSessionToZip(sessionDir, output)
                }
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Backup exportado: ${targetFile.name}",
                    lastSessionDir = sessionDir
                )
            } catch (e: Exception) {
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Erro ao exportar: ${e.message}"
                )
            }
        }
    }

    fun exportLatestConfigMsq(targetFile: File, sessionDirOverride: File? = null) {
        scope.launch(Dispatchers.IO) {
            val sessionDir = sessionDirOverride ?: localSessionDir ?: configManager.latestSavedConfig()
            if (sessionDir == null) {
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Nenhuma sessão salva para exportar."
                )
                return@launch
            }
            val definition = _activeIniDefinition.value
            if (definition == null) {
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Nenhuma definição .ini ativa - conecte a ECU antes de exportar .msq."
                )
                return@launch
            }
            _configState.value = _configState.value.copy(
                isBusy = true,
                message = "Exportando .msq..."
            )
            try {
                val pages = configManager.loadConfig(sessionDir).mapKeys { (page, _) -> page.toInt() and 0xFF }
                val xml = configManager.exportSessionToMsq(definition, pages)
                targetFile.writeText(xml, Charsets.UTF_8)
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Tune .msq exportado: ${targetFile.name}",
                    lastSessionDir = sessionDir
                )
            } catch (e: Exception) {
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Erro ao exportar .msq: ${e.message}"
                )
            }
        }
    }

    fun importConfigFromMsq(sourceFile: File) {
        scope.launch(Dispatchers.IO) {
            val definition = _activeIniDefinition.value
            if (definition == null) {
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Nenhuma definição .ini ativa - conecte a ECU antes de importar .msq."
                )
                return@launch
            }
            val baseSessionDir = localSessionDir ?: configManager.latestSavedConfig()
            if (baseSessionDir == null) {
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Baixe a configuração da ECU antes de importar um .msq."
                )
                return@launch
            }
            _configState.value = _configState.value.copy(
                isBusy = true,
                message = "Importando .msq..."
            )
            try {
                val xmlText = sourceFile.readText(Charsets.UTF_8)
                val doc = io.ecucore.definition.MsqCodec.decode(xmlText)
                if (doc.signature.isNotBlank() && doc.signature != definition.signature) {
                    _configState.value = _configState.value.copy(
                        isBusy = false,
                        message = "Assinatura do .msq (${doc.signature}) não corresponde à ECU conectada (${definition.signature})."
                    )
                    return@launch
                }
                val basePages = configManager.loadConfig(baseSessionDir).mapKeys { (page, _) -> page.toInt() and 0xFF }
                val sessionDir = configManager.importSessionFromMsq(xmlText, definition, basePages)
                localSessionDir = sessionDir
                loadTablesFromSession(sessionDir)
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Tune .msq importado. Conecte e grave para aplicar na ECU.",
                    lastSessionDir = sessionDir
                )
            } catch (e: Exception) {
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Erro ao importar .msq: ${e.message}"
                )
            }
        }
    }

    fun importConfigAndRestore(sourceFile: File) {
        scope.launch(Dispatchers.IO) {
            _configState.value = _configState.value.copy(
                isBusy = true,
                message = "Importando backup..."
            )
            try {
                val sessionDir = FileInputStream(sourceFile).use { input ->
                    configManager.importSessionFromZip(input)
                }
                localSessionDir = sessionDir
                loadTablesFromSession(sessionDir)
                val warningText = "Sessão importada. Conecte para sincronizar."
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = warningText,
                    lastSessionDir = sessionDir
                )
            } catch (e: Exception) {
                _configState.value = _configState.value.copy(
                    isBusy = false,
                    message = "Erro ao importar: ${e.message}"
                )
            }
        }
    }

    private suspend fun downloadAllConfigs(autoRestartStream: Boolean): Boolean = withContext(Dispatchers.IO) {
        val activeClient = client
        if (activeClient == null || !_connectionState.value.isConnected) {
            _configState.value = _configState.value.copy(
                isBusy = false,
                message = "ECU não conectada."
            )
            return@withContext false
        }

        ConnectionDiagnosticsLogger.log(
            "desktop",
            "config_download",
            "starting autoRestartStream=$autoRestartStream transport=${activeClient.getConnectionInfo()}"
        )
        _configState.value = _configState.value.copy(
            isBusy = true,
            progressPercent = 0,
            message = "Iniciando download..."
        )

        val (result, decision) = withPausedLiveDataStream(restartStream = autoRestartStream) {
            syncService.downloadAndResolveSync(it, localSessionDir) { current, total, message ->
                val progress = if (total > 0) (current * 100) / total else 0
                _configState.value = _configState.value.copy(
                    progressPercent = progress,
                    message = message
                )
            }
        } ?: return@withContext false

        if (result.success) {
            val sessionDir = result.sessionDir
            ecuSessionDir = sessionDir
            _configState.value = _configState.value.copy(
                isBusy = false,
                progressPercent = 100,
                message = "Download concluído.",
                lastSessionDir = sessionDir
            )
            // A ECU e sempre a fonte de verdade: carrega os dados baixados dela imediatamente,
            // sem esperar o usuario decidir nada. Se houver uma sessao local divergente, o prompt
            // ainda aparece, mas apenas como opcao de restaurar o backup local por cima - nunca
            // bloqueia o preenchimento das telas.
            if (sessionDir != null) {
                localSessionDir = sessionDir
                loadTablesFromSession(sessionDir)
            }
            val prompt = decision.prompt
            if (prompt != null) {
                _syncPrompt.value = prompt.toDesktop()
            }
        } else {
            _configState.value = _configState.value.copy(
                isBusy = false,
                message = "Erro: ${result.error}"
            )
        }

        ConnectionDiagnosticsLogger.log(
            "desktop",
            "config_download",
            "finished success=${result.success} pages=${result.pagesDownloaded}/${result.totalPages} error=${result.error ?: "none"}"
        )

        return@withContext result.success
    }

    private suspend fun loadTablesFromSession(sessionDir: File) {
        val snapshot = syncService.loadTablesFromSession(sessionDir)
        _veTable.value = snapshot.veTable
        _veTable2.value = snapshot.veTable2
        _ignitionTable.value = snapshot.ignitionTable
        _ignitionTable2.value = snapshot.ignitionTable2
        _afrTable.value = snapshot.afrTable
        _engineConstants.value = snapshot.engineConstants
        _triggerSettings.value = snapshot.triggerSettings
        _idleControlSettings.value = loadIdleControlSettingsFromSession(sessionDir)
        _closedLoopCorrections.value = loadClosedLoopCorrectionsFromSession(sessionDir)
    }

    private fun ensureLocalSessionDir(): File? {
        if (localSessionDir == null) {
            localSessionDir = ecuSessionDir
        }
        return localSessionDir
    }

    private fun updateLocalEngineConstants(constants: EngineConstants) {
        val sessionDir = ensureLocalSessionDir() ?: return
        val pageFile = File(sessionDir, "page_1.bin")
        val basePage = if (pageFile.exists() && pageFile.length() >= 128) {
            pageFile.readBytes()
        } else {
            ByteArray(128)
        }
        val data = constants.applyToPage1(basePage)
        pageFile.writeBytes(data)
    }

    private fun updateLocalTriggerSettings(settings: TriggerSettings) {
        val sessionDir = ensureLocalSessionDir() ?: return
        val pageFile = File(sessionDir, "page_${TriggerSettings.PAGE_NUMBER}.bin")
        val basePage = if (pageFile.exists() && pageFile.length() >= TriggerSettings.PAGE_LENGTH) {
            pageFile.readBytes()
        } else {
            ByteArray(TriggerSettings.PAGE_LENGTH)
        }
        val data = settings.toPageData(basePage)
        pageFile.writeBytes(data)
    }

    private fun updateLocalIdleControlSettings(settings: IdleControlSettings) {
        val sessionDir = ensureLocalSessionDir() ?: return
        val page4File = File(sessionDir, "page_${IdleControlSettings.PAGE_NUMBER}.bin")
        val page7File = File(sessionDir, "page_${IdleControlSettings.TARGET_PAGE_NUMBER}.bin")
        val basePage4 = if (page4File.exists() && page4File.length() >= IdleControlSettings.PAGE_LENGTH) {
            page4File.readBytes()
        } else {
            ByteArray(IdleControlSettings.PAGE_LENGTH)
        }
        val basePage7 = if (page7File.exists() && page7File.length() >= IdleControlSettings.TARGET_PAGE_LENGTH) {
            page7File.readBytes()
        } else {
            ByteArray(IdleControlSettings.TARGET_PAGE_LENGTH)
        }
        page4File.writeBytes(settings.applyToPage4(basePage4))
        page7File.writeBytes(settings.applyTargetRpmToPage7(basePage7))
    }

    private fun updateLocalClosedLoopCorrections(config: ClosedLoopCorrectionConfig) {
        val sessionDir = ensureLocalSessionDir() ?: return
        val pageFile = File(sessionDir, "page_${ClosedLoopCorrectionMapper.PAGE_NUMBER}.bin")
        val basePage = if (pageFile.exists() && pageFile.length() >= ClosedLoopCorrectionMapper.PAGE_SIZE) {
            pageFile.readBytes()
        } else {
            ByteArray(ClosedLoopCorrectionMapper.PAGE_SIZE)
        }
        val era = _firmwareInfo.value?.era ?: FirmwareEra.MODERN_2025
        pageFile.writeBytes(ClosedLoopCorrectionMapper.applyToPage(basePage, config, era))
    }

    private fun updateLocalVeTable(table: VeTable, mapIndex: Int = 1) {
        val sessionDir = ensureLocalSessionDir() ?: return
        val pageFile = File(sessionDir, if (mapIndex == 2) "page_7.bin" else "page_2.bin")
        val format = resolveTableFormat(pageFile, VeTable.StorageFormat.MODERN_288, VeTable.StorageFormat.LEGACY_304)
        pageFile.writeBytes(table.toByteArray(format))
    }

    private fun updateLocalIgnitionTable(table: IgnitionTable, mapIndex: Int = 1) {
        val sessionDir = ensureLocalSessionDir() ?: return
        val pageFile = File(sessionDir, if (mapIndex == 2) "page_10.bin" else "page_3.bin")
        val format = resolveTableFormat(
            pageFile,
            IgnitionTable.StorageFormat.MODERN_288,
            IgnitionTable.StorageFormat.LEGACY_304
        )
        pageFile.writeBytes(table.toByteArray(format))
    }

    private fun updateLocalAfrTable(table: AfrTable) {
        val sessionDir = ensureLocalSessionDir() ?: return
        val pageFile = File(sessionDir, "page_5.bin")
        val format = resolveTableFormat(
            pageFile,
            AfrTable.StorageFormat.MODERN_288,
            AfrTable.StorageFormat.LEGACY_304
        )
        pageFile.writeBytes(table.toByteArray(format))
    }

    private fun <T> resolveTableFormat(
        pageFile: File,
        modern: T,
        legacy: T
    ): T {
        if (!pageFile.exists()) {
            return modern
        }
        val size = pageFile.length().toInt()
        val legacySize = when (legacy) {
            is VeTable.StorageFormat -> legacy.totalSize
            is IgnitionTable.StorageFormat -> legacy.totalSize
            is AfrTable.StorageFormat -> legacy.totalSize
            else -> 0
        }
        return if (size >= legacySize) legacy else modern
    }

    fun startLogCapture(intervalMs: Long) {
        logRecorder.start(intervalMs)
    }

    fun stopLogCapture() {
        logRecorder.stop()
        _logSnapshot.value = logRecorder.snapshot()
        _logSnapshotSourcePath.value = null
        _logViewerError.value = null
    }

    fun captureSnapshot() {
        _logSnapshot.value = logRecorder.snapshot()
        _logSnapshotSourcePath.value = null
        _logViewerError.value = null
    }

    fun loadLogSnapshotFromCsv(path: String) {
        scope.launch(Dispatchers.IO) {
            val strings = LocalizationManager.currentStrings()
            try {
                val snapshot = parseLogSnapshotCsv(File(path))
                _logSnapshot.value = snapshot
                _logSnapshotSourcePath.value = path
                _logViewerError.value = null
            } catch (e: Exception) {
                _logViewerError.value = strings.format("label.errorWithValue", e.message ?: "unknown")
            }
        }
    }

    fun saveLogSnapshot(
        fileName: String,
        selectedSignalKeys: Set<String>,
        exportFormat: DesktopLogExportFormat = DesktopLogExportFormat.CSV
    ) {
        scope.launch(Dispatchers.IO) {
            val strings = LocalizationManager.currentStrings()
            try {
                val snapshot = logRecorder.snapshot()
                if (snapshot == null || snapshot.entries.isEmpty()) {
                    _logSaveStatus.value = strings["label.logSaveNoData"]
                    return@launch
                }

                val rawName = fileName.ifBlank { strings["label.logFilenamePrefix"] }
                val sanitizedName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val baseName = sanitizedName.substringBeforeLast('.').ifBlank { strings["label.logFilenamePrefix"] }
                val targetDir = java.nio.file.Paths.get(
                    System.getProperty("user.home"),
                    "SpeeduinoManagerDesktop",
                    "logs"
                )
                java.nio.file.Files.createDirectories(targetDir)
                val baseFile = targetDir.resolve("$baseName.${exportFormat.fileExtension}").toFile()
                val targetFile = uniqueFile(baseFile)

                val selected = selectedSignalKeys.ifEmpty { DefaultSelectedLogSignals }
                val activeSignals = LogExportSignals.filter { selected.contains(it.key) }

                targetFile.bufferedWriter().use { writer ->
                    when (exportFormat) {
                        DesktopLogExportFormat.CSV -> writeSnapshotAsCsv(writer, snapshot, activeSignals)
                        DesktopLogExportFormat.MSL -> {
                            val firstRawEntry = snapshot.entries.firstOrNull { entry ->
                                entry.outputChannelBlockSize > 0 && entry.outputChannelData != null
                            }
                            val fields = firstRawEntry
                                ?.outputChannelBlockSize
                                ?.takeIf { it > 0 }
                                ?.let(EcuOutputChannels::getDefinition)
                                .orEmpty()
                            writeSnapshotAsMsl(writer, snapshot, fields, activeSignals)
                        }
                    }
                }

                if (exportFormat == DesktopLogExportFormat.CSV) {
                    _lastSavedLogPath.value = targetFile.absolutePath
                    _analyzerLogFile.value = targetFile.absolutePath
                }
                _logSaveStatus.value = strings.format("label.logSaveSuccess", targetFile.absolutePath)
            } catch (e: Exception) {
                _logSaveStatus.value = strings.format("label.logSaveError", e.message ?: "unknown")
            }
        }
    }

    private fun writeSnapshotAsCsv(
        writer: java.io.Writer,
        snapshot: LiveLogSnapshot,
        activeSignals: List<LogExportSignal>
    ) {
        val header = buildList {
            add("timestamp_ms")
            activeSignals.forEach { add(it.header) }
        }
        writer.appendLine(header.joinToString(","))
        snapshot.entries.forEach { entry ->
            val row = mutableListOf<String>()
            row += entry.timestampMs.toString()
            activeSignals.forEach { signal ->
                row += logSignalValue(entry, signal.key, csv = true)
            }
            writer.appendLine(row.joinToString(","))
        }
    }

    private fun writeSnapshotAsMsl(
        writer: java.io.Writer,
        snapshot: LiveLogSnapshot,
        fields: List<OutputField>,
        activeSignals: List<LogExportSignal>
    ) {
        val captureDateFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US)
        writer.appendLine("\"speeduino_manager_live_log: SpeeduinoManager Desktop LiveData Export\"")
        writer.appendLine("\"Capture Date: ${captureDateFormat.format(Date())}, File author: SpeeduinoManager\"")

        val selectedRawFieldNames = activeSignals.map { it.header }.toSet()
        val selectedFields = fields.filter { field -> selectedRawFieldNames.contains(field.name) }
        if (selectedFields.isNotEmpty()) {
            writeRawSnapshotAsMsl(writer, snapshot, selectedFields)
            return
        }

        writer.appendLine(buildString {
            append("Time")
            activeSignals.forEach { append('\t').append(it.header) }
        })
        writer.appendLine(buildString {
            append("s")
            activeSignals.forEach { append('\t').append(mslUnitForSignal(it.key)) }
        })

        val firstTimestampMs = snapshot.entries.firstOrNull()?.timestampMs ?: 0L
        snapshot.entries.forEach { entry ->
            val relTimeSeconds = (entry.timestampMs - firstTimestampMs).coerceAtLeast(0L) / 1000.0
            writer.appendLine(buildString {
                append(String.format(Locale.US, "%.3f", relTimeSeconds))
                activeSignals.forEach { signal ->
                    append('\t').append(logSignalValue(entry, signal.key, csv = false))
                }
            })
        }
    }

    private fun writeRawSnapshotAsMsl(
        writer: java.io.Writer,
        snapshot: LiveLogSnapshot,
        selectedFields: List<OutputField>
    ) {
        writer.appendLine(buildString {
            append("Time")
            selectedFields.forEach { field -> append('\t').append(field.name) }
        })
        writer.appendLine(buildString {
            append("s")
            selectedFields.forEach { field -> append('\t').append(formatMslUnit(field.units)) }
        })

        val firstTimestampMs = snapshot.entries.firstOrNull()?.timestampMs ?: 0L
        snapshot.entries.forEach { entry ->
            val relTimeSeconds = (entry.timestampMs - firstTimestampMs).coerceAtLeast(0L) / 1000.0
            writer.appendLine(buildString {
                append(String.format(Locale.US, "%.3f", relTimeSeconds))
                val data = entry.outputChannelData
                selectedFields.forEach { field ->
                    append('\t')
                    if (data != null && entry.outputChannelBlockSize > 0) {
                        append(formatLogValue(field.parse(data)))
                    }
                }
            })
        }
    }

    private fun logSignalValue(entry: LiveLogEntry, key: String, csv: Boolean): String {
        return when (key) {
            "rpm" -> entry.rpm.toString()
            "map" -> entry.mapKpa.toString()
            "tps" -> entry.tps.toString()
            "coolant" -> entry.coolantTempC.toString()
            "iat" -> entry.intakeTempC.toString()
            "battery" -> if (csv) (entry.batteryDeciVolt / 10.0).toString() else String.format(Locale.US, "%.1f", entry.batteryDeciVolt / 10.0)
            "advance" -> entry.advanceDeg.toString()
            "afr" -> entry.o2.toString()
            "afr_target" -> ""
            "candidate_speed" -> entry.candidateSpeedKph?.toString() ?: ""
            "candidate_pedal" -> entry.candidateAccelPedalPosPct?.toString() ?: ""
            "candidate_gear" -> entry.candidateGear?.let { if (!csv && it == 0) "N" else it.toString() } ?: ""
            "candidate_throttle_angle" -> entry.candidateThrottleAngleDeg?.let(::formatLogValue) ?: ""
            "candidate_ignition_advance" -> entry.candidateIgnitionAdvanceDeg?.let(::formatLogValue) ?: ""
            "candidate_injection_ms" -> entry.candidateInjectionDurationMs?.let(::formatLogValue) ?: ""
            "candidate_injection_mirror_ms" -> entry.candidateInjectionDurationMirrorMs?.let(::formatLogValue) ?: ""
            else -> ""
        }
    }

    private fun mslUnitForSignal(key: String): String {
        return when (key) {
            "rpm" -> "rpm"
            "map" -> "kPa"
            "tps", "candidate_pedal" -> "%"
            "coolant", "iat" -> "C"
            "battery" -> "V"
            "advance", "candidate_throttle_angle", "candidate_ignition_advance" -> "deg"
            "afr", "afr_target" -> "AFR"
            "candidate_speed" -> "km/h"
            "candidate_gear" -> "gear"
            "candidate_injection_ms", "candidate_injection_mirror_ms" -> "ms"
            else -> ""
        }
    }

    private fun formatMslUnit(rawUnit: String): String {
        if (rawUnit.isBlank()) return ""
        return when (rawUnit) {
            "°C" -> "C"
            "uS" -> "us"
            else -> rawUnit
        }
    }

    private fun formatLogValue(value: Double): String {
        val rounded = value.roundToInt().toDouble()
        return if (abs(value - rounded) < 0.0001) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }

    private fun uniqueFile(baseFile: File): File {
        if (!baseFile.exists()) return baseFile
        val name = baseFile.nameWithoutExtension
        val ext = baseFile.extension
        var index = 1
        while (true) {
            val candidateName = if (ext.isBlank()) "${name}_$index" else "${name}_$index.$ext"
            val candidate = File(baseFile.parentFile, candidateName)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    fun selectAnalyzerLogFile(path: String?) {
        _analyzerLogFile.value = path
        _analyzerResult.value = null
        _analyzerError.value = null
    }

    private fun parseLogSnapshotCsv(file: File): LiveLogSnapshot {
        require(file.exists()) { "CSV file not found." }
        val lines = file.readLines().filter { it.isNotBlank() }
        require(lines.size >= 2) { "CSV has no samples." }

        val header = lines.first().split(',').map { normalizeCsvHeader(it) }
        val timestampIndex = header.indexOfFirst {
            it in listOf("timestamp_ms", "timestamp", "time_ms", "time")
        }
        require(timestampIndex >= 0) { "Missing timestamp_ms column." }

        fun headerIndex(vararg aliases: String): Int {
            val normalizedAliases = aliases.map(::normalizeCsvHeader)
            return header.indexOfFirst { value -> value in normalizedAliases }
        }

        val rpmIndex = headerIndex("rpm")
        val mapIndex = headerIndex("map", "map_kpa", "mapkpa")
        val tpsIndex = headerIndex("tps")
        val coolantIndex = headerIndex("coolant", "coolant_c", "clt", "clt_c")
        val iatIndex = headerIndex("iat", "iat_c", "intake", "intake_c")
        val batteryIndex = headerIndex("battery", "battery_v", "batt", "batt_v")
        val advanceIndex = headerIndex("advance", "advance_deg", "ignition_advance")
        val o2Index = headerIndex("o2", "afr", "lambda")
        val candidateSpeedIndex = headerIndex("candidate_speed", "candidate_speed_kph", "candidate_speed_kmh")
        val candidatePedalIndex = headerIndex("candidate_pedal", "candidate_pedal_pct")
        val candidateGearIndex = headerIndex("candidate_gear")
        val candidateThrottleAngleIndex = headerIndex("candidate_throttle_angle", "candidate_throttle_angle_deg")
        val candidateIgnitionAdvanceIndex = headerIndex("candidate_ignition_advance", "candidate_ignition_advance_deg")
        val candidateInjectionMsIndex = headerIndex("candidate_injection_ms", "candidate_inj_time_ms")
        val candidateInjectionMirrorMsIndex = headerIndex("candidate_injection_mirror_ms", "candidate_inj_time_mirror_ms")
        val gpsSpeedIndex = headerIndex("gps_speed_kph", "gps_speed_kmh")

        val entries = lines.drop(1).mapNotNull { line ->
            val cells = line.split(',')
            val timestampMs = cells.valueAt(timestampIndex)?.toLongOrNull() ?: return@mapNotNull null
            LiveLogEntry(
                timestampMs = timestampMs,
                rpm = cells.parseInt(rpmIndex),
                mapKpa = cells.parseInt(mapIndex),
                tps = cells.parseInt(tpsIndex),
                coolantTempC = cells.parseInt(coolantIndex),
                intakeTempC = cells.parseInt(iatIndex),
                batteryDeciVolt = cells.parseBatteryDeciVolt(batteryIndex),
                advanceDeg = cells.parseInt(advanceIndex),
                o2 = cells.parseRoundedInt(o2Index),
                candidateSpeedKph = cells.parseOptionalInt(candidateSpeedIndex),
                candidateAccelPedalPosPct = cells.parseOptionalInt(candidatePedalIndex),
                candidateGear = cells.parseOptionalInt(candidateGearIndex),
                candidateThrottleAngleDeg = cells.parseOptionalDouble(candidateThrottleAngleIndex),
                candidateIgnitionAdvanceDeg = cells.parseOptionalDouble(candidateIgnitionAdvanceIndex),
                candidateInjectionDurationMs = cells.parseOptionalDouble(candidateInjectionMsIndex),
                candidateInjectionDurationMirrorMs = cells.parseOptionalDouble(candidateInjectionMirrorMsIndex),
                gpsSpeedKph = cells.parseOptionalDouble(gpsSpeedIndex),
                gpsLatitude = null,
                gpsLongitude = null,
                outputChannelBlockSize = 0,
                outputChannelData = null
            )
        }
        require(entries.isNotEmpty()) { "CSV has no valid samples." }

        val averageInterval = entries.zipWithNext()
            .map { (prev, next) -> next.timestampMs - prev.timestampMs }
            .filter { it > 0L }
            .average()
            .takeIf { !it.isNaN() && it > 0.0 }
            ?.roundToInt()
            ?.toLong()
            ?: 100L

        return LiveLogSnapshot(
            id = "csv-${UUID.randomUUID()}",
            startedAtMs = entries.first().timestampMs,
            stoppedAtMs = entries.last().timestampMs,
            sampleIntervalMs = averageInterval,
            entries = entries
        )
    }

    private fun normalizeCsvHeader(value: String): String {
        return value.trim().lowercase()
    }

    private fun List<String>.valueAt(index: Int): String? {
        if (index < 0 || index >= size) return null
        return this[index].trim().takeIf { it.isNotEmpty() }
    }

    private fun List<String>.parseInt(index: Int): Int {
        return parseOptionalDouble(index)?.roundToInt() ?: 0
    }

    private fun List<String>.parseRoundedInt(index: Int): Int {
        return parseOptionalDouble(index)?.roundToInt() ?: 0
    }

    private fun List<String>.parseOptionalInt(index: Int): Int? {
        return parseOptionalDouble(index)?.roundToInt()
    }

    private fun List<String>.parseOptionalDouble(index: Int): Double? {
        return valueAt(index)?.replace(',', '.')?.toDoubleOrNull()
    }

    private fun List<String>.parseBatteryDeciVolt(index: Int): Int {
        return parseOptionalDouble(index)?.times(10.0)?.roundToInt() ?: 0
    }

    fun setBeforeAfterBeforeLogPath(path: String?) {
        _beforeAfterBeforeLogPath.value = path
        _beforeAfterResult.value = null
        _beforeAfterError.value = null
    }

    fun setBeforeAfterAfterLogPath(path: String?) {
        _beforeAfterAfterLogPath.value = path
        _beforeAfterResult.value = null
        _beforeAfterError.value = null
    }

    fun compareBeforeAfterLogs() {
        scope.launch(Dispatchers.IO) {
            val beforePath = _beforeAfterBeforeLogPath.value
            val afterPath = _beforeAfterAfterLogPath.value
            val ve = _veTable.value
            if (beforePath.isNullOrBlank() || afterPath.isNullOrBlank() || ve == null) {
                _beforeAfterError.value = "Load VE and select before/after logs first."
                return@launch
            }
            _beforeAfterBusy.value = true
            _beforeAfterError.value = null
            try {
                _beforeAfterResult.value = beforeAfterComparator.compareLogs(
                    beforePath = beforePath,
                    afterPath = afterPath,
                    rpmBins = ve.rpmBins,
                    loadBins = ve.loadBins,
                    preferredLoadType = ve.loadType
                )
            } catch (e: Exception) {
                _beforeAfterResult.value = null
                _beforeAfterError.value = when ((e as? LogCompareException)?.reason) {
                    LogCompareReason.EMPTY_LOG -> "Empty CSV."
                    LogCompareReason.MISSING_RPM -> "Missing RPM column."
                    LogCompareReason.MISSING_AFR_MEASURED -> "Missing AFR measured column."
                    LogCompareReason.MISSING_AFR_TARGET -> "Missing AFR target column."
                    LogCompareReason.MISSING_LOAD_CHANNEL -> "Missing MAP/TPS load column."
                    LogCompareReason.NO_VALID_SAMPLES -> "No valid samples in CSV."
                    null -> e.message ?: "Failed to compare logs."
                }
            } finally {
                _beforeAfterBusy.value = false
            }
        }
    }

    fun analyzeLogFile(strategy: TuningStrategy) {
        scope.launch(Dispatchers.IO) {
            val logPath = _analyzerLogFile.value
            val ve = _veTable.value
            val afr = _afrTable.value
            if (logPath.isNullOrBlank() || ve == null || afr == null) {
                _analyzerError.value = "Load VE, AFR and a log file first."
                return@launch
            }

            _analyzerBusy.value = true
            _analyzerError.value = null
            try {
                val result = File(logPath).useLines { lines ->
                    TuningAssistantAnalyzer.analyzeLines(
                        logName = File(logPath).name,
                        lines = lines,
                        veTable = ve,
                        afrTable = afr,
                        strategy = strategy
                    )
                }
                _analyzerResult.value = result
            } catch (e: Exception) {
                _analyzerError.value = e.message
                _analyzerResult.value = null
            } finally {
                _analyzerBusy.value = false
            }
        }
    }

    fun applyAnalyzerToVe(strategy: TuningStrategy, includedClusterIds: Set<String>) {
        scope.launch(Dispatchers.IO) {
            val ve = _veTable.value ?: return@launch
            val result = _analyzerResult.value ?: return@launch
            val logPath = _analyzerLogFile.value
            val afr = _afrTable.value
            _analyzerBusy.value = true
            _analyzerError.value = null
            try {
                _analyzerUndoVeTable.value = ve
                val allClusterIds = result.clusters.map { it.id }.toSet()
                val updated = TuningAssistantAnalyzer.applyClustersToVe(
                    veTable = ve,
                    suggestions = result.cellSuggestions,
                    includedClusterIds = if (includedClusterIds.isEmpty()) allClusterIds else includedClusterIds,
                    clusters = result.clusters
                )
                withPausedLiveDataStream { it.writeVeTable(updated) }
                updateLocalVeTable(updated)
                _veTable.value = updated
                if (!logPath.isNullOrBlank() && afr != null) {
                    _analyzerResult.value = File(logPath).useLines { lines ->
                        TuningAssistantAnalyzer.analyzeLines(
                            logName = File(logPath).name,
                            lines = lines,
                            veTable = updated,
                            afrTable = afr,
                            strategy = strategy
                        )
                    }
                }
            } catch (e: Exception) {
                _analyzerError.value = e.message
            } finally {
                _analyzerBusy.value = false
            }
        }
    }

    fun undoLastAnalyzerApply() {
        scope.launch(Dispatchers.IO) {
            val previous = _analyzerUndoVeTable.value ?: return@launch
            try {
                withPausedLiveDataStream { it.writeVeTable(previous) }
                updateLocalVeTable(previous)
                _veTable.value = previous
                _analyzerUndoVeTable.value = null
            } catch (e: Exception) {
                _analyzerError.value = e.message
            }
        }
    }

    fun reloadAnalyzerVeTable() {
        loadVeTable(1)
    }

    fun applyGeneratedBaseMap(map: GeneratedBaseMap, writeTables: Boolean = true, writeConstants: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            try {
                withPausedLiveDataStream { activeClient ->
                    if (writeTables) {
                        activeClient.writeVeTable(map.veTable)
                        activeClient.writeIgnitionTable(map.ignitionTable)
                        activeClient.writeAfrTable(map.afrTable)
                    }
                    if (writeConstants) {
                        activeClient.writeEngineConstants(map.engineConstants)
                    }
                }
                if (writeTables) {
                    updateLocalVeTable(map.veTable)
                    updateLocalIgnitionTable(map.ignitionTable)
                    updateLocalAfrTable(map.afrTable)
                    _veTable.value = map.veTable
                    _ignitionTable.value = map.ignitionTable
                    _afrTable.value = map.afrTable
                }
                if (writeConstants) {
                    updateLocalEngineConstants(map.engineConstants)
                    _engineConstants.value = map.engineConstants
                }
            } catch (e: Exception) {
                _lastError.value = e.message
                refreshDiagnosticSummary(lastError = e.message)
            }
        }
    }

    private fun applyConfiguredIniDefinition(activeClient: EcuTransport) {
        val signature = activeClient.getFirmwareInfoCached()?.signature?.trim().orEmpty()
        if (signature.isBlank()) return

        val settings = _desktopSettings.value
        if (settings.iniSelectionMode == IniSelectionMode.AUTOMATIC) {
            val cachedDefinitionId = DesktopSettingsStore.loadCachedRemoteIniId(signature)
            if (!cachedDefinitionId.isNullOrBlank() && definitionRepository.hasCachedDefinitionById(cachedDefinitionId)) {
                if (isAlreadyActiveDefinition(lastIniDefinitionSignature, _activeIniCatalogEntry.value?.id, signature, cachedDefinitionId)) {
                    return
                }
                val cachedDefinition = definitionRepository.loadCachedDefinitionById(cachedDefinitionId)
                _activeIniCatalogEntry.value = _availableIniDefinitions.value.firstOrNull { it.id == cachedDefinitionId }
                _activeIniDefinition.value = cachedDefinition
                if (!activeClient.applyIniDefinition(cachedDefinition)) {
                    _lastError.value = "Falha ao aplicar definicao .ini em cache $cachedDefinitionId"
                } else {
                    lastIniDefinitionSignature = signature
                }
                return
            }
        }

        val definition = when {
            settings.iniSelectionMode == IniSelectionMode.MANUAL &&
                settings.iniSelectionSource == IniSelectionSource.IMPORTED &&
                !settings.iniDefinitionId.isNullOrBlank() -> {
                _activeIniCatalogEntry.value = null
                definitionRepository.loadImportedDefinition(settings.iniDefinitionId)
            }

            else -> {
                val entry = resolveCatalogEntryForSignature(signature, settings) ?: return
                if (isAlreadyActiveDefinition(lastIniDefinitionSignature, _activeIniCatalogEntry.value?.id, signature, entry.id)) {
                    return
                }
                _activeIniCatalogEntry.value = entry
                val wasCached = definitionRepository.isDefinitionCached(entry)
                val loaded = runCatching {
                    definitionRepository.loadDefinition(entry)
                }.getOrElse { error ->
                    val category = IniCatalogErrorClassifier.classify(error)
                    _lastError.value = describeIniCatalogError(category, error)
                    throw error
                }
                DesktopSettingsStore.persistCachedRemoteIniId(signature, entry.id)
                if (!wasCached) {
                    _availableIniDefinitions.value = runCatching {
                        definitionRepository.loadCatalog(forceRefresh = false)
                    }.getOrDefault(_availableIniDefinitions.value)
                }
                loaded
            }
        }

        _activeIniDefinition.value = definition
        if (!activeClient.applyIniDefinition(definition)) {
            _lastError.value = "Falha ao aplicar definicao .ini ${definition.sourceName}"
        } else {
            lastIniDefinitionSignature = signature
        }
    }

    private fun describeIniCatalogError(category: IniCatalogErrorCategory, error: Throwable): String {
        val reason = when (category) {
            IniCatalogErrorCategory.TIMEOUT -> "tempo esgotado ao contatar o servidor"
            IniCatalogErrorCategory.DNS -> "nao foi possivel resolver o servidor de definicoes"
            IniCatalogErrorCategory.CONNECTION_FAILED -> "falha de conexao com o servidor de definicoes"
            IniCatalogErrorCategory.HASH_INVALID -> "arquivo .ini baixado com hash invalido"
            IniCatalogErrorCategory.NOT_FOUND -> "definicao .ini nao encontrada no servidor"
            IniCatalogErrorCategory.UNKNOWN -> "erro desconhecido"
        }
        return "Falha ao baixar definicao .ini: $reason (${error.message ?: "sem detalhes"})"
    }

    private fun resolveCatalogEntryForSignature(
        signature: String,
        settings: DesktopSettingsState,
    ): IniCatalogEntry? {
        if (settings.iniSelectionMode == IniSelectionMode.AUTOMATIC) {
            val cachedDefinitionId = DesktopSettingsStore.loadCachedRemoteIniId(signature)
            if (!cachedDefinitionId.isNullOrBlank() && definitionRepository.hasCachedDefinitionById(cachedDefinitionId)) {
                val cachedCatalogEntry = _availableIniDefinitions.value.firstOrNull { it.id == cachedDefinitionId }
                if (cachedCatalogEntry != null) {
                    return cachedCatalogEntry
                }
            }
        }

        val preferred = if (
            settings.iniSelectionMode == IniSelectionMode.MANUAL &&
            settings.iniSelectionSource == IniSelectionSource.CATALOG &&
            !settings.iniDefinitionId.isNullOrBlank()
        ) {
            definitionRepository.loadCatalog(forceRefresh = false).firstOrNull { it.id == settings.iniDefinitionId }
        } else {
            definitionRepository.findMatchingEntry(signature, forceCatalogRefresh = false)
        }
        if (preferred != null) return preferred

        return if (
            settings.iniSelectionMode == IniSelectionMode.MANUAL &&
            settings.iniSelectionSource == IniSelectionSource.CATALOG &&
            !settings.iniDefinitionId.isNullOrBlank()
        ) {
            definitionRepository.loadCatalog(forceRefresh = true).firstOrNull { it.id == settings.iniDefinitionId }
        } else {
            definitionRepository.findMatchingEntry(signature, forceCatalogRefresh = true)
        }
    }

    private fun loadIdleControlSettingsFromSession(sessionDir: File?): IdleControlSettings? {
        if (sessionDir == null) return null
        val page4File = File(sessionDir, "page_${IdleControlSettings.PAGE_NUMBER}.bin")
        val page7File = File(sessionDir, "page_${IdleControlSettings.TARGET_PAGE_NUMBER}.bin")
        if (!page4File.exists() || !page7File.exists()) return null

        val page4 = page4File.readBytes()
        val page7 = page7File.readBytes()
        if (page4.size < IdleControlSettings.PAGE_LENGTH || page7.size < IdleControlSettings.TARGET_PAGE_LENGTH) {
            return null
        }

        return IdleControlSettings.fromPage4(page4).copy(
            idleTargetRpm = IdleControlSettings.readTargetRpmFromPage7(page7)
        )
    }

    private fun loadClosedLoopCorrectionsFromSession(sessionDir: File?): ClosedLoopCorrectionConfig? {
        if (sessionDir == null) return null
        val pageFile = File(sessionDir, "page_${ClosedLoopCorrectionMapper.PAGE_NUMBER}.bin")
        if (!pageFile.exists()) return null

        val pageData = pageFile.readBytes()
        if (pageData.size < ClosedLoopCorrectionMapper.PAGE_SIZE) return null

        val era = _firmwareInfo.value?.era ?: FirmwareEra.MODERN_2025
        return runCatching {
            ClosedLoopCorrectionMapper.fromPage(pageData, era)
        }.getOrNull()
    }
}

private fun SessionSyncPrompt.toDesktop(): SyncPrompt {
    return SyncPrompt(
        localSessionDir = localSessionDir,
        ecuSessionDir = ecuSessionDir,
    )
}
