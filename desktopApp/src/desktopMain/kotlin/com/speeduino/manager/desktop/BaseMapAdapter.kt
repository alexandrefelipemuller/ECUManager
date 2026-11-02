package com.speeduino.manager.desktop

import io.ecucore.model.AfrTable
import io.ecucore.model.EngineConstants
import io.ecucore.model.IgnitionTable
import io.ecucore.model.VeTable
import io.ecucore.model.Ms3TableDefinitions
import io.ecucore.model.basemap.GeneratedBaseMap
import kotlin.math.roundToInt

/** Adapta o mapa inicial gerado ao formato real da ECU (espelha `BaseMapController` do app Android). */
internal object BaseMapAdapter {

    /** Reamostra uma tabela (eixos + valores) para [targetCols] x [targetRows]. */
    fun reshape(
        rpmBins: List<Int>,
        loadBins: List<Int>,
        values: List<List<Int>>,
        targetCols: Int,
        targetRows: Int,
    ): Triple<List<Int>, List<Int>, List<List<Int>>> {
        if (values.size == targetRows && rpmBins.size == targetCols) {
            return Triple(rpmBins, loadBins, values)
        }
        val newRpm = resampleAxis(rpmBins, targetCols)
        val newLoad = resampleAxis(loadBins, targetRows)
        val newValues = newLoad.map { load ->
            newRpm.map { rpm -> interpolateValue(rpmBins, loadBins, values, rpm, load) }
        }
        return Triple(newRpm, newLoad, newValues)
    }

    /** Reamostra VE, ignição e AFR para o formato (colunas x linhas) que a ECU realmente tem (MS2/MegaSpeed). */
    fun adaptToEcuTables(generated: GeneratedBaseMap, ve: VeTable, ign: IgnitionTable, afr: AfrTable): GeneratedBaseMap {
        val g = generated
        val veAxes = reshape(g.veTable.rpmBins, g.veTable.loadBins, g.veTable.values, ve.rpmBins.size, ve.loadBins.size)
        val ignAxes = reshape(g.ignitionTable.rpmBins, g.ignitionTable.loadBins, g.ignitionTable.values, ign.rpmBins.size, ign.loadBins.size)
        val afrAxes = reshape(g.afrTable.rpmBins, g.afrTable.loadBins, g.afrTable.values, afr.rpmBins.size, afr.loadBins.size)
        return g.copy(
            veTable = g.veTable.copy(rpmBins = veAxes.first, loadBins = veAxes.second, values = veAxes.third),
            ignitionTable = g.ignitionTable.copy(rpmBins = ignAxes.first, loadBins = ignAxes.second, values = ignAxes.third),
            afrTable = g.afrTable.copy(rpmBins = afrAxes.first, loadBins = afrAxes.second, values = afrAxes.third),
        )
    }

    /** MS3: só o AFR muda de formato. */
    fun adaptToMs3(generated: GeneratedBaseMap): GeneratedBaseMap {
        val targetRows = Ms3TableDefinitions.AFR_TABLE_1.metadata.valuesShape.first
        val targetCols = Ms3TableDefinitions.AFR_TABLE_1.metadata.valuesShape.second
        val source = generated.afrTable
        if (source.values.size == targetRows && source.rpmBins.size == targetCols) return generated
        val reshaped = reshape(source.rpmBins, source.loadBins, source.values, targetCols, targetRows)
        return generated.copy(afrTable = source.copy(rpmBins = reshaped.first, loadBins = reshaped.second, values = reshaped.third))
    }

    /**
     * MS2/MegaSpeed: grava só o que o assistente calcula de fato (required fuel, cilindros, injetores,
     * algoritmo e estequiométrica) sobre as Engine Constants lidas agora da ECU.
     */
    fun mergeMs2EngineConstants(generated: EngineConstants, current: EngineConstants): EngineConstants =
        current.copy(
            reqFuel = generated.reqFuel,
            numberOfCylinders = generated.numberOfCylinders,
            numberOfInjectors = generated.numberOfInjectors,
            algorithm = generated.algorithm,
            ignitionAlgorithm = generated.algorithm,
            stoichiometricRatio = generated.stoichiometricRatio,
        )

    internal fun resampleAxis(source: List<Int>, targetSize: Int): List<Int> {
        if (source.isEmpty() || targetSize <= 0) return emptyList()
        if (source.size == targetSize) return source
        if (targetSize == 1) return listOf(source.first())

        val lastIndex = (source.size - 1).toDouble()
        return List(targetSize) { targetIndex ->
            val position = targetIndex * lastIndex / (targetSize - 1)
            val lowerIndex = position.toInt().coerceIn(0, source.lastIndex)
            val upperIndex = (lowerIndex + 1).coerceIn(0, source.lastIndex)
            val fraction = position - lowerIndex
            val value = source[lowerIndex] + (source[upperIndex] - source[lowerIndex]) * fraction
            value.roundToInt()
        }
    }

    internal fun interpolateValue(
        rpmBins: List<Int>,
        loadBins: List<Int>,
        values: List<List<Int>>,
        rpm: Int,
        load: Int,
    ): Int {
        val rowIndex = locateAxisCell(loadBins, load)
        val colIndex = locateAxisCell(rpmBins, rpm)

        val loadLower = loadBins[rowIndex]
        val loadUpper = loadBins[(rowIndex + 1).coerceAtMost(loadBins.lastIndex)]
        val rpmLower = rpmBins[colIndex]
        val rpmUpper = rpmBins[(colIndex + 1).coerceAtMost(rpmBins.lastIndex)]

        val rowFraction = normalizedFraction(load, loadLower, loadUpper)
        val colFraction = normalizedFraction(rpm, rpmLower, rpmUpper)

        val v00 = values[rowIndex][colIndex].toDouble()
        val v01 = values[rowIndex][(colIndex + 1).coerceAtMost(values[rowIndex].lastIndex)].toDouble()
        val nextRow = (rowIndex + 1).coerceAtMost(values.lastIndex)
        val v10 = values[nextRow][colIndex].toDouble()
        val v11 = values[nextRow][(colIndex + 1).coerceAtMost(values[nextRow].lastIndex)].toDouble()

        val top = v00 + (v01 - v00) * colFraction
        val bottom = v10 + (v11 - v10) * colFraction
        return (top + (bottom - top) * rowFraction).roundToInt()
    }

    internal fun locateAxisCell(axis: List<Int>, value: Int): Int {
        if (axis.size < 2) return 0
        if (value <= axis.first()) return 0
        if (value >= axis.last()) return axis.size - 2
        for (index in 0 until axis.lastIndex) {
            if (value >= axis[index] && value <= axis[index + 1]) {
                return index
            }
        }
        return (axis.size - 2).coerceAtLeast(0)
    }

    internal fun normalizedFraction(value: Int, lower: Int, upper: Int): Double {
        if (upper == lower) return 0.0
        return ((value - lower).toDouble() / (upper - lower).toDouble()).coerceIn(0.0, 1.0)
    }
}
