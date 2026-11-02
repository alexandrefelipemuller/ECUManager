package com.speeduino.manager.desktop

import java.io.File
import java.util.Properties

/**
 * Ponto de restauração guardado pelo app (cópia completa de uma sessão de configuração).
 * Permite restaurar a ECU sem manipular arquivos; a exportação ZIP/MSQ parte daqui.
 * Espelha `RestorePointStore` do app Android.
 */
internal data class RestorePoint(
    val id: String,
    val dir: File,
    val name: String?,
    val createdAtEpochMs: Long,
    val ecuFamily: String?,
    val firmware: String?,
    val pageCount: Int,
    val sizeBytes: Long,
)

internal class RestorePointStore(
    private val root: File = DesktopSettingsStore.settingsDir().resolve(ROOT_DIR),
) {
    init { root.mkdirs() }

    fun list(): List<RestorePoint> =
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { readPoint(it) }
            ?.sortedByDescending { it.createdAtEpochMs }
            ?: emptyList()

    fun find(id: String): RestorePoint? = resolveDir(id)?.let { readPoint(it) }

    /** Copia [source] (sessão baixada da ECU) para um novo ponto de restauração. */
    fun create(source: File, name: String?, ecuFamily: String?, firmware: String?): RestorePoint {
        val now = System.currentTimeMillis()
        val dest = File(root, "rp_$now")
        try {
            check(source.copyRecursively(dest, overwrite = true)) { "copy failed" }
            writeInfo(dest, Info(name?.trim()?.ifBlank { null }, now, ecuFamily, firmware))
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw e
        }
        return checkNotNull(readPoint(dest))
    }

    fun rename(id: String, name: String): Boolean {
        val dir = resolveDir(id) ?: return false
        val info = readInfo(dir) ?: return false
        writeInfo(dir, info.copy(name = name.trim().ifBlank { null }))
        return true
    }

    fun delete(id: String): Boolean = resolveDir(id)?.deleteRecursively() ?: false

    /** Resolve o id para uma pasta dentro de [root] (impede path traversal). */
    private fun resolveDir(id: String): File? {
        val dir = File(root, id)
        return dir.takeIf { it.isDirectory && it.parentFile?.canonicalPath == root.canonicalPath }
    }

    private data class Info(val name: String?, val createdAt: Long, val ecuFamily: String?, val firmware: String?)

    private fun readInfo(dir: File): Info? {
        val file = File(dir, INFO_FILE)
        if (!file.isFile) return null
        val p = Properties()
        runCatching { file.inputStream().use { p.load(it) } }.onFailure { return null }
        return Info(
            name = p.getProperty(KEY_NAME)?.ifBlank { null },
            createdAt = p.getProperty(KEY_CREATED_AT)?.toLongOrNull() ?: dir.lastModified(),
            ecuFamily = p.getProperty(KEY_FAMILY)?.ifBlank { null },
            firmware = p.getProperty(KEY_FIRMWARE)?.ifBlank { null },
        )
    }

    private fun writeInfo(dir: File, info: Info) {
        val p = Properties()
        info.name?.let { p.setProperty(KEY_NAME, it) }
        p.setProperty(KEY_CREATED_AT, info.createdAt.toString())
        info.ecuFamily?.let { p.setProperty(KEY_FAMILY, it) }
        info.firmware?.let { p.setProperty(KEY_FIRMWARE, it) }
        File(dir, INFO_FILE).outputStream().use { p.store(it, null) }
    }

    private fun readPoint(dir: File): RestorePoint? {
        val info = readInfo(dir) ?: return null
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        return RestorePoint(
            id = dir.name,
            dir = dir,
            name = info.name,
            createdAtEpochMs = info.createdAt,
            ecuFamily = info.ecuFamily,
            firmware = info.firmware,
            pageCount = files.count { PAGE_FILE_REGEX.matches(it.name.lowercase()) },
            sizeBytes = files.sumOf { it.length() },
        )
    }

    companion object {
        const val ROOT_DIR = "restore_points"
        const val INFO_FILE = "restore_point.properties"
        private const val KEY_NAME = "name"
        private const val KEY_CREATED_AT = "created_at_epoch_ms"
        private const val KEY_FAMILY = "ecu_family"
        private const val KEY_FIRMWARE = "firmware"
        private val PAGE_FILE_REGEX = Regex("""page_-?\d+\.bin""")
    }
}
