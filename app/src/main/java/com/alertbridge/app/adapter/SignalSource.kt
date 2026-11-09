package com.alertbridge.app.adapter

import com.alertbridge.engine.fixture.Scenario
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.VehicleEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 차량 신호의 출처. 엔진과 UI는 이 뒤가 픽스처인지 실차인지 모른다. */
interface SignalSource {
    suspend fun capabilities(): CapabilityMap
    fun events(): Flow<VehicleEvent>

    /** 엔진에 넘길 현재 시각. 이벤트의 tMs와 같은 시계여야 한다. */
    fun nowMs(): Long
}

/** 시나리오 JSON을 가상 시계로 재생한다. 배속과 일시정지를 지원한다. */
class FixtureSignalSource(private val scenario: Scenario) : SignalSource {
    var speed: Float = 1f
    var paused: Boolean = false

    private var clockMs = 0L
    private var lastRealMs = 0L

    override suspend fun capabilities(): CapabilityMap = scenario.capabilityMap
    override fun nowMs(): Long = clockMs

    override fun events(): Flow<VehicleEvent> = flow {
        lastRealMs = System.nanoTime() / 1_000_000
        for (e in scenario.events.sortedBy { it.tMs }) {
            // 아무 일도 없는 긴 구간은 건너뛴다. 시각 값 자체는 그대로라 판단에 영향이 없다.
            if (e.tMs - clockMs > IDLE_SKIP_MS) clockMs = e.tMs - IDLE_LEAD_MS
            while (clockMs < e.tMs) {
                delay(FRAME_MS)
                advance()
            }
            emit(e)
        }
        // 신호가 끝나도 시계는 계속 간다. 무응답 타이머가 여기에 달려 있다.
        while (true) {
            delay(FRAME_MS)
            advance()
        }
    }

    private fun advance() {
        val real = System.nanoTime() / 1_000_000
        if (!paused) clockMs += ((real - lastRealMs) * speed).toLong()
        lastRealMs = real
    }

    private companion object {
        const val FRAME_MS = 16L
        const val IDLE_SKIP_MS = 5_000L
        const val IDLE_LEAD_MS = 3_000L
    }
}
