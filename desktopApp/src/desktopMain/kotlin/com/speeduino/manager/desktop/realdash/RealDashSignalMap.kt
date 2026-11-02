package com.speeduino.manager.desktop.realdash


/**
 * Tabela de target IDs do RealDash (https://www.realdash.net/manuals/targetid.php) → nome de sinal
 * entendido por `resolveSignalRawValue`. IDs sem entrada aqui ficam sem valor ("--" / 0).
 */
internal const val PETROL_CENTER_SIGNAL_BOOST_BAR = "petrol_center_boost_bar"
internal const val PETROL_CENTER_SIGNAL_BOOST_KPA = "petrol_center_boost_kpa"
internal const val PETROL_CENTER_SIGNAL_BOOST_PSI = "petrol_center_boost_psi"

object RealDashSignalMap {
    private val signals = mapOf(
        0 to "afr", 1 to "afr",
        2 to "afrTarget", 3 to "afrTarget",
        12 to "batteryVoltage",
        14 to "coolantRaw",
        27 to "iatRaw",
        31 to "map",
        37 to "rpm",
        38 to "advance",
        42 to "tps",
        64 to "vss", 81 to "vss",
        83 to PETROL_CENTER_SIGNAL_BOOST_BAR,
        84 to PETROL_CENTER_SIGNAL_BOOST_PSI,
        200 to "candidateGear",     // marcha decodificada pela ECU; o canal "gear" da Speeduino não é a marcha
        254 to "lambda", 255 to "lambda",
        256 to "targetLambda", 257 to "targetLambda",
    )

    fun signalFor(targetId: Int): String? = signals[targetId]

    /** Boost em kPa relativo, para quem pedir o sinal pela unidade. */
    const val BOOST_KPA = PETROL_CENTER_SIGNAL_BOOST_KPA
    const val GEAR_ID = 200
}

/** Fórmulas simples do campo "gauge math" do RealDash: `=V/1000`, `=V*2`, `=V+10`, `=V-5`. Outras são ignoradas. */
fun applyGaugeMath(formula: String, value: Double): Double {
    val m = Regex("""^=\s*V\s*([/*+\-])\s*(-?[0-9.]+)\s*$""").find(formula.trim()) ?: return value
    val n = m.groupValues[2].toDoubleOrNull() ?: return value
    return when (m.groupValues[1]) {
        "/" -> if (n != 0.0) value / n else value
        "*" -> value * n
        "+" -> value + n
        else -> value - n
    }
}
