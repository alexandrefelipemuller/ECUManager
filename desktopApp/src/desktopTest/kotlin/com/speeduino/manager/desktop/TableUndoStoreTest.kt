package com.speeduino.manager.desktop

import io.ecucore.safety.TableChangeConfirmation
import io.ecucore.safety.TableUndoStore
import io.ecucore.safety.UndoKey
import io.ecucore.safety.UndoTableKind
import io.ecucore.model.TableChangeAssessment
import io.ecucore.model.TableChangeKind
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableUndoStoreTest {
    private val ve1 = UndoKey(UndoTableKind.VE, 1)
    private val ve2 = UndoKey(UndoTableKind.VE, 2)

    @Test
    fun pushPeekPopPerKey() {
        val store = TableUndoStore()
        store.push(ve1, "a")
        store.push(ve1, "b")
        store.push(ve2, "c")
        assertEquals("b", store.peek<String>(ve1))
        assertEquals(setOf(ve1, ve2), store.available.value)
        store.pop(ve1)
        assertEquals("a", store.peek<String>(ve1))
        store.pop(ve1)
        assertNull(store.peek<String>(ve1))
        assertEquals(setOf(ve2), store.available.value)
    }

    @Test
    fun ignoresDuplicateTopAndCapsDepth() {
        val store = TableUndoStore(maxDepth = 2)
        store.push(ve1, "a")
        store.push(ve1, "a")
        store.push(ve1, "b")
        store.push(ve1, "c")
        store.pop(ve1)
        assertEquals("b", store.peek<String>(ve1))
        store.pop(ve1)
        assertTrue(store.available.value.isEmpty())
    }

    @Test
    fun clearDropsEverything() {
        val store = TableUndoStore()
        store.push(UndoKey(UndoTableKind.DWELL), "x")
        store.clear()
        assertTrue(store.available.value.isEmpty())
    }

    private val assessment = TableChangeAssessment(TableChangeKind.VE, 10, 5, 5, 40, 0 to 0, shapeChanged = false)

    @Test
    fun confirmationWaitsForResolve() = runBlocking {
        val confirmation = TableChangeConfirmation()
        val result = async { confirmation.confirm(assessment) }
        while (confirmation.pending.value == null) yield()
        confirmation.resolve(true)
        assertTrue(result.await())
        assertNull(confirmation.pending.value)
    }

    @Test
    fun confirmationBypassedWhenWithoutConfirmation() = runBlocking {
        val confirmation = TableChangeConfirmation()
        val accepted = confirmation.withoutConfirmation { confirmation.confirm(assessment) }
        assertTrue(accepted)
        assertFalse(confirmation.pending.value != null)
    }
}
