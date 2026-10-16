package com.speeduino.manager.transport

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
import com.speeduino.manager.telemetry.Obd2InvestigationRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Camada preparatória para fluxo Renault proprietário.
 *
 * Fase atual:
 * - detecta sessão Renault (21/22/10xx)
 * - stream base continua SAE via Obd2Transport
 * - tentativa híbrida: lê sinais OEM (rpm/coolant) por 22F40C/22F40D com fallback automático SAE
 */
class RenaultTransport(
    private val connection: IEcuConnection,
    private val onDataReceived: (EcuLiveData) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val obd2ProfileStore: Obd2ProfileStore? = null,
    private val investigationRecorder: Obd2InvestigationRecorder? = null,
) : EcuTransport {
    companion object {
        private const val TAG = "RenaultTransport"
        private const val OEM_POLL_INTERVAL_MS = 1600L
        private const val CONFIDENCE_MIN = 3
        private const val CONFIDENCE_MAX = 8
        private const val SCAN_BUDGET_MS = 35_000L
        private const val MAX_NO_DATA_STREAK = 3

        private data class OemCommandProfile(
            val id: String,
            val keywords: Set<String>,
            val rpmCommands: List<String>,
            val coolantCommands: List<String>,
        )

        private val OEM_PROFILES = listOf(
            OemCommandProfile(
                id = "sirius_32",
                keywords = setOf("SIEMENS", "SIRIUS", "S32", "K4M"),
                rpmCommands = listOf("22F40C", "210C", "22F45C"),
                coolantCommands = listOf("22F40D", "210D", "22F45D")
            ),
            OemCommandProfile(
                id = "bosch_me7",
                keywords = setOf("BOSCH", "ME7", "ME 7", "ME7.4"),
                rpmCommands = listOf("220C", "22F40C", "210C"),
                coolantCommands = listOf("2205", "22F40D", "210D")
            ),
            OemCommandProfile(
                id = "generic",
                keywords = emptySet(),
                rpmCommands = listOf("22F40C", "210C"),
                coolantCommands = listOf("22F40D", "210D")
            )
        )
    }

    private data class RenaultCanCandidate(
        val txId: String,
        val rxId: String,
        val label: String
    )

    private data class RenaultDetection(
        val protocol: String,
        val txId: String? = null,
        val rxId: String? = null,
        val address: String? = null,
        val initMode: String? = null,
        val hints: Set<String> = emptySet()
    )

    private data class OemOverlay(
        val rpm: Int? = null,
        val coolant: Int? = null,
        val rpmConfidence: Int = 0,
        val coolantConfidence: Int = 0,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var oemPollingJob: Job? = null
    @Volatile
    private var lastSaeSample: EcuLiveData? = null
    @Volatile
    private var overlay: OemOverlay = OemOverlay()
    @Volatile
    private var oemPollCycle = 0L

    private val obd2Delegate = Obd2Transport(
        connection = connection,
        onDataReceived = { sample -> onDelegateData(sample) },
        onConnectionStateChanged = onConnectionStateChanged,
        onError = onError,
        profileStore = obd2ProfileStore,
    )
    private var cachedFirmwareInfo: FirmwareInfo? = null
    private var detectedSession: RenaultDetection? = null
    private var oemProfile = OEM_PROFILES.last()

    override suspend fun connect() {
        try {
            if (!connection.isConnected()) {
                connection.connect()
            }
            investigationRecorder?.startSession(
                transport = "renault",
                metadataExtras = mapOf(
                    "connection" to connection.getConnectionInfo().trim().ifBlank { "unknown" },
                    "campaign" to "exploratory"
                )
            )
            investigationRecorder?.info("campaign", "starting renault exploratory scan")

            val detection = scanRenaultSession()
            if (detection == null || detection.hints.isEmpty()) {
                investigationRecorder?.info("campaign", "renault scan finished without positive hints")
                runCatching { connection.disconnect() }
                throw IllegalStateException("ECU Renault proprietária não detectada")
            }

            detectedSession = detection

            obd2Delegate.connect()
            oemProfile = detectOemProfile()
            val delegateInfo = obd2Delegate.getFirmwareInfoCached()
            val detail = buildString {
                append("RENAULT ").append(detection.protocol.uppercase(Locale.US)).append(" ELM327")
                if (!detection.txId.isNullOrBlank() && !detection.rxId.isNullOrBlank()) {
                    append(" TX=").append(detection.txId).append(" RX=").append(detection.rxId)
                } else if (!detection.address.isNullOrBlank()) {
                    append(" ADDR=").append(detection.address)
                }
                if (!detection.initMode.isNullOrBlank()) {
                    append(" INIT=").append(detection.initMode.uppercase(Locale.US))
                }
            }
            cachedFirmwareInfo = delegateInfo?.copy(signature = detail) ?: delegateInfo
            investigationRecorder?.updateMetadata("renault_protocol", detection.protocol)
            investigationRecorder?.updateMetadata("renault_hints", detection.hints.toList())
            investigationRecorder?.updateMetadata("renault_oem_profile", oemProfile.id)
            investigationRecorder?.updateMetadata("renault_init_mode", detection.initMode ?: "")
            investigationRecorder?.info(
                "campaign",
                "renault detection succeeded protocol=${detection.protocol} tx=${detection.txId ?: "-"} rx=${detection.rxId ?: "-"} addr=${detection.address ?: "-"} init=${detection.initMode ?: "-"} profile=${oemProfile.id}"
            )

            ConnectionDiagnosticsLogger.log(
                "renault",
                "probe",
                "detected protocol=${detection.protocol} tx=${detection.txId ?: "-"} rx=${detection.rxId ?: "-"} addr=${detection.address ?: "-"} hints=${detection.hints.joinToString(",")}" 
            )
            ConnectionDiagnosticsLogger.log("renault", "oem_profile", "selected=${oemProfile.id}")
        } catch (t: Throwable) {
            investigationRecorder?.info(
                "connect_failure",
                "reason=${t.message ?: t.javaClass.simpleName}"
            )
            detectedSession = null
            cachedFirmwareInfo = null
            oemProfile = OEM_PROFILES.last()
            runCatching { obd2Delegate.disconnect() }
            throw t
        }
    }

    override fun disconnect() {
        val profileAtDisconnect = oemProfile.id
        stopOemPolling()
        detectedSession = null
        cachedFirmwareInfo = null
        overlay = OemOverlay()
        lastSaeSample = null
        oemPollCycle = 0L
        oemProfile = OEM_PROFILES.last()
        obd2Delegate.disconnect()
        investigationRecorder?.closeSession(
            summary = mapOf(
                "transport" to "renault",
                "profile" to profileAtDisconnect
            )
        )
    }

    override fun isConnected(): Boolean = obd2Delegate.isConnected()

    override fun isStreaming(): Boolean = obd2Delegate.isStreaming()

    override fun startLiveDataStream(intervalMs: Long) {
        obd2Delegate.startLiveDataStream(intervalMs)
        startOemPolling()
    }

    override fun stopLiveDataStream() {
        stopOemPolling()
        obd2Delegate.stopLiveDataStream()
    }

    override suspend fun pauseLiveDataStream(timeoutMs: Long) {
        stopOemPolling()
        obd2Delegate.pauseLiveDataStream(timeoutMs)
    }

    override suspend fun getFirmwareInfo(): String {
        return cachedFirmwareInfo?.signature ?: obd2Delegate.getFirmwareInfo()
    }

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
        val applied = applyOverlay(sample)
        onDataReceived(applied)
    }

    private fun applyOverlay(sample: EcuLiveData): EcuLiveData {
        val current = overlay
        val rpm = if (current.rpmConfidence >= CONFIDENCE_MIN) current.rpm ?: sample.rpm else sample.rpm
        val coolant = if (current.coolantConfidence >= CONFIDENCE_MIN) current.coolant ?: sample.coolantTemp else sample.coolantTemp
        return sample.copy(rpm = rpm, coolantTemp = coolant)
    }

    private fun startOemPolling() {
        if (oemPollingJob?.isActive == true) return
        if (detectedSession == null) return

        oemPollingJob = scope.launch {
            while (isActive && obd2Delegate.isConnected() && obd2Delegate.isStreaming()) {
                runCatching { pollOemOnce() }
                    .onFailure { err ->
                        ConnectionDiagnosticsLogger.log("renault", "oem_poll", "failed: ${err.message ?: "unknown"}")
                    }
                delay(OEM_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopOemPolling() {
        oemPollingJob?.cancel()
        oemPollingJob = null
    }

    private suspend fun pollOemOnce() {
        val session = detectedSession ?: return
        val sae = lastSaeSample
        oemPollCycle++

        if (session.hints.any { it.startsWith("session_") }) {
            listOf("1003", "10C0").forEach { cmd ->
                val resp = obd2Delegate.sendRawElmCommand(cmd, timeoutMs = 900L).uppercase(Locale.US)
                if (resp.contains("50")) return@forEach
            }
        }

        var rpmCandidate: Int? = null
        var rpmWinner: String? = null
        for (cmd in oemProfile.rpmCommands) {
            val response = obd2Delegate.sendRawElmCommand(cmd, timeoutMs = 900L)
            val parsed = parseRpmResponse(cmd, response)
            if (parsed != null) {
                rpmCandidate = parsed
                rpmWinner = cmd
                break
            }
        }

        var coolantCandidate: Int? = null
        var coolantWinner: String? = null
        for (cmd in oemProfile.coolantCommands) {
            val response = obd2Delegate.sendRawElmCommand(cmd, timeoutMs = 900L)
            val parsed = parseCoolantResponse(cmd, response)
            if (parsed != null) {
                coolantCandidate = parsed
                coolantWinner = cmd
                break
            }
        }

        val rpmValid = validateRpm(rpmCandidate, sae?.rpm)
        val coolantValid = validateCoolant(coolantCandidate, sae?.coolantTemp)
        overlay = overlay.copy(
            rpm = rpmCandidate ?: overlay.rpm,
            rpmConfidence = computeConfidence(
                previous = overlay.rpmConfidence,
                valid = rpmValid
            ),
            coolant = coolantCandidate ?: overlay.coolant,
            coolantConfidence = computeConfidence(
                previous = overlay.coolantConfidence,
                valid = coolantValid
            )
        )

        ConnectionDiagnosticsLogger.log(
            "renault",
            "oem_poll",
            "cycle=$oemPollCycle profile=${oemProfile.id} rpm_cmd=${rpmWinner ?: "-"} rpm_oem=${rpmCandidate ?: -1} rpm_sae=${sae?.rpm ?: -1} rpm_valid=$rpmValid rpm_conf=${overlay.rpmConfidence} clt_cmd=${coolantWinner ?: "-"} clt_oem=${coolantCandidate ?: -99} clt_sae=${sae?.coolantTemp ?: -99} clt_valid=$coolantValid clt_conf=${overlay.coolantConfidence}"
        )
    }

    private fun computeConfidence(previous: Int, valid: Boolean): Int {
        return if (valid) {
            (previous + 1).coerceAtMost(CONFIDENCE_MAX)
        } else {
            (previous - 1).coerceAtLeast(0)
        }
    }

    private fun validateRpm(value: Int?, saeRpm: Int?): Boolean {
        value ?: return false
        if (value !in 0..9000) return false
        val baseline = saeRpm?.takeIf { it > 0 } ?: return value > 250
        val tolerance = max(250, (baseline * 0.35).toInt())
        return abs(value - baseline) <= tolerance
    }

    private fun validateCoolant(value: Int?, saeCoolant: Int?): Boolean {
        value ?: return false
        if (value !in -40..140) return false
        val baseline = saeCoolant ?: return true
        return abs(value - baseline) <= 20
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

    private suspend fun detectOemProfile(): OemCommandProfile {
        val fingerprint = buildString {
            append(obd2Delegate.sendRawElmCommand("22F1A0", timeoutMs = 900L)).append(' ')
            append(obd2Delegate.sendRawElmCommand("2180", timeoutMs = 900L)).append(' ')
            append(obd2Delegate.sendRawElmCommand("22F190", timeoutMs = 900L))
        }
        val decoded = decodeAsciiFromHexPayload(fingerprint).uppercase(Locale.US)
        ConnectionDiagnosticsLogger.log(
            "renault",
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

    private fun scanRenaultSession(): RenaultDetection? {
        val deadline = System.currentTimeMillis() + SCAN_BUDGET_MS
        val adapterProtocol = sendRawElmCommand("ATDP").uppercase(Locale.US)
        val preferIso = adapterProtocol.contains("KWP FAST") || adapterProtocol.contains("ISO 9141")
        investigationRecorder?.updateMetadata("renault_adapter_protocol", adapterProtocol)
        investigationRecorder?.info(
            "campaign",
            "renault adapter protocol probe=$adapterProtocol preferIso=$preferIso"
        )
        if (preferIso) {
            val isoDetection = scanRenaultIso(deadline)
            if (isoDetection != null && isoDetection.hints.isNotEmpty()) {
                return isoDetection
            }
            if (System.currentTimeMillis() >= deadline) {
                ConnectionDiagnosticsLogger.log("renault", "probe", "scan timeout before CAN fallback")
                return isoDetection
            }
            return scanRenaultCan(deadline) ?: isoDetection
        }
        val canDetection = scanRenaultCan(deadline)
        if (canDetection != null && canDetection.hints.isNotEmpty()) {
            return canDetection
        }
        if (System.currentTimeMillis() >= deadline) {
            ConnectionDiagnosticsLogger.log("renault", "probe", "scan timeout before ISO fallback")
            return null
        }
        return scanRenaultIso(deadline)
    }

    private fun scanRenaultCan(deadlineMillis: Long): RenaultDetection? {
        if (!initCanElm()) return null

        val protocols = listOf("6", "8")
        val candidates = listOf(
            RenaultCanCandidate(txId = "7E0", rxId = "7E8", label = "engine_primary"),
            RenaultCanCandidate(txId = "7E1", rxId = "7E9", label = "engine_secondary"),
            RenaultCanCandidate(txId = "740", rxId = "760", label = "renault_01"),
            RenaultCanCandidate(txId = "742", rxId = "762", label = "renault_04"),
            RenaultCanCandidate(txId = "743", rxId = "763", label = "renault_51"),
            RenaultCanCandidate(txId = "744", rxId = "764", label = "renault_29"),
            RenaultCanCandidate(txId = "74D", rxId = "76D", label = "renault_27"),
            RenaultCanCandidate(txId = "74E", rxId = "76E", label = "renault_0E"),
            RenaultCanCandidate(txId = "750", rxId = "770", label = "renault_0F"),
            RenaultCanCandidate(txId = "751", rxId = "771", label = "renault_07")
        )

        for (sp in protocols) {
            if (System.currentTimeMillis() >= deadlineMillis) {
                ConnectionDiagnosticsLogger.log("renault", "probe", "scan timeout on CAN phase")
                return null
            }
            sendRawElmCommand("ATSP$sp")
            for (candidate in candidates) {
                if (System.currentTimeMillis() >= deadlineMillis) {
                    ConnectionDiagnosticsLogger.log("renault", "probe", "scan timeout while iterating CAN candidates")
                    return null
                }
                configureCanAddress(candidate)
                val canSanity = sendRawElmCommand("0210C0").uppercase(Locale.US)
                if ("CAN ERROR" in canSanity) {
                    investigationRecorder?.info(
                        "campaign",
                        "renault can sanity failed protocol=$sp candidate=${candidate.label} resp=${canSanity.take(80)}"
                    )
                    continue
                }
                val hints = probeRenaultCommands(deadlineMillis)
                if (hints.isNotEmpty()) {
                    return RenaultDetection(
                        protocol = if (sp == "6") "can500" else "can250",
                        txId = candidate.txId,
                        rxId = candidate.rxId,
                        hints = hints + setOf("candidate_${candidate.label}")
                    )
                }
            }
        }
        return null
    }

    private fun initCanElm(): Boolean {
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
            sendRawElmCommand(cmd)
        }
        return true
    }

    private fun configureCanAddress(candidate: RenaultCanCandidate) {
        sendRawElmCommand("ATSH ${candidate.txId}")
        sendRawElmCommand("ATCRA ${candidate.rxId}")
        sendRawElmCommand("ATFCSH ${candidate.txId}")
        sendRawElmCommand("ATFCSD 30 00 00")
        sendRawElmCommand("ATFCSM 1")
    }

    private fun scanRenaultIso(deadlineMillis: Long): RenaultDetection? {
        val candidateAddresses = listOf("7A", "01", "51", "26", "2C", "24", "29", "6E", "04", "07", "27", "32")
        var softCandidate: RenaultDetection? = null
        for (address in candidateAddresses) {
            if (System.currentTimeMillis() >= deadlineMillis) {
                ConnectionDiagnosticsLogger.log("renault", "probe", "scan timeout on ISO phase")
                return softCandidate
            }
            for (initMode in listOf("fast", "slow")) {
                val initialized = initIsoElm(address, initMode)
                if (!initialized) continue
                val hints = probeRenaultCommands(deadlineMillis, address = address, initMode = initMode)
                if (hints.isNotEmpty()) {
                    return RenaultDetection(
                        protocol = "iso",
                        address = address,
                        initMode = initMode,
                        hints = hints
                    )
                }
                if (softCandidate == null) {
                    val saeProbe = sendRawElmCommand("0100").uppercase(Locale.US)
                    if (
                        saeProbe.contains("410000000000") ||
                        saeProbe.contains("7F0112") ||
                        saeProbe.contains("7F 01 12")
                    ) {
                        investigationRecorder?.info(
                            "campaign",
                            "renault soft ISO hint address=$address init=$initMode saeProbe=${saeProbe.take(80)}"
                        )
                        softCandidate = RenaultDetection(
                            protocol = "iso",
                            address = address,
                            initMode = initMode,
                            hints = setOf("kwp_fast_detected", "address_$address", "init_$initMode")
                        )
                    }
                }
            }
        }
        return softCandidate
    }

    private fun initIsoElm(address: String, initMode: String): Boolean {
        val initSequence = when (initMode) {
            "slow" -> listOf(
                "ATWS",
                "ATE0",
                "ATL1",
                "ATH0",
                "ATSH81${address}F1",
                "ATSW96",
                "ATIB10",
                "ATAT0",
                "ATSTFF",
                "ATSP4",
                "ATSI",
                "ATAT1"
            )

            else -> listOf(
                "ATWS",
                "ATE0",
                "ATL1",
                "ATH0",
                "ATSH81${address}F1",
                "ATSW96",
                "ATIB10",
                "ATAT0",
                "ATSTFF",
                "ATSP5",
                "ATFI",
                "ATAT1"
            )
        }
        investigationRecorder?.info(
            "campaign",
            "renault iso init address=$address mode=$initMode"
        )
        for (cmd in initSequence) {
            sendRawElmCommand(cmd)
        }
        return true
    }

    private fun probeRenaultCommands(deadlineMillis: Long, address: String? = null, initMode: String? = null): Set<String> {
        val hints = linkedSetOf<String>()
        val probeCommands = listOf("10C0", "1003", "10A0", "10B0", "22F190", "2180", "22F1A0", "2181")
        var noDataStreak = 0
        for (cmd in probeCommands) {
            if (System.currentTimeMillis() >= deadlineMillis) {
                break
            }
            val response = sendRawElmCommand(cmd).uppercase(Locale.US)
            investigationRecorder?.info(
                "campaign",
                "renault probe cmd=$cmd address=${address ?: "-"} init=${initMode ?: "-"} resp=${response.take(120)}"
            )
            val noData = isNoDataLike(response)
            noDataStreak = if (noData) noDataStreak + 1 else 0
            if (response.contains("50C0")) hints.add("session_10C0")
            if (response.contains("5003")) hints.add("session_1003")
            if (response.contains("50A0")) hints.add("session_10A0")
            if (response.contains("50B0")) hints.add("session_10B0")
            if (response.contains("6180")) hints.add("id_2180")
            if (response.contains("6181")) hints.add("vin_2181")
            if (response.contains("62F190")) hints.add("vin_22F190")
            if (response.contains("62F1A0")) hints.add("diag_22F1A0")
            if (hints.isNotEmpty()) continue
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

    private fun sendRawElmCommand(command: String): String {
        return runCatching {
            val startedAt = System.currentTimeMillis()
            connection.clearInputBuffer()
            connection.send("$command\r".toByteArray(StandardCharsets.US_ASCII))
            val deadline = System.currentTimeMillis() + 1200L
            val builder = StringBuilder()
            while (System.currentTimeMillis() < deadline) {
                val chunk = connection.receive(0)
                if (chunk.isEmpty()) continue
                val text = chunk.toString(StandardCharsets.US_ASCII)
                builder.append(text)
                if (text.contains(">") || builder.contains(">")) {
                    break
                }
            }
            val response = builder.toString().trim()
            investigationRecorder?.recordCommand(
                transport = "renault",
                command = command,
                response = response,
                timeoutMs = 1200L,
                elapsedMs = System.currentTimeMillis() - startedAt,
                extra = mapOf(
                    "detected_protocol" to (detectedSession?.protocol ?: ""),
                    "oem_profile" to oemProfile.id
                )
            )
            response
        }.onFailure { error ->
            Log.d(TAG, "raw command failed cmd=$command err=${error.message}")
            investigationRecorder?.info(
                "command_failure",
                "cmd=$command reason=${error.message ?: error.javaClass.simpleName}"
            )
        }.getOrDefault("")
    }
}
