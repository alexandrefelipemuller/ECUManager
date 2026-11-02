package com.speeduino.manager.desktop.realdash

import com.speeduino.manager.desktop.DesktopSettingsStore
import java.io.File
import java.util.Properties

/**
 * Hodômetro calculado pelo app: integra a velocidade (km/h) ao longo do tempo e persiste em arquivo.
 * Alimenta os targets do RealDash 310 (Odometer) e 311 (Tripmeter A), que a ECU não fornece.
 * Só acumula enquanto um dashboard importado está na tela recebendo live data.
 */
class Odometer(private val file: File = DesktopSettingsStore.settingsDir().resolve("rd_odometer.properties")) {
    private val props = Properties().also { p -> if (file.isFile) runCatching { file.inputStream().use(p::load) } }

    var odometerKm: Double = props.getProperty(KEY_ODO)?.toDoubleOrNull() ?: 0.0
        private set
    var tripKm: Double = props.getProperty(KEY_TRIP)?.toDoubleOrNull() ?: 0.0
        private set

    private var lastSampleMs = 0L
    private var lastSavedMs = 0L

    /** [speedKph] nulo = sem velocidade; o relógio é reiniciado para não contar o intervalo sem dados. */
    fun update(speedKph: Double?, nowMs: Long = System.currentTimeMillis()) {
        val previous = lastSampleMs
        if (speedKph == null || speedKph < 0.0) { lastSampleMs = 0L; return }
        lastSampleMs = nowMs
        if (previous == 0L) return
        val dt = nowMs - previous
        if (dt <= 0L || dt > MAX_GAP_MS) return   // lacuna longa (pausa/reconexão): não extrapolar
        val km = speedKph * dt / 3_600_000.0
        odometerKm += km
        tripKm += km
        if (nowMs - lastSavedMs >= SAVE_INTERVAL_MS) save(nowMs)
    }

    fun resetTrip() { tripKm = 0.0; save(System.currentTimeMillis()) }

    fun save(nowMs: Long = System.currentTimeMillis()) {
        lastSavedMs = nowMs
        props.setProperty(KEY_ODO, odometerKm.toString())
        props.setProperty(KEY_TRIP, tripKm.toString())
        runCatching { file.outputStream().use { props.store(it, null) } }
    }

    companion object {
        const val ODOMETER_TARGET_ID = 310
        const val TRIPMETER_A_TARGET_ID = 311
        private const val KEY_ODO = "odometer_km"
        private const val KEY_TRIP = "trip_a_km"
        private const val MAX_GAP_MS = 5_000L
        private const val SAVE_INTERVAL_MS = 5_000L
    }
}
