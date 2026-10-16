package com.speeduino.manager.io

import io.ecucore.EcuLiveData
import io.ecucore.model.EcuOutputChannels
import kotlin.math.abs

class IoConfigEngine {

    fun validate(channel: IoChannel): IoStatus {
        if (channel.name.isBlank()) return IoStatus.ERROR
        return when (val config = channel.config) {
            is SensorConfig -> validateSensor(config)
            is VirtualConfig -> validateVirtual(config)
            is ActuatorConfig -> IoStatus.LOCAL_ONLY
        }
    }

    fun evaluate(channel: IoChannel, liveData: EcuLiveData?): IoComputedValue {
        val status = validate(channel)
        if (status == IoStatus.ERROR) {
            return IoComputedValue("--", "--", null, status)
        }
        return when (val config = channel.config) {
            is SensorConfig -> evaluateSensor(config, liveData)
            is VirtualConfig -> evaluateVirtual(config, liveData)
            is ActuatorConfig -> evaluateActuator(config, liveData, status)
        }
    }

    fun buildChannelMap(liveData: EcuLiveData?): Map<String, Double> {
        if (liveData == null) return emptyMap()
        val data = liveData.outputChannelData
        val blockSize = liveData.outputChannelBlockSize
        if (data != null && blockSize > 0) {
            val fields = EcuOutputChannels.getDefinition(blockSize)
            return fields.associate { it.name to it.parse(data) }
        }
        return mapOf(
            "rpm" to liveData.rpm.toDouble(),
            "map" to liveData.mapPressure.toDouble(),
            "coolantRaw" to liveData.coolantTemp.toDouble(),
            "iatRaw" to liveData.intakeTemp.toDouble(),
            "tps" to liveData.tps.toDouble(),
            "batteryVoltage" to liveData.batteryVoltage,
            "advance" to liveData.advance.toDouble(),
            "afr" to liveData.o2.toDouble()
        )
    }

    private fun validateSensor(config: SensorConfig): IoStatus {
        if (config.sourceChannel.isBlank()) return IoStatus.ERROR
        return when (config.conversion) {
            is LinearConversion -> IoStatus.OK
            is TableConversion -> if (config.conversion.points.isNotEmpty()) IoStatus.OK else IoStatus.ERROR
            is ExpressionConversion -> if (config.conversion.expression.isNotBlank()) IoStatus.OK else IoStatus.ERROR
            else -> IoStatus.ERROR
        }
    }

    private fun validateVirtual(config: VirtualConfig): IoStatus {
        return when (config.mode) {
            VirtualMode.SIMPLE -> {
                if (config.sourceChannel.isNullOrBlank()) return IoStatus.ERROR
                if (config.operation == null) return IoStatus.ERROR
                IoStatus.OK
            }
            VirtualMode.EXPRESSION -> if (!config.expression.isNullOrBlank()) IoStatus.OK else IoStatus.ERROR
        }
    }

    private fun evaluateSensor(config: SensorConfig, liveData: EcuLiveData?): IoComputedValue {
        val inputs = buildChannelMap(liveData)
        val raw = inputs[config.sourceChannel]
        val rawDisplay = raw?.let { formatValue(it) } ?: "--"
        if (raw == null) {
            return IoComputedValue("--", rawDisplay, config.conversion.unit, IoStatus.ERROR)
        }
        val value = when (val conversion = config.conversion) {
            is LinearConversion -> linear(raw, conversion)
            is TableConversion -> table(raw, conversion)
            is ExpressionConversion -> evaluateExpression(conversion.expression, inputs)
            else -> raw
        }
        return IoComputedValue(formatValue(value), rawDisplay, config.conversion.unit, IoStatus.OK)
    }

