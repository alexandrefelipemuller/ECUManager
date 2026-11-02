package com.speeduino.manager.desktop

import io.ecucore.safety.WriteSafetyGate
import io.ecucore.safety.WriteSafetyRequest
import io.ecucore.ConfigManager
import io.ecucore.EcuClient
import io.ecucore.TableChangeRejectedException
import io.ecucore.WriteNotPermittedException
import io.ecucore.connection.EcuTcpConnection
import io.ecucore.model.EcuFamily
import io.ecucore.model.VeTable
import io.ecucore.sync.BackupCorruptedException
import io.ecucore.sync.ConfigSyncService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integração ponta a ponta contra os simuladores TCP do app Android (`EcuManager/simulator`).
 * Exercita o `EcuClient` real + os fluxos portados para o Desktop (WriteSafetyGate, confirmação de
 * mudança grande, download/restauração com CRC32 e backup de segurança, pontos de restauração).
 *
 * Os testes são ignorados (retornam sem falhar) se `python3` ou os simuladores não existirem.
 * Aponte outro diretório com a variável de ambiente `ECU_SIMULATOR_DIR`.
 */
class SimulatorIntegrationTest {

    private class Simulator(val script: String) : AutoCloseable {
        val port = ServerSocket(0).use { it.localPort }
        private var process: Process? = null

        val available: Boolean
            get() = simulatorDir().resolve(script).isFile && runCatching {
                ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)

