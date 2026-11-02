package com.speeduino.manager.desktop.realdash

import io.ecucore.EcuLiveData
import io.ecucore.dashboard.resolveSignalSourceValue

/**
 * Valor bruto (unidades métricas) do sinal pedido por [io.ecucore.realdash.RealDashSignalMap], ou null se a ECU
 * não o fornece. Usa o mesmo resolvedor do app Android (`resolveSignalSourceValue`, no ecu-core): o arquivo do
 * RealDash assume métrico, então a unidade devolvida é ignorada.
 */
internal fun resolveRealDashSignal(liveData: EcuLiveData?, signal: String, stoichRatio: Double = 14.7): Double? =
    resolveSignalSourceValue(liveData, signal, stoichRatio)?.first
