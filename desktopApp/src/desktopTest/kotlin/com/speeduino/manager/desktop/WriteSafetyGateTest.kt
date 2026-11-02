package com.speeduino.manager.desktop

import io.ecucore.safety.VehicleMotion
import io.ecucore.safety.WriteSafetyGate
import io.ecucore.safety.WriteSafetyRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriteSafetyGateTest {

    private class Fixture(
        var noticeAccepted: Boolean = false,
        var speed: Double? = null,
        var now: Long = 1_000L,
    ) {
        val gate = WriteSafetyGate(
            isNoticeAccepted = { noticeAccepted },
            markNoticeAccepted = { noticeAccepted = true },
            currentSpeedKmh = { speed },
            nowMs = { now },
            movingAckWindowMs = 60_000L,
        )
    }

    @Test
    fun firstWriteShowsNoticeOnceAndRemembersAcceptance() = runBlocking {
        val f = Fixture()
        val first = async(start = CoroutineStart.UNDISPATCHED) { f.gate.authorize() }
        assertEquals(WriteSafetyRequest.FirstUseNotice, f.gate.pending.value)
        f.gate.resolve(true)
        assertTrue(first.await())
        assertTrue(f.noticeAccepted)
        assertNull(f.gate.pending.value)

        assertTrue(f.gate.authorize()) // sem diálogo na segunda vez
    }

    @Test
    fun decliningNoticeDeniesAndAsksAgainNextTime() = runBlocking {
        val f = Fixture()
        val first = async(start = CoroutineStart.UNDISPATCHED) { f.gate.authorize() }
        f.gate.resolve(false)
        assertFalse(first.await())
        assertFalse(f.noticeAccepted)

        val second = async(start = CoroutineStart.UNDISPATCHED) { f.gate.authorize() }
        assertEquals(WriteSafetyRequest.FirstUseNotice, f.gate.pending.value)
        f.gate.resolve(true)
        assertTrue(second.await())
    }

    @Test
    fun stoppedOrUnknownSpeedNeverAsksAboutMovement() = runBlocking {
        val f = Fixture(noticeAccepted = true, speed = 0.0)
        assertTrue(f.gate.authorize())
        f.speed = null
        assertTrue(f.gate.authorize())
        f.speed = 4.9
        assertTrue(f.gate.authorize())
    }

    @Test
    fun movingVehicleAsksAndAckLastsForTheWindowOnly() = runBlocking {
        val f = Fixture(noticeAccepted = true, speed = 42.0)
        val first = async(start = CoroutineStart.UNDISPATCHED) { f.gate.authorize() }
        assertEquals(WriteSafetyRequest.VehicleMoving(42.0), f.gate.pending.value)
        f.gate.resolve(true)
        assertTrue(first.await())

        f.now += 30_000L
        assertTrue(f.gate.authorize()) // dentro da janela: não pergunta

        f.now += 31_000L
        val again = async(start = CoroutineStart.UNDISPATCHED) { f.gate.authorize() }
        assertEquals(WriteSafetyRequest.VehicleMoving(42.0), f.gate.pending.value)
        f.gate.resolve(false)
        assertFalse(again.await())
    }

    @Test
    fun speedSourcesPickTheHighestAvailable() {
        assertNull(VehicleMotion.speedKmh(null, null))
        assertEquals(30.0, VehicleMotion.speedKmh(30, null)!!, 0.0)
        assertEquals(50.0, VehicleMotion.speedKmh(30, 50.0)!!, 0.0)
        assertNull(VehicleMotion.speedKmh(null, -1.0))
        assertTrue(VehicleMotion.isMoving(5.0))
        assertFalse(VehicleMotion.isMoving(4.9))
        assertFalse(VehicleMotion.isMoving(null))
    }
}
