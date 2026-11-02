package com.speeduino.manager.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestorePointStoreTest {
    private fun fixture(): Triple<RestorePointStore, java.io.File, java.io.File> {
        val base = Files.createTempDirectory("rp").toFile()
        val source = java.io.File(base, "session").apply { mkdirs() }
        java.io.File(source, "page_1.bin").writeBytes(ByteArray(10))
        java.io.File(source, "page_2.bin").writeBytes(ByteArray(20))
        return Triple(RestorePointStore(java.io.File(base, "points")), source, base)
    }

    @Test fun createListRenameDelete() {
        val (store, source, base) = fixture()
        val point = store.create(source, "  Antes  ", "MS2", "MS2Extra")
        assertEquals("Antes", point.name)
        assertEquals(2, point.pageCount)
        assertEquals(1, store.list().size)
        assertTrue(store.rename(point.id, "Depois"))
        assertEquals("Depois", store.find(point.id)?.name)
        assertTrue(store.delete(point.id))
        assertTrue(store.list().isEmpty())
        base.deleteRecursively()
    }

    @Test fun rejectsPathTraversal() {
        val (store, _, base) = fixture()
        assertNull(store.find("../session"))
        assertFalse(store.delete("../session"))
        base.deleteRecursively()
    }
}
