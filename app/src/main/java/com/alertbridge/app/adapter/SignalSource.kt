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
class FixtureSignalSource(
    private val scenario: Scenario,
    private val realClockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitFrame: suspend () -> Unit = { delay(16L) },
) : SignalSource {
    var speed: Float = 1f
    var paused: Boolean = false

    private var clockMs = 0L
    private var lastRealMs = 0L

    override suspend fun capabilities(): CapabilityMap = scenario.capabilityMap
    override fun nowMs(): Long = clockMs

    override fun events(): Flow<VehicleEvent> = flow {
        lastRealMs = realClockMs()
        var skippedLeadIn = false
        for (e in scenario.events.sortedBy { it.tMs }) {
            // 도입부의 첫 긴 대기만 줄인다. 이후 구간을 생략하면 질문·타임아웃을 건너뛴다.
            if (!skippedLeadIn && e.tMs - clockMs > IDLE_SKIP_MS) {
                clockMs = e.tMs - IDLE_LEAD_MS
                skippedLeadIn = true
            }
            while (clockMs < e.tMs) {
                waitFrame()
                advance()
            }
            emit(e)
        }
        // 신호가 끝나도 시계는 계속 간다. 무응답 타이머가 여기에 달려 있다.
        while (true) {
            waitFrame()
            advance()
        }
    }

    private fun advance() {
        val real = realClockMs()
        if (!paused) clockMs += ((real - lastRealMs) * speed).toLong()
        lastRealMs = real
    }

    private companion object {
        const val IDLE_SKIP_MS = 5_000L
        const val IDLE_LEAD_MS = 3_000L
    }
}
