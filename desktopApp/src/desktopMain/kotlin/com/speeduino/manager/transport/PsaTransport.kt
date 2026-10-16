package com.speeduino.manager.transport

import io.ecucore.transport.PsaSessionStore
import io.ecucore.transport.PsaPersistedSession
import io.ecucore.transport.Obd2ProfileStore
import io.ecucore.transport.EcuTransport
import android.util.Log
import io.ecucore.EcuLiveData
import io.ecucore.connection.IEcuConnection
import io.ecucore.definition.IniDefinition
import io.ecucore.ecu.FirmwareInfo
import io.ecucore.model.AfrTable
import io.ecucore.model.EcuCapabilities
import io.ecucore.model.EcuFamily
import io.ecucore.model.EcuPageDescriptor
import io.ecucore.model.EngineConstants
import io.ecucore.model.FirmwareEra
import io.ecucore.model.EngineProtectionConfig
import io.ecucore.model.IgnitionTable
import io.ecucore.model.PinLayoutInfo
import io.ecucore.model.PressureCalibration
import io.ecucore.model.RusefiInputOutputSnapshot
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.TableDefinitions
import io.ecucore.model.TpsCalibration
import io.ecucore.model.TriggerSettings
import io.ecucore.model.VeTable
import io.ecucore.protocol.SerialCapability
import com.speeduino.manager.telemetry.ConnectionDiagnosticsLogger
import com.speeduino.manager.telemetry.DiagnosticsFlags
import com.speeduino.manager.telemetry.Obd2InvestigationRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Camada preparatória para PSA (Peugeot/Citroen/DS/Opel) proprietário.
 *
 * Fase atual:
 * - scanner CAN por pares TX/RX (seeded do ECU_LIST.md do arduino-psa-diag)
 * - probe de sessão 10xx/22/2180
 * - stream SAE via Obd2Transport + tentativa híbrida OEM (rpm/coolant) com fallback automático
 */
