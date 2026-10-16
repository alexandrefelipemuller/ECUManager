package com.speeduino.manager.desktop

import java.io.File
import io.ecucore.model.RusefiInputOutputSnapshot
import io.ecucore.model.SecondarySerialConfig

internal enum class ConnectionStatus {
    Connected,
    Disconnected,
    Connecting,
    Failed
}

internal data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val detail: String? = null
) {
    val isConnected: Boolean
        get() = status == ConnectionStatus.Connected
}

internal data class ConfigSyncState(
    val isBusy: Boolean = false,
    val progressPercent: Int = 0,
    val message: String? = null,
    val lastSessionDir: File? = null
)

internal data class RestoreOutcome(
    val warnings: List<String>,
    val inconsistentPages: List<Byte>,
    val completed: Boolean
)

internal data class SyncPrompt(
    val localSessionDir: File,
    val ecuSessionDir: File
)

internal data class TuningConfigState(
    val rusefiSnapshot: RusefiInputOutputSnapshot? = null,
    val secondarySerialConfig: SecondarySerialConfig? = null,
)

internal data class TransportCallbacks(
    val onDataReceived: (io.ecucore.EcuLiveData) -> Unit,
    val onConnectionStateChanged: (Boolean) -> Unit,
    val onError: (String) -> Unit,
)

internal enum class ConnectionType(val labelKey: String) {
    TCP("label.connectionTypeTcp"),
    USB("label.connectionTypeUsb"),
    BLUETOOTH("label.connectionTypeBluetooth");

    fun label(strings: Strings): String = strings[labelKey]
}

internal data class SerialPortInfo(
    val systemPortName: String,
    val displayName: String
)

internal data class BluetoothDeviceInfo(
    val address: String,
    val displayName: String
)
