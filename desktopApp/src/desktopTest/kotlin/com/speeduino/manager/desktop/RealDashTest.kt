package com.speeduino.manager.desktop

import com.speeduino.manager.desktop.realdash.Odometer
import com.speeduino.manager.desktop.realdash.RealDashSignalMap
import com.speeduino.manager.desktop.realdash.applyGaugeMath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RealDashTest {
    @Test fun gaugeMathAppliesSimpleFormulas() {
        assertEquals(2.0, applyGaugeMath("=V/1000", 2000.0), 1e-9)
        assertEquals(30.0, applyGaugeMath("= V * 3", 10.0), 1e-9)
        assertEquals(7.0, applyGaugeMath("=sqrt(V)", 7.0), 1e-9) // fórmula desconhecida é ignorada
    }

    @Test fun signalMapKnowsRpmAndIgnoresUnknown() {
        assertEquals("rpm", RealDashSignalMap.signalFor(37))
        assertNull(RealDashSignalMap.signalFor(99999))
    }

    @Test fun odometerIntegratesSpeedAndIgnoresLongGaps() {
        val file = Files.createTempFile("odo", ".properties").toFile().also { it.delete() }
        val odo = Odometer(file)
        odo.update(36.0, nowMs = 1_000)   // primeira amostra só arma o relógio
        odo.update(36.0, nowMs = 2_000)   // 36 km/h por 1 s = 10 m
        assertEquals(0.01, odo.odometerKm, 1e-9)
        odo.update(36.0, nowMs = 60_000)  // lacuna > 5 s: não extrapola
        assertEquals(0.01, odo.odometerKm, 1e-9)
        odo.save()
        assertEquals(0.01, Odometer(file).odometerKm, 1e-9)
        file.delete()
    }
}
