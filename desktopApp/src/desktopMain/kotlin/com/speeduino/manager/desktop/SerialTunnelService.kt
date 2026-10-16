package com.speeduino.manager.desktop

import io.ecucore.connection.EcuSerialConnection
import io.ecucore.shared.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.io.IOException
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

enum class SerialTunnelStatus { IDLE, LISTENING, CLIENT_CONNECTED, ERROR }

data class SerialTunnelState(
    val status: SerialTunnelStatus = SerialTunnelStatus.IDLE,
    val tcpPort: Int = 0,
    val serialPort: String = "",
    val clientAddress: String? = null,
    val errorMessage: String? = null,
    val localAddresses: List<String> = emptyList(),
    val bytesFromClient: Long = 0,
    val bytesFromSerial: Long = 0
)

private const val TAG = "SerialTunnel"

/**
 * Bridges a local serial-connected ECU to a TCP listener so a Wi-Fi-only client
 * (e.g. the iOS app) can talk to it, replacing the standalone Python proxy script.
 * Only one TCP client is served at a time, mirroring the original script's behavior.
 */
internal class SerialTunnelService(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(SerialTunnelState())
    val state: StateFlow<SerialTunnelState> = _state.asStateFlow()

    private var job: Job? = null
    private var serverSocket: ServerSocket? = null
    private var serialConnection: EcuSerialConnection? = null

    fun start(serialPortDescriptor: String, baudRate: Int, tcpPort: Int) {
        stop()
        _state.value = SerialTunnelState(
            status = SerialTunnelStatus.LISTENING,
            tcpPort = tcpPort,
            serialPort = serialPortDescriptor,
            localAddresses = discoverLocalAddresses()
        )
        job = scope.launch(Dispatchers.IO) {
            try {
                Logger.i(TAG, "Opening serial port $serialPortDescriptor @ $baudRate")
                val serial = EcuSerialConnection(serialPortDescriptor, baudRate)
                serial.connect()
                serialConnection = serial
                Logger.i(TAG, "Serial port open, listening on TCP port $tcpPort")
                val server = ServerSocket(tcpPort)
                serverSocket = server
                while (true) {
                    val socket = server.accept()
                    Logger.i(TAG, "Client connected from ${socket.inetAddress?.hostAddress}")
                    handleClient(socket, serial)
                    Logger.i(TAG, "Client disconnected, waiting for next connection")
                    if (_state.value.status == SerialTunnelStatus.IDLE || _state.value.status == SerialTunnelStatus.ERROR) {
                        break
                    }
                    _state.value = _state.value.copy(
                        status = SerialTunnelStatus.LISTENING,
                        clientAddress = null,
                        bytesFromClient = 0,
                        bytesFromSerial = 0
                    )
                }
            } catch (e: IOException) {
                // Expected when stop()/server.close() is called while accept() is blocked.
                Logger.i(TAG, "Tunnel listener stopped: ${e.message}")
            } catch (e: Exception) {
                Logger.e(TAG, "Tunnel failed", e)
                _state.value = _state.value.copy(status = SerialTunnelStatus.ERROR, errorMessage = e.message ?: e.toString())
            }
        }
    }

    private suspend fun handleClient(socket: Socket, serial: EcuSerialConnection) {
        _state.value = _state.value.copy(
            status = SerialTunnelStatus.CLIENT_CONNECTED,
            clientAddress = socket.inetAddress?.hostAddress
        )
        try {
            socket.tcpNoDelay = true
            supervisorScope {
                val toSerial = launch {
                    try {
                        val buffer = ByteArray(4096)
                        val input = socket.getInputStream()
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) {
                                Logger.i(TAG, "Client closed its write side")
                                break
                            }
                            if (read > 0) {
                                serial.send(buffer.copyOf(read))
                                _state.value = _state.value.copy(bytesFromClient = _state.value.bytesFromClient + read)
                            }
                        }
                    } catch (e: SocketException) {
                        Logger.i(TAG, "Client socket closed (tcp->serial): ${e.message}")
                    } catch (e: Exception) {
                        Logger.e(TAG, "tcp->serial pump failed", e)
                    }
                }
                val toSocket = launch {
                    try {
                        val output = socket.getOutputStream()
                        while (true) {
                            val data = try {
                                serial.readAvailable(4096, 50)
                            } catch (e: Exception) {
                                Logger.e(TAG, "serial read failed", e)
                                ByteArray(0)
                            }
                            if (data.isNotEmpty()) {
                                output.write(data)
                                output.flush()
                                _state.value = _state.value.copy(bytesFromSerial = _state.value.bytesFromSerial + data.size)
                            } else {
                                delay(5)
                            }
                        }
                    } catch (e: SocketException) {
                        Logger.i(TAG, "Client socket closed (serial->tcp): ${e.message}")
                    } catch (e: Exception) {
                        Logger.e(TAG, "serial->tcp pump failed", e)
                    }
                }
                toSerial.join()
                toSocket.cancel()
                toSocket.join()
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { serialConnection?.disconnect() }
        serialConnection = null
        _state.value = SerialTunnelState()
    }

    private fun discoverLocalAddresses(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .mapNotNull { it.hostAddress }
                .filter { !it.contains(":") }
                .toList()
        }.getOrDefault(emptyList())
    }
}