class PsaTransport(
    private val connection: IEcuConnection,
    private val onDataReceived: (EcuLiveData) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val obd2ProfileStore: Obd2ProfileStore? = null,
    private val sessionStore: PsaSessionStore? = null,
    private val investigationRecorder: Obd2InvestigationRecorder? = null,
    private val enableInvestigationCampaign: Boolean = false,
) : EcuTransport {
    companion object {
        private const val TAG = "PsaTransport"
        private const val DEFAULT_LIVE_POLL_INTERVAL_MS = 150L
        private const val C4_CRITICAL_INTERVAL_CYCLES = 1L
        private const val C4_SECONDARY_INTERVAL_CYCLES = 4L
        private const val C4_SPEED_CANDIDATE_INTERVAL_CYCLES = 6L
        private const val C4_FULL_SWEEP_INTERVAL_CYCLES = 14L
        private const val C4_CRITICAL_TIMEOUT_MS = 170L
        private const val C4_SECONDARY_TIMEOUT_MS = 220L
        private const val C4_SPEED_CANDIDATE_TIMEOUT_MS = 220L
        private const val C4_FULL_TIMEOUT_MS = 260L
        private const val C4_STALE_FRAME_THRESHOLD_MS = 4_500L
        private const val C4_SCHEDULER_LOG_INTERVAL = 25L
        private const val O2_POLL_INTERVAL_CYCLES = 6L
        private const val O2_DISABLE_AFTER_NEGATIVE_RESPONSES = 3
        private const val O2_RETRY_COOLDOWN_MS = 60_000L
        private const val DEFAULT_BATTERY_VOLTAGE = 12.0
        private const val CONFIDENCE_MIN = 3
        private const val SCAN_BUDGET_MS = 40_000L
        private const val MAX_NO_DATA_STREAK = 3
        private const val SCAN_PROBE_TIMEOUT_MS = 650L
        private const val C4_CMD_080 = "21C080013"
        private const val C4_CMD_180 = "21C180012"
        private const val C4_CMD_380 = "21C380014"
        private const val C4_CMD_480 = "21C480014"
        private const val C4_CMD_580 = "21C580012"
        private const val C4_CMD_980 = "21C980011"
        private val C4_LIVE_COMMANDS = listOf(
            C4_CMD_080,
            C4_CMD_180,
            C4_CMD_380,
            C4_CMD_480,
            C4_CMD_580,
            C4_CMD_980,
        )

        private data class OemCommandProfile(
            val id: String,
            val keywords: Set<String>,
            val rpmCommands: List<String>,
            val coolantCommands: List<String>,
        )

        private val OEM_PROFILES = listOf(
            OemCommandProfile(
                id = "bosch_me7",
                keywords = setOf("BOSCH", "ME7", "ME 7", "ME7.4"),
                rpmCommands = listOf("220C", "22F40C", "210C"),
                coolantCommands = listOf("2205", "22F40D", "210D")
            ),
            OemCommandProfile(
                id = "sirius_32",
                keywords = setOf("SIRIUS", "S32", "K4M"),
                rpmCommands = listOf("22F40C", "210C", "22F45C"),
                coolantCommands = listOf("22F40D", "210D", "22F45D")
            ),
            OemCommandProfile(
                id = "generic",
                keywords = emptySet(),
                rpmCommands = listOf("22F40C", "210C"),
                coolantCommands = listOf("22F40D", "210D")
            )
        )
        private val INVESTIGATION_STANDARD_COMMANDS = listOf(
            "0103",
            "0106",
            "0107",
            "0114",
            "0134",
            "0144",
        )
        private val INVESTIGATION_PSA_COMMANDS = listOf(
            "2180",
            "21FE",
            "17FF00",
            C4_CMD_980,
            C4_CMD_380,
            C4_CMD_080,
        )
        private val INVESTIGATION_PSA_CANDIDATE_COMMANDS = (0xC0..0xCF).map { prefix ->
            "21%02X8001".format(Locale.US, prefix)
        }
        private val INVESTIGATION_PROBE_CONTEXT_COMMANDS = listOf(
            "2180",
            "21FE",
            "17FF00",
            "ATDP",
            C4_CMD_980,
            C4_CMD_380,
            C4_CMD_080,
        )
        private val INVESTIGATION_FAP_HANDSHAKE = listOf(
            "ATWS",
            "ATE0",
            "ATL0",
            "ATH0",
            "ATS0",
            "ATAL",
            "ATV0",
            "ATSP6",
            "ATSH6A8",
            "ATCRA688",
            "ATFCSH6A8",
            "ATFCSD300000",
            "ATFCSM1",
            "1003",
            "ATDP",
        )
        private val INVESTIGATION_TESTER_PRESENT_COMMANDS = listOf(
            "3E",
            "3E00",
            "3E01",
        )
    }

    private data class PsaCandidate(
        val txId: String,
        val rxIds: List<String>,
        val label: String
    )

    private data class PsaDetection(
        val protocol: String,
        val txId: String,
        val rxId: String,
        val hints: Set<String>
    )

    private data class OemOverlay(
        val rpm: Int? = null,
        val coolant: Int? = null,
        val rpmConfidence: Int = 0,
        val coolantConfidence: Int = 0,
    )

    private data class PsaLiveSnapshot(
        val rpm: Int? = null,
        val coolant: Int? = null,
        val intake: Int? = null,
        val map: Int? = null,
        val tps: Int? = null,
        val o2AfrX10: Int? = null,
        val candidateSpeedKph: Int? = null,
        val candidateAccelPedalPosPct: Int? = null,
        val candidateGear: Int? = null,
        val candidateThrottleAngleDeg: Double? = null,
        val candidateIgnitionAdvanceDeg: Double? = null,
        val candidateInjectionDurationMs: Double? = null,
        val candidateInjectionDurationMirrorMs: Double? = null,
        val batteryVoltage: Double? = null,
        val frameCount: Int = 0,
    )

    private data class LiveAddressCandidate(
        val txId: String,
        val rxId: String?,
        val label: String,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val directIoMutex = Mutex()
    private var oemPollingJob: Job? = null
    @Volatile
    private var streaming = false
    @Volatile
    private var connectedAtMs = 0L
    @Volatile
    private var lastSaeSample: EcuLiveData? = null
    @Volatile
    private var overlay: OemOverlay = OemOverlay()
    @Volatile
    private var oemPollCycle = 0L
    @Volatile
    private var currentLiveData = defaultLiveData()
    @Volatile
    private var activeLiveAddress: LiveAddressCandidate? = null
    @Volatile
    private var noDataCycleStreak: Int = 0
    @Volatile
    private var directPsaMode: Boolean = false
    @Volatile
    private var currentFunctionalHeader: String? = null
    @Volatile
    private var c4LiveModeEnabled: Boolean = false
    @Volatile
    private var lastLivePayloadFingerprint: String = ""
    @Volatile
    private var lastLivePayloadAtMs: Long = 0L
    @Volatile
    private var softResyncStreak: Int = 0
    @Volatile
    private var schedulerCycleCount: Long = 0L
    @Volatile
    private var schedulerWindowStartedAtMs: Long = 0L
    @Volatile
    private var schedulerWindowBusyMs: Long = 0L
    @Volatile
    private var schedulerTimeouts: Int = 0
    @Volatile
    private var schedulerResponses: Int = 0
    @Volatile
    private var o2ConsecutiveUnsupportedResponses: Int = 0
    @Volatile
    private var o2RetryAfterMs: Long = 0L
    @Volatile
    private var investigationInProgress: Boolean = false
    @Volatile
    private var preserveProbeContextForPolling: Boolean = false
    @Volatile
    private var liveSessionConfigured: Boolean = false
    @Volatile
    private var legacyPollCyclesWithoutCandidates: Int = 0

    private val obd2Delegate = Obd2Transport(
        connection = connection,
        onDataReceived = { sample -> onDelegateData(sample) },
        onConnectionStateChanged = onConnectionStateChanged,
        onError = onError,
        profileStore = obd2ProfileStore,
        allowNonStandardPreflight = true,
        enableFeatureOptimization = false,
        investigationRecorder = investigationRecorder,
    )

    private val canCandidates = listOf(
        PsaCandidate("744", listOf("664", "764", "644"), "injection_alt"),
        PsaCandidate("742", listOf("652", "762", "642"), "injection_alt_2"),
        PsaCandidate("752", listOf("652", "772"), "bsi_bsm"),
        PsaCandidate("7E0", listOf("7E8"), "sae_engine"),
        PsaCandidate("73F", listOf("761"), "engine_81"),
        PsaCandidate("743", listOf("763"), "engine_51"),
        PsaCandidate("745", listOf("765"), "display_26"),
        PsaCandidate("764", listOf("664"), "telematic"),
        PsaCandidate("6A8", listOf("688"), "engine_inj"),
        PsaCandidate("760", listOf("660"), "autoradio"),
        PsaCandidate("765", listOf("665"), "display"),
        PsaCandidate("75F", listOf("65F"), "cluster"),
        PsaCandidate("76D", listOf("66D"), "hvac"),
        PsaCandidate("6A9", listOf("689"), "gearbox"),
        PsaCandidate("6AD", listOf("68D"), "abs_esp"),
        PsaCandidate("6AF", listOf("68F"), "tpms"),
        PsaCandidate("77D", listOf("67D"), "ampli"),
        PsaCandidate("77C", listOf("67C"), "mds"),
        PsaCandidate("771", listOf("671"), "cd_changer"),
        PsaCandidate("773", listOf("673"), "nomad"),
        PsaCandidate("779", listOf("679"), "wiper"),
        PsaCandidate("74A", listOf("64A"), "rain_light"),
        PsaCandidate("74B", listOf("64B"), "door"),
        PsaCandidate("6B6", listOf("696"), "radar_lidar"),
        PsaCandidate("6B7", listOf("697"), "afs"),
        PsaCandidate("6B8", listOf("698"), "suspension"),
        PsaCandidate("6C1", listOf("601"), "additive"),
        PsaCandidate("6C4", listOf("604"), "becb")
    )

    private var detected: PsaDetection? = null
    private var cachedFirmwareInfo: FirmwareInfo? = null
    private var oemProfile = OEM_PROFILES.last()
    private var restoredPersistedSession = false

    override suspend fun connect() {
        try {
            if (!connection.isConnected()) {
                connection.connect()
            }
            restoredPersistedSession = false
            liveSessionConfigured = false
            if (tryRestorePersistedSession()) {
                ConnectionDiagnosticsLogger.log("psa", "probe", "restored persisted session")
            }
            val detection = detected ?: scanPsaCan()
            if (detection == null || detection.hints.isEmpty()) {
                runCatching { connection.disconnect() }
                throw IllegalStateException("ECU PSA proprietária não detectada")
            }

            detected = detection
            // Happy-path PSA: avoid full OBD preflight reset after a successful OEM probe.
            // FAP keeps the proprietary session alive and starts live afterwards.
            directPsaMode = true
            investigationRecorder?.startSession(
                transport = "psa",
                metadataExtras = mapOf(
                    "connection" to connection.getConnectionInfo().trim().ifBlank { "unknown" },
                    "tx_id" to detection.txId,
                    "rx_id" to detection.rxId,
                    "protocol" to detection.protocol,
                )
            )
            connectedAtMs = System.currentTimeMillis()
            oemProfile = detectOemProfile()
            val signature = "PSA ${detection.protocol.uppercase(Locale.US)} ELM327 TX=${detection.txId} RX=${detection.rxId}"
            cachedFirmwareInfo = FirmwareInfo(
                signature = signature,
                productString = "ELM327",
                era = FirmwareEra.MODERN_2025,
                family = EcuFamily.UNKNOWN,
                capabilities = EcuCapabilities(
                    supportsModernProtocol = false,
                    supportsLegacyProtocol = true,
                    supportsPageRead = false,
                    supportsPageWrite = false,
                    supportsBurn = false,
                    supportsLiveData = true
                ),
            )

            ConnectionDiagnosticsLogger.log(
                "psa",
                "probe",
                "detected protocol=${detection.protocol} tx=${detection.txId} rx=${detection.rxId} hints=${detection.hints.joinToString(",")}" 
            )
            ConnectionDiagnosticsLogger.log("psa", "oem_profile", "selected=${oemProfile.id}")
            investigationRecorder?.updateMetadata("psa_hints", detection.hints.toList())
            investigationRecorder?.updateMetadata("oem_profile", oemProfile.id)
            persistDetectedSession(detection)
            onConnectionStateChanged(true)
            if (enableInvestigationCampaign && !restoredPersistedSession) {
                launchInvestigationCampaign()
                while (investigationInProgress) {
                    delay(80L)
                }
            } else {
                preserveProbeContextForPolling = false
                investigationInProgress = false
            }
        } catch (t: Throwable) {
            detected = null
            directPsaMode = false
            cachedFirmwareInfo = null
            oemProfile = OEM_PROFILES.last()
            runCatching { obd2Delegate.disconnect() }
            throw t
        }
    }

    override fun disconnect() {
        stopOemPolling()
        detected = null
        cachedFirmwareInfo = null
        overlay = OemOverlay()
        lastSaeSample = null
        oemPollCycle = 0L
        connectedAtMs = 0L
        streaming = false
        currentLiveData = defaultLiveData()
        activeLiveAddress = null
        noDataCycleStreak = 0
        directPsaMode = false
        currentFunctionalHeader = null
        c4LiveModeEnabled = false
        lastLivePayloadFingerprint = ""
        lastLivePayloadAtMs = 0L
        softResyncStreak = 0
        schedulerCycleCount = 0L
        schedulerWindowStartedAtMs = 0L
        schedulerWindowBusyMs = 0L
        schedulerTimeouts = 0
        schedulerResponses = 0
        o2ConsecutiveUnsupportedResponses = 0
        o2RetryAfterMs = 0L
        preserveProbeContextForPolling = false
        oemProfile = OEM_PROFILES.last()
        restoredPersistedSession = false
        liveSessionConfigured = false
        legacyPollCyclesWithoutCandidates = 0
        runCatching { obd2Delegate.disconnect() }
        runCatching { connection.disconnect() }
        onConnectionStateChanged(false)
    }

    override fun isConnected(): Boolean = connection.isConnected()
    override fun isStreaming(): Boolean = streaming

    override fun startLiveDataStream(intervalMs: Long) {
        if (streaming) return
        streaming = true
        ConnectionDiagnosticsLogger.log("psa", "oem_live", "start intervalMs=$intervalMs")
        onDataReceived(currentLiveData)
        startOemPolling(intervalMs)
    }

    override fun stopLiveDataStream() {
        streaming = false
        stopOemPolling()
    }

    override suspend fun pauseLiveDataStream(timeoutMs: Long) {
        streaming = false
        stopOemPolling()
    }

    override suspend fun getFirmwareInfo(): String = cachedFirmwareInfo?.signature ?: "PSA ELM327"
    override fun getFirmwareInfoCached(): FirmwareInfo? = cachedFirmwareInfo ?: obd2Delegate.getFirmwareInfoCached()
    override fun getEcuFamily(): EcuFamily = obd2Delegate.getEcuFamily()
    override fun getEcuCapabilities(): EcuCapabilities? = obd2Delegate.getEcuCapabilities()
    override fun getTableDefinitions(): TableDefinitions? = obd2Delegate.getTableDefinitions()
    override fun getEcuPageCatalog(): List<EcuPageDescriptor> = obd2Delegate.getEcuPageCatalog()
    override fun getPinLayoutInfoCached(): PinLayoutInfo? = obd2Delegate.getPinLayoutInfoCached()
    override fun cachePinLayoutInfo(info: PinLayoutInfo?) = obd2Delegate.cachePinLayoutInfo(info)
    override fun isReadOnlySafeMode(): Boolean = obd2Delegate.isReadOnlySafeMode()
    override fun applyIniDefinition(definition: IniDefinition): Boolean = obd2Delegate.applyIniDefinition(definition)
    override fun setManualFirmwareProfile(signature: String, readOnly: Boolean) =
        obd2Delegate.setManualFirmwareProfile(signature, readOnly)
    override suspend fun getSerialCapability(): SerialCapability = obd2Delegate.getSerialCapability()
    override suspend fun readFullPage(pageNum: Int, pageSize: Int, blockSize: Int): ByteArray =
        obd2Delegate.readFullPage(pageNum, pageSize, blockSize)
    override suspend fun readVeTable(mapIndex: Int): VeTable = obd2Delegate.readVeTable(mapIndex)
    override suspend fun readIgnitionTable(mapIndex: Int): IgnitionTable = obd2Delegate.readIgnitionTable(mapIndex)
    override suspend fun readAfrTable(): AfrTable = obd2Delegate.readAfrTable()
    override suspend fun readEngineConstants(): EngineConstants = obd2Delegate.readEngineConstants()
    override suspend fun readTriggerSettings(): TriggerSettings = obd2Delegate.readTriggerSettings()
    override suspend fun readRusefiInputOutputSnapshot(): RusefiInputOutputSnapshot =
        obd2Delegate.readRusefiInputOutputSnapshot()
    override suspend fun readEngineProtectionConfig(): EngineProtectionConfig =
        obd2Delegate.readEngineProtectionConfig()
    override suspend fun readPressureCalibration(): PressureCalibration = obd2Delegate.readPressureCalibration()
    override suspend fun writePressureCalibration(calibration: PressureCalibration, burn: Boolean) =
        obd2Delegate.writePressureCalibration(calibration, burn)
    override suspend fun readTpsCalibration(): TpsCalibration = obd2Delegate.readTpsCalibration()
    override suspend fun writeTpsCalibration(calibration: TpsCalibration, burn: Boolean) =
        obd2Delegate.writeTpsCalibration(calibration, burn)
    override suspend fun readSecondarySerialConfig(): SecondarySerialConfig =
        obd2Delegate.readSecondarySerialConfig()
    override suspend fun writeSecondarySerialConfig(config: SecondarySerialConfig, burn: Boolean) =
        obd2Delegate.writeSecondarySerialConfig(config, burn)
    override suspend fun writeEngineProtectionConfig(config: EngineProtectionConfig, burn: Boolean) =
        obd2Delegate.writeEngineProtectionConfig(config, burn)
    override suspend fun writeTriggerSettings(settings: TriggerSettings, burn: Boolean) =
        obd2Delegate.writeTriggerSettings(settings, burn)
    override suspend fun writeRawPage(pageNum: Int, data: ByteArray) = obd2Delegate.writeRawPage(pageNum, data)
    override suspend fun writeRawPageWithoutBurn(pageNum: Int, data: ByteArray) =
        obd2Delegate.writeRawPageWithoutBurn(pageNum, data)
    override suspend fun burnConfigs() = obd2Delegate.burnConfigs()
    override suspend fun writeVeTable(veTable: VeTable, mapIndex: Int) =
        obd2Delegate.writeVeTable(veTable, mapIndex)
    override suspend fun writeIgnitionTable(ignitionTable: IgnitionTable, mapIndex: Int) =
        obd2Delegate.writeIgnitionTable(ignitionTable, mapIndex)
    override suspend fun writeAfrTable(afrTable: AfrTable) = obd2Delegate.writeAfrTable(afrTable)
    override suspend fun writeEngineConstants(engineConstants: EngineConstants) =
        obd2Delegate.writeEngineConstants(engineConstants)

    private fun onDelegateData(sample: EcuLiveData) {
        lastSaeSample = sample
        onDataReceived(applyOverlay(sample))
    }

    private fun applyOverlay(sample: EcuLiveData): EcuLiveData {
        val current = overlay
        val rpm = if (current.rpmConfidence >= CONFIDENCE_MIN) current.rpm ?: sample.rpm else sample.rpm
        val coolant = if (current.coolantConfidence >= CONFIDENCE_MIN) current.coolant ?: sample.coolantTemp else sample.coolantTemp
        // Keep the generic advance neutral; the PSA-specific candidate is surfaced separately
        // as a decimal field so we do not relay the wrong generic OBD2 value.
        return sample.copy(rpm = rpm, coolantTemp = coolant, advance = 0)
    }

    private fun startOemPolling(intervalMs: Long) {
        if (oemPollingJob?.isActive == true) return
        if (detected == null) return
        val pollIntervalMs = intervalMs.coerceAtLeast(DEFAULT_LIVE_POLL_INTERVAL_MS)

        oemPollingJob = scope.launch {
            schedulerWindowStartedAtMs = System.currentTimeMillis()
            runCatching { ensureLiveAddressConfigured(forceRediscovery = false) }
                .onFailure { err ->
                    ConnectionDiagnosticsLogger.log("psa", "oem_poll", "addr_setup_failed: ${err.message ?: "unknown"}")
                }
            while (isActive && streaming && connection.isConnected()) {
                if (investigationInProgress) {
                    delay(120L)
                    continue
                }
                val cycleStartedAtMs = System.currentTimeMillis()
                runCatching { pollOemOnce() }
                    .onFailure { err ->
                        ConnectionDiagnosticsLogger.log("psa", "oem_poll", "failed: ${err.message ?: "unknown"}")
                    }
                val elapsedMs = (System.currentTimeMillis() - cycleStartedAtMs).coerceAtLeast(0L)
                val sleepMs = (pollIntervalMs - elapsedMs).coerceAtLeast(0L)
                if (sleepMs > 0) delay(sleepMs)
            }
        }
    }

    private fun stopOemPolling() {
        oemPollingJob?.cancel()
        oemPollingJob = null
    }

    private suspend fun pollOemOnce() {
        val detection = detected ?: return
        oemPollCycle++

        if (detection.hints.any { it.startsWith("session_") }) {
            listOf("1003", "10C0").forEach { cmd ->
                val resp = obd2Delegate.sendRawElmCommand(cmd, timeoutMs = 900L).uppercase(Locale.US)
                if (resp.contains("50")) return@forEach
            }
        }

        val preferC4 = c4LiveModeEnabled ||
            detection.hints.contains("id_21c") ||
            detection.hints.contains("id_2180_c4_sig")

        var response = if (preferC4) readC4LiveFrameBundle() else readPsaLiveFrame()
        var snapshot = if (preferC4) parseC4LiveSnapshot(response) else parseLiveSnapshot(response)
        var cmdLabel = if (preferC4) "21C*" else "21FF"
        if (snapshot != null && preferC4 && !c4LiveModeEnabled) {
            c4LiveModeEnabled = true
            legacyPollCyclesWithoutCandidates = 0
            ConnectionDiagnosticsLogger.log("psa", "oem_live", "promoted to c4 live mode from stored/detected hints")
        }
        if (snapshot == null && !preferC4) {
            val c4Response = readC4LiveFrameBundle()
            val c4Snapshot = parseC4LiveSnapshot(c4Response)
            if (c4Snapshot != null || c4Response.uppercase(Locale.US).contains("61FF")) {
                c4LiveModeEnabled = true
                response = c4Response
                snapshot = c4Snapshot
                cmdLabel = "21C*"
                ConnectionDiagnosticsLogger.log("psa", "oem_live", "auto-switched to c4 live mode after 21FF miss")
            }
        }
        if (!preferC4 && snapshot != null) {
            val hasLegacyOnlySample = snapshot.frameCount > 0 &&
                snapshot.candidateSpeedKph == null &&
                snapshot.candidateGear == null &&
                snapshot.candidateThrottleAngleDeg == null &&
                snapshot.candidateIgnitionAdvanceDeg == null &&
                snapshot.candidateInjectionDurationMs == null &&
                snapshot.candidateInjectionDurationMirrorMs == null
            legacyPollCyclesWithoutCandidates = if (hasLegacyOnlySample) {
                legacyPollCyclesWithoutCandidates + 1
            } else {
                0
            }
            if (legacyPollCyclesWithoutCandidates >= 2) {
                val c4Response = readC4LiveFrameBundle()
                val c4Snapshot = parseC4LiveSnapshot(c4Response)
                if (c4Snapshot != null) {
                    c4LiveModeEnabled = true
                    legacyPollCyclesWithoutCandidates = 0
                    response = c4Response
                    snapshot = c4Snapshot
                    cmdLabel = "21C*"
                    ConnectionDiagnosticsLogger.log(
                        "psa",
                        "oem_live",
                        "promoted to c4 live mode after legacy-only samples"
                    )
                }
            }
        } else if (preferC4) {
            legacyPollCyclesWithoutCandidates = 0
        }
        val compactRaw = response.replace(Regex("\\s+"), " ").trim().take(220)
        if (snapshot == null) {
            noDataCycleStreak += 1
            ConnectionDiagnosticsLogger.log(
                "psa",
                "oem_live",
                "cycle=$oemPollCycle cmd=$cmdLabel frames=0 no_data_streak=$noDataCycleStreak addr=${activeLiveAddress?.txId ?: "default"}>${activeLiveAddress?.rxId ?: "*"} raw=${compactRaw.ifBlank { "-" }}"
            )
            if (noDataCycleStreak >= 10) {
                ensureLiveAddressConfigured(forceRediscovery = true)
            }
            maybeLogSchedulerWindow()
            return
        }
        noDataCycleStreak = 0

        val staleDetected = registerPayloadFingerprint(response)
        if (staleDetected && c4LiveModeEnabled) {
            softResyncStreak += 1
            ConnectionDiagnosticsLogger.log(
                "psa",
                "oem_live",
                "stale_detected cycle=$oemPollCycle stale_ms=${System.currentTimeMillis() - lastLivePayloadAtMs} soft_resync_streak=$softResyncStreak"
            )
            runCatching { softResyncLiveSession() }
            if (softResyncStreak >= 3) {
                ensureLiveAddressConfigured(forceRediscovery = true)
                softResyncStreak = 0
            }
        } else if (!staleDetected) {
            softResyncStreak = 0
        }

        val o2AfrX10 = if (!preserveProbeContextForPolling && shouldAttemptO2Read() && oemPollCycle % O2_POLL_INTERVAL_CYCLES == 0L) {
            readStandardO2AfrX10()
        } else {
            null
        }

        val updated = currentLiveData.copy(
            secl = ((System.currentTimeMillis() - connectedAtMs) / 1000L).toInt(),
            rpm = snapshot.rpm ?: currentLiveData.rpm,
            coolantTemp = snapshot.coolant ?: currentLiveData.coolantTemp,
            intakeTemp = snapshot.intake ?: currentLiveData.intakeTemp,
            mapPressure = (snapshot.map ?: currentLiveData.mapPressure).coerceIn(0, 255),
            tps = snapshot.tps ?: currentLiveData.tps,
            advance = snapshot.candidateIgnitionAdvanceDeg?.roundToInt() ?: currentLiveData.advance,
            o2 = o2AfrX10 ?: snapshot.o2AfrX10 ?: currentLiveData.o2,
            batteryVoltage = snapshot.batteryVoltage ?: currentLiveData.batteryVoltage,
            candidateSpeedKph = snapshot.candidateSpeedKph ?: currentLiveData.candidateSpeedKph,
            candidateAccelPedalPosPct = snapshot.candidateAccelPedalPosPct ?: currentLiveData.candidateAccelPedalPosPct,
            candidateGear = snapshot.candidateGear ?: currentLiveData.candidateGear,
            candidateThrottleAngleDeg = snapshot.candidateThrottleAngleDeg ?: currentLiveData.candidateThrottleAngleDeg,
            candidateIgnitionAdvanceDeg = snapshot.candidateIgnitionAdvanceDeg ?: currentLiveData.candidateIgnitionAdvanceDeg,
            candidateInjectionDurationMs = snapshot.candidateInjectionDurationMs ?: currentLiveData.candidateInjectionDurationMs,
            candidateInjectionDurationMirrorMs = snapshot.candidateInjectionDurationMirrorMs ?: currentLiveData.candidateInjectionDurationMirrorMs,
            engineStatus = if ((snapshot.rpm ?: currentLiveData.rpm) > 0) 0x01 else 0x00,
            sparkStatus = if ((snapshot.rpm ?: currentLiveData.rpm) > 0) 0x01 else 0x00,
        )
        currentLiveData = updated
        persistResolvedLiveSession()
        onDataReceived(updated)
        investigationRecorder?.recordSample(
            source = if (c4LiveModeEnabled) "psa_live_c4" else "psa_live",
            sample = updated,
            extras = mapOf(
                "cycle" to oemPollCycle.toString(),
                "cmd" to cmdLabel,
                "frames" to snapshot.frameCount.toString(),
                "active_tx" to (activeLiveAddress?.txId ?: currentFunctionalHeader ?: ""),
                "active_rx" to (activeLiveAddress?.rxId ?: "")
            )
        )
        if (c4LiveModeEnabled) {
            val frameMap = extract61ffCommandPayloads(response)
            frameMap.forEach { (command, payload) ->
                investigationRecorder?.recordProprietaryFrame(
                    source = "psa_live_c4",
                    cycle = oemPollCycle,
                    command = command,
                    rawHex = payload.joinToString("") { "%02X".format(Locale.US, it) },
                    bytes = payload,
                    extras = mapOf(
                        "active_tx" to (activeLiveAddress?.txId ?: currentFunctionalHeader ?: ""),
                        "active_rx" to (activeLiveAddress?.rxId ?: ""),
                        "frame_count" to snapshot.frameCount.toString()
                    )
                )
            }
        }
        ConnectionDiagnosticsLogger.log(
            "psa",
            "oem_live",
            "cycle=$oemPollCycle cmd=$cmdLabel frames=${snapshot.frameCount} addr=${activeLiveAddress?.txId ?: "default"}>${activeLiveAddress?.rxId ?: "*"} rpm=${updated.rpm} tps=${updated.tps} map=${updated.mapPressure} clt=${updated.coolantTemp} iat=${updated.intakeTemp} o2=${updated.o2} batt=${"%.1f".format(Locale.US, updated.batteryVoltage)} raw=${compactRaw.ifBlank { "-" }}"
        )
        maybeLogSchedulerWindow()
    }

    private suspend fun ensureLiveAddressConfigured(forceRediscovery: Boolean) {
        if (!forceRediscovery && liveSessionConfigured && (activeLiveAddress != null || currentFunctionalHeader != null)) return
        if (!forceRediscovery && (activeLiveAddress != null || currentFunctionalHeader != null)) {
            configureLiveSessionForCurrentTarget()
            noDataCycleStreak = 0
            return
        }
        val detection = detected
        if (detection != null && detection.txId == "default" && detection.rxId == "*") {
            if (preserveProbeContextForPolling && c4LiveModeEnabled) {
                noDataCycleStreak = 0
                ConnectionDiagnosticsLogger.log(
                    "psa",
                    "oem_live_addr",
                    "reusing preserved probe-context session for polling"
                )
                return
            }
            // Functional PSA mode: keep CAN11/500 and try rotating physical headers in polling.
            sendRawDirect("ATD", timeoutMs = 700L)
            sendRawDirect("ATE0", timeoutMs = 500L)
            sendRawDirect("ATS0", timeoutMs = 500L)
            sendRawDirect("ATL0", timeoutMs = 500L)
            sendRawDirect("ATH0", timeoutMs = 500L)
            sendRawDirect(liveProtocolAtCommand(), timeoutMs = 500L)
            sendRawDirect("ATAT1", timeoutMs = 500L)
            sendRawDirect("ATST20", timeoutMs = 500L)
            currentFunctionalHeader = null
            activeLiveAddress = null
            liveSessionConfigured = true
            c4LiveModeEnabled = detection.hints.contains("id_21c") || detection.hints.contains("id_2180_c4_sig")
            if (!c4LiveModeEnabled) {
                c4LiveModeEnabled = probeAndSelectC4LiveOnHeaders()
            }
            noDataCycleStreak = 0
            ConnectionDiagnosticsLogger.log(
                "psa",
                "oem_live_addr",
                "functional detection; skip physical scan c4_mode=$c4LiveModeEnabled"
            )
            return
        }
        val candidates = linkedSetOf<LiveAddressCandidate>()

        if (detection != null && detection.txId != "default" && detection.rxId != "*") {
            candidates += LiveAddressCandidate(
                txId = detection.txId,
                rxId = detection.rxId,
                label = "detected"
            )
        }

        candidates += listOf(
            LiveAddressCandidate("7C0", null, "psa_engine_7c"),
            LiveAddressCandidate("7B0", null, "psa_engine_7b"),
            LiveAddressCandidate("744", null, "injection_alt"),
            LiveAddressCandidate("742", null, "injection_alt_2"),
            LiveAddressCandidate("752", null, "bsi_bsm"),
            LiveAddressCandidate("7E0", null, "sae_engine"),
            LiveAddressCandidate("73F", null, "engine_81"),
        )

        // Reset to CAN 11/500 mode before trying physical addressing.
        obd2Delegate.sendRawElmCommand(liveProtocolAtCommand(), timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATH1", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATS0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATAT1", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATSTFF", timeoutMs = 500L)

        for (candidate in candidates) {
            // No live discovery we avoid ATCRA filter because some PSA ECUs answer on varying IDs.
            obd2Delegate.sendRawElmCommand("ATD", timeoutMs = 600L)
            obd2Delegate.sendRawElmCommand("ATE0", timeoutMs = 500L)
            obd2Delegate.sendRawElmCommand("ATS0", timeoutMs = 500L)
            obd2Delegate.sendRawElmCommand("ATL0", timeoutMs = 500L)
            obd2Delegate.sendRawElmCommand("ATH1", timeoutMs = 500L)
            obd2Delegate.sendRawElmCommand(liveProtocolAtCommand(), timeoutMs = 500L)
            obd2Delegate.sendRawElmCommand("ATSH${candidate.txId}", timeoutMs = 500L)
            // Prime proprietary session before asking 21FF.
            obd2Delegate.sendRawElmCommand("2180", timeoutMs = 900L)
            obd2Delegate.sendRawElmCommand("21FE", timeoutMs = 900L)
            val c4Probe = obd2Delegate.sendRawElmCommand(C4_CMD_080, timeoutMs = 1200L).uppercase(Locale.US)
            if (c4Probe.contains("0:") || c4Probe.contains("1:") || c4Probe.contains("61FF")) {
                activeLiveAddress = candidate
                c4LiveModeEnabled = true
                liveSessionConfigured = true
                noDataCycleStreak = 0
                ConnectionDiagnosticsLogger.log(
                    "psa",
                    "oem_live_addr",
                    "selected=${candidate.label} tx=${candidate.txId} rx=* via=21C"
                )
                return
            }
            val probe = obd2Delegate.sendRawElmCommand("21FF", timeoutMs = 1400L).uppercase(Locale.US)
            if (probe.contains("61FF")) {
                activeLiveAddress = candidate
                c4LiveModeEnabled = detection?.hints?.contains("id_21c") == true || detection?.hints?.contains("id_2180_c4_sig") == true
                liveSessionConfigured = true
                noDataCycleStreak = 0
                ConnectionDiagnosticsLogger.log(
                    "psa",
                    "oem_live_addr",
                    "selected=${candidate.label} tx=${candidate.txId} rx=* c4_mode=$c4LiveModeEnabled"
                )
                return
            }
            if (probe.contains("61FE")) {
                activeLiveAddress = candidate
                c4LiveModeEnabled = detection?.hints?.contains("id_21c") == true || detection?.hints?.contains("id_2180_c4_sig") == true
                liveSessionConfigured = true
                noDataCycleStreak = 0
                ConnectionDiagnosticsLogger.log(
                    "psa",
                    "oem_live_addr",
                    "selected=${candidate.label} tx=${candidate.txId} rx=* via=61FE c4_mode=$c4LiveModeEnabled"
                )
                return
            }
        }

        activeLiveAddress = null
        currentFunctionalHeader = null
        liveSessionConfigured = false
        c4LiveModeEnabled = detection?.hints?.contains("id_21c") == true || detection?.hints?.contains("id_2180_c4_sig") == true
        if (!c4LiveModeEnabled) {
            c4LiveModeEnabled = probeAndSelectC4LiveOnHeaders()
        }
        // Keep functional fallback in case ECU only answers to broadcast.
        obd2Delegate.sendRawElmCommand("ATD", timeoutMs = 600L)
        obd2Delegate.sendRawElmCommand("ATE0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATS0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATL0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATH0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATSP0", timeoutMs = 500L)
        ConnectionDiagnosticsLogger.log("psa", "oem_live_addr", "no physical address matched; using functional")
    }

    private suspend fun readPsaLiveFrame(): String {
        val active = activeLiveAddress
        if (active != null) {
            return obd2Delegate.sendRawElmCommand("21FF", timeoutMs = 900L)
        }

        if (preserveProbeContextForPolling && c4LiveModeEnabled) {
            return "NO DATA >"
        }

        val headers = linkedSetOf<String?>().apply {
            add(currentFunctionalHeader)
            add(null)      // functional default
            add("7DF")     // functional OBD broadcast
            add("7E0")     // common engine physical
            add("7B0")     // PSA physical variants seen with 61FF streams
            add("7C0")
            add("7D0")
        }

        for (header in headers) {
            if (header == null) {
                obd2Delegate.sendRawElmCommand("ATD", timeoutMs = 600L)
                obd2Delegate.sendRawElmCommand("ATE0", timeoutMs = 500L)
                obd2Delegate.sendRawElmCommand("ATS0", timeoutMs = 500L)
                obd2Delegate.sendRawElmCommand("ATL0", timeoutMs = 500L)
                obd2Delegate.sendRawElmCommand("ATH0", timeoutMs = 500L)
                obd2Delegate.sendRawElmCommand(liveProtocolAtCommand(), timeoutMs = 500L)
                obd2Delegate.sendRawElmCommand("ATAT1", timeoutMs = 500L)
                obd2Delegate.sendRawElmCommand("ATST20", timeoutMs = 500L)
            } else {
                obd2Delegate.sendRawElmCommand("ATSH$header", timeoutMs = 500L)
            }

            val prime = obd2Delegate.sendRawElmCommand("21FE", timeoutMs = 800L)
            val primeNorm = prime.uppercase(Locale.US)
            val response = obd2Delegate.sendRawElmCommand("21FF", timeoutMs = 900L)
            val norm = response.uppercase(Locale.US)
            if (norm.contains("61FF")) {
                currentFunctionalHeader = header
                ConnectionDiagnosticsLogger.log("psa", "oem_live_addr", "functional_live_selected header=${header ?: "default"}")
                return response
            }
            if (primeNorm.contains("61FE") && !isNoDataLike(norm)) {
                currentFunctionalHeader = header
                return response
            }
        }

        return "NO DATA >"
    }

    private suspend fun readC4LiveFrameBundle(): String {
        val responses = mutableListOf<String>()
        schedulerCycleCount += 1L
        val roadTestFocused = preserveProbeContextForPolling
        val commands = buildList {
            // Critical frame on every cycle.
            if (schedulerCycleCount % C4_CRITICAL_INTERVAL_CYCLES == 0L) add(C4_CMD_080)
            // Road-test mode prioritizes raw capture rate over breadth.
            if (roadTestFocused) {
                if (schedulerCycleCount % (C4_SECONDARY_INTERVAL_CYCLES * 2L) == 0L) add(C4_CMD_380)
                if (schedulerCycleCount % C4_SECONDARY_INTERVAL_CYCLES == 0L) add(C4_CMD_180)
                if (schedulerCycleCount % C4_SPEED_CANDIDATE_INTERVAL_CYCLES == 0L) add(C4_CMD_480)
            } else {
                // Fast secondary for RPM/MAP consistency.
                if (schedulerCycleCount % C4_SECONDARY_INTERVAL_CYCLES == 0L) {
                    add(C4_CMD_380)
                    add(C4_CMD_180)
                }
                // Full sweep less often for thermal/secondary channels.
                if (schedulerCycleCount % C4_FULL_SWEEP_INTERVAL_CYCLES == 0L) {
                    C4_LIVE_COMMANDS.forEach { if (!contains(it)) add(it) }
                }
            }
            if (isEmpty()) add(C4_CMD_080)
        }
        val startedAtMs = System.currentTimeMillis()
        commands.forEach { command ->
            val timeoutMs = when (command) {
                C4_CMD_080 -> C4_CRITICAL_TIMEOUT_MS
                C4_CMD_180, C4_CMD_380, C4_CMD_580 -> C4_SECONDARY_TIMEOUT_MS
                C4_CMD_480 -> C4_SPEED_CANDIDATE_TIMEOUT_MS
                C4_CMD_980 -> C4_FULL_TIMEOUT_MS
                else -> C4_SECONDARY_TIMEOUT_MS
            }
            val response = obd2Delegate.sendRawElmCommand(command, timeoutMs = timeoutMs)
            val normalized = response.uppercase(Locale.US)
            val looksTimedOut = isNoDataLike(normalized) || (!normalized.contains("61FF") && !normalized.contains("0:"))
            schedulerResponses += 1
            if (looksTimedOut) schedulerTimeouts += 1
            responses += "CMD:$command RESP:${response.trim()}"
        }
        schedulerWindowBusyMs += (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        // Quick fallback when primary frame misses.
        if (commands.size == 1 && isNoDataLike(responses.firstOrNull().orEmpty())) {
            val fallback = obd2Delegate.sendRawElmCommand(C4_CMD_380, timeoutMs = C4_SECONDARY_TIMEOUT_MS)
            responses += "CMD:$C4_CMD_380 RESP:${fallback.trim()}"
        }
        return responses.joinToString("\n")
    }

    private suspend fun softResyncLiveSession() {
        // Lightweight re-sync: keep current BT session and avoid heavy ATD reset.
        runCatching { obd2Delegate.sendRawElmCommand("ATAT1", timeoutMs = 280L) }
        runCatching { obd2Delegate.sendRawElmCommand("ATST20", timeoutMs = 280L) }
        activeLiveAddress?.txId?.let { tx ->
            runCatching { obd2Delegate.sendRawElmCommand("ATSH$tx", timeoutMs = 300L) }
        }
        runCatching { obd2Delegate.sendRawElmCommand("2180", timeoutMs = 320L) }
        liveSessionConfigured = true
    }

    private suspend fun probeAndSelectC4LiveOnHeaders(): Boolean {
        val headers = listOf<String?>(null, "7DF", "7E0", "7B0", "7C0")
        for (header in headers) {
            if (header == null) {
                obd2Delegate.sendRawElmCommand("ATD", timeoutMs = 600L)
                obd2Delegate.sendRawElmCommand("ATE0", timeoutMs = 500L)
                obd2Delegate.sendRawElmCommand("ATS0", timeoutMs = 500L)
                obd2Delegate.sendRawElmCommand("ATL0", timeoutMs = 500L)
                obd2Delegate.sendRawElmCommand("ATH0", timeoutMs = 500L)
                obd2Delegate.sendRawElmCommand(liveProtocolAtCommand(), timeoutMs = 500L)
            } else {
                obd2Delegate.sendRawElmCommand("ATSH$header", timeoutMs = 500L)
            }
            obd2Delegate.sendRawElmCommand("2180", timeoutMs = 900L)
            obd2Delegate.sendRawElmCommand("21FE", timeoutMs = 900L)
            val probe = obd2Delegate.sendRawElmCommand(C4_CMD_080, timeoutMs = 1200L).uppercase(Locale.US)
            if (probe.contains("0:") || probe.contains("1:") || probe.contains("61FF")) {
                currentFunctionalHeader = header
                ConnectionDiagnosticsLogger.log(
                    "psa",
                    "oem_live_addr",
                    "c4 probe selected header=${header ?: "default"}"
                )
                return true
            }
        }
        return false
    }

    private suspend fun readPassive61ffFromMonitor(timeoutMs: Long): String {
        return directIoMutex.withLock {
            runCatching {
                // Passive CAN monitor fallback when direct query 21FF is blocked.
                sendRawDirect("ATD", timeoutMs = 700L)
                sendRawDirect("ATE0", timeoutMs = 500L)
                sendRawDirect("ATS0", timeoutMs = 500L)
                sendRawDirect("ATL0", timeoutMs = 500L)
                sendRawDirect("ATH1", timeoutMs = 500L)
                sendRawDirect(liveProtocolAtCommand(), timeoutMs = 500L)
                sendRawDirect("ATAT1", timeoutMs = 500L)
                sendRawDirect("ATSTFF", timeoutMs = 500L)

                connection.clearInputBuffer()
                connection.send("ATMA\r".toByteArray(StandardCharsets.US_ASCII))
                val deadline = System.currentTimeMillis() + timeoutMs
                val builder = StringBuilder()
                while (System.currentTimeMillis() < deadline) {
                    val chunk = connection.receive(0)
                    if (chunk.isEmpty()) continue
                    builder.append(chunk.toString(StandardCharsets.US_ASCII))
                    if (builder.contains("61FF")) break
                }

                // Any key stops monitor mode on ELM327.
                connection.send(" \r".toByteArray(StandardCharsets.US_ASCII))
                val stopDeadline = System.currentTimeMillis() + 500L
                while (System.currentTimeMillis() < stopDeadline) {
                    val chunk = connection.receive(0)
                    if (chunk.isEmpty()) continue
                    builder.append(chunk.toString(StandardCharsets.US_ASCII))
                    if (builder.contains(">")) break
                }
                val raw = builder.toString().trim()
                ConnectionDiagnosticsLogger.log(
                    "psa",
                    "oem_live_monitor",
                    "captured=${raw.replace(Regex("\\s+"), " ").take(220)}"
                )
                raw
            }.getOrDefault("")
        }
    }

    private fun sendRawDirect(command: String, timeoutMs: Long): String {
        return runCatching {
            val startedAt = System.currentTimeMillis()
            connection.clearInputBuffer()
            connection.send("$command\r".toByteArray(StandardCharsets.US_ASCII))
            val deadline = System.currentTimeMillis() + timeoutMs
            val builder = StringBuilder()
            while (System.currentTimeMillis() < deadline) {
                val chunk = connection.receive(0)
                if (chunk.isEmpty()) continue
                val text = chunk.toString(StandardCharsets.US_ASCII)
                builder.append(text)
                if (builder.contains(">")) break
            }
            val response = builder.toString().trim()
            investigationRecorder?.recordCommand(
                transport = "psa_direct",
                command = command,
                response = response,
                timeoutMs = timeoutMs,
                elapsedMs = System.currentTimeMillis() - startedAt,
                extra = mapOf(
                    "active_header" to (currentFunctionalHeader ?: activeLiveAddress?.txId ?: ""),
                    "c4_mode" to c4LiveModeEnabled.toString()
                )
            )
            response
        }.getOrDefault("")
    }

    private fun liveProtocolAtCommand(): String {
        return when (detected?.protocol) {
            "can500" -> "ATSP6"
            "can250" -> "ATSP8"
            "can_auto" -> "ATSP6"
            else -> "ATSP0"
        }
    }

    private suspend fun prepareElmForInvestigation(
        headersEnabled: Boolean,
        txHeader: String? = null,
    ) {
        obd2Delegate.sendRawElmCommand("ATD", timeoutMs = 700L)
        obd2Delegate.sendRawElmCommand("ATE0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATS0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATL0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand(if (headersEnabled) "ATH1" else "ATH0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATAL", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATCAF0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATCFC1", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand(liveProtocolAtCommand(), timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATAT1", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATST20", timeoutMs = 500L)
        txHeader?.let { header ->
            obd2Delegate.sendRawElmCommand("ATSH$header", timeoutMs = 500L)
            obd2Delegate.sendRawElmCommand("ATFCSH$header", timeoutMs = 500L)
            obd2Delegate.sendRawElmCommand("ATFCSD300000", timeoutMs = 500L)
            obd2Delegate.sendRawElmCommand("ATFCSM1", timeoutMs = 500L)
        }
    }

    private suspend fun runFapStyleInvestigationHandshake() {
        INVESTIGATION_FAP_HANDSHAKE.forEach { command ->
            val timeoutMs = when (command) {
                "ATWS" -> 1200L
                "1003" -> 900L
                else -> 500L
            }
            obd2Delegate.sendRawElmCommand(command, timeoutMs = timeoutMs)
        }
        INVESTIGATION_TESTER_PRESENT_COMMANDS.forEach { command ->
            obd2Delegate.sendRawElmCommand(command, timeoutMs = 700L)
        }
    }

    private suspend fun runProbeContextReplay(): Boolean {
        val detection = detected
        investigationRecorder?.info(
            "campaign",
            "running probe-context replay tx=${detection?.txId ?: "unknown"} rx=${detection?.rxId ?: "unknown"} protocol=${detection?.protocol ?: "unknown"}"
        )
        var success = false
        INVESTIGATION_PROBE_CONTEXT_COMMANDS.forEach { command ->
            val timeoutMs = when {
                command == "ATDP" -> 500L
                command.startsWith("21C") -> 1400L
                else -> 950L
            }
            runCatching {
                val response = obd2Delegate.sendRawElmCommand(command, timeoutMs = timeoutMs)
                val normalized = response.uppercase(Locale.US)
                if (
                    normalized.contains("61FF") ||
                    normalized.contains("61FE") ||
                    normalized.contains("6180") ||
                    normalized.contains("5700")
                ) {
                    success = true
                }
                response
            }.onFailure { error ->
                investigationRecorder?.info(
                    "campaign",
                    "probe-context cmd=$command failed reason=${error.message ?: error.javaClass.simpleName}"
                )
            }
        }
        if (success) {
            investigationRecorder?.info("campaign", "probe-context replay produced positive PSA frames")
        }
        return success
    }

    private suspend fun readPassive61ffAfterFapHandshake(timeoutMs: Long): String {
        return directIoMutex.withLock {
            runCatching {
                sendRawDirect("ATWS", timeoutMs = 1200L)
                sendRawDirect("ATE0", timeoutMs = 500L)
                sendRawDirect("ATL0", timeoutMs = 500L)
                sendRawDirect("ATH1", timeoutMs = 500L)
                sendRawDirect("ATS0", timeoutMs = 500L)
                sendRawDirect("ATAL", timeoutMs = 500L)
                sendRawDirect("ATSP6", timeoutMs = 500L)
                sendRawDirect("ATSH6A8", timeoutMs = 500L)
                sendRawDirect("ATCRA688", timeoutMs = 500L)
                sendRawDirect("ATFCSH6A8", timeoutMs = 500L)
                sendRawDirect("ATFCSD300000", timeoutMs = 500L)
                sendRawDirect("ATFCSM1", timeoutMs = 500L)
                sendRawDirect("1003", timeoutMs = 900L)
                INVESTIGATION_TESTER_PRESENT_COMMANDS.forEach { command ->
                    sendRawDirect(command, timeoutMs = 700L)
                }

                connection.clearInputBuffer()
                connection.send("ATMA\r".toByteArray(StandardCharsets.US_ASCII))
                val deadline = System.currentTimeMillis() + timeoutMs
                val builder = StringBuilder()
                while (System.currentTimeMillis() < deadline) {
                    val chunk = connection.receive(0)
                    if (chunk.isEmpty()) continue
                    builder.append(chunk.toString(StandardCharsets.US_ASCII))
                    if (builder.contains("61FF")) break
                }

                connection.send(" \r".toByteArray(StandardCharsets.US_ASCII))
                val stopDeadline = System.currentTimeMillis() + 600L
                while (System.currentTimeMillis() < stopDeadline) {
                    val chunk = connection.receive(0)
                    if (chunk.isEmpty()) continue
                    builder.append(chunk.toString(StandardCharsets.US_ASCII))
                    if (builder.contains(">")) break
                }
                val raw = builder.toString().trim()
                investigationRecorder?.info(
                    "campaign",
                    "passive_monitor=${raw.replace(Regex("\\s+"), " ").take(280)}"
                )
                ConnectionDiagnosticsLogger.log(
                    "psa",
                    "campaign",
                    "passive61ff=${raw.replace(Regex("\\s+"), " ").take(220)}"
                )
                raw
            }.getOrDefault("")
        }
    }

    private fun parseRpmResponse(command: String, response: String): Int? {
        val bytes = parsePositiveResponse(command, response) ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] shl 8) or bytes[1]) / 4
    }

    private fun parseCoolantResponse(command: String, response: String): Int? {
        val bytes = parsePositiveResponse(command, response) ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0] - 40
    }

    private fun parsePositiveResponse(command: String, response: String): List<Int>? {
        val clean = response.uppercase(Locale.US)
        val bytes = Regex("[0-9A-F]{2}")
            .findAll(clean)
            .map { it.value.toInt(16) }
            .toList()
        if (bytes.isEmpty()) return null

        return when {
            command.startsWith("22") -> {
                if (bytes.size < 5) return null
                val didHi = command.substring(2, 4).toInt(16)
                val didLo = command.substring(4, 6).toInt(16)
                val idx = bytes.windowed(3, 1).indexOfFirst { it[0] == 0x62 && it[1] == didHi && it[2] == didLo }
                if (idx < 0) null else bytes.drop(idx + 3)
            }

            command.startsWith("21") -> {
                if (bytes.size < 3) return null
                val loc = command.substring(2, 4).toInt(16)
                val idx = bytes.windowed(2, 1).indexOfFirst { it[0] == 0x61 && it[1] == loc }
                if (idx < 0) null else bytes.drop(idx + 2)
            }

            else -> null
        }
    }

    private fun parseLiveSnapshot(response: String): PsaLiveSnapshot? {
        val frames = extract61ffPayloads(response)
        if (frames.isEmpty()) return null

        var batteryVoltage: Double? = null
        var coolant: Int? = null
        var intake: Int? = null
        var map: Int? = null
        var tps: Int? = null
        var rpm: Int? = null

        frames.forEach { payload ->
            batteryVoltage = batteryVoltage ?: payload.getOrNull(1)?.let { raw ->
                if (raw in 90..180) raw / 10.0 else null
            }

            val frameType = payload.getOrNull(3)
            if (frameType == 0x55 && payload.size >= 10) {
                // Layout inferred from FAPlite traces:
                // [.., battery, profile, 0x55, tps?, ..., coolantRaw, manifoldTempRaw, ...]
                tps = tps ?: payload.getOrNull(4)?.takeIf { it in 0..100 }
                coolant = coolant ?: payload.getOrNull(8)?.let { raw ->
                    (raw - 40).takeIf { it in -30..140 }
                }
                intake = intake ?: payload.getOrNull(9)?.let { raw ->
                    (raw - 44).takeIf { it in -30..120 }
                }
                map = map ?: payload.getOrNull(4)?.takeIf { it in 10..150 }
            }

            if (frameType == 0x32 && payload.size >= 12) {
                // Secondary 0x32 frames carry thermal-like bytes that track the same points.
                coolant = coolant ?: payload.getOrNull(9)?.let { raw ->
                    (raw - 40).takeIf { it in -30..140 }
                }
                intake = intake ?: payload.getOrNull(10)?.let { raw ->
                    (raw - 44).takeIf { it in -30..120 }
                }
                map = map ?: payload.getOrNull(4)?.takeIf { it in 10..150 }

                // Prevent bogus fixed RPM from signature-like bytes (0C27 appears constant in logs).
                val hi = payload.getOrNull(7)
                val lo = payload.getOrNull(8)
                if (hi != null && lo != null && !(hi == 0x0C && lo == 0x27)) {
                    val candidate = ((hi shl 8) or lo) / 4
                    if (candidate in 0..9000) {
                        rpm = candidate
                    }
                } else if (hi == 0x0C && lo == 0x27) {
                    rpm = rpm ?: 0
                }
            }
        }
        return PsaLiveSnapshot(
            rpm = rpm,
            coolant = coolant,
            intake = intake,
            map = map,
            tps = tps,
            batteryVoltage = batteryVoltage,
            frameCount = frames.size,
        )
    }

    private fun parseC4LiveSnapshot(bundleResponse: String): PsaLiveSnapshot? {
        val upper = bundleResponse.uppercase(Locale.US)
        if (!upper.contains("61FF") && !upper.contains("0:") && !upper.contains("1:")) return null

        val byCommand = extract61ffCommandPayloads(bundleResponse)
        val commandPayloads = byCommand.values.toMutableList()

        if (commandPayloads.isEmpty()) return null
        val merged = commandPayloads.flatten()

        val c080 = byCommand[C4_CMD_080].orEmpty()
        val c380 = byCommand[C4_CMD_380].orEmpty()
        val c180 = byCommand[C4_CMD_180].orEmpty()
        val c480 = byCommand[C4_CMD_480].orEmpty()
        val c980 = byCommand[C4_CMD_980].orEmpty()

        val battery = listOf(c080, c380, c980)
            .flatten()
            .firstOrNull { it in 110..150 }
            ?.div(10.0)

        val rpm = inferC4Rpm(c080)

        val map = inferC4Map(c080)

        val candidateThrottleAngleDeg = inferC4CandidateThrottleAngle(c380)
        val candidateIgnitionAdvanceDeg = inferC4CandidateIgnitionAdvance(c180)
        val candidateInjectionDurationMs = inferC4CandidateInjectionDuration(c080)
        val candidateInjectionDurationMirrorMs = inferC4CandidateInjectionDurationMirror(c480)
        // For this PSA stream, the app's generic TPS field should follow the throttle plate,
        // not the accelerator pedal. FAP shows idle around 5-6 degrees and peaks near 73.79.
        val tps = candidateThrottleAngleDeg
            ?.let { ((it / 73.79) * 100.0).roundToInt().coerceIn(0, 100) }

        // Coolant near 0x7A-0x90 and IAT often at penultimate byte of 21C0.
        val coolantRaw = (c080 + c380).firstOrNull { it in 70..150 }
        val intakeRaw = c080.dropLast(1).lastOrNull { it in 40..100 }
            ?: c380.dropLast(1).lastOrNull { it in 40..100 }
        val coolant = coolantRaw?.minus(40)
        val intake = intakeRaw?.minus(44)
        val candidateSpeedKph = inferC4CandidateSpeed(c480)
        val candidateAccelPedalPosPct = inferC4CandidateAccelPedal(c080)
        val candidateGear = inferC4CandidateGear(c380, c480, c980)
        if (rpm == null && map == null && tps == null) return null
        return PsaLiveSnapshot(
            rpm = rpm,
            map = map,
            tps = tps,
            coolant = coolant?.coerceIn(-30, 140),
            intake = intake?.coerceIn(-30, 120),
            candidateSpeedKph = candidateSpeedKph,
            candidateAccelPedalPosPct = candidateAccelPedalPosPct,
            candidateGear = candidateGear,
            candidateThrottleAngleDeg = candidateThrottleAngleDeg,
            candidateIgnitionAdvanceDeg = candidateIgnitionAdvanceDeg,
            candidateInjectionDurationMs = candidateInjectionDurationMs,
            candidateInjectionDurationMirrorMs = candidateInjectionDurationMirrorMs,
            batteryVoltage = battery,
            frameCount = commandPayloads.size
        )
    }

    private fun inferC4Rpm(
        c080: List<Int>,
    ): Int? {
        if (c080.isEmpty()) return null
        val primary = c080.getOrNull(0) ?: return null
        if (primary <= 0) return 0

        // Experimental calibration from controlled staircase test:
        // idle, 2000 rpm, 3000 rpm, 4000 rpm.
        val effective = primary.toDouble()

        val anchors = listOf(
            0.0 to 850.0,
            23.0 to 900.0,
            52.0 to 2000.0,
            95.0 to 3000.0,
            122.0 to 4000.0,
        )

        val rpm = when {
            effective <= anchors.first().first -> anchors.first().second
            effective >= anchors.last().first -> {
                val (x1, y1) = anchors[anchors.lastIndex - 1]
                val (x2, y2) = anchors.last()
                y1 + ((effective - x1) * (y2 - y1) / (x2 - x1))
            }
            else -> {
                val pair = anchors.zipWithNext().first { effective >= it.first.first && effective <= it.second.first }
                val (x1, y1) = pair.first
                val (x2, y2) = pair.second
                y1 + ((effective - x1) * (y2 - y1) / (x2 - x1))
            }
        }
        return rpm.toInt().coerceIn(0, 9000)
    }

    private fun inferC4CandidateSpeed(c480: List<Int>): Int? {
        if (c480.isEmpty()) return null
        // Strongest current velocity candidate from GPS-correlated road logs.
        // Keep it explicitly labeled as a candidate in the UI until the FAP stream is fully closed.
        return c480.getOrNull(18)?.takeIf { it in 0..250 }
    }

    private fun inferC4CandidateGear(vararg frames: List<Int>): Int? {
        frames.asSequence()
            .flatMap { frame ->
                listOf(
                    frame.getOrNull(19),
                    frame.getOrNull(20),
                    frame.lastOrNull(),
                ).asSequence()
            }
            .filterNotNull()
            .firstOrNull { it in 0..7 }
            ?.let { return it }
        return null
    }

    private fun inferC4Map(c080: List<Int>): Int? {
        val raw = c080.getOrNull(13) ?: return null
        if (raw !in 0..180) return null

        // Current best PSA C4 MAP hypothesis:
        // 21C080013.b13 scaled at ~15.3 mbar/LSB.
        // The app's live data field is kPa, so convert mbar -> kPa.
        return ((raw * 15.3) / 10.0).roundToInt().coerceIn(0, 255)
    }

    private fun inferC4CandidateAccelPedal(c080: List<Int>): Int? {
        // Current hypothesis for pedal is not reliable enough yet.
        // Recent sessions showed false zeros while FAP reported clear pedal movement.
        return null
    }

    private fun inferC4CandidateThrottleAngle(c380: List<Int>): Double? {
        val raw = c380.getOrNull(5) ?: return null
        // FAP exports throttle angle in ~0.47 degree steps (e.g. 5.64, 7.52, 73.79).
        // This remains a candidate until we close the byte-level correlation with the raw stream.
        return (raw * 0.47).coerceIn(0.0, 90.0)
    }

    private fun inferC4CandidateIgnitionAdvance(c180: List<Int>): Double? {
        val raw = listOfNotNull(c180.getOrNull(4), c180.getOrNull(12))
            .firstOrNull { it in 0..255 }
            ?: return null
        // The visible advance signal now tracks a raw byte that maps linearly back to degrees.
        // Keep it as a double because the FAP shows fractional values like -0.25 deg.
        return (raw * 0.35 - 30.0).coerceIn(-60.0, 80.0)
    }

    private fun inferC4CandidateInjectionDuration(c080: List<Int>): Double? {
        val raw = c080.getOrNull(4) ?: return null
        if (raw !in 0..80) return null
        // Strong current hypothesis from FAP correlation:
        // 21C080013.b04 * 0.39 ms
        return raw * 0.39
    }

    private fun inferC4CandidateInjectionDurationMirror(c480: List<Int>): Double? {
        val raw = c480.getOrNull(5) ?: return null
        if (raw !in 0..80) return null
        return raw * 0.39
    }

    private fun findOemProfileById(id: String?): OemCommandProfile {
        if (id.isNullOrBlank()) return OEM_PROFILES.last()
        return OEM_PROFILES.firstOrNull { it.id == id } ?: OEM_PROFILES.last()
    }

    private fun tryRestorePersistedSession(): Boolean {
        val stored = sessionStore?.load() ?: return false
        if (stored.protocol.isBlank() || stored.txId.isBlank()) return false

        val hints = stored.hintsCsv
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        detected = PsaDetection(
            protocol = stored.protocol,
            txId = stored.txId,
            rxId = stored.rxId,
            hints = hints,
        )
        restoredPersistedSession = true
        liveSessionConfigured = false
        directPsaMode = true
        preserveProbeContextForPolling = false
        investigationInProgress = false
        c4LiveModeEnabled = stored.c4LiveModeEnabled || hints.any { it.startsWith("id_21c") || it == "id_2180_c4_sig" }
        activeLiveAddress = if (stored.isFunctional) {
            null
        } else {
            LiveAddressCandidate(
                txId = stored.txId,
                rxId = stored.rxId.takeIf { it.isNotBlank() && it != "*" },
                label = "restored",
            )
        }
        currentFunctionalHeader = if (stored.isFunctional) {
            stored.functionalHeader.takeIf { it.isNotBlank() && it != "*" }
        } else {
            null
        }
        oemProfile = findOemProfileById(stored.oemProfileId)
        cachedFirmwareInfo = FirmwareInfo(
            signature = "PSA ${stored.protocol.uppercase(Locale.US)} ELM327 TX=${stored.txId} RX=${stored.rxId}",
            productString = "ELM327",
            era = FirmwareEra.MODERN_2025,
            family = EcuFamily.UNKNOWN,
            capabilities = EcuCapabilities(
                supportsModernProtocol = false,
                supportsLegacyProtocol = true,
                supportsPageRead = false,
                supportsPageWrite = false,
                supportsBurn = false,
                supportsLiveData = true
            ),
        )
        investigationRecorder?.updateMetadata("psa_hints", hints.toList())
        investigationRecorder?.updateMetadata("oem_profile", oemProfile.id)
        ConnectionDiagnosticsLogger.log(
            "psa",
            "probe",
            "restored session protocol=${stored.protocol} tx=${stored.txId} rx=${stored.rxId} functional=${stored.isFunctional} c4_mode=$c4LiveModeEnabled"
        )
        return true
    }

    private fun persistDetectedSession(detection: PsaDetection) {
        val store = sessionStore ?: return
        val isFunctional = detection.txId == "default" || detection.rxId == "*" || detection.rxId.isBlank()
        val session = PsaPersistedSession(
            protocol = detection.protocol,
            txId = detection.txId,
            rxId = detection.rxId,
            hintsCsv = detection.hints.sorted().joinToString(","),
            oemProfileId = oemProfile.id,
            functionalHeader = currentFunctionalHeader ?: if (isFunctional) detection.txId else "",
            c4LiveModeEnabled = c4LiveModeEnabled || detection.hints.any { it.startsWith("id_21c") || it == "id_2180_c4_sig" },
            isFunctional = isFunctional,
            timestampMs = System.currentTimeMillis(),
        )
        store.save(session)
        ConnectionDiagnosticsLogger.log(
            "psa",
            "probe",
            "persisted session protocol=${session.protocol} tx=${session.txId} rx=${session.rxId} functional=${session.isFunctional}"
        )
    }

    private fun persistResolvedLiveSession() {
        val store = sessionStore ?: return
        val detection = detected ?: return
        val resolvedTx = activeLiveAddress?.txId ?: detection.txId
        val resolvedRx = activeLiveAddress?.rxId ?: detection.rxId
        val isFunctional = activeLiveAddress == null
        val session = PsaPersistedSession(
            protocol = detection.protocol,
            txId = resolvedTx,
            rxId = resolvedRx,
            hintsCsv = detection.hints.sorted().joinToString(","),
            oemProfileId = oemProfile.id,
            functionalHeader = currentFunctionalHeader ?: "",
            c4LiveModeEnabled = c4LiveModeEnabled || detection.hints.any { it.startsWith("id_21c") || it == "id_2180_c4_sig" },
            isFunctional = isFunctional,
            timestampMs = System.currentTimeMillis(),
        )
        store.save(session)
    }

    private suspend fun configureLiveSessionForCurrentTarget() {
        obd2Delegate.sendRawElmCommand("ATD", timeoutMs = 600L)
        obd2Delegate.sendRawElmCommand("ATE0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATS0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATL0", timeoutMs = 500L)
        val targetHeader = activeLiveAddress?.txId ?: currentFunctionalHeader
        obd2Delegate.sendRawElmCommand(if (targetHeader != null) "ATH1" else "ATH0", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand(liveProtocolAtCommand(), timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATAT1", timeoutMs = 500L)
        obd2Delegate.sendRawElmCommand("ATSTFF", timeoutMs = 500L)
        targetHeader?.let { header ->
            obd2Delegate.sendRawElmCommand("ATSH$header", timeoutMs = 500L)
        }
        if (c4LiveModeEnabled || targetHeader != null) {
            runCatching { obd2Delegate.sendRawElmCommand("2180", timeoutMs = 900L) }
            runCatching { obd2Delegate.sendRawElmCommand("21FE", timeoutMs = 900L) }
        }
        liveSessionConfigured = true
        ConnectionDiagnosticsLogger.log(
            "psa",
            "oem_live_addr",
            "configured session header=${targetHeader ?: "default"} c4_mode=$c4LiveModeEnabled"
        )
    }

    private fun extract61ffCommandPayloads(bundleResponse: String): LinkedHashMap<String, List<Int>> {
        val upper = bundleResponse.uppercase(Locale.US)
        val lines = upper.split('\n', '\r').map { it.trim() }.filter { it.isNotBlank() }
        val byCommand = linkedMapOf<String, List<Int>>()
        lines.forEach { line ->
            val commandId = if (line.contains("CMD:")) line.substringAfter("CMD:").substringBefore(' ').trim() else null
            val respPart = if (line.contains("RESP:")) line.substringAfter("RESP:") else line
            val rawBytes = extractAsciiHexBytes(respPart)
            if (rawBytes.isEmpty()) return@forEach
            val serviceIdx = rawBytes.windowed(2, 1).indexOfFirst { it[0] == 0x61 && it[1] == 0xFF }
            if (serviceIdx >= 0 && serviceIdx + 2 < rawBytes.size) {
                val payload = rawBytes.drop(serviceIdx + 2)
                if (!commandId.isNullOrBlank()) {
                    byCommand[commandId] = payload
                }
            }
        }
        return byCommand
    }

    private fun extractAsciiHexBytes(line: String?): List<Int> {
        if (line.isNullOrBlank()) return emptyList()
        val payload = if (line.contains(':')) line.substringAfter(':', "") else line
        if (payload.isBlank()) return emptyList()
        return Regex("[0-9A-F]{2}")
            .findAll(payload)
            .map { it.value.toInt(16) }
            .toList()
    }

    private fun extract61ffPayloads(response: String): List<List<Int>> {
        val bytes = Regex("[0-9A-F]{2}")
            .findAll(response.uppercase(Locale.US))
            .map { it.value.toInt(16) }
            .toList()
        if (bytes.size < 3) return emptyList()

        val frames = mutableListOf<List<Int>>()
        var index = 0
        while (index < bytes.lastIndex) {
            if (bytes[index] == 0x61 && bytes[index + 1] == 0xFF) {
                val payload = extractPayload(bytes, index)
                if (payload.isNotEmpty()) {
                    frames += payload
                }
            }
            index++
        }
        return frames
    }

    private fun extractPayload(bytes: List<Int>, serviceIndex: Int): List<Int> {
        val prefixedLength = bytes.getOrNull(serviceIndex - 1)
        if (prefixedLength != null && prefixedLength in 0x03..0x40) {
            val endExclusive = (serviceIndex + prefixedLength).coerceAtMost(bytes.size)
            if (endExclusive > serviceIndex + 2) {
                return bytes.subList(serviceIndex + 2, endExclusive)
            }
        }
        return if (serviceIndex + 2 < bytes.size) {
            bytes.subList(serviceIndex + 2, bytes.size)
        } else {
            emptyList()
        }
    }

    private fun defaultLiveData(): EcuLiveData {
        return EcuLiveData(
            secl = 0,
            rpm = 0,
            coolantTemp = 0,
            intakeTemp = 0,
            mapPressure = 100,
            tps = 0,
            batteryVoltage = DEFAULT_BATTERY_VOLTAGE,
            advance = 0,
            o2 = 0,
            engineStatus = 0,
            sparkStatus = 0,
        )
    }

    private suspend fun detectOemProfile(): OemCommandProfile {
        val fingerprint = buildString {
            append(obd2Delegate.sendRawElmCommand("22F1A0", timeoutMs = 900L)).append(' ')
            append(obd2Delegate.sendRawElmCommand("2180", timeoutMs = 900L)).append(' ')
            append(obd2Delegate.sendRawElmCommand("22F190", timeoutMs = 900L))
        }
        val decoded = decodeAsciiFromHexPayload(fingerprint).uppercase(Locale.US)
        ConnectionDiagnosticsLogger.log(
            "psa",
            "oem_fingerprint",
            "raw=${fingerprint.replace(Regex("\\s+"), " ").trim().take(180)} decoded=${decoded.take(80)}"
        )
        val match = OEM_PROFILES.firstOrNull { profile ->
            profile.keywords.isNotEmpty() && profile.keywords.any { it in decoded }
        }
        return match ?: OEM_PROFILES.last()
    }

    private fun decodeAsciiFromHexPayload(raw: String): String {
        val bytes = Regex("[0-9A-F]{2}")
            .findAll(raw.uppercase(Locale.US))
            .map { it.value.toInt(16) }
            .toList()
        if (bytes.isEmpty()) return raw
        return bytes
            .mapNotNull { value ->
                val ch = value.toChar()
                if (ch in ' '..'~') ch else null
            }
            .joinToString("")
    }

    private fun scanPsaCan(): PsaDetection? {
        val deadline = System.currentTimeMillis() + SCAN_BUDGET_MS
        initCanElm()
        probeFunctionalPsa(deadline)?.let { return it }
        val protocols = listOf("6", "8")
        for (sp in protocols) {
            if (System.currentTimeMillis() >= deadline) {
                ConnectionDiagnosticsLogger.log("psa", "probe", "scan timeout on CAN phase")
                return null
            }
            sendRaw("ATSP$sp", timeoutMs = 400L)
            var activeTxId: String? = null
            for (candidate in canCandidates) {
                if (System.currentTimeMillis() >= deadline) {
                    ConnectionDiagnosticsLogger.log("psa", "probe", "scan timeout while iterating CAN candidates")
                    return null
                }
                for (rxId in candidate.rxIds) {
                    if (System.currentTimeMillis() >= deadline) {
                        ConnectionDiagnosticsLogger.log("psa", "probe", "scan timeout while iterating CAN candidates")
                        return null
                    }
                    val reuseTxConfig = activeTxId == candidate.txId
                    configureCanAddress(candidate.txId, rxId, reuseTxConfig = reuseTxConfig)
                    activeTxId = candidate.txId
                    ConnectionDiagnosticsLogger.log(
                        "psa",
                        "scan",
                        "candidate=${candidate.label} tx=${candidate.txId} rx=$rxId protocol=can$sp"
                    )
                    val hints = probePsaCommands(deadline)
                    if (hints.isNotEmpty()) {
                        return PsaDetection(
                            protocol = if (sp == "6") "can500" else "can250",
                            txId = candidate.txId,
                            rxId = rxId,
                            hints = hints + setOf("candidate_${candidate.label}")
                        )
                    }
                }
            }
        }
        return null
    }

    private fun probeFunctionalPsa(deadlineMillis: Long): PsaDetection? {
        val headers = listOf<String?>(null, "7DF", "7E0")
        sendRaw("ATSP0", timeoutMs = 400L)
        sendRaw("ATAT1", timeoutMs = 400L)
        sendRaw("ATSTFF", timeoutMs = 400L)

        for (header in headers) {
            if (System.currentTimeMillis() >= deadlineMillis) {
                return null
            }
            if (header != null) {
                sendRaw("ATSH $header", timeoutMs = 400L)
            }
            ConnectionDiagnosticsLogger.log(
                "psa",
                "scan",
                "candidate=functional tx=${header ?: "default"} rx=* protocol=auto"
            )
            val hints = probePsaCommands(deadlineMillis)
            if (hints.isNotEmpty()) {
                return PsaDetection(
                    protocol = "can_auto",
                    txId = header ?: "default",
                    rxId = "*",
                    hints = hints + setOf("candidate_functional")
                )
            }
        }

        return null
    }

    private fun initCanElm() {
        val sequence = listOf(
            "ATZ",
            "ATE0",
            "ATL0",
            "ATH0",
            "ATS0",
            "ATAL",
            "ATCAF0",
            "ATCFC1",
            "ATAT1",
            "ATSTFF",
            "ATSP0"
        )
        for (cmd in sequence) {
            val timeout = if (cmd == "ATZ") 1200L else 400L
            sendRaw(cmd, timeoutMs = timeout)
        }
    }

    private fun configureCanAddress(txId: String, rxId: String, reuseTxConfig: Boolean = false) {
        if (!reuseTxConfig) {
            sendRaw("ATSH $txId", timeoutMs = 400L)
            sendRaw("ATFCSH $txId", timeoutMs = 400L)
            sendRaw("ATFCSD 30 00 00", timeoutMs = 400L)
            sendRaw("ATFCSM 1", timeoutMs = 400L)
            sendRaw("ATSTFF", timeoutMs = 400L)
            sendRaw("ATAT0", timeoutMs = 400L)
        }
        sendRaw("ATCRA $rxId", timeoutMs = 400L)
        sendRaw("ATAT1", timeoutMs = 400L)
    }

    private fun probePsaCommands(deadlineMillis: Long): Set<String> {
        val hints = linkedSetOf<String>()
        val probes = listOf("21FE", "2180", "2181", C4_CMD_080)
        var noDataStreak = 0
        for (cmd in probes) {
            if (System.currentTimeMillis() >= deadlineMillis) {
                break
            }
            val response = sendRaw(cmd, timeoutMs = SCAN_PROBE_TIMEOUT_MS).uppercase(Locale.US)
            val noData = isNoDataLike(response)
            noDataStreak = if (noData) noDataStreak + 1 else 0
            if (response.contains("61 80") || response.contains("6180")) hints.add("id_2180")
            if (response.contains("61809652552080")) hints.add("id_2180_c4_sig")
            if (response.contains("61 FE") || response.contains("61FE")) hints.add("id_21FE")
            if (response.contains("61 81") || response.contains("6181")) hints.add("id_2181")
            if (response.contains("61FF") || response.contains("0:") || response.contains("1:")) {
                if (cmd.startsWith("21C")) hints.add("id_21c")
            }
            if (hints.isNotEmpty()) break
            if (noDataStreak >= MAX_NO_DATA_STREAK) break
        }
        return hints
    }

    private fun isNoDataLike(response: String): Boolean {
        if (response.isBlank()) return true
        val normalized = response.uppercase(Locale.US)
        return normalized.contains("NO DATA") ||
            normalized == "?" ||
            normalized.startsWith("?") ||
            normalized.contains("UNABLE TO CONNECT")
    }

    private suspend fun readStandardO2AfrX10(): Int? {
        val now = System.currentTimeMillis()
        if (o2RetryAfterMs > now) return null
        // Try wideband first (lambda-capable), then narrowband fallback.
        val widebandPids = listOf("0134", "0135", "0136", "0137", "0138", "0139", "013A", "013B")
        for (pid in widebandPids) {
            val afr = parseMode01Pid(pid, expectedDataLen = 4, trackUnsupported = true) { bytes ->
                val lambda = ((bytes[0] shl 8) or bytes[1]).toDouble() / 32768.0
                (lambda * 14.7 * 10.0).toInt().coerceIn(80, 220)
            }
            if (afr != null) return afr
            if (o2RetryAfterMs > System.currentTimeMillis()) return null
        }
        val narrowbandPids = listOf("0114", "0115", "0116", "0117", "0118", "0119", "011A", "011B")
        for (pid in narrowbandPids) {
            val afr = parseMode01Pid(pid, expectedDataLen = 2, trackUnsupported = true) { bytes ->
                val voltage = bytes[0] * 0.005
                val afrValue = (14.7 + ((voltage - 0.45) * 6.0)).coerceIn(10.0, 19.9)
                (afrValue * 10.0).toInt()
            }
            if (afr != null) return afr
            if (o2RetryAfterMs > System.currentTimeMillis()) return null
        }
        return null
    }

    private suspend fun parseMode01Pid(
        command: String,
        expectedDataLen: Int,
        trackUnsupported: Boolean = false,
        parser: (List<Int>) -> Int
    ): Int? {
        return runCatching {
            val resp = obd2Delegate.sendRawElmCommand(command, timeoutMs = 220L)
            val upper = resp.uppercase(Locale.US)
            if (trackUnsupported && Regex("\\b7F\\s*01\\s*11\\b").containsMatchIn(upper.replace("\r", " ").replace("\n", " "))) {
                o2ConsecutiveUnsupportedResponses += 1
                if (o2ConsecutiveUnsupportedResponses >= O2_DISABLE_AFTER_NEGATIVE_RESPONSES) {
                    o2RetryAfterMs = System.currentTimeMillis() + O2_RETRY_COOLDOWN_MS
                    ConnectionDiagnosticsLogger.log(
                        "psa",
                        "o2",
                        "temporarily_disabled cooldown_ms=$O2_RETRY_COOLDOWN_MS negative_responses=$o2ConsecutiveUnsupportedResponses"
                    )
                }
                return null
            }
            val bytes = Regex("[0-9A-F]{2}")
                .findAll(upper)
                .map { it.value.toInt(16) }
                .toList()
            val pid = command.removePrefix("01").toInt(16)
            val headerIdx = bytes.windowed(2, 1).indexOfFirst { it[0] == 0x41 && it[1] == pid }
            if (headerIdx < 0) return null
            val start = headerIdx + 2
            if (start + expectedDataLen > bytes.size) return null
            o2ConsecutiveUnsupportedResponses = 0
            o2RetryAfterMs = 0L
            parser(bytes.subList(start, start + expectedDataLen))
        }.getOrNull()
    }

    private fun shouldAttemptO2Read(): Boolean {
        val retryAfter = o2RetryAfterMs
        if (retryAfter <= 0L) return true
        if (System.currentTimeMillis() >= retryAfter) {
            o2RetryAfterMs = 0L
            o2ConsecutiveUnsupportedResponses = 0
            ConnectionDiagnosticsLogger.log("psa", "o2", "cooldown_expired retrying_o2")
            return true
        }
        return false
    }

    private fun launchInvestigationCampaign() {
        if (investigationRecorder == null) return
        scope.launch {
            investigationInProgress = true
            ConnectionDiagnosticsLogger.log("psa", "campaign", "investigation_started")
            try {
                runCatching {
                    investigationRecorder.info("campaign", "starting psa investigation sweep")
                    val probeReplaySucceeded = runProbeContextReplay()
                    if (probeReplaySucceeded) {
                        c4LiveModeEnabled = true
                        preserveProbeContextForPolling = true
                        investigationRecorder.info("campaign", "skipping destructive fap-style reset after probe-context success")
                        investigationRecorder.info("campaign", "running proprietary candidate sweep for o2/mix exploration")
                        INVESTIGATION_PSA_CANDIDATE_COMMANDS.forEach { command ->
                            runCatching {
                                obd2Delegate.sendRawElmCommand(command, timeoutMs = 1400L)
                            }.onFailure { error ->
                                investigationRecorder.info(
                                    "campaign",
                                    "candidate cmd=$command failed reason=${error.message ?: error.javaClass.simpleName}"
                                )
                            }
                        }
                    } else {
                        preserveProbeContextForPolling = false
                        investigationRecorder.info("campaign", "running fap-style handshake tx=6A8 rx=688")
                        runFapStyleInvestigationHandshake()
                        INVESTIGATION_PSA_COMMANDS.forEach { command ->
                            runCatching {
                                obd2Delegate.sendRawElmCommand(
                                    command,
                                    timeoutMs = if (command.startsWith("21C")) 1400L else 950L
                                )
                            }.onFailure { error ->
                                investigationRecorder.info(
                                    "campaign",
                                    "psa cmd=$command failed reason=${error.message ?: error.javaClass.simpleName}"
                                )
                            }
                        }
                        investigationRecorder.info("campaign", "running passive 61ff monitor after fap handshake")
                        runCatching {
                            readPassive61ffAfterFapHandshake(timeoutMs = 3_500L)
                        }.onFailure { error ->
                            investigationRecorder.info(
                                "campaign",
                                "passive monitor failed reason=${error.message ?: error.javaClass.simpleName}"
                            )
                        }
                    }
                    if (probeReplaySucceeded) {
                        investigationRecorder.info("campaign", "preserving positive PSA session; skipping sae lambda spot-check")
                    } else {
                        investigationRecorder.info("campaign", "running post-psa sae lambda spot-check")
                        prepareElmForInvestigation(headersEnabled = false)
                        INVESTIGATION_STANDARD_COMMANDS.forEach { command ->
                            runCatching {
                                obd2Delegate.sendRawElmCommand(command, timeoutMs = 550L)
                            }.onFailure { error ->
                                investigationRecorder.info(
                                    "campaign",
                                    "sae pid=$command failed reason=${error.message ?: error.message ?: error.javaClass.simpleName}"
                                )
                            }
                        }
                    }
                    investigationRecorder.info("campaign", "finished psa investigation sweep")
                }.onFailure { error ->
                    preserveProbeContextForPolling = false
                    investigationRecorder.info(
                        "campaign",
                        "aborted reason=${error.message ?: error.javaClass.simpleName}"
                    )
                }
            } finally {
                investigationInProgress = false
                ConnectionDiagnosticsLogger.log("psa", "campaign", "investigation_finished")
            }
        }
    }

    private fun registerPayloadFingerprint(response: String): Boolean {
        val compact = response.replace(Regex("\\s+"), "").uppercase(Locale.US)
        if (compact.isBlank()) return false
        val now = System.currentTimeMillis()
        if (compact == lastLivePayloadFingerprint) {
            return now - lastLivePayloadAtMs >= C4_STALE_FRAME_THRESHOLD_MS
        }
        lastLivePayloadFingerprint = compact
        lastLivePayloadAtMs = now
        return false
    }

    private fun maybeLogSchedulerWindow() {
        if (schedulerCycleCount == 0L || schedulerCycleCount % C4_SCHEDULER_LOG_INTERVAL != 0L) return
        val now = System.currentTimeMillis()
        val elapsed = (now - schedulerWindowStartedAtMs).coerceAtLeast(1L)
        val hz = (C4_SCHEDULER_LOG_INTERVAL.toDouble() * 1000.0) / elapsed.toDouble()
        val timeoutRate = if (schedulerResponses > 0) schedulerTimeouts.toDouble() / schedulerResponses.toDouble() else 0.0
        val busyRate = schedulerWindowBusyMs.toDouble() / elapsed.toDouble()
        ConnectionDiagnosticsLogger.log(
            "psa",
            "oem_perf",
            "cycles=$C4_SCHEDULER_LOG_INTERVAL hz=${"%.2f".format(Locale.US, hz)} busy=${"%.2f".format(Locale.US, busyRate)} timeout_rate=${"%.2f".format(Locale.US, timeoutRate)} cmds=$schedulerResponses timeouts=$schedulerTimeouts"
        )
        schedulerWindowStartedAtMs = now
        schedulerWindowBusyMs = 0L
        schedulerResponses = 0
        schedulerTimeouts = 0
    }

    private fun sendRaw(command: String, timeoutMs: Long = 1200L): String {
        return runCatching {
            connection.clearInputBuffer()
            connection.send("$command\r".toByteArray(StandardCharsets.US_ASCII))
            val deadline = System.currentTimeMillis() + timeoutMs
            val builder = StringBuilder()
            while (System.currentTimeMillis() < deadline) {
                val chunk = connection.receive(0)
                if (chunk.isEmpty()) continue
                val text = chunk.toString(StandardCharsets.US_ASCII)
                builder.append(text)
                if (text.contains(">") || builder.contains(">")) break
            }
            builder.toString().trim()
        }.onFailure {
            Log.d(TAG, "raw command failed cmd=$command err=${it.message}")
        }.getOrDefault("")
    }
}
