package com.speeduino.manager.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class UndoTableKind { VE, IGNITION, AFR, DWELL }

/** Uma tabela desfazível: o tipo e o mapa (VE/Ignição podem ter mais de um). */
internal data class UndoKey(val kind: UndoTableKind, val mapIndex: Int = 1)

/**
 * Guarda, em memória, as tabelas anteriores às últimas gravações (VE, Ignição, AFR, Dwell) para o
 * botão "Desfazer". É só da sessão de conexão: ao desconectar, [clear] descarta tudo, já que uma
 * tabela de outra ECU/firmware nunca pode ser regravada por engano.
 */
internal class TableUndoStore(private val maxDepth: Int = DEFAULT_DEPTH) {
    private val stacks = LinkedHashMap<UndoKey, ArrayDeque<Any>>()
    private val _available = MutableStateFlow<Set<UndoKey>>(emptySet())

    /** Chaves que têm algo para desfazer; a UI observa para habilitar o botão. */
    val available: StateFlow<Set<UndoKey>> = _available.asStateFlow()

    @Synchronized
    fun push(key: UndoKey, previous: Any) {
        val stack = stacks.getOrPut(key) { ArrayDeque() }
        if (stack.lastOrNull() == previous) return
        stack.addLast(previous)
        while (stack.size > maxDepth) stack.removeFirst()
        publish()
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T : Any> peek(key: UndoKey): T? = stacks[key]?.lastOrNull() as T?

    /** Remove o topo (chamado depois que o desfazer foi gravado com sucesso). */
    @Synchronized
    fun pop(key: UndoKey) {
        val stack = stacks[key] ?: return
        stack.removeLastOrNull()
        if (stack.isEmpty()) stacks.remove(key)
        publish()
    }

    @Synchronized
    fun clear() {
        stacks.clear()
        publish()
    }

    private fun publish() {
        _available.value = stacks.filterValues { it.isNotEmpty() }.keys.toSet()
    }

    companion object {
        const val DEFAULT_DEPTH = 5
    }
}
