package com.speeduino.manager.transport

import io.ecucore.transport.toMask
import io.ecucore.transport.resolveObd2ProfileKey
import io.ecucore.transport.featuresFromMask
import io.ecucore.transport.classifyVehicleBrandHint
import io.ecucore.transport.VehicleBrandHint
import io.ecucore.transport.Obd2ProfileStore
import io.ecucore.transport.Obd2PersistedProfile
import io.ecucore.transport.Obd2OptimizationStatusBus
import io.ecucore.transport.Obd2OptimizationStatus
import io.ecucore.transport.Obd2OptimizationFeature
import io.ecucore.transport.EcuTransport
import android.util.Log
import io.ecucore.EcuLiveData
import io.ecucore.connection.ConnectionTrace
import io.ecucore.connection.IEcuConnection
import io.ecucore.ecu.FirmwareInfo
import io.ecucore.model.EcuCapabilities
import io.ecucore.model.EcuFamily
import io.ecucore.model.FirmwareEra
import io.ecucore.model.TableDefinitions
import io.ecucore.protocol.SerialCapability
import com.speeduino.manager.telemetry.ConnectionDiagnosticsLogger
import com.speeduino.manager.telemetry.Obd2InvestigationRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

class Obd2Transport(
    private val connection: IEcuConnection,
    private val onDataReceived: (EcuLiveData) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val profileStore: Obd2ProfileStore? = null,
    private val allowNonStandardPreflight: Boolean = false,
    private val enableFeatureOptimization: Boolean = true,
    private val investigationRecorder: Obd2InvestigationRecorder? = null,
) : EcuTransport {

    companion object {
        private const val TAG = "Obd2Transport"
        private const val ELM327_PROMPT = '>'
        private const val SAFE_POLL_INTERVAL_MS = 200L
        private const val MEDIUM_POLL_INTERVAL_CYCLES = 2L
        private const val SLOW_POLL_INTERVAL_CYCLES = 6L
        private const val STALE_STREAM_THRESHOLD_MS = 6_000L
        private const val PERF_LOG_WINDOW_CYCLES = 20L
        private const val DEFAULT_BATTERY_VOLTAGE = 12.0
        private val OBD2_CAPABILITIES = EcuCapabilities(
            supportsModernProtocol = false,
            supportsLegacyProtocol = false,
            supportsPageRead = false,
            supportsPageWrite = false,
            supportsBurn = false,
            supportsLiveData = true,
        )
        private val BASE_INIT_COMMANDS = listOf("ATZ", "ATL0", "ATH0")
        private const val FIRMWARE_SIGNATURE = "OBD2 ELM327"
        private const val OBD2_PRECHECK_PID = "0100"
        private val DISCOVERY_BASE_PIDS = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)
        private val BITMAP_QUERY_PIDS = setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)
        private val CORE_PIDS = setOf(0x0C, 0x05, 0x0B, 0x0F, 0x11, 0x0E, 0x42)
        private val WIDEBAND_O2_PIDS = (0x34..0x3B).toList()
        private val EQUIVALENCE_RATIO_PIDS = setOf(0x44)
        private val NARROWBAND_O2_PIDS = (0x14..0x1B).toList()
        private const val PID_DISABLE_FAILURE_THRESHOLD = 8
        private val INVESTIGATION_DISCOVERY_COMMANDS = listOf(
            "ATI",
            "AT@1",
            "ATDP",
            "ATDPN",
            "0100",
            "0120",
            "0140",
            "0160",
            "0180",
            "01A0",
            "0900",
            "0902",
            "03",
            "07",
            "0A",
        )
        private val INVESTIGATION_MIXTURE_PIDS = listOf(
            "0103",
            "0106",
            "0107",
            "0108",
            "0109",
            "0114",
            "0115",
            "0134",
            "0135",
            "0144",
        )
        private val INVESTIGATION_LIVE_DATA_PIDS = listOf(
            "0104",
            "0105",
            "010B",
            "010C",
            "010D",
            "010E",
            "010F",
            "0110",
            "0111",
            "012F",
            "0142",
        )
        private val INVESTIGATION_PROTOCOL_SWEEP = listOf("0", "6", "8", "3", "4", "5")
    }

    private data class ProfileFlags(
        val disableEcho: Boolean,
        val disableSpaces: Boolean,
        val fixedProtocol: Boolean,
        val priorityScheduler: Boolean,
        val fastTimeout: Boolean,
        val multiPidExperimental: Boolean,
        val pidTimeoutMs: Long,
        val preflightTimeoutMs: Long,
        val readLoopDelayMs: Long,
        val pollIntervalMs: Long,
    )

    private data class PreflightFailure(
        val reason: String,
        val compactResponse: String
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ioMutex = Mutex()
    private var streamJob: Job? = null
    private var optimizationJob: Job? = null
    private var streaming = false
    private var pollCycleCount = 0L
    private var connectedAtMs = 0L
    private var firmwareInfo: FirmwareInfo? = null
    private var currentFeatures: Set<Obd2OptimizationFeature> = emptySet()
    private var currentFlags: ProfileFlags = flagsForFeatures(currentFeatures)
    private var supportedMode1Pids: Set<Int> = CORE_PIDS
    private var preferredO2PidHex: String? = null
    private var profileKey: String? = null
    private var lastDataFingerprint: String = ""
    private var lastDataChangeAtMs: Long = 0L
    private var perfWindowStartAtMs: Long = 0L
    private var perfWindowBusyMs: Long = 0L
    private var perfWindowTimeouts: Int = 0
    private var perfWindowReads: Int = 0
    private var detectedVin: String? = null
    private var detectedCalibrationId: String? = null
    private var vehicleBrandHint: VehicleBrandHint = VehicleBrandHint.UNKNOWN
    private var preflightBypassReason: String? = null
    private var discoveredMode1Bitmap: Boolean = false
    private var observedMode09Unsupported: Boolean = false
    private val pidFailureStreak = mutableMapOf<String, Int>()
    private var currentLiveData = EcuLiveData(
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

    override suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            if (!connection.isConnected()) {
                connection.connect()
            }
            investigationRecorder?.startSession(
                transport = "obd2",
                metadataExtras = mapOf(
                    "connection" to connection.getConnectionInfo().trim().ifBlank { "unknown" },
                    "preflight_bypass" to allowNonStandardPreflight.toString(),
                    "campaign" to "exploratory"
                )
            )
            ensureElmInitialized()
            runObd2PreflightChecks()
            supportedMode1Pids = discoverSupportedMode1Pids()
            preferredO2PidHex = selectPreferredO2PidHex(supportedMode1Pids)
            detectedVin = readVin()?.takeIf { it.length >= 11 }
            detectedCalibrationId = readCalibrationId()?.takeIf { it.length >= 4 }
            vehicleBrandHint = classifyVehicleBrandHint(
                vin = detectedVin,
                calibrationId = detectedCalibrationId,
            )
            profileKey = resolveObd2ProfileKey(
                connectionInfo = connection.getConnectionInfo(),
                vin = detectedVin,
                calibrationId = detectedCalibrationId,
            )
            loadPersistedProfile()
            firmwareInfo = FirmwareInfo(
                signature = FIRMWARE_SIGNATURE,
                productString = "ELM327",
                era = FirmwareEra.MODERN_2025,
                family = EcuFamily.UNKNOWN,
                capabilities = OBD2_CAPABILITIES,
            )
            connectedAtMs = System.currentTimeMillis()
            pollCycleCount = 0L
            lastDataFingerprint = ""
            lastDataChangeAtMs = connectedAtMs
            perfWindowStartAtMs = connectedAtMs
            perfWindowBusyMs = 0L
            perfWindowTimeouts = 0
            perfWindowReads = 0
            onConnectionStateChanged(true)
            investigationRecorder?.updateMetadata(
                "supported_mode1_pids",
                supportedMode1Pids.map { "0x%02X".format(Locale.US, it) }
            )
            investigationRecorder?.updateMetadata("preferred_o2_pid", preferredO2PidHex ?: "")
            launchInvestigationCampaign()
            if (enableFeatureOptimization) {
                startFeatureOptimization()
            } else {
                Obd2OptimizationStatusBus.clear()
            }
        } catch (e: Exception) {
            investigationRecorder?.info(
                "connect_failure",
                "reason=${e.message ?: e.javaClass.simpleName}"
            )
            safeDisconnect()
            throw e
        }
    }

    override fun disconnect() {
        streaming = false
        streamJob?.cancel()
        streamJob = null
        optimizationJob?.cancel()
        optimizationJob = null
        Obd2OptimizationStatusBus.clear()
        safeDisconnect()
    }

    override fun isConnected(): Boolean = connection.isConnected()

    override fun isStreaming(): Boolean = streaming

    override fun startLiveDataStream(intervalMs: Long) {
        if (streaming) return
        streaming = true
        streamJob = scope.launch {
            while (isActive && streaming && connection.isConnected()) {
                val cycleStartedAt = System.currentTimeMillis()
                try {
                    pollCycle()
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    logError("poll cycle failed: ${e.message}", e, notifyUser = false)
                    if (!connection.isConnected()) {
                        safeDisconnect()
                        break
                    }
                }
                val elapsed = (System.currentTimeMillis() - cycleStartedAt).coerceAtLeast(0L)
                val sleepMs = (currentFlags.pollIntervalMs - elapsed).coerceAtLeast(0L)
                if (sleepMs > 0) delay(sleepMs)
            }
            streaming = false
        }
    }

    override fun stopLiveDataStream() {
        streaming = false
        streamJob?.cancel()
        streamJob = null
    }

    override suspend fun pauseLiveDataStream(timeoutMs: Long) {
        streaming = false
        val job = streamJob
        streamJob = null
        if (job != null) {
            job.cancelAndJoin()
        }
        runCatching { connection.clearInputBuffer() }
    }

    override suspend fun getFirmwareInfo(): String = firmwareInfo?.signature ?: FIRMWARE_SIGNATURE

    override fun getFirmwareInfoCached(): FirmwareInfo? = firmwareInfo

    override fun getEcuFamily(): EcuFamily = firmwareInfo?.family ?: EcuFamily.UNKNOWN

    override fun getEcuCapabilities(): EcuCapabilities = OBD2_CAPABILITIES

    fun getVehicleBrandHint(): VehicleBrandHint = vehicleBrandHint

    fun getDetectedVin(): String? = detectedVin

    fun shouldPromoteToPsa(): Boolean {
        if (vehicleBrandHint == VehicleBrandHint.PSA) {
            return true
        }
        val bypassLooksPsaLike = preflightBypassReason == "SERVICE_NOT_SUPPORTED_7F0111" ||
            preflightBypassReason == "NO DATA" ||
            preflightBypassReason == "CAN ERROR"
        val noIdentity = detectedVin.isNullOrBlank() &&
            (detectedCalibrationId.isNullOrBlank() || observedMode09Unsupported)
        val weakGenericProfile = !discoveredMode1Bitmap ||
            (supportedMode1Pids == CORE_PIDS && preferredO2PidHex == null)
        val psaLikeUnsupportedIdentity = observedMode09Unsupported && weakGenericProfile && noIdentity
        return (bypassLooksPsaLike && noIdentity && weakGenericProfile) || psaLikeUnsupportedIdentity
    }

    override fun getTableDefinitions(): TableDefinitions? = null

    override suspend fun getSerialCapability(): SerialCapability =
        SerialCapability(protocolVersion = 1, blockingFactor = 64, tableBlockingFactor = 64)

    private suspend fun ensureElmInitialized() {
        ioMutex.withLock {
            runCatching { connection.clearInputBuffer() }
            for (command in BASE_INIT_COMMANDS) {
                initializeCommand(command, required = command != "ATZ")
            }
            applyOptimizationConfigLocked(emptySet())
            currentFeatures = emptySet()
            currentFlags = flagsForFeatures(currentFeatures)
            supportedMode1Pids = CORE_PIDS
            preferredO2PidHex = null
            profileKey = null
            detectedVin = null
            detectedCalibrationId = null
            vehicleBrandHint = VehicleBrandHint.UNKNOWN
            preflightBypassReason = null
            discoveredMode1Bitmap = false
            observedMode09Unsupported = false
            pidFailureStreak.clear()
        }
    }

    private suspend fun initializeCommand(command: String, required: Boolean = true) {
        var success = false
        repeat(2) { attempt ->
            val response = runCatching { sendCommand(command, timeoutMs = if (command == "ATZ") 1800 else 1200) }
                .onFailure { error ->
                    logError(
                        "init command failed cmd=$command attempt=${attempt + 1}: ${error.message}",
                        error,
                        notifyUser = false
                    )
                }
                .getOrNull()
            if (response != null && isSuccessfulInitResponse(command, response)) {
                success = true
                return
            }
            runCatching { connection.clearInputBuffer() }
            delay(if (command == "ATZ") 600 else 120)
        }
        if (!success) {
            logInfo("init command proceeding without OK cmd=$command required=$required")
            investigationRecorder?.info(
                "init_tolerated",
                "cmd=$command required=$required"
            )
            if (required) {
                runCatching { connection.clearInputBuffer() }
            }
        }
    }

    private fun isSuccessfulInitResponse(command: String, response: String): Boolean {
        val normalized = response.uppercase(Locale.US)
        if ("OK" in normalized) return true
        if (command == "ATZ" && normalized.isNotBlank()) return true
        return false
    }

    private suspend fun pollCycle() {
        pollCycleCount++
        var rpm: Int? = null
        var coolant: Int? = null
        var map: Int? = null
        var intake: Int? = null
        var throttle: Int? = null
        var advance: Int? = null
        var battery: Double? = null
        var o2AfrX10: Int? = null

        if (currentFlags.multiPidExperimental && canUseExperimentalBatch()) {
            val batch = readPidBatchExperimental()
            rpm = batch["010C"]?.let { bytes -> (((bytes[0] shl 8) or bytes[1]).toDouble() / 4.0).roundToInt() }
            coolant = batch["0105"]?.let { bytes -> bytes[0] - 40 }
            map = batch["010B"]?.let { bytes -> bytes[0] }
            intake = batch["010F"]?.let { bytes -> bytes[0] - 40 }
            throttle = batch["0111"]?.let { bytes -> ((bytes[0] * 100.0) / 255.0).roundToInt() }
            advance = batch["010E"]?.let { bytes -> bytes[0] - 64 }
            battery = batch["0142"]?.let { bytes ->
                (((bytes[0] shl 8) or bytes[1]).toDouble() / 1000.0)
            }
        }

        if (shouldPoll("010C", 1L)) {
            rpm = rpm ?: readPidAdaptiveIntValue("010C") { bytes ->
                (((bytes[0] shl 8) or bytes[1]).toDouble() / 4.0).roundToInt()
            }
        }
        if (shouldPoll("0105", MEDIUM_POLL_INTERVAL_CYCLES)) {
            coolant = coolant ?: readPidAdaptiveIntValue("0105") { bytes -> bytes[0] - 40 }
        }
        if (shouldPoll("010B", 1L)) {
            map = map ?: readPidAdaptiveIntValue("010B") { bytes -> bytes[0] }
        }
        if (shouldPoll("010F", MEDIUM_POLL_INTERVAL_CYCLES)) {
            intake = intake ?: readPidAdaptiveIntValue("010F") { bytes -> bytes[0] - 40 }
        }
        if (shouldPoll("0111", 1L)) {
            throttle = throttle ?: readPidAdaptiveIntValue("0111") { bytes -> ((bytes[0] * 100.0) / 255.0).roundToInt() }
        }
        if (shouldPoll("010E", MEDIUM_POLL_INTERVAL_CYCLES)) {
            advance = advance ?: readPidAdaptiveIntValue("010E") { bytes -> bytes[0] - 64 }
        }

        if (shouldPoll("0142", SLOW_POLL_INTERVAL_CYCLES)) {
            battery = battery ?: readPidAdaptiveDoubleValue("0142") { bytes ->
                (((bytes[0] shl 8) or bytes[1]).toDouble() / 1000.0)
            }
        }

        if (shouldPoll("0104", MEDIUM_POLL_INTERVAL_CYCLES)) {
            readPidAdaptiveIntValue("0104") { bytes -> ((bytes[0] * 100.0) / 255.0).roundToInt() }
        }
        if (shouldPoll("010D", MEDIUM_POLL_INTERVAL_CYCLES)) {
            readPidAdaptiveIntValue("010D") { bytes -> bytes[0] }
        }
        if (shouldPoll("0110", SLOW_POLL_INTERVAL_CYCLES)) {
            readPidAdaptiveDoubleValue("0110") { bytes ->
                (((bytes[0] shl 8) or bytes[1]).toDouble() / 100.0)
            }
        }
        if (shouldPoll("012F", SLOW_POLL_INTERVAL_CYCLES)) {
            readPidAdaptiveIntValue("012F") { bytes -> ((bytes[0] * 100.0) / 255.0).roundToInt() }
        }
        if (shouldPoll("0106", SLOW_POLL_INTERVAL_CYCLES)) {
            readPidAdaptiveDoubleValue("0106") { bytes -> (bytes[0] - 128) * 100.0 / 128.0 }
            readPidAdaptiveDoubleValue("0107") { bytes -> (bytes[0] - 128) * 100.0 / 128.0 }
            readPidAdaptiveDoubleValue("0108") { bytes -> (bytes[0] - 128) * 100.0 / 128.0 }
            readPidAdaptiveDoubleValue("0109") { bytes -> (bytes[0] - 128) * 100.0 / 128.0 }
        }

        if (preferredO2PidHex == null && supportedMode1Pids.none { it in WIDEBAND_O2_PIDS || it in NARROWBAND_O2_PIDS }) {
            probePreferredO2Pid()
        }

        preferredO2PidHex?.let { command ->
            if (shouldPoll(command, SLOW_POLL_INTERVAL_CYCLES)) {
                o2AfrX10 = readO2AfrX10(command)
            }
        }

        rpm = rpm ?: readPidAdaptiveIntValue("010C") { bytes ->
            (((bytes[0] shl 8) or bytes[1]).toDouble() / 4.0).roundToInt()
        }
        coolant = coolant ?: readPidAdaptiveIntValue("0105") { bytes -> bytes[0] - 40 }
        map = map ?: readPidAdaptiveIntValue("010B") { bytes -> bytes[0] }
        intake = intake ?: readPidAdaptiveIntValue("010F") { bytes -> bytes[0] - 40 }
        throttle = throttle ?: readPidAdaptiveIntValue("0111") { bytes -> ((bytes[0] * 100.0) / 255.0).roundToInt() }
        advance = advance ?: readPidAdaptiveIntValue("010E") { bytes -> bytes[0] - 64 }

        val updated = currentLiveData.copy(
            secl = ((System.currentTimeMillis() - connectedAtMs) / 1000L).toInt(),
            rpm = rpm ?: currentLiveData.rpm,
            coolantTemp = coolant ?: currentLiveData.coolantTemp,
            intakeTemp = intake ?: currentLiveData.intakeTemp,
            mapPressure = (map ?: currentLiveData.mapPressure).coerceIn(0, 255),
            tps = throttle ?: currentLiveData.tps,
            batteryVoltage = battery ?: currentLiveData.batteryVoltage,
            advance = advance ?: currentLiveData.advance,
            o2 = o2AfrX10 ?: currentLiveData.o2,
            engineStatus = if ((rpm ?: currentLiveData.rpm) > 0) 0x01 else 0x00,
            sparkStatus = if ((rpm ?: currentLiveData.rpm) > 0) 0x01 else 0x00,
        )
        currentLiveData = updated
        onDataReceived(updated)
        investigationRecorder?.recordSample(
            source = "obd2_poll",
            sample = updated,
            extras = mapOf(
                "poll_cycle" to pollCycleCount.toString(),
                "preferred_o2_pid" to (preferredO2PidHex ?: "")
            )
        )
        checkAndHandleStaleStream(updated)
        maybeLogPollPerformance()
    }

    private suspend fun readPidBatchExperimental(): Map<String, List<Int>> {
        return runCatching {
            val command = "010C0105010B010F0111010E0142"
            val response = ioMutex.withLock { sendCommand(command, timeoutMs = currentFlags.pidTimeoutMs) }
            parsePidMap(response, mapOf("0C" to 2, "05" to 1, "0B" to 1, "0F" to 1, "11" to 1, "0E" to 1, "42" to 2))
                .mapKeys { (pid, _) -> "01$pid" }
        }.getOrElse {
            emptyMap()
        }
    }

    private suspend fun runObd2PreflightChecks() {
        val attempts = listOf(
            "default" to emptyList<String>(),
            "fallback_kwp_5baud" to listOf("ATD", "ATE0", "ATS0", "ATL0", "ATH0", "ATSP4", "ATSI", "ATAT1", "ATST20"),
            "fallback_kwp_5baud_96" to listOf("ATD", "ATE0", "ATS0", "ATL0", "ATH0", "ATSP4", "ATSI", "ATAT1", "ATST96"),
            "fallback_kwp_fast" to listOf("ATD", "ATE0", "ATS0", "ATL0", "ATH0", "ATSP5", "ATFI", "ATAT1", "ATST20"),
            "fallback_kwp_fast_96" to listOf("ATD", "ATE0", "ATS0", "ATL0", "ATH0", "ATSP5", "ATFI", "ATAT1", "ATST96"),
            "fallback_can11_500" to listOf("ATD", "ATE0", "ATS0", "ATL0", "ATH0", "ATSP6", "ATAT1", "ATST20"),
            "fallback_can11_500_7df" to
                listOf("ATD", "ATE0", "ATS0", "ATL0", "ATH0", "ATSP6", "ATSH7DF", "ATAT1", "ATST20")
        )
        val attemptSummaries = mutableListOf<String>()
        var lastFailure: PreflightFailure? = null

        ioMutex.withLock {
            for ((label, setupCommands) in attempts) {
                for (command in setupCommands) {
                    initializeCommand(command)
                }

                val response = try {
                    sendCommand(OBD2_PRECHECK_PID, timeoutMs = currentFlags.preflightTimeoutMs)
                } catch (error: Exception) {
                    val message = (error.message ?: error.javaClass.simpleName).take(80)
                    val failure = PreflightFailure(reason = "IO_EXCEPTION", compactResponse = message)
                    attemptSummaries += "$label:IO_EXCEPTION"
                    lastFailure = failure
                    ConnectionDiagnosticsLogger.log(
                        "elm327",
                        "probe",
                        "preflight pid=0100 failed profile=$label reason=${failure.reason} response=${failure.compactResponse}"
                    )
                    continue
                }

                val hasPidSupportResponse =
                    parsePidBytes(command = OBD2_PRECHECK_PID, response = response, dataLength = 4) != null
                if (hasPidSupportResponse) {
                    val suffix = if (label == "default") "" else " profile=$label"
                    ConnectionDiagnosticsLogger.log("elm327", "probe", "preflight pid=0100 ok$suffix")
                    return
                }

                val failure = classifyPreflightFailure(response)
                attemptSummaries += "$label:${failure.reason}"
                lastFailure = failure
                ConnectionDiagnosticsLogger.log(
                    "elm327",
                    "probe",
                    "preflight pid=0100 failed profile=$label reason=${failure.reason} response=${failure.compactResponse}"
                )
            }
        }

        if (runChryslerKwp5baudPreflight()) {
            ConnectionDiagnosticsLogger.log(
                "elm327",
                "probe",
                "preflight chrysler kwp5baud ok"
            )
            return
        }

        val fallbackSummary = attemptSummaries.joinToString(" -> ")
        val reason = lastFailure?.reason ?: "INVALID_RESPONSE"
        val compactResponse = lastFailure?.compactResponse ?: "sem resposta útil"

        if (allowNonStandardPreflight && shouldBypassPreflightFailure(reason)) {
            preflightBypassReason = reason
            ConnectionDiagnosticsLogger.log(
                "elm327",
                "probe",
                "preflight pid=0100 bypassed reason=$reason response=$compactResponse"
            )
            return
        }

        throw IllegalStateException(
            "Adaptador ELM327 conectado, mas sem comunicação OBD2 válida (PID 0100: $reason). " +
                "Resposta: $compactResponse. Perfis testados: $fallbackSummary"
        )
    }

    private suspend fun runChryslerKwp5baudPreflight(): Boolean {
        val attempts = listOf(
            "chrysler_kwp5baud_96" to listOf(
                "ATD", "ATE0", "ATS0", "ATL0", "ATH0",
                "ATSP4", "ATIB96", "ATIIA13", "ATSW05", "ATWM221201",
                "ATAT1", "ATAL", "ATST96"
            ),
            "chrysler_kwp5baud_20" to listOf(
                "ATD", "ATE0", "ATS0", "ATL0", "ATH0",
                "ATSP4", "ATIB96", "ATIIA13", "ATSW05", "ATWM221201",
                "ATAT1", "ATAL", "ATST20"
            ),
            "chrysler_kwp5baud_fast" to listOf(
                "ATD", "ATE0", "ATS0", "ATL0", "ATH0",
                "ATSP5", "ATIB10", "ATIIA13", "ATSW05", "ATWM221201",
                "ATAT1", "ATAL", "ATST96"
            )
        )

        fun looksPositive(response: String): Boolean {
            val normalized = response.uppercase(Locale.US)
            return normalized.contains("49 02") ||
                normalized.contains("49 00") ||
                normalized.contains("62 12 01") ||
                ("NO DATA" !in normalized && "BUS INIT" !in normalized && "CAN ERROR" !in normalized && "ERROR" !in normalized)
        }

        ioMutex.withLock {
            for ((label, setupCommands) in attempts) {
                for (command in setupCommands) {
                    initializeCommand(command)
                }

                for (probe in listOf("221201", "0902")) {
                    val response = try {
                        sendCommand(probe, timeoutMs = currentFlags.preflightTimeoutMs + 600L)
                    } catch (error: Exception) {
                        ConnectionDiagnosticsLogger.log(
                            "elm327",
                            "probe",
                            "preflight chrysler failed profile=$label probe=$probe error=${error.message ?: error.javaClass.simpleName}"
                        )
                        continue
                    }

                    if (looksPositive(response)) {
                        ConnectionDiagnosticsLogger.log(
                            "elm327",
                            "probe",
                            "preflight chrysler ok profile=$label probe=$probe"
                        )
                        return true
                    }

                    val compact = response
                        .replace("\r", " ")
                        .replace("\n", " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(80)
                        .ifBlank { "sem resposta útil" }
                    ConnectionDiagnosticsLogger.log(
                        "elm327",
                        "probe",
                        "preflight chrysler failed profile=$label probe=$probe response=$compact"
                    )
                }
            }
        }

        return false
    }

    private fun shouldBypassPreflightFailure(reason: String): Boolean {
        return reason == "SERVICE_NOT_SUPPORTED_7F0111" ||
            reason == "SEARCHING_WITHOUT_DATA" ||
            reason == "NO DATA"
    }

    private fun classifyPreflightFailure(response: String): PreflightFailure {
        val normalized = response.uppercase(Locale.US)
        val compactResponse = response
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifBlank { "sem resposta útil" }
        val compactUpper = compactResponse.uppercase(Locale.US)

        val reason = when {
            Regex("\\b7F\\s*01\\s*11\\b").containsMatchIn(compactUpper) -> "SERVICE_NOT_SUPPORTED_7F0111"
            "NO DATA" in normalized -> "NO DATA"
            "UNABLE TO CONNECT" in normalized -> "UNABLE TO CONNECT"
            "BUS INIT" in normalized -> "BUS INIT"
            "CAN ERROR" in normalized -> "CAN ERROR"
            "ERROR" in normalized -> "ERROR"
            "SEARCHING" in normalized -> "SEARCHING_WITHOUT_DATA"
            else -> "INVALID_RESPONSE"
        }

        return PreflightFailure(reason = reason, compactResponse = compactResponse)
    }

    private suspend fun discoverSupportedMode1Pids(): Set<Int> {
        val discovered = mutableSetOf<Int>()

        for (base in DISCOVERY_BASE_PIDS) {
            val command = "01%02X".format(Locale.US, base)
            val block = try {
                val response = ioMutex.withLock { sendCommand(command, timeoutMs = currentFlags.preflightTimeoutMs) }
                parsePidBytes(command = command, response = response, dataLength = 4)
            } catch (_: Exception) {
                break
            } ?: break

            val raw = ((block[0] and 0xFF) shl 24) or
                ((block[1] and 0xFF) shl 16) or
                ((block[2] and 0xFF) shl 8) or
                (block[3] and 0xFF)

            for (bit in 0 until 32) {
                if ((raw and (1 shl (31 - bit))) != 0) {
                    discovered.add(base + bit + 1)
                }
            }
            if ((raw and 0x1) == 0) {
                break
            }
        }

        if (discovered.isEmpty()) {
            discoveredMode1Bitmap = false
            return CORE_PIDS
        }
        discoveredMode1Bitmap = true
        return (discovered + CORE_PIDS).toSet()
    }

    private fun selectPreferredO2PidHex(supportedPids: Set<Int>): String? {
        val wideband = WIDEBAND_O2_PIDS.firstOrNull { it in supportedPids }
        if (wideband != null) {
            return "01%02X".format(Locale.US, wideband)
        }
        val equivalenceRatio = EQUIVALENCE_RATIO_PIDS.firstOrNull { it in supportedPids }
        if (equivalenceRatio != null) {
            return "01%02X".format(Locale.US, equivalenceRatio)
        }
        val narrowband = NARROWBAND_O2_PIDS.firstOrNull { it in supportedPids }
        return narrowband?.let { "01%02X".format(Locale.US, it) }
    }

    private suspend fun readVin(): String? {
        val response = runCatching {
            ioMutex.withLock { sendCommand("0902", timeoutMs = currentFlags.preflightTimeoutMs + 1200L) }
        }.getOrNull() ?: return null
        val normalized = response.uppercase(Locale.US)
        if (Regex("\\b7F\\s*09\\s*11\\b").containsMatchIn(normalized)) {
            observedMode09Unsupported = true
        }

        val chars = Regex("[0-9A-F]{2}")
            .findAll(normalized)
            .mapNotNull { token ->
                val byte = token.value.toInt(16)
                val ch = byte.toChar()
                if (ch in '0'..'9' || ch in 'A'..'Z') ch else null
            }
            .joinToString("")

        return Regex("[A-HJ-NPR-Z0-9]{11,17}")
            .find(chars)
            ?.value
    }

    private suspend fun readCalibrationId(): String? {
        val response = runCatching {
            ioMutex.withLock { sendCommand("0904", timeoutMs = currentFlags.preflightTimeoutMs + 1200L) }
        }.getOrNull() ?: return null
        val normalized = response.uppercase(Locale.US)
        if (Regex("\\b7F\\s*09\\s*11\\b").containsMatchIn(normalized) || normalized.contains("CAN ERROR")) {
            observedMode09Unsupported = true
        }

        val chars = Regex("[0-9A-F]{2}")
            .findAll(normalized)
            .mapNotNull { token ->
                val byte = token.value.toInt(16)
                val ch = byte.toChar()
                if (ch in '0'..'9' || ch in 'A'..'Z') ch else null
            }
            .joinToString("")

        return chars
            .trim()
            .takeIf { it.length >= 4 }
            ?.take(32)
    }

    private suspend fun loadPersistedProfile() {
        val key = profileKey ?: return
        val persisted = profileStore?.load(key) ?: return
        val resolvedFeatures = featuresFromMask(persisted.mask)
        if (resolvedFeatures.isEmpty()) return

        runCatching {
            ioMutex.withLock {
                applyOptimizationConfigLocked(resolvedFeatures)
                currentFeatures = resolvedFeatures
                currentFlags = flagsForFeatures(resolvedFeatures)
            }
        }.onSuccess {
            if (persisted.supportedPids.isNotEmpty()) {
                supportedMode1Pids = supportedMode1Pids + persisted.supportedPids
            }
            if (persisted.preferredO2Pid != null) {
                preferredO2PidHex = "01%02X".format(Locale.US, persisted.preferredO2Pid)
            }
            logInfo("loaded persisted OBD2 profile key=$key mask=${persisted.mask}")
        }
    }

    private fun persistCurrentProfile() {
        val key = profileKey ?: return
        profileStore?.save(
            key,
            Obd2PersistedProfile(
                mask = currentFeatures.toMask(),
                supportedPids = supportedMode1Pids,
                preferredO2Pid = preferredO2PidHex?.removePrefix("01")?.toIntOrNull(16)
            )
        )
    }

    private suspend fun readPidValue(command: String, parser: (List<Int>) -> Int): Int? {
        return runCatching {
            val response = ioMutex.withLock { sendCommand(command, timeoutMs = currentFlags.pidTimeoutMs) }
            val bytes = parsePidBytes(command, response) ?: return null
            val value = parser(bytes)
            logInfo("parsed pid=$command value=$value")
            value
        }.onFailure { error ->
            logError("pid read failed pid=$command: ${error.message}", error, notifyUser = false)
        }.getOrNull()
    }

    private suspend fun readPidDoubleValue(command: String, parser: (List<Int>) -> Double): Double? {
        return runCatching {
            val response = ioMutex.withLock { sendCommand(command, timeoutMs = currentFlags.pidTimeoutMs) }
            val bytes = parsePidBytes(command, response) ?: return null
            val value = parser(bytes)
            logInfo("parsed pid=$command value=$value")
            value
        }.onFailure { error ->
            logError("pid read failed pid=$command: ${error.message}", error, notifyUser = false)
        }.getOrNull()
    }

    private suspend fun readPidAdaptiveIntValue(command: String, parser: (List<Int>) -> Int): Int? {
        if (!isPidSupported(command)) return null
        val value = readPidValue(command, parser)
        updatePidFailureStreak(command, value != null)
        return value
    }

    private suspend fun readPidAdaptiveDoubleValue(command: String, parser: (List<Int>) -> Double): Double? {
        if (!isPidSupported(command)) return null
        val value = readPidDoubleValue(command, parser)
        updatePidFailureStreak(command, value != null)
        return value
    }

    private fun learnSupportedPidIfPositive(command: String, response: String) {
        val pid = command.removePrefix("01").toIntOrNull(16) ?: return
        if (pid in BITMAP_QUERY_PIDS) return
        if (pid in supportedMode1Pids) return

        if (parsePidBytes(command, response) == null) return

        supportedMode1Pids = supportedMode1Pids + pid
        preferredO2PidHex = selectPreferredO2PidHex(supportedMode1Pids)
        persistCurrentProfile()
        investigationRecorder?.updateMetadata(
            "supported_mode1_pids",
            supportedMode1Pids.map { "0x%02X".format(Locale.US, it) }
        )
        investigationRecorder?.updateMetadata("preferred_o2_pid", preferredO2PidHex ?: "")
        ConnectionDiagnosticsLogger.log(
            "elm327",
            "learned_support",
            "command=$command pid=0x%02X".format(Locale.US, pid)
        )
        logInfo("learned supported pid=$command from positive response")
    }

    private suspend fun readO2AfrX10(command: String): Int? {
        return when {
            command.removePrefix("01").toIntOrNull(16) in EQUIVALENCE_RATIO_PIDS -> {
                readPidAdaptiveIntValue(command) { bytes ->
                    if (bytes.size < 2) return@readPidAdaptiveIntValue currentLiveData.o2
                    val lambda = ((bytes[0] shl 8) or bytes[1]).toDouble() / 32768.0
                    (lambda * 14.7 * 10.0).roundToInt().coerceIn(80, 220)
                }
            }

            command.removePrefix("01").toIntOrNull(16) in WIDEBAND_O2_PIDS -> {
                readPidAdaptiveIntValue(command) { bytes ->
                    if (bytes.size < 4) return@readPidAdaptiveIntValue currentLiveData.o2
                    val lambda = ((bytes[0] shl 8) or bytes[1]).toDouble() / 32768.0
                    (lambda * 14.7 * 10.0).roundToInt().coerceIn(80, 220)
                }
            }

            command.removePrefix("01").toIntOrNull(16) in NARROWBAND_O2_PIDS -> {
                readPidAdaptiveIntValue(command) { bytes ->
                    if (bytes.size < 2) return@readPidAdaptiveIntValue currentLiveData.o2
                    val voltage = bytes[0] * 0.005
                    val afr = (14.7 + ((voltage - 0.45) * 6.0)).coerceIn(10.0, 19.9)
                    (afr * 10.0).roundToInt()
                }
            }

            else -> null
        }
    }

    private suspend fun probePreferredO2Pid(): String? {
        if (supportedMode1Pids.any { it in WIDEBAND_O2_PIDS || it in NARROWBAND_O2_PIDS }) {
            preferredO2PidHex = selectPreferredO2PidHex(supportedMode1Pids)
            return preferredO2PidHex
        }

        val probeCommands = listOf("0144", "0134", "0114")
        for (command in probeCommands) {
            val value = readPidValue(command) { bytes ->
                when {
                    command.removePrefix("01").toIntOrNull(16) in EQUIVALENCE_RATIO_PIDS -> {
                        if (bytes.size < 2) return@readPidValue currentLiveData.o2
                        val lambda = ((bytes[0] shl 8) or bytes[1]).toDouble() / 32768.0
                        (lambda * 14.7 * 10.0).roundToInt().coerceIn(80, 220)
                    }

                    command.removePrefix("01").toIntOrNull(16) in WIDEBAND_O2_PIDS -> {
                        if (bytes.size < 4) return@readPidValue currentLiveData.o2
                        val lambda = ((bytes[0] shl 8) or bytes[1]).toDouble() / 32768.0
                        (lambda * 14.7 * 10.0).roundToInt().coerceIn(80, 220)
                    }

                    command.removePrefix("01").toIntOrNull(16) in NARROWBAND_O2_PIDS -> {
                        if (bytes.size < 2) return@readPidValue currentLiveData.o2
                        val voltage = bytes[0] * 0.005
                        val afr = (14.7 + ((voltage - 0.45) * 6.0)).coerceIn(10.0, 19.9)
                        (afr * 10.0).roundToInt()
                    }

                    else -> currentLiveData.o2
                }
            }
            if (value != null) {
                return preferredO2PidHex ?: command
            }
        }
        return preferredO2PidHex
    }

    private fun shouldPoll(command: String, baseEveryCycles: Long): Boolean {
        if (!isPidSupported(command)) return false
        val failureStreak = pidFailureStreak[command] ?: 0
        val multiplier = when {
            failureStreak >= 6 -> 4L
            failureStreak >= 3 -> 2L
            else -> 1L
        }
        val effectiveEveryCycles = (baseEveryCycles * multiplier).coerceAtLeast(1L)
        return pollCycleCount % effectiveEveryCycles == 0L
    }

    private fun isPidSupported(command: String): Boolean {
        val pid = command.removePrefix("01").toIntOrNull(16) ?: return true
        return pid in supportedMode1Pids
    }

    private fun canUseExperimentalBatch(): Boolean {
        val required = listOf("010C", "0105", "010B", "010F", "0111", "010E", "0142")
        return required.all { isPidSupported(it) }
    }

    private fun updatePidFailureStreak(command: String, success: Boolean) {
        if (success) {
            pidFailureStreak.remove(command)
        } else {
            val streak = (pidFailureStreak[command] ?: 0) + 1
            pidFailureStreak[command] = streak
            maybeDisableUnstablePid(command, streak)
        }
    }

    private fun maybeDisableUnstablePid(command: String, streak: Int) {
        if (streak < PID_DISABLE_FAILURE_THRESHOLD) return
        val pid = command.removePrefix("01").toIntOrNull(16) ?: return
        if (pid !in supportedMode1Pids) return

        supportedMode1Pids = supportedMode1Pids - pid
        pidFailureStreak.remove(command)

        val preferredPid = preferredO2PidHex?.removePrefix("01")?.toIntOrNull(16)
        if (preferredPid == pid) {
            preferredO2PidHex = selectPreferredO2PidHex(supportedMode1Pids)
        }

        ConnectionDiagnosticsLogger.log(
            "elm327",
            "adaptive_support",
            "pid_disabled command=$command streak=$streak"
        )
    }

    private suspend fun sendCommand(command: String, timeoutMs: Long): String {
        val startedAt = System.currentTimeMillis()
        val payload = "$command\r".toByteArray(StandardCharsets.US_ASCII)
        logInfo("command sent: $command")
        ConnectionTrace.tx("elm327", payload)
        ConnectionDiagnosticsLogger.log("elm327", "tx", command)
        connection.send(payload)
        if (command == "ATZ") {
            delay(1000)
        }
        val response = readResponse(timeoutMs)
        perfWindowBusyMs += (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        perfWindowReads += 1
        if (response.isBlank() || response.uppercase(Locale.US).contains("NO DATA")) {
            perfWindowTimeouts += 1
        }
        if (command.startsWith("01")) {
            learnSupportedPidIfPositive(command, response)
        }
        logInfo("response received: ${response.replace("\r", "\\r").replace("\n", "\\n")}")
        ConnectionDiagnosticsLogger.log("elm327", "rx", response.take(160))
        investigationRecorder?.recordCommand(
            transport = "elm327",
            command = command,
            response = response,
            timeoutMs = timeoutMs,
            elapsedMs = System.currentTimeMillis() - startedAt,
            extra = mapOf(
                "profile_key" to (profileKey ?: ""),
                "supported" to isPidSupported(command).toString()
            )
        )
        return response
    }

    suspend fun sendRawElmCommand(command: String, timeoutMs: Long = 1200L): String {
        return ioMutex.withLock { sendCommand(command, timeoutMs = timeoutMs) }
    }

    private suspend fun readResponse(timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val builder = StringBuilder()

        while (System.currentTimeMillis() < deadline) {
            val chunk = runCatching { connection.receive(0) }.getOrNull()
            if (chunk == null || chunk.isEmpty()) {
                delay(currentFlags.readLoopDelayMs)
                continue
            }
            val text = chunk.toString(StandardCharsets.US_ASCII)
            builder.append(text)
            if (builder.contains(ELM327_PROMPT)) {
                break
            }
            delay(currentFlags.readLoopDelayMs)
        }

        return builder.toString()
            .replace('\u0000', ' ')
            .trim()
    }

    private fun parsePidBytes(command: String, response: String): List<Int>? {
        val dataLength = when (command) {
            "0100" -> 4
            "010C" -> 2
            "0110" -> 2
            "0142" -> 2
            "0144" -> 2
            "0134", "0135", "0136", "0137", "0138", "0139", "013A", "013B" -> 4
            "0114", "0115", "0116", "0117", "0118", "0119", "011A", "011B" -> 2
            "0104", "0105", "0106", "0107", "0108", "0109", "010B", "010D", "010F", "0111", "010E", "012F" -> 1
            else -> 0
        }
        return parsePidBytes(command = command, response = response, dataLength = dataLength)
    }

    private fun parsePidBytes(command: String, response: String, dataLength: Int): List<Int>? {
        val expectedPid = command.removePrefix("01").uppercase(Locale.US)
        val bytes = Regex("[0-9A-F]{2}")
            .findAll(response.uppercase(Locale.US))
            .map { it.value.toInt(16) }
            .toList()

        val headerIndex = bytes.windowed(2, 1).indexOfFirst { it[0] == 0x41 && it[1] == expectedPid.toInt(16) }
        if (headerIndex < 0) {
            return null
        }

        val dataStart = headerIndex + 2
        if (dataStart + dataLength > bytes.size) {
            return null
        }
        return bytes.subList(dataStart, dataStart + dataLength)
    }

    private fun parsePidMap(response: String, expectedLengths: Map<String, Int>): Map<String, List<Int>> {
        val bytes = Regex("[0-9A-F]{2}")
            .findAll(response.uppercase(Locale.US))
            .map { it.value.toInt(16) }
            .toList()
        if (bytes.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, List<Int>>()
        var index = 0
        while (index < bytes.size - 1) {
            if (bytes[index] != 0x41) {
                index++
                continue
            }
            val pidHex = "%02X".format(bytes[index + 1])
            val dataLength = expectedLengths[pidHex]
            if (dataLength == null) {
                index += 2
                continue
            }
            val dataStart = index + 2
            val dataEnd = dataStart + dataLength
            if (dataEnd <= bytes.size) {
                result[pidHex] = bytes.subList(dataStart, dataEnd)
                index = dataEnd
            } else {
                break
            }
        }
        return result
    }

    private fun safeDisconnect() {
        investigationRecorder?.closeSession(
            summary = mapOf(
                "profile_key" to (profileKey ?: ""),
                "supported_pids" to supportedMode1Pids.size.toString(),
                "preferred_o2_pid" to (preferredO2PidHex ?: "")
            )
        )
        runCatching { connection.disconnect() }
        optimizationJob?.cancel()
        optimizationJob = null
        Obd2OptimizationStatusBus.clear()
        firmwareInfo = null
        connectedAtMs = 0L
        pollCycleCount = 0L
        currentFeatures = emptySet()
        currentFlags = flagsForFeatures(currentFeatures)
        supportedMode1Pids = CORE_PIDS
        preferredO2PidHex = null
        profileKey = null
        pidFailureStreak.clear()
    }

    private fun launchInvestigationCampaign() {
        if (investigationRecorder == null) return
        scope.launch {
            runCatching {
                investigationRecorder.updateMetadata("investigation_mode", "exploratory")
                investigationRecorder.info("campaign", "starting obd2 exploratory sweep")

                runExplorationBatch(
                    label = "discovery",
                    commands = INVESTIGATION_DISCOVERY_COMMANDS,
                    timeoutMs = currentFlags.pidTimeoutMs + 400L,
                )
                runProtocolExplorationSweep()
                runExplorationBatch(
                    label = "live_data",
                    commands = INVESTIGATION_LIVE_DATA_PIDS,
                    timeoutMs = currentFlags.pidTimeoutMs + 300L,
                )
                runExplorationBatch(
                    label = "mixture",
                    commands = INVESTIGATION_MIXTURE_PIDS,
                    timeoutMs = currentFlags.pidTimeoutMs + 300L,
                )

                investigationRecorder.info("campaign", "finished obd2 exploratory sweep")
                ioMutex.withLock {
                    runCatching { applyOptimizationConfigLocked(currentFeatures) }
                }
            }.onFailure { error ->
                investigationRecorder.info(
                    "campaign",
                    "aborted reason=${error.message ?: error.javaClass.simpleName}"
                )
            }
        }
    }

    private suspend fun runExplorationBatch(
        label: String,
        commands: List<String>,
        timeoutMs: Long,
    ) {
        investigationRecorder?.info("campaign", "batch=$label commands=${commands.joinToString(" ")}")
        commands.forEach { command ->
            runCatching {
                sendRawElmCommand(command, timeoutMs = timeoutMs)
            }.onFailure { error ->
                investigationRecorder?.info(
                    "campaign",
                    "batch=$label cmd=$command failed reason=${error.message ?: error.javaClass.simpleName}"
                )
            }
        }
    }

    private suspend fun runProtocolExplorationSweep() {
        investigationRecorder?.info(
            "campaign",
            "batch=protocol_sweep candidates=${INVESTIGATION_PROTOCOL_SWEEP.joinToString(",")}"
        )
        INVESTIGATION_PROTOCOL_SWEEP.forEach { protocol ->
            runCatching {
                sendRawElmCommand("ATSP$protocol", timeoutMs = currentFlags.pidTimeoutMs + 400L)
                sendRawElmCommand("ATDP", timeoutMs = currentFlags.pidTimeoutMs + 400L)
                sendRawElmCommand("0100", timeoutMs = currentFlags.pidTimeoutMs + 400L)
                sendRawElmCommand("0902", timeoutMs = currentFlags.pidTimeoutMs + 400L)
            }.onFailure { error ->
                investigationRecorder?.info(
                    "campaign",
                    "batch=protocol_sweep protocol=$protocol failed reason=${error.message ?: error.javaClass.simpleName}"
                )
            }
        }
    }

    private fun startFeatureOptimization() {
        optimizationJob?.cancel()
        optimizationJob = scope.launch {
            val orderedFeatures = Obd2OptimizationFeature.entries
            val totalCount = orderedFeatures.size
            val enabledFeatures = currentFeatures.toMutableSet()
            var testedCount = enabledFeatures.size

            publishOptimizationStatus(
                appliedMask = enabledFeatures.toMask(),
                testedCount = testedCount,
                totalCount = totalCount,
                currentFeature = null,
                message = "OBD2 conectado em perfil universal. Iniciando otimizações por recurso…",
                testing = true
            )
            delay(600)

            for (feature in orderedFeatures) {
                if (feature in enabledFeatures) {
                    continue
                }
                val accepted = tryEnableFeature(
                    feature = feature,
                    stableFeatures = enabledFeatures,
                    testedCount = testedCount,
                    totalCount = totalCount,
                )
                testedCount++

                if (!accepted) {
                    publishOptimizationStatus(
                        appliedMask = enabledFeatures.toMask(),
                        testedCount = testedCount,
                        totalCount = totalCount,
                        currentFeature = feature,
                        message = "Recurso ${feature.displayName} instável. Mantendo configuração anterior.",
                        testing = false,
                        fallbackOccurred = true
                    )
                    delay(700)
                }
            }

            currentFeatures = enabledFeatures.toSet()
            currentFlags = flagsForFeatures(currentFeatures)
            val finalMask = currentFeatures.toMask()
            persistCurrentProfile()
            publishOptimizationStatus(
                appliedMask = finalMask,
                testedCount = testedCount,
                totalCount = totalCount,
                currentFeature = null,
                message = "Otimização OBD2 concluída. Máscara aplicada: $finalMask (0x${finalMask.toString(16).uppercase(Locale.US)}).",
                testing = false
            )
            delay(1200)
            Obd2OptimizationStatusBus.clear()
        }
    }

    private suspend fun tryEnableFeature(
        feature: Obd2OptimizationFeature,
        stableFeatures: MutableSet<Obd2OptimizationFeature>,
        testedCount: Int,
        totalCount: Int
    ): Boolean {
        val candidate = stableFeatures + feature
        publishOptimizationStatus(
            appliedMask = stableFeatures.toMask(),
            testedCount = testedCount,
            totalCount = totalCount,
            currentFeature = feature,
            message = "Testando recurso ${feature.displayName}…",
            testing = true
        )

        val applied = ioMutex.withLock {
            runCatching {
                applyOptimizationConfigLocked(candidate)
                currentFeatures = candidate
                currentFlags = flagsForFeatures(candidate)
            }.isSuccess
        }
        if (!applied) {
            restoreFeatures(stableFeatures)
            return false
        }

        val stable = runFeatureHealthCheck(feature)
        if (stable) {
            stableFeatures.add(feature)
            publishOptimizationStatus(
                appliedMask = stableFeatures.toMask(),
                testedCount = testedCount + 1,
                totalCount = totalCount,
                currentFeature = feature,
                message = "Recurso ${feature.displayName} habilitado.",
                testing = false
            )
            delay(300)
            return true
        }

        restoreFeatures(stableFeatures)
        return false
    }

    private suspend fun restoreFeatures(features: Set<Obd2OptimizationFeature>) {
        ioMutex.withLock {
            runCatching {
                applyOptimizationConfigLocked(features)
                currentFeatures = features
                currentFlags = flagsForFeatures(features)
            }
        }
    }

    private suspend fun runFeatureHealthCheck(feature: Obd2OptimizationFeature): Boolean {
        var successCount = 0
        repeat(3) {
            if (!connection.isConnected()) return false
            val rpm = readPidAdaptiveIntValue("010C") { bytes ->
                (((bytes[0] shl 8) or bytes[1]).toDouble() / 4.0).roundToInt()
            }
            val coolant = readPidAdaptiveIntValue("0105") { bytes -> bytes[0] - 40 }
            if (rpm != null || coolant != null) {
                successCount++
            }
        }
        val minimumSuccess = if (feature == Obd2OptimizationFeature.MULTI_PID_EXPERIMENTAL) 2 else 3
        return successCount >= minimumSuccess
    }

    private suspend fun applyOptimizationConfigLocked(features: Set<Obd2OptimizationFeature>) {
        val flags = flagsForFeatures(features)

        if (flags.disableEcho) {
            initializeCommand("ATE0")
        } else {
            initializeCommand("ATE1")
        }

        if (flags.disableSpaces) {
            initializeCommand("ATS0")
        } else {
            initializeCommand("ATS1")
        }

        initializeCommand("ATL0")
        initializeCommand("ATH0")

        if (flags.fixedProtocol) {
            initializeCommand("ATSP6")
        } else {
            initializeCommand("ATSP0")
        }

        if (flags.priorityScheduler) {
            initializeCommand("ATAT2")
        } else {
            initializeCommand("ATAT1")
        }

        if (flags.fastTimeout) {
            initializeCommand(
                if (Obd2OptimizationFeature.MULTI_PID_EXPERIMENTAL in features) {
                    "ATST05"
                } else {
                    "ATST0A"
                }
            )
        } else {
            initializeCommand("ATST20")
        }
    }

    private fun flagsForFeatures(features: Set<Obd2OptimizationFeature>): ProfileFlags {
        val fastTimeout = Obd2OptimizationFeature.FAST_TIMEOUT in features
        val tightReadLoop = Obd2OptimizationFeature.TIGHT_READ_LOOP in features
        val multiPid = Obd2OptimizationFeature.MULTI_PID_EXPERIMENTAL in features
        val fastPoll = Obd2OptimizationFeature.FAST_POLL_INTERVAL in features

        val pidTimeoutMs = when {
            tightReadLoop -> 420L
            fastTimeout -> 600L
            else -> 900L
        }
        val preflightTimeoutMs = when {
            tightReadLoop -> 900L
            fastTimeout -> 1200L
            else -> 1800L
        }
        val readLoopDelayMs = when {
            tightReadLoop && fastPoll -> 5L
            tightReadLoop -> 8L
            else -> 25L
        }
        val pollIntervalMs = when {
            multiPid && fastPoll -> 100L
            fastPoll -> 140L
            else -> SAFE_POLL_INTERVAL_MS
        }

        return ProfileFlags(
            disableEcho = true,
            disableSpaces = Obd2OptimizationFeature.DISABLE_SPACES in features,
            fixedProtocol = Obd2OptimizationFeature.FIXED_PROTOCOL in features,
            priorityScheduler = Obd2OptimizationFeature.PRIORITY_SCHEDULER in features,
            fastTimeout = fastTimeout,
            multiPidExperimental = multiPid,
            pidTimeoutMs = pidTimeoutMs,
            preflightTimeoutMs = preflightTimeoutMs,
            readLoopDelayMs = readLoopDelayMs,
            pollIntervalMs = pollIntervalMs,
        )
    }

    private suspend fun checkAndHandleStaleStream(updated: EcuLiveData) {
        val fingerprint = "${updated.rpm}|${updated.mapPressure}|${updated.tps}|${updated.coolantTemp}|${updated.intakeTemp}|${updated.batteryVoltage}"
        val now = System.currentTimeMillis()
        if (fingerprint != lastDataFingerprint) {
            lastDataFingerprint = fingerprint
            lastDataChangeAtMs = now
            return
        }

        val staleForMs = now - lastDataChangeAtMs
        if (staleForMs < STALE_STREAM_THRESHOLD_MS) return

        ConnectionDiagnosticsLogger.log(
            "elm327",
            "stale",
            "stream stale_for_ms=$staleForMs forcing quick rpm/map refresh"
        )
        val rpmProbe = readPidAdaptiveIntValue("010C") { bytes ->
            (((bytes[0] shl 8) or bytes[1]).toDouble() / 4.0).roundToInt()
        }
        val mapProbe = readPidAdaptiveIntValue("010B") { bytes -> bytes[0] }
        if (rpmProbe != null || mapProbe != null) {
            lastDataChangeAtMs = now
        }
    }

    private fun maybeLogPollPerformance() {
        if (pollCycleCount == 0L || pollCycleCount % PERF_LOG_WINDOW_CYCLES != 0L) return
        val now = System.currentTimeMillis()
        val elapsed = (now - perfWindowStartAtMs).coerceAtLeast(1L)
        val hz = (PERF_LOG_WINDOW_CYCLES.toDouble() * 1000.0) / elapsed.toDouble()
        val busy = perfWindowBusyMs.toDouble() / elapsed.toDouble()
        val timeoutRate = if (perfWindowReads > 0) perfWindowTimeouts.toDouble() / perfWindowReads.toDouble() else 0.0
        ConnectionDiagnosticsLogger.log(
            "elm327",
            "perf",
            "cycles=$PERF_LOG_WINDOW_CYCLES hz=${"%.2f".format(Locale.US, hz)} busy=${"%.2f".format(Locale.US, busy)} timeout_rate=${"%.2f".format(Locale.US, timeoutRate)} reads=$perfWindowReads"
        )
        perfWindowStartAtMs = now
        perfWindowBusyMs = 0L
        perfWindowTimeouts = 0
        perfWindowReads = 0
    }

    private fun publishOptimizationStatus(
        appliedMask: Int,
        testedCount: Int,
        totalCount: Int,
        currentFeature: Obd2OptimizationFeature?,
        message: String,
        testing: Boolean,
        fallbackOccurred: Boolean = false
    ) {
        Obd2OptimizationStatusBus.publish(
            Obd2OptimizationStatus(
                appliedMask = appliedMask,
                testedCount = testedCount,
                totalCount = totalCount,
                currentFeature = currentFeature,
                message = message,
                testing = testing,
                fallbackOccurred = fallbackOccurred
            )
        )
    }

    private fun logInfo(message: String) {
        Log.d(TAG, message)
        ConnectionTrace.info("elm327", message)
    }

    private fun logError(message: String, error: Throwable? = null, notifyUser: Boolean = true) {
        Log.e(TAG, message, error)
        ConnectionTrace.error("elm327", message, error)
        ConnectionDiagnosticsLogger.logError("elm327", "obd2", message, error)
        if (notifyUser) {
            onError("OBD2: $message")
        }
    }

    private fun StringBuilder.contains(ch: Char): Boolean = indexOf(ch.toString()) >= 0
}
