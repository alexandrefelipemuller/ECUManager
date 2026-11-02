package com.speeduino.manager.desktop.realdash

import io.ecucore.EcuLiveData
import io.ecucore.model.EcuOutputChannels

/**
 * Valor bruto (unidades métricas) do sinal pedido por [RealDashSignalMap], ou null se a ECU não o fornece.
 * Versão enxuta do `resolveSignalRawValue` do app Android: o arquivo do RealDash assume métrico.
 */
internal fun resolveRealDashSignal(liveData: EcuLiveData?, signal: String, stoichRatio: Double = 14.7): Double? {
    val d = liveData ?: return null
    // Prioridade igual à do Android (`resolveSignalValue`): sinais canônicos, depois o canal de saída da
    // ECU pelo nome (ex.: "vss" em Speeduino/MS), e só então o que o app decodifica (OBD2/GPS).
    resolveCanonical(d, signal, stoichRatio)?.let { return it }
    val data = d.outputChannelData
    if (data != null && d.outputChannelBlockSize > 0) {
        EcuOutputChannels.getField(d.outputChannelBlockSize, signal)?.let { return it.parse(data) }
    }
    return resolveFallback(d, signal, stoichRatio)
}

private fun resolveCanonical(d: EcuLiveData, signal: String, stoichRatio: Double): Double? {
    return when (signal) {
        "rpm" -> d.rpm.toDouble()
        "map" -> d.mapPressure.toDouble()
        "coolant", "coolantRaw" -> d.coolantTemp.toDouble()
        "iat", "intake", "iatRaw" -> d.intakeTemp.toDouble()
        "tps" -> d.tps.toDouble()
        "batteryVoltage" -> d.batteryVoltage
        "advance" -> d.advance.toDouble()
        "afr" -> d.o2.toDouble()
        else -> null
    }
}

private fun resolveFallback(d: EcuLiveData, signal: String, stoichRatio: Double): Double? {
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