    private fun evaluateVirtual(config: VirtualConfig, liveData: EcuLiveData?): IoComputedValue {
        val inputs = buildChannelMap(liveData)
        return when (config.mode) {
            VirtualMode.SIMPLE -> {
                val source = config.sourceChannel
                val op = config.operation
                if (source == null || op == null) {
                    IoComputedValue("--", "--", null, IoStatus.ERROR)
                } else {
                    val raw = inputs[source] ?: 0.0
                    val result = when (op) {
                        is VirtualBitConfig -> {
                            val mask = 1 shl op.bit
                            if ((raw.toLong() and mask.toLong()) != 0L) 1.0 else 0.0
                        }
                        is VirtualCompareConfig -> {
                            when (op.compareOp) {
                                CompareOp.LT -> if (raw < op.compareValue) 1.0 else 0.0
                                CompareOp.LTE -> if (raw <= op.compareValue) 1.0 else 0.0
                                CompareOp.GT -> if (raw > op.compareValue) 1.0 else 0.0
                                CompareOp.GTE -> if (raw >= op.compareValue) 1.0 else 0.0
                                CompareOp.EQ -> if (abs(raw - op.compareValue) < 0.0001) 1.0 else 0.0
                                CompareOp.NEQ -> if (abs(raw - op.compareValue) >= 0.0001) 1.0 else 0.0
                            }
                        }
                        else -> 0.0
                    }
                    val display = formatVirtual(result, config.outputFormat)
                    IoComputedValue(display, formatValue(raw), null, IoStatus.OK)
                }
            }
            VirtualMode.EXPRESSION -> {
                val expr = config.expression.orEmpty()
                val value = evaluateExpression(expr, inputs)
                IoComputedValue(formatValue(value), null, null, IoStatus.OK)
            }
        }
    }

    private fun evaluateActuator(
        config: ActuatorConfig,
        liveData: EcuLiveData?,
        status: IoStatus
    ): IoComputedValue {
        val inputs = buildChannelMap(liveData)
        return when (config.controlMode) {
            ActuatorControlMode.MANUAL -> {
                val value = config.manualValue ?: 0.0
                IoComputedValue(formatValue(value), null, null, status)
            }
            ActuatorControlMode.EXPRESSION -> {
                val value = evaluateExpression(config.expression.orEmpty(), inputs)
                IoComputedValue(formatValue(value), null, null, status)
            }
        }
    }

    private fun linear(raw: Double, conversion: LinearConversion): Double {
        val dx = conversion.x2 - conversion.x1
        if (abs(dx) < 0.0001) return conversion.y1
        val ratio = (raw - conversion.x1) / dx
        return conversion.y1 + ratio * (conversion.y2 - conversion.y1)
    }

    private fun table(raw: Double, conversion: TableConversion): Double {
        val points = conversion.points.sortedBy { it.x }
        if (points.isEmpty()) return raw
        if (raw <= points.first().x) return points.first().y
        if (raw >= points.last().x) return points.last().y
        val upperIndex = points.indexOfFirst { it.x >= raw }
        val lower = points[upperIndex - 1]
        val upper = points[upperIndex]
        val range = upper.x - lower.x
        if (abs(range) < 0.0001) return lower.y
        val ratio = (raw - lower.x) / range
        return lower.y + ratio * (upper.y - lower.y)
    }

    private fun evaluateExpression(expression: String, inputs: Map<String, Double>): Double {
        if (expression.isBlank()) return 0.0
        return runCatching { IoExpressionEvaluator(inputs).evaluate(expression) }
            .getOrDefault(0.0)
    }

    private fun formatVirtual(value: Double, format: VirtualOutputFormat): String {
        return when (format) {
            VirtualOutputFormat.ZERO_ONE -> if (value != 0.0) "1" else "0"
            VirtualOutputFormat.INACTIVE_ACTIVE -> if (value != 0.0) "Ativo" else "Inativo"
            VirtualOutputFormat.OFF_ON -> if (value != 0.0) "On" else "Off"
        }
    }

    private fun formatValue(value: Double): String {
        val rounded = value.toInt().toDouble()
        return if (abs(value - rounded) < 0.0001) {
            rounded.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", value)
        }
    }
}

/**
 * Value computed from I/O config evaluation.
 */
data class IoComputedValue(
    val displayValue: String,
    val rawValue: String?,
    val unit: String?,
    val status: IoStatus
)
