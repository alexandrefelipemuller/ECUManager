package com.speeduino.manager.io

import io.ecucore.io.ActuatorConfig
import io.ecucore.io.ActuatorControlMode
import io.ecucore.io.ActuatorType
import io.ecucore.io.CompareOp
import io.ecucore.io.Conversion
import io.ecucore.io.ExpressionConversion
import io.ecucore.io.IoChannel
import io.ecucore.io.IoChannelType
import io.ecucore.io.IoConfig
import io.ecucore.io.LinearConversion
import io.ecucore.io.SensorConfig
import io.ecucore.io.TableConversion
import io.ecucore.io.TablePoint
import io.ecucore.io.VirtualBitConfig
import io.ecucore.io.VirtualCompareConfig
import io.ecucore.io.VirtualConfig
import io.ecucore.io.VirtualMode
import io.ecucore.io.VirtualOperationConfig
import io.ecucore.io.VirtualOutputFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class IoConfigRepository(
    private val storageFile: File = defaultStorageFile()
) {

    fun load(): List<IoChannel> {
        val raw = runCatching { storageFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                parseChannel(obj)
            }
        }.getOrDefault(emptyList())
    }

    fun save(channels: List<IoChannel>) {
        storageFile.parentFile?.mkdirs()
        val array = JSONArray()
        channels.forEach { channel -> array.put(serializeChannel(channel)) }
        storageFile.writeText(array.toString())
    }

    private fun parseChannel(obj: JSONObject): IoChannel? {
        val id = obj.optString("id")
        val name = obj.optString("name")
        val type = IoChannelType.valueOf(obj.optString("type", IoChannelType.SENSOR.name))
        val enabled = obj.optBoolean("enabled", true)
        val configObj = obj.optJSONObject("config") ?: return null
        val config = parseConfig(type, configObj) ?: return null
        return IoChannel(
            id = if (id.isBlank()) java.util.UUID.randomUUID().toString() else id,
            name = name,
            type = type,
            enabled = enabled,
            config = config,
        )
    }

    private fun parseConfig(type: IoChannelType, obj: JSONObject): IoConfig? {
        return when (type) {
            IoChannelType.SENSOR -> {
                val source = obj.optString("source", "")
                val conversionObj = obj.optJSONObject("conversion") ?: return null
                val conversion = parseConversion(conversionObj) ?: return null
                SensorConfig(sourceChannel = source, conversion = conversion)
            }
            IoChannelType.VIRTUAL -> {
                val mode = VirtualMode.valueOf(obj.optString("mode", VirtualMode.SIMPLE.name))
                val outputFormat = VirtualOutputFormat.valueOf(obj.optString("format", VirtualOutputFormat.ZERO_ONE.name))
                val source = obj.optString("source", null)
                val expr = obj.optString("expression", null)
                val opObj = obj.optJSONObject("operation")
                val operation = parseVirtualOperation(opObj)
                VirtualConfig(
                    mode = mode,
                    sourceChannel = source,
                    operation = operation,
                    outputFormat = outputFormat,
                    expression = expr,
                )
            }
            IoChannelType.ACTUATOR -> {
                val outputName = obj.optString("outputName", "")
                val outputType = ActuatorType.valueOf(obj.optString("outputType", ActuatorType.DIGITAL.name))
                val controlMode = ActuatorControlMode.valueOf(obj.optString("controlMode", ActuatorControlMode.MANUAL.name))
                val expression = obj.optString("expression", null)
                val manualValue = obj.optDouble("manualValue", Double.NaN).takeIf { !it.isNaN() }
                ActuatorConfig(
                    outputName = outputName,
                    outputType = outputType,
                    controlMode = controlMode,
                    expression = expression,
                    manualValue = manualValue,
                )
            }
        }
    }

    private fun parseConversion(obj: JSONObject): Conversion? {
        val type = obj.optString("type", "linear")
        val unit = obj.optString("unit", "")
        return when (type) {
            "linear" -> LinearConversion(
                x1 = obj.optDouble("x1", 0.0),
                y1 = obj.optDouble("y1", 0.0),
                x2 = obj.optDouble("x2", 1.0),
                y2 = obj.optDouble("y2", 1.0),
                unit = unit,
            )
            "table" -> {
                val pointsArray = obj.optJSONArray("points") ?: JSONArray()
                val points = (0 until pointsArray.length()).mapNotNull { index ->
                    val pointObj = pointsArray.optJSONObject(index) ?: return@mapNotNull null
                    TablePoint(pointObj.optDouble("x"), pointObj.optDouble("y"))
                }
                TableConversion(points = points, unit = unit)
            }
            "expression" -> ExpressionConversion(expression = obj.optString("expression", ""), unit = unit)
            else -> null
        }
    }

    private fun parseVirtualOperation(obj: JSONObject?): VirtualOperationConfig? {
        if (obj == null) return null
        return when (obj.optString("type", "bit")) {
            "bit" -> VirtualBitConfig(bit = obj.optInt("bit", 0))
            "compare" -> VirtualCompareConfig(
                compareOp = CompareOp.valueOf(obj.optString("op", CompareOp.EQ.name)),
                compareValue = obj.optDouble("value", 0.0),
            )
            else -> null
        }
    }

    private fun serializeChannel(channel: IoChannel): JSONObject {
        return JSONObject().apply {
            put("id", channel.id)
            put("name", channel.name)
            put("type", channel.type.name)
            put("enabled", channel.enabled)
            put("config", serializeConfig(channel.config))
        }
    }

    private fun serializeConfig(config: IoConfig): JSONObject {
        return when (config) {
            is SensorConfig -> JSONObject().apply {
                put("source", config.sourceChannel)
                put("conversion", serializeConversion(config.conversion))
            }
            is VirtualConfig -> JSONObject().apply {
                put("mode", config.mode.name)
                put("source", config.sourceChannel)
                put("format", config.outputFormat.name)
                put("expression", config.expression)
                put("operation", serializeVirtualOperation(config.operation))
            }
            is ActuatorConfig -> JSONObject().apply {
                put("outputName", config.outputName)
                put("outputType", config.outputType.name)
                put("controlMode", config.controlMode.name)
                put("expression", config.expression)
                put("manualValue", config.manualValue)
            }
        }
    }

    private fun serializeConversion(conversion: Conversion): JSONObject {
        return when (conversion) {
            is LinearConversion -> JSONObject().apply {
                put("type", "linear")
                put("x1", conversion.x1)
                put("y1", conversion.y1)
                put("x2", conversion.x2)
                put("y2", conversion.y2)
                put("unit", conversion.unit)
            }
            is TableConversion -> JSONObject().apply {
                put("type", "table")
                put("unit", conversion.unit)
                val array = JSONArray()
                conversion.points.forEach { point ->
                    array.put(JSONObject().apply {
                        put("x", point.x)
                        put("y", point.y)
                    })
                }
                put("points", array)
            }
            is ExpressionConversion -> JSONObject().apply {
                put("type", "expression")
                put("unit", conversion.unit)
                put("expression", conversion.expression)
            }
        }
    }

    private fun serializeVirtualOperation(operation: VirtualOperationConfig?): JSONObject? {
        return when (operation) {
            is VirtualBitConfig -> JSONObject().apply {
                put("type", "bit")
                put("bit", operation.bit)
            }
            is VirtualCompareConfig -> JSONObject().apply {
                put("type", "compare")
                put("op", operation.compareOp.name)
                put("value", operation.compareValue)
            }
            else -> null
        }
    }

    companion object {
        private fun defaultStorageFile(): File {
            return File(System.getProperty("user.home"), ".speeduino-desktop/io/io_configs.json")
        }
    }
}
