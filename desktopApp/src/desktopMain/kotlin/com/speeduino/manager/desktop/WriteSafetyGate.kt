package com.speeduino.manager.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pedido exibido pela UI antes de uma gravação (HARA H9). Espelha o app Android. */
internal sealed interface WriteSafetyRequest {
    /** Aviso de uso, mostrado uma vez antes da primeira gravação. */
    data object FirstUseNotice : WriteSafetyRequest

    /** O veículo parece estar em movimento. */
    data class VehicleMoving(val speedKmh: Double) : WriteSafetyRequest
}

/**
 * Estima se o veículo está em movimento a partir das velocidades disponíveis (HARA H9).
 * Best-effort: vem de OBD2/PSA (`candidateSpeedKph`) ou do GPS; sem fonte, a velocidade é
 * desconhecida e só o aviso de uso da primeira gravação vale.
 */
internal object VehicleMotion {
    const val MOVING_THRESHOLD_KMH = 5.0

    fun speedKmh(candidateSpeedKph: Int?, gpsSpeedKph: Double?): Double? =
        listOfNotNull(candidateSpeedKph?.toDouble(), gpsSpeedKph?.takeIf { it >= 0.0 }).maxOrNull()

    fun isMoving(speedKmh: Double?): Boolean = speedKmh != null && speedKmh >= MOVING_THRESHOLD_KMH
}

/**
 * Autorização antes de qualquer escrita na ECU (`EcuClient.writeGuard`):
 *  1. aviso de uso (bancada/off-road, responsabilidade do usuário) uma única vez;
 *  2. se a velocidade indica movimento, confirma que a edição não é feita por quem dirige; a
 *     resposta vale por [movingAckWindowMs] para não perguntar a cada página de uma restauração.
 */
internal class WriteSafetyGate(
    private val isNoticeAccepted: () -> Boolean,
    private val markNoticeAccepted: () -> Unit,
    private val currentSpeedKmh: () -> Double?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val movingAckWindowMs: Long = MOVING_ACK_WINDOW_MS,
) {
    private val _pending = MutableStateFlow<WriteSafetyRequest?>(null)
    val pending: StateFlow<WriteSafetyRequest?> = _pending.asStateFlow()

    @Volatile private var decision: CompletableDeferred<Boolean>? = null
    private var movingAckedAtMs: Long? = null

    suspend fun authorize(): Boolean {
        if (!isNoticeAccepted()) {
            if (!ask(WriteSafetyRequest.FirstUseNotice)) return false
            markNoticeAccepted()
        }
        val speed = currentSpeedKmh()
        if (VehicleMotion.isMoving(speed)) {
            val ackedAt = movingAckedAtMs
            val stillAcked = ackedAt != null && nowMs() - ackedAt < movingAckWindowMs
            if (!stillAcked) {
                if (!ask(WriteSafetyRequest.VehicleMoving(speed!!))) return false
                movingAckedAtMs = nowMs()
            }
        }
        return true
    }

    fun resolve(accepted: Boolean) {
        decision?.complete(accepted)
    }

    private suspend fun ask(request: WriteSafetyRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        decision = deferred
        _pending.value = request
        return try {
            deferred.await()
        } finally {
            _pending.value = null
            if (decision === deferred) decision = null
        }
    }

    companion object {
        const val MOVING_ACK_WINDOW_MS = 2 * 60 * 1000L
    }
}
