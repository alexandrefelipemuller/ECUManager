package com.speeduino.manager.transport

import io.ecucore.transport.EcuTransport
import android.util.Log
import io.ecucore.EcuLiveData
import io.ecucore.connection.IEcuConnection
import io.ecucore.definition.IniDefinition
import io.ecucore.ecu.FirmwareInfo
import io.ecucore.model.AfrTable
import io.ecucore.model.ClosedLoopCorrectionConfig
import io.ecucore.model.EcuCapabilities
import io.ecucore.model.EcuFamily
import io.ecucore.model.EcuPageDescriptor
import io.ecucore.model.EngineConstants
import io.ecucore.model.EngineProtectionConfig
import io.ecucore.model.IdleControlSettings
import io.ecucore.model.IgnitionTable
import io.ecucore.model.PinLayoutInfo
import io.ecucore.model.PressureCalibration
import io.ecucore.model.RusefiInputOutputSnapshot
import io.ecucore.model.SecondarySerialConfig
import io.ecucore.model.TableDefinitions
import io.ecucore.model.TpsCalibration
import io.ecucore.model.TriggerSettings
import io.ecucore.model.VeTable
import io.ecucore.model.FirmwareEra
import io.ecucore.protocol.SerialCapability
import com.speeduino.manager.telemetry.ConnectionDiagnosticsLogger
import com.speeduino.manager.telemetry.Obd2InvestigationRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Transporte VAG inicial para KWP2000/K-line sobre ELM327.
 *
 * Escopo desta primeira versao:
 * - autodetect por alguns pares init/controller mais comuns
 * - sessao VW (0x10 0x89)
 * - leitura de identificacao ECU (0x1A 0x9B)
 * - polling de measurement blocks (0x21 xx)
 * - mapeamento heuristico dos sinais mais universais
 */
