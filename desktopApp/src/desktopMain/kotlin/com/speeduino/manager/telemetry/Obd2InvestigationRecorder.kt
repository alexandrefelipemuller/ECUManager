package com.speeduino.manager.telemetry

import io.ecucore.EcuLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Persiste sessões de investigação OBD2/PSA em arquivos para análise off-line.
 *
 * Os arquivos são gravados em ~/.speeduino-manager-desktop/logs/obd2_investigation_<timestamp>/.
 */
class Obd2InvestigationRecorder(
    private val baseDir: File = File(System.getProperty("user.home"), ".speeduino-manager-desktop"),
) {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dirFormatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val lock = Any()

    private var sessionDir: File? = null
    private var traceFile: File? = null
    private var sampleFile: File? = null
    private var commandFile: File? = null
    private var proprietaryFrameFile: File? = null
    private var metadataFile: File? = null
    private var metadata = JSONObject()
    private var sampleHeaderWritten = false
    private var commandHeaderWritten = false
    private var proprietaryFrameHeaderWritten = false
    @Volatile private var gpsSpeedKmh: Double? = null
    @Volatile private var gpsAccuracyMeters: Float? = null

    fun updateGpsSnapshot(speedKmh: Double?, accuracyMeters: Float?) {
        gpsSpeedKmh = speedKmh
        gpsAccuracyMeters = accuracyMeters
    }

    fun getLatestSessionDir(): File? {
        synchronized(lock) {
            sessionDir?.let { if (it.exists()) return it }
            val logsDir = File(baseDir, "logs")
            return logsDir
                .listFiles { file -> file.isDirectory && file.name.startsWith("obd2_investigation_") }
                ?.maxByOrNull { it.lastModified() }
        }
    }

    fun packageLatestSessionZip(): File? {
        synchronized(lock) {
            val dir = getLatestSessionDir() ?: return null
            val zipFile = File(dir.parentFile, "${dir.name}.zip")
            FileOutputStream(zipFile).use { output ->
                ZipOutputStream(output).use { zip ->
                    dir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            val entryName = "${dir.name}/${file.relativeTo(dir).invariantSeparatorsPath}"
                            zip.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                        }
                }
            }
            return zipFile
        }
    }

    fun startSession(
        transport: String,
        metadataExtras: Map<String, String> = emptyMap(),
    ) {
        synchronized(lock) {
            closeSession(mapOf("closed_reason" to "superseded"))
            val timestamp = System.currentTimeMillis()
            val logsDir = File(baseDir, "logs").apply { mkdirs() }
            val dir = File(logsDir, "obd2_investigation_${dirFormatter.format(Date(timestamp))}").apply { mkdirs() }
            sessionDir = dir
            traceFile = File(dir, "session_trace.txt")
            sampleFile = File(dir, "decoded_samples.csv")
            commandFile = File(dir, "command_timeline.csv")
            proprietaryFrameFile = File(dir, "proprietary_frames.csv")
            metadataFile = File(dir, "metadata.json")
            sampleHeaderWritten = false
            commandHeaderWritten = false
            proprietaryFrameHeaderWritten = false

            metadata = JSONObject().apply {
                put("started_at_epoch_ms", timestamp)
                put("started_at_text", formatter.format(Date(timestamp)))
                put("transport", transport)
                put("extras", JSONObject(metadataExtras))
            }
            writeMetadataLocked()
            info("session", "started transport=$transport dir=${dir.name}")
        }
    }

    fun closeSession(summary: Map<String, String> = emptyMap()) {
        synchronized(lock) {
            val dir = sessionDir ?: return
            val closedAt = System.currentTimeMillis()
            metadata.put("closed_at_epoch_ms", closedAt)
            metadata.put("closed_at_text", formatter.format(Date(closedAt)))
            metadata.put("summary", JSONObject(summary))
            writeMetadataLocked()
            traceFile?.appendText("[${formatter.format(Date(closedAt))}] stage=session msg=closed dir=${dir.name}\n")
            sessionDir = null
            traceFile = null
            sampleFile = null
            commandFile = null
            proprietaryFrameFile = null
            metadataFile = null
            sampleHeaderWritten = false
            commandHeaderWritten = false
            proprietaryFrameHeaderWritten = false
            metadata = JSONObject()
        }
    }

    fun info(stage: String, message: String) {
        synchronized(lock) {
            val file = traceFile ?: return
            val timestamp = System.currentTimeMillis()
            file.appendText("[${formatter.format(Date(timestamp))}] stage=$stage msg=$message\n")
        }
    }

    fun recordCommand(
        transport: String,
        command: String,
        response: String,
        timeoutMs: Long,
        elapsedMs: Long,
        extra: Map<String, String> = emptyMap(),
    ) {
        synchronized(lock) {
            val trace = traceFile ?: return
            val csv = commandFile ?: return
            if (!commandHeaderWritten) {
                csv.writeText("timestamp_ms,timestamp_text,transport,command,timeout_ms,elapsed_ms,response,extras\n")
                commandHeaderWritten = true
            }

            val timestamp = System.currentTimeMillis()
            val compactResponse = response
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\"", "\"\"")
            val compactExtras = JSONObject(extra).toString().replace("\"", "\"\"")
            csv.appendText(
                buildString {
                    append(timestamp)
                    append(",")
                    append('"').append(formatter.format(Date(timestamp))).append('"')
                    append(",")
                    append('"').append(transport).append('"')
                    append(",")
                    append('"').append(command.replace("\"", "\"\"")).append('"')
                    append(",")
                    append(timeoutMs)
                    append(",")
                    append(elapsedMs)
                    append(",")
                    append('"').append(compactResponse).append('"')
                    append(",")
                    append('"').append(compactExtras).append('"')
                    append("\n")
                }
            )
            trace.appendText(
                "[${formatter.format(Date(timestamp))}] stage=command transport=$transport timeoutMs=$timeoutMs elapsedMs=$elapsedMs cmd=$command resp=${response.replace(Regex("\\s+"), " ").trim().take(240)}\n"
            )
        }
    }

    fun recordSample(
        source: String,
        sample: EcuLiveData,
        extras: Map<String, String> = emptyMap(),
    ) {
        synchronized(lock) {
            val csv = sampleFile ?: return
            if (!sampleHeaderWritten) {
                csv.writeText(
                    "timestamp_ms,timestamp_text,source,secl,rpm,coolant,intake,map,tps,battery,advance,o2,engine_status,spark_status,candidate_speed_kmh,candidate_pedal_pct,candidate_throttle_angle_deg,candidate_ignition_advance_deg,candidate_inj_time_ms,candidate_inj_time_mirror_ms,gps_speed_kmh,gps_accuracy_m,extras\n"
                )
                sampleHeaderWritten = true
            }
            val timestamp = System.currentTimeMillis()
            val gpsSpeed = gpsSpeedKmh
            val gpsAccuracy = gpsAccuracyMeters
            csv.appendText(
                buildString {
                    append(timestamp)
                    append(",")
                    append('"').append(formatter.format(Date(timestamp))).append('"')
                    append(",")
                    append('"').append(source).append('"')
                    append(",")
                    append(sample.secl)
                    append(",")
                    append(sample.rpm)
                    append(",")
                    append(sample.coolantTemp)
                    append(",")
                    append(sample.intakeTemp)
                    append(",")
                    append(sample.mapPressure)
                    append(",")
                    append(sample.tps)
                    append(",")
                    append(String.format(Locale.US, "%.2f", sample.batteryVoltage))
                    append(",")
                    append(sample.advance)
                    append(",")
                    append(sample.o2)
                    append(",")
                    append(sample.engineStatus)
                    append(",")
                    append(sample.sparkStatus)
                    append(",")
                    sample.candidateSpeedKph?.let { append(it) }
                    append(",")
                    sample.candidateAccelPedalPosPct?.let { append(it) }
                    append(",")
                    sample.candidateThrottleAngleDeg?.let { append(String.format(Locale.US, "%.2f", it)) }
                    append(",")
                    sample.candidateIgnitionAdvanceDeg?.let { append(String.format(Locale.US, "%.2f", it)) }
                    append(",")
                    sample.candidateInjectionDurationMs?.let { append(String.format(Locale.US, "%.2f", it)) }
                    append(",")
                    sample.candidateInjectionDurationMirrorMs?.let { append(String.format(Locale.US, "%.2f", it)) }
                    append(",")
                    gpsSpeed?.let { append(String.format(Locale.US, "%.2f", it)) }
                    append(",")
                    gpsAccuracy?.let { append(String.format(Locale.US, "%.2f", it)) }
                    append(",")
                    append('"').append(JSONObject(extras).toString().replace("\"", "\"\"")).append('"')
                    append("\n")
                }
            )
        }
    }

    fun recordProprietaryFrame(
        transport: String,
        frameType: String,
        payload: String,
        extras: Map<String, String> = emptyMap(),
    ) {
        synchronized(lock) {
            val csv = proprietaryFrameFile ?: return
            if (!proprietaryFrameHeaderWritten) {
                csv.writeText("timestamp_ms,timestamp_text,transport,frame_type,payload,extras\n")
                proprietaryFrameHeaderWritten = true
            }
            val timestamp = System.currentTimeMillis()
            csv.appendText(
                "${timestamp},\"${formatter.format(Date(timestamp))}\",\"$transport\",\"$frameType\",\"${payload.replace("\"", "\"\"")}\",\"${JSONObject(extras).toString().replace("\"", "\"\"")}\"\n"
            )
        }
    }

    fun recordProprietaryFrame(
        source: String,
        cycle: Long,
        command: String,
        rawHex: String,
        bytes: List<Int>,
        extras: Map<String, String> = emptyMap(),
    ) {
        recordProprietaryFrame(
            transport = source,
            frameType = "cycle=$cycle command=$command",
            payload = rawHex,
            extras = extras + mapOf("bytes" to bytes.joinToString(","))
        )
    }

    fun updateMetadata(key: String, value: Any?) {
        synchronized(lock) {
            metadata.put(key, value)
            writeMetadataLocked()
        }
    }

    private fun writeMetadataLocked() {
        metadataFile?.writeText(metadata.toString(2))
    }
}
