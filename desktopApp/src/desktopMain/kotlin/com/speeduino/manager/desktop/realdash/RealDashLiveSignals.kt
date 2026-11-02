package com.speeduino.manager.desktop.realdash

import io.ecucore.EcuLiveData

/**
 * Valor bruto (unidades métricas) do sinal pedido por [RealDashSignalMap], ou null se a ECU não o fornece.
 * Versão enxuta do `resolveSignalRawValue` do app Android: o arquivo do RealDash assume métrico.
 */
internal fun resolveRealDashSignal(liveData: EcuLiveData?, signal: String, stoichRatio: Double = 14.7): Double? {
    val d = liveData ?: return null
    return when (signal) {
        "rpm" -> d.rpm.toDouble()
        "map" -> d.mapPressure.toDouble()
        "coolant", "coolantRaw" -> d.coolantTemp.toDouble()
        "iat", "intake", "iatRaw" -> d.intakeTemp.toDouble()
        "tps" -> d.tps.toDouble()
        "batteryVoltage" -> d.batteryVoltage
        "advance" -> d.advance.toDouble()
        "afr" -> d.o2.toDouble()
        "lambda" -> d.o2.toDouble() / stoichRatio.takeIf { it > 0.0 }.let { it ?: 14.7 }
        PETROL_CENTER_SIGNAL_BOOST_KPA -> (d.mapPressure - 100).toDouble()
        PETROL_CENTER_SIGNAL_BOOST_BAR -> (d.mapPressure - 100).toDouble() / 100.0
        PETROL_CENTER_SIGNAL_BOOST_PSI -> (d.mapPressure - 100).toDouble() * 0.1450377377
        "vss" -> d.candidateSpeedKph?.toDouble() ?: d.gpsSpeedKph
        "candidateGear" -> d.candidateGear?.toDouble()
        else -> null
    }
}