        fun start(): Boolean {
            process = ProcessBuilder("python3", "-u", simulatorDir().resolve(script).path, "--host", "127.0.0.1", "--port", port.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            repeat(100) {
                if (runCatching { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess) return true
                Thread.sleep(100)
            }
            return false
        }

        override fun close() {
            process?.destroy()
            process?.waitFor()
        }
    }

    private fun connected(sim: Simulator): EcuClient = EcuClient(
        connection = EcuTcpConnection("127.0.0.1", sim.port),
        onDataReceived = {},
        onConnectionStateChanged = {},
        onError = {},
    ).also { runBlocking { it.connect() } }

    private fun VeTable.withCell(row: Int, col: Int, delta: Int): VeTable = copy(
        values = values.mapIndexed { r, line -> line.mapIndexed { c, v -> if (r == row && c == col) v + delta else v } }
    )

    private fun runScenario(script: String, family: EcuFamily, speeduinoGuards: Boolean) {
        val sim = Simulator(script)
        if (!sim.available) return
        sim.use {
            assertTrue(sim.start(), "simulador $script não abriu a porta ${sim.port}")
            val client = connected(sim)
            val tmp = Files.createTempDirectory("sim-it").toFile()
            try {
                assertEquals(family, client.getEcuFamily())

                // 0. Velocidade do RealDash (ids 64/81 = "vss") deve vir dos dados ao vivo, não ficar em "--".
                // Nenhum sinal do RealDash pode lançar exceção com dados ao vivo reais (derrubaria a tela ao conectar).
                run {
                    val live0 = runBlocking { client.readLiveData() }
                    println("LIVE family=$family block=${live0.outputChannelBlockSize} data=${live0.outputChannelData?.size}")
                    listOf("rpm","map","coolantRaw","iatRaw","tps","batteryVoltage","advance","afr","afrTarget","targetLambda","lambda","vss","candidateGear")
                        .forEach { sig -> com.speeduino.manager.desktop.realdash.resolveRealDashSignal(live0, sig) }
                }
                if (speeduinoGuards) { // o simulador MS2 não injeta velocidade
                    val live = runBlocking { client.readLiveData() }
                    kotlin.test.assertNotNull(
                        com.speeduino.manager.desktop.realdash.resolveRealDashSignal(live, "vss"),
                        "vss deveria resolver a partir do canal de saída da ECU",
                    )
                }

                // 1. Escrita e releitura de uma célula da VE.
                val original = runBlocking { client.readVeTable(1) }
                val delta = if (original.values[0][0] < 250) 1 else -1
                runBlocking { client.writeVeTable(original.withCell(0, 0, delta), 1) }
                val updated = runBlocking { client.readVeTable(1) }
                assertEquals(original.values[0][0] + delta, updated.values[0][0])

                // 2. writeGuard negado (WriteSafetyGate): nada é gravado.
                val gate = WriteSafetyGate(
                    isNoticeAccepted = { true },
                    markNoticeAccepted = {},
                    currentSpeedKmh = { 80.0 },     // veículo em movimento
                )
                client.writeGuard = { gate.authorize() }
                runBlocking {
                    val denier = launch {
                        while (gate.pending.value == null) delay(10)
                        assertEquals(WriteSafetyRequest.VehicleMoving(80.0), gate.pending.value)
                        gate.resolve(false)
                    }
                    assertFailsWith<WriteNotPermittedException> { client.writeVeTable(updated.withCell(0, 0, delta), 1) }
                    denier.join()
                }
                assertEquals(updated.values[0][0], runBlocking { client.readVeTable(1) }.values[0][0])
                client.writeGuard = null

                // 3. Mudança grande de tabela exige confirmação (Speeduino).
                if (speeduinoGuards) {
                    var assessed = false
                    client.tableChangeGuard = { assessment -> assessed = true; assessment.requiresConfirmation && false }
                    val huge = updated.copy(values = updated.values.map { row -> row.map { if (it > 100) 10 else 200 } })
                    assertFailsWith<TableChangeRejectedException> { runBlocking { client.writeVeTable(huge, 1) } }
                    assertTrue(assessed)
                    assertEquals(updated.values, runBlocking { client.readVeTable(1) }.values)
                    client.tableChangeGuard = null
                }

                // 4. Download completo + ponto de restauração + restauração com CRC32 e backup de segurança.
                val configManager = ConfigManager(tmp)
                val sync = ConfigSyncService(configManager)
                val download = runBlocking { configManager.downloadAllConfigs(client) { _, _, _ -> } }
                assertTrue(download.success, "download falhou: ${download.error}")
                val store = RestorePointStore(File(tmp, "points"))
                val point = store.create(download.sessionDir!!, "integração", family.name, null)
                assertTrue(point.pageCount > 0)

                Thread.sleep(1100)   // sessões são nomeadas por segundo: evita que o backup de segurança reaproveite a pasta
                val outcome = runBlocking { sync.restoreConfigToEcu(client, point.dir, stopOnRangeErr = true, safetyBackup = true) }
                assertTrue(outcome.completed, "restauração incompleta: ${outcome.warnings}")
                assertTrue(configManager.listSavedConfigs().size >= 2, "backup de segurança não foi criado")

                // 5. Página adulterada é recusada antes de gravar.
                val page = point.dir.listFiles { f -> f.name.matches(Regex("""page_-?\d+\.bin""")) }!!.first()
                page.writeBytes(page.readBytes().also { it[0] = (it[0] + 1).toByte() })
                assertFailsWith<BackupCorruptedException> {
                    runBlocking { sync.restoreConfigToEcu(client, point.dir, stopOnRangeErr = true, safetyBackup = false) }
                }
                assertNotEquals(0, store.list().size)
            } finally {
                client.disconnect()
                tmp.deleteRecursively()
            }
        }
    }

    @Test fun speeduinoEndToEnd() = runScenario("speeduino_tcp_simulator.py", EcuFamily.SPEEDUINO, speeduinoGuards = true)

    @Test fun ms2EndToEnd() = runScenario("ms2_tcp_simulator.py", EcuFamily.MS2, speeduinoGuards = false)

    companion object {
        private fun simulatorDir(): File =
            File(System.getenv("ECU_SIMULATOR_DIR") ?: (System.getProperty("user.home") + "/Projects/EcuManager/simulator"))
    }
}