class VagTransport(
    private val connection: IEcuConnection,
    private val onDataReceived: (EcuLiveData) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val investigationRecorder: Obd2InvestigationRecorder? = null,
) : EcuTransport {
    companion object {
        private const val TAG = "VagTransport"
        private const val ELM_PROMPT = '>'
        private const val COMMAND_TIMEOUT_MS = 1400L
        private const val POLL_TESTER_PRESENT_EVERY = 4L
        private val VAG_CAPABILITIES = EcuCapabilities(
            supportsModernProtocol = false,
            supportsLegacyProtocol = false,
            supportsPageRead = false,
            supportsPageWrite = false,
            supportsBurn = false,
            supportsLiveData = true,
        )
        private val ADDRESS_CANDIDATES = listOf(
            VagCandidate(0x01, 0x10, "engine_primary"),
            VagCandidate(0x02, 0x1A, "transmission_primary"),
            VagCandidate(0x03, 0x20, "abs_primary"),
            VagCandidate(0x08, 0x28, "hvac_primary"),
            VagCandidate(0x11, 0x17, "cluster_primary"),
        )
        private val BLOCK_CANDIDATES = listOf(
            1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 15, 20, 31, 32, 33, 34, 54, 60, 90, 98, 115, 118
        )
    }

    private data class VagCandidate(
        val initAddress: Int,
        val controllerAddress: Int,
        val label: String,
    )

    private data class EcuIdentification(
        val hardwareNumber: String,
        val softwareNumber: String,
    )

    private data class DetectionResult(
        val candidate: VagCandidate,
        val identification: EcuIdentification?,
        val activeBlocks: List<Int>,
    )

    private data class MeasurementValue(
        val rawType: Int,
        val valueText: String,
        val unit: String,
        val numericValue: Double? = null,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ioMutex = Mutex()
    private var streamJob: Job? = null
    private var streaming = false
    private var firmwareInfo: FirmwareInfo? = null
    private var detection: DetectionResult? = null
    private var currentLiveData = EcuLiveData(
        secl = 0,
        rpm = 0,
        coolantTemp = 0,
        intakeTemp = 0,
        mapPressure = 100,
        tps = 0,
        batteryVoltage = 12.0,
        advance = 0,
        o2 = 0,
        engineStatus = 0,
        sparkStatus = 0,
    )
    private var connectedAtMs = 0L
    private var pollCycle = 0L

    override suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            if (!connection.isConnected()) {
                connection.connect()
            }
            investigationRecorder?.startSession(
                transport = "vag",
                metadataExtras = mapOf(
                    "connection" to connection.getConnectionInfo().trim().ifBlank { "unknown" },
                    "campaign" to "exploratory"
                )
            )
            investigationRecorder?.info("campaign", "starting vag kwp exploratory scan")

            val detected = detectSession()
                ?: throw IllegalStateException("ECU VAG KWP2000 nao detectada")
            detection = detected
            connectedAtMs = System.currentTimeMillis()
            pollCycle = 0L

            val signature = buildString {
                append("VAG KWP2000 ")
                append(detected.candidate.label)
                detected.identification?.hardwareNumber
                    ?.takeIf { it.isNotBlank() }
                    ?.let { append(" HW=").append(it) }
                detected.identification?.softwareNumber
                    ?.takeIf { it.isNotBlank() }
                    ?.let { append(" SW=").append(it) }
            }
            firmwareInfo = FirmwareInfo(
                signature = signature,
                productString = "ELM327",
                era = FirmwareEra.MODERN_2025,
                family = EcuFamily.UNKNOWN,
                capabilities = VAG_CAPABILITIES,
            )
            investigationRecorder?.updateMetadata("vag_candidate", detected.candidate.label)
            investigationRecorder?.updateMetadata(
                "vag_addresses",
                mapOf(
                    "init" to "0x%02X".format(Locale.US, detected.candidate.initAddress),
                    "controller" to "0x%02X".format(Locale.US, detected.candidate.controllerAddress),
                )
            )
            investigationRecorder?.updateMetadata("vag_blocks", detected.activeBlocks)
            investigationRecorder?.updateMetadata("vag_hw", detected.identification?.hardwareNumber ?: "")
            investigationRecorder?.updateMetadata("vag_sw", detected.identification?.softwareNumber ?: "")
            onConnectionStateChanged(true)
        } catch (t: Throwable) {
            investigationRecorder?.info(
                "connect_failure",
                "reason=${t.message ?: t.javaClass.simpleName}"
            )
            safeDisconnect()
            throw t
        }
    }

    override fun disconnect() {
        streaming = false
        streamJob?.cancel()
        streamJob = null
        safeDisconnect()
    }

    override fun isConnected(): Boolean = connection.isConnected()

    override fun isStreaming(): Boolean = streaming

    override fun startLiveDataStream(intervalMs: Long) {
        if (streaming) return
        streaming = true
        streamJob = scope.launch {
            val activeBlocks = detection?.activeBlocks.orEmpty()
            if (activeBlocks.isEmpty()) {
                streaming = false
                return@launch
            }
            var blockIndex = 0
            while (isActive && streaming && connection.isConnected()) {
                try {
                    val block = activeBlocks[blockIndex % activeBlocks.size]
                    pollMeasurementBlock(block)
                    blockIndex++
                    pollCycle++
                    if (pollCycle % POLL_TESTER_PRESENT_EVERY == 0L) {
                        runCatching { sendTesterPresent() }
                    }
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    ConnectionDiagnosticsLogger.log("vag", "poll", "failed=${e.message ?: "unknown"}")
                    if (!connection.isConnected()) {
                        safeDisconnect()
                        break
                    }
                }
                delay(intervalMs.coerceAtLeast(150L))
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
        streamJob?.cancel()
        streamJob = null
        runCatching { connection.clearInputBuffer() }
    }

    override suspend fun getFirmwareInfo(): String = firmwareInfo?.signature ?: "VAG KWP2000"

    override fun getFirmwareInfoCached(): FirmwareInfo? = firmwareInfo

    override fun getEcuFamily(): EcuFamily = EcuFamily.UNKNOWN

    override fun getEcuCapabilities(): EcuCapabilities = VAG_CAPABILITIES

    override fun getTableDefinitions(): TableDefinitions? = null

    override fun getEcuPageCatalog(): List<EcuPageDescriptor> = emptyList()

    override fun getPinLayoutInfoCached(): PinLayoutInfo? = null

    override fun cachePinLayoutInfo(info: PinLayoutInfo?) = Unit

    override fun isReadOnlySafeMode(): Boolean = true

    override fun applyIniDefinition(definition: IniDefinition): Boolean = false

    override fun setManualFirmwareProfile(signature: String, readOnly: Boolean) = Unit

    override suspend fun getSerialCapability(): SerialCapability =
        SerialCapability(protocolVersion = 1, blockingFactor = 256, tableBlockingFactor = 256)

    override suspend fun readFullPage(pageNum: Int, pageSize: Int, blockSize: Int): ByteArray =
        throw UnsupportedOperationException("Page read not supported by VagTransport")

    override suspend fun readVeTable(mapIndex: Int): VeTable =
        throw UnsupportedOperationException("VE table not supported by VagTransport")

    override suspend fun readIgnitionTable(mapIndex: Int): IgnitionTable =
        throw UnsupportedOperationException("Ignition table not supported by VagTransport")

    override suspend fun readAfrTable(): AfrTable =
        throw UnsupportedOperationException("AFR table not supported by VagTransport")

    override suspend fun readEngineConstants(): EngineConstants =
        throw UnsupportedOperationException("Engine constants not supported by VagTransport")

    override suspend fun readTriggerSettings(): TriggerSettings =
        throw UnsupportedOperationException("Trigger settings not supported by VagTransport")

    override suspend fun readRusefiInputOutputSnapshot(): RusefiInputOutputSnapshot =
        throw UnsupportedOperationException("I/O snapshot not supported by VagTransport")

    override suspend fun readEngineProtectionConfig(): EngineProtectionConfig =
        throw UnsupportedOperationException("Engine protection not supported by VagTransport")

    override suspend fun readIdleControlSettings(): IdleControlSettings =
        throw UnsupportedOperationException("Idle control not supported by VagTransport")

    override suspend fun writeIdleControlSettings(settings: IdleControlSettings, burn: Boolean) =
        throw UnsupportedOperationException("Idle control write not supported by VagTransport")

    override suspend fun readClosedLoopCorrectionConfig(): ClosedLoopCorrectionConfig =
        throw UnsupportedOperationException("Closed-loop correction not supported by VagTransport")

    override suspend fun writeClosedLoopCorrectionConfig(
        config: ClosedLoopCorrectionConfig,
        burn: Boolean,
    ) = throw UnsupportedOperationException("Closed-loop correction write not supported by VagTransport")

    override suspend fun readMapSelectionSupport() =
        throw UnsupportedOperationException("Map selection support not supported by VagTransport")

    override suspend fun readPressureCalibration(): PressureCalibration =
        throw UnsupportedOperationException("Pressure calibration not supported by VagTransport")

    override suspend fun writePressureCalibration(calibration: PressureCalibration, burn: Boolean) =
        throw UnsupportedOperationException("Pressure calibration write not supported by VagTransport")

    override suspend fun readTpsCalibration(): TpsCalibration =
        throw UnsupportedOperationException("TPS calibration not supported by VagTransport")

    override suspend fun writeTpsCalibration(calibration: TpsCalibration, burn: Boolean) =
        throw UnsupportedOperationException("TPS calibration write not supported by VagTransport")

    override suspend fun readSecondarySerialConfig(): SecondarySerialConfig =
        throw UnsupportedOperationException("Secondary serial config not supported by VagTransport")

    override suspend fun writeSecondarySerialConfig(config: SecondarySerialConfig, burn: Boolean) =
        throw UnsupportedOperationException("Secondary serial config write not supported by VagTransport")

    override suspend fun writeEngineProtectionConfig(config: EngineProtectionConfig, burn: Boolean) =
        throw UnsupportedOperationException("Engine protection write not supported by VagTransport")

    override suspend fun writeTriggerSettings(settings: TriggerSettings, burn: Boolean) =
        throw UnsupportedOperationException("Trigger settings write not supported by VagTransport")

    override suspend fun writeRawPage(pageNum: Int, data: ByteArray) =
        throw UnsupportedOperationException("Raw page write not supported by VagTransport")

    override suspend fun writeRawPageWithoutBurn(pageNum: Int, data: ByteArray) =
        throw UnsupportedOperationException("Raw page write not supported by VagTransport")

    override suspend fun burnConfigs() =
        throw UnsupportedOperationException("Burn not supported by VagTransport")

    override suspend fun writeVeTable(veTable: VeTable, mapIndex: Int) =
        throw UnsupportedOperationException("VE table write not supported by VagTransport")

    override suspend fun writeIgnitionTable(ignitionTable: IgnitionTable, mapIndex: Int) =
        throw UnsupportedOperationException("Ignition table write not supported by VagTransport")

    override suspend fun writeAfrTable(afrTable: AfrTable) =
        throw UnsupportedOperationException("AFR table write not supported by VagTransport")

    override suspend fun writeEngineConstants(engineConstants: EngineConstants) =
        throw UnsupportedOperationException("Engine constants write not supported by VagTransport")

    private suspend fun detectSession(): DetectionResult? {
        for (candidate in ADDRESS_CANDIDATES) {
            investigationRecorder?.info(
                "campaign",
                "trying vag candidate=${candidate.label} init=0x%02X controller=0x%02X".format(
                    Locale.US,
                    candidate.initAddress,
                    candidate.controllerAddress
                )
            )
            if (!initializeElmForCandidate(candidate)) continue
            if (!startVwDiagnosticSession()) continue
            val identification = runCatching { readEcuIdentification() }.getOrNull()
            val supportedBlocks = probeMeasurementBlocks()
            if (identification != null || supportedBlocks.isNotEmpty()) {
                return DetectionResult(
                    candidate = candidate,
                    identification = identification,
                    activeBlocks = supportedBlocks.ifEmpty { listOf(6) }
                )
            }
        }
        return null
    }

    private suspend fun initializeElmForCandidate(candidate: VagCandidate): Boolean {
        val commands = listOf(
            "ATZ",
            "ATE0",
            "ATL0",
            "ATH0",
            "ATS0",
            "ATAL",
            "ATSTFF",
            "ATSP0",
            "ATIIA %02X".format(Locale.US, candidate.initAddress),
            "ATSH 80 %02X F1".format(Locale.US, candidate.controllerAddress),
        )
        commands.forEachIndexed { index, command ->
            val response = sendElmCommand(command, if (index == 0) 1800L else COMMAND_TIMEOUT_MS)
            if (command != "ATZ" && !response.uppercase(Locale.US).contains("OK")) {
                investigationRecorder?.info(
                    "campaign",
                    "vag init failed candidate=${candidate.label} cmd=$command resp=${response.take(120)}"
                )
                return false
            }
        }
        return true
    }

    private suspend fun startVwDiagnosticSession(): Boolean {
        val response = sendKwpPayload(byteArrayOf(0x10, 0x89.toByte()))
        val ok = response.isNotEmpty() && response[0] != 0x7F.toByte()
        if (!ok) {
            investigationRecorder?.info(
                "campaign",
                "vag session start negative=${toHex(response)}"
            )
        }
        return ok
    }

    private suspend fun readEcuIdentification(): EcuIdentification? {
        val response = sendKwpPayload(byteArrayOf(0x1A, 0x9B.toByte()))
        if (response.isEmpty() || response[0] == 0x7F.toByte() || response.size < 13) {
            return null
        }
        val hardware = asciiSlice(response, 2, 12)
        val software = asciiSlice(response, 13, response.size)
        return EcuIdentification(hardware, software)
    }

    private suspend fun probeMeasurementBlocks(): List<Int> {
        val positive = mutableListOf<Int>()
        BLOCK_CANDIDATES.forEach { block ->
            val response = runCatching {
                sendKwpPayload(byteArrayOf(0x21, block.toByte()))
            }.getOrDefault(byteArrayOf())
            val values = parseMeasurementValues(response)
            if (values.isNotEmpty()) {
                positive += block
                investigationRecorder?.info(
                    "campaign",
                    "vag block=$block values=${values.joinToString(" | ") { "${it.valueText} ${it.unit}".trim() }}"
                )
            }
        }
        return positive
    }

    private suspend fun pollMeasurementBlock(block: Int) {
        val response = sendKwpPayload(byteArrayOf(0x21, block.toByte()))
        val values = parseMeasurementValues(response)
        if (values.isEmpty()) return

        val updated = mapValuesToLiveData(values)
        currentLiveData = updated.copy(
            secl = (((System.currentTimeMillis() - connectedAtMs) / 1000L).coerceAtLeast(0L)).toInt(),
            engineStatus = if (updated.rpm > 0) 1 else 0,
            sparkStatus = if (updated.rpm > 0) 1 else 0,
        )
        onDataReceived(currentLiveData)
        investigationRecorder?.recordSample(
            source = "vag_kwp",
            sample = currentLiveData,
            extras = mapOf(
                "block" to block.toString(),
                "candidate" to (detection?.candidate?.label ?: ""),
                "values" to values.joinToString(" | ") { "${it.valueText} ${it.unit}".trim() }
            )
        )
    }

    private fun mapValuesToLiveData(values: List<MeasurementValue>): EcuLiveData {
        var rpm = currentLiveData.rpm
        var coolant = currentLiveData.coolantTemp
        var intake = currentLiveData.intakeTemp
        var mapKpa = currentLiveData.mapPressure
        var tps = currentLiveData.tps
        var battery = currentLiveData.batteryVoltage
        var candidateSpeed = currentLiveData.candidateSpeedKph
        var injMs = currentLiveData.candidateInjectionDurationMs

        values.forEach { value ->
            val number = value.numericValue ?: return@forEach
            when {
                value.unit == "rpm" && number in 0.0..9000.0 -> rpm = number.roundToInt()
                value.unit == "deg C" && coolant == currentLiveData.coolantTemp -> coolant = number.roundToInt()
                value.unit == "deg C" -> intake = number.roundToInt()
                value.unit == "mbar" && number in 0.0..3000.0 -> mapKpa = (number / 10.0).roundToInt()
                value.unit == "bar" && number in 0.0..3.5 -> mapKpa = (number * 100.0).roundToInt()
                value.unit == "%" && number in 0.0..100.0 && abs(number - tps) > 0.5 -> tps = number.roundToInt()
                value.unit == "V" && number in 0.0..20.0 -> battery = number
                value.unit == "km/h" && number in 0.0..320.0 -> candidateSpeed = number.roundToInt()
                value.unit == "ms" && number in 0.0..50.0 -> injMs = number
            }
        }

        return currentLiveData.copy(
            rpm = rpm,
            coolantTemp = coolant,
            intakeTemp = intake,
            mapPressure = mapKpa,
            tps = tps,
            batteryVoltage = battery,
            candidateSpeedKph = candidateSpeed,
            candidateInjectionDurationMs = injMs,
        )
    }

    private fun parseMeasurementValues(response: ByteArray): List<MeasurementValue> {
        if (response.isEmpty() || response[0] == 0x7F.toByte()) return emptyList()
        val values = mutableListOf<MeasurementValue>()
        var index = 2
        while (index + 2 < response.size) {
            values += parseMeasurementValue(response[index], response[index + 1], response[index + 2])
            index += 3
        }
        return values
    }

    private fun parseMeasurementValue(typeByte: Byte, aByte: Byte, bByte: Byte): MeasurementValue {
        val type = typeByte.toInt() and 0xFF
        val a = aByte.toInt() and 0xFF
        val b = bByte.toInt() and 0xFF
        return when (type) {
            0x01 -> MeasurementValue(type, formatDouble(a * b / 5.0), "rpm", a * b / 5.0)
            0x05 -> MeasurementValue(type, formatDouble((a * b) / 10.0 - (a * 10.0)), "deg C", (a * b) / 10.0 - (a * 10.0))
            0x06 -> MeasurementValue(type, formatDouble(a * b / 1000.0), "V", a * b / 1000.0)
            0x07 -> MeasurementValue(type, formatDouble((a * b) / 100.0), "km/h", (a * b) / 100.0)
            0x0E -> MeasurementValue(type, formatDouble(a * b / 200.0), "bar", a * b / 200.0)
            0x0F -> MeasurementValue(type, formatDouble(a * b / 100.0), "ms", a * b / 100.0)
            0x12 -> MeasurementValue(type, formatDouble(a * b / 25.0), "mbar", a * b / 25.0)
            0x14 -> MeasurementValue(type, formatDouble(a * (b - 128) / 128.0), "%", a * (b - 128) / 128.0)
            0x1A -> MeasurementValue(type, (b - a).toString(), "deg C", (b - a).toDouble())
            0x20 -> {
                val numeric = if (b > 128) (b - 256).toDouble() else b.toDouble()
                MeasurementValue(type, formatDouble(numeric), "deg C", numeric)
            }
            0x21 -> {
                val safeA = if (a == 0) 1 else a
                val numeric = 100.0 * b / safeA
                MeasurementValue(type, formatDouble(numeric), "%", numeric)
            }
            else -> MeasurementValue(type, "%02X %02X".format(Locale.US, a, b), "raw_$type", null)
        }
    }

    private suspend fun sendTesterPresent() {
        sendKwpPayload(byteArrayOf(0x3E, 0x00))
    }

    private suspend fun sendKwpPayload(payload: ByteArray, timeoutMs: Long = COMMAND_TIMEOUT_MS): ByteArray {
        return ioMutex.withLock {
            val ascii = payload.joinToString(" ") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
            val startedAt = System.currentTimeMillis()
            connection.clearInputBuffer()
            connection.send("$ascii \r".toByteArray(StandardCharsets.US_ASCII))
            val response = readKwpResponse(timeoutMs)
            investigationRecorder?.recordCommand(
                transport = "vag",
                command = ascii,
                response = toHex(response),
                timeoutMs = timeoutMs,
                elapsedMs = System.currentTimeMillis() - startedAt,
                extra = mapOf(
                    "candidate" to (detection?.candidate?.label ?: ""),
                    "mode" to "kwp_payload"
                )
            )
            if (response.size > 2 && response[0] == 0x7F.toByte() && response[2] == 0x78.toByte()) {
                return@withLock readKwpResponse(timeoutMs)
            }
            response
        }
    }

    private suspend fun sendElmCommand(command: String, timeoutMs: Long): String {
        return ioMutex.withLock {
            val startedAt = System.currentTimeMillis()
            connection.clearInputBuffer()
            connection.send("$command\r".toByteArray(StandardCharsets.US_ASCII))
            if (command == "ATZ") {
                delay(250L)
            }
            val response = readTextResponse(timeoutMs)
            investigationRecorder?.recordCommand(
                transport = "vag",
                command = command,
                response = response,
                timeoutMs = timeoutMs,
                elapsedMs = System.currentTimeMillis() - startedAt,
                extra = mapOf(
                    "candidate" to (detection?.candidate?.label ?: ""),
                    "mode" to "elm_command"
                )
            )
            response
        }
    }

    private suspend fun readTextResponse(timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val builder = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val chunk = runCatching { connection.receive(0) }.getOrNull()
            if (chunk == null || chunk.isEmpty()) {
                delay(10L)
                continue
            }
            val text = chunk.toString(StandardCharsets.US_ASCII)
            builder.append(text)
            if (builder.contains(ELM_PROMPT)) {
                break
            }
        }
        return builder.toString().trim()
    }

    private suspend fun readKwpResponse(timeoutMs: Long): ByteArray {
        val responseText = readTextResponse(timeoutMs)
        responseText
            .split("\r")
            .map { it.trim() }
            .firstOrNull { parseHexLine(it).isNotEmpty() }
            ?.let { return parseHexLine(it) }
        return byteArrayOf()
    }

    private fun parseHexLine(line: String): ByteArray {
        val compact = line.replace(Regex("\\s+"), "")
        if (!compact.matches(Regex("^[0-9A-Fa-f]+$")) || compact.length % 2 != 0) {
            return byteArrayOf()
        }
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun asciiSlice(bytes: ByteArray, startInclusive: Int, endExclusive: Int): String {
        return bytes
            .copyOfRange(startInclusive.coerceAtMost(bytes.size), endExclusive.coerceAtMost(bytes.size))
            .map { value ->
                val ch = (value.toInt() and 0xFF).toChar()
                if (ch in ' '..'~') ch else ' '
            }
            .joinToString("")
            .trim()
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(Locale.US, it.toInt() and 0xFF) }

    private fun formatDouble(value: Double): String {
        val rounded = value.roundToInt().toDouble()
        return if (abs(value - rounded) < 0.0001) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }

    private fun safeDisconnect() {
        runCatching { ioMutex.tryLock() }.getOrDefault(false).let { locked ->
            if (locked) {
                try {
                    runCatching {
                        connection.clearInputBuffer()
                        connection.send("82 \r".toByteArray(StandardCharsets.US_ASCII))
                    }
                } finally {
                    ioMutex.unlock()
                }
            }
        }
        runCatching { connection.disconnect() }
        streaming = false
        streamJob?.cancel()
        streamJob = null
        firmwareInfo = null
        detection = null
        connectedAtMs = 0L
        pollCycle = 0L
        investigationRecorder?.closeSession(
            summary = mapOf(
                "transport" to "vag",
                "candidate" to (detection?.candidate?.label ?: "")
            )
        )
    }
}
