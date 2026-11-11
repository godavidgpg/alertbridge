package com.alertbridge.app.adapter

import com.alertbridge.engine.model.CapabilityKey
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.VehicleEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveSourcesTest {
    private class Fake(val caps: Map<CapabilityKey, Boolean?>, val events: List<VehicleEvent> = emptyList()) : SignalSource {
        override suspend fun capabilities() = CapabilityMap(caps)
        override fun events(): Flow<VehicleEvent> = flowOf(*events.toTypedArray())
        override fun nowMs() = 0L
    }

    @Test
    fun `여러 입력의 지원 여부는 지원 우선, 그다음 미지원, 모두 모르면 알 수 없음`() {
        val gps = Fake(mapOf(CapabilityKey.LOCATION to true))
        val speedOnPhone = Fake(mapOf(CapabilityKey.VEHICLE_SPEED to false))
        val pleosNotReady = Fake(mapOf(CapabilityKey.VEHICLE_SPEED to null, CapabilityKey.SEAT_AIRBAG_DEPLOYED to null, CapabilityKey.LOCATION to false))
        val caps = runBlocking { CompositeSignalSource(listOf(gps, speedOnPhone, pleosNotReady)).capabilities() }
        assertEquals(true, caps.supported[CapabilityKey.LOCATION])
        assertEquals(false, caps.supported[CapabilityKey.VEHICLE_SPEED])
        assertNull(caps.supported[CapabilityKey.SEAT_AIRBAG_DEPLOYED])
        assertEquals(setOf(CapabilityKey.LOCATION, CapabilityKey.VEHICLE_SPEED, CapabilityKey.SEAT_AIRBAG_DEPLOYED), caps.supported.keys)
    }

    @Test
    fun `여러 입력의 이벤트를 모두 받는다`() {
        val a = Fake(emptyMap(), listOf(VehicleEvent(1, Signal.VehicleSpeed(10f))))
        val b = Fake(emptyMap(), listOf(VehicleEvent(2, Signal.Location(37.0, 127.0))))
        val all = runBlocking { CompositeSignalSource(listOf(a, b)).events().toList() }
        assertEquals(setOf(1L, 2L), all.map { it.tMs }.toSet())
    }

    @Test
    fun `차속 단위 변환 m-s 에서 km-h`() {
        assertEquals(36f, CarSpeedSource.metersPerSecondToKmh(10f), 0.001f)
        assertEquals(0f, CarSpeedSource.metersPerSecondToKmh(0f))
    }
}
