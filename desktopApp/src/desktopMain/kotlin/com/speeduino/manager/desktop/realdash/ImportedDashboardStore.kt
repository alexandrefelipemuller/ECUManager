package com.speeduino.manager.desktop.realdash

import io.ecucore.realdash.RdFile
import com.speeduino.manager.desktop.DesktopSettingsStore
import java.io.File
import java.util.Properties

/** Um dashboard importado de um arquivo .rd do RealDash. */
data class ImportedDashboardInfo(val id: String, val name: String)

/**
 * Guarda os .rd importados em `<settings>/imported_dashboards/<id>.rd` e o índice (id + nome)
 * em `index.properties`, para listar sem precisar reler cada arquivo.
 */
class ImportedDashboardStore(private val dir: File = DesktopSettingsStore.settingsDir().resolve("imported_dashboards")) {
    private val indexFile get() = File(dir, "index.properties")

    fun list(): List<ImportedDashboardInfo> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val p = Properties().also { indexFile.inputStream().use(it::load) }
            p.stringPropertyNames().sorted()
                .map { ImportedDashboardInfo(it, p.getProperty(it)) }
                .filter { File(dir, "${it.id}.rd").isFile }
        }.getOrDefault(emptyList())
    }

    /** Valida os bytes (parse completo) e salva como um novo dashboard. Lança exceção se o .rd for inválido. */
    fun import(bytes: ByteArray, fallbackName: String): ImportedDashboardInfo {
        val dash = RdFile.parse(bytes)
        require(dash.pages.isNotEmpty()) { "O arquivo .rd não tem páginas" }
        val existing = list()
        val baseName = dash.name.trim().ifEmpty { fallbackName.removeSuffix(".rd") }.ifEmpty { "Imported" }
        val name = uniqueName(baseName, existing.map { it.name })
        val id = "rd_${System.currentTimeMillis()}"
        dir.mkdirs()
        File(dir, "$id.rd").writeBytes(bytes)
        val info = ImportedDashboardInfo(id, name)
        saveIndex(existing + info)
        return info
    }

    fun load(id: String): RdFile? {
        val file = File(dir, "$id.rd")
        if (!file.isFile) return null
        return runCatching { RdFile.parse(file.readBytes()) }.getOrNull()
    }

    fun delete(id: String) {
        File(dir, "$id.rd").delete()
        saveIndex(list().filterNot { it.id == id })
    }

    private fun saveIndex(items: List<ImportedDashboardInfo>) {
        dir.mkdirs()
        val p = Properties()
        items.forEach { p.setProperty(it.id, it.name) }
        indexFile.outputStream().use { p.store(it, null) }
    }

    private fun uniqueName(base: String, taken: List<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }
}
