package com.speeduino.manager.telemetry

import android.util.Log
import io.ecucore.transport.ConnectionDiagnosticsSink
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class ConnectionLogEntry(
    val timestampMs: Long,
    val transport: String,
    val thread: String,
    val state: String,
    val message: String,
)

object ConnectionDiagnosticsLogger : ConnectionDiagnosticsSink {
    private const val TAG = "ConnDiag"
    private const val MAX_ENTRIES = 200

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val entries = ArrayDeque<ConnectionLogEntry>()

    override fun log(transport: String, state: String, message: String) {
        val entry = ConnectionLogEntry(
            timestampMs = System.currentTimeMillis(),
            transport = transport,
            thread = Thread.currentThread().name,
            state = state,
            message = message,
        )
        append(entry)
        Log.d(TAG, format(entry))
    }

    override fun logError(transport: String, state: String, message: String, throwable: Throwable?) {
        log(transport, state, message)
        if (throwable != null) {
            val stack = stackTrace(throwable)
            stack.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                log(transport, state, "stacktrace: $line")
            }
        }
    }

    fun getTail(maxEntries: Int = MAX_ENTRIES): List<String> = synchronized(entries) {
        entries.toList().takeLast(maxEntries).map { format(it) }
    }

    private fun append(entry: ConnectionLogEntry) {
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
    }

    private fun format(entry: ConnectionLogEntry): String {
        val timestamp = formatter.format(Date(entry.timestampMs))
        return "[$timestamp] transport=${entry.transport} state=${entry.state} thread=${entry.thread} msg=${entry.message}"
    }

    private fun stackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
