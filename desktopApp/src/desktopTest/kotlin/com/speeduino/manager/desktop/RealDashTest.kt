package com.speeduino.manager.desktop

import io.ecucore.realdash.RealDashSignalMap
import io.ecucore.realdash.applyGaugeMath
import com.speeduino.manager.desktop.realdash.Odometer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RealDashTest {
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
