package com.speeduino.manager.desktop

import io.ecucore.model.TableChangeAssessment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ponte entre o `EcuClient.tableChangeGuard` (coroutine de gravação) e o diálogo de
 * confirmação (UI): [confirm] publica o pedido e suspende até [resolve].
 *
 * Fluxos em que a substituição em massa é o objetivo (ex.: desfazer) rodam dentro de
 * [withoutConfirmation] e não perguntam.
 */
internal class TableChangeConfirmation {
    private val _pending = MutableStateFlow<TableChangeAssessment?>(null)
    val pending: StateFlow<TableChangeAssessment?> = _pending.asStateFlow()

    private val bypassDepth = AtomicInteger(0)
    @Volatile private var decision: CompletableDeferred<Boolean>? = null

    suspend fun confirm(assessment: TableChangeAssessment): Boolean {
        if (bypassDepth.get() > 0) return true
        val deferred = CompletableDeferred<Boolean>()
        decision = deferred
        _pending.value = assessment
        return try {
            deferred.await()
        } finally {
            _pending.value = null
            if (decision === deferred) decision = null
        }
    }

    fun resolve(accepted: Boolean) {
        decision?.complete(accepted)
    }

    suspend fun <T> withoutConfirmation(block: suspend () -> T): T {
        bypassDepth.incrementAndGet()
        try {
            return block()
        } finally {
            bypassDepth.decrementAndGet()
        }
    }
}
