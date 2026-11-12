package com.alertbridge.app.adapter

import com.alertbridge.engine.fixture.Expected
import com.alertbridge.engine.fixture.Scenario
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.VehicleEvent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixturePlaybackTest {
    @Test fun `second long gap keeps every question and timeout tick`() = runBlocking {
        val scenario = Scenario("test", "test", emptyMap(), events = listOf(
            VehicleEvent(0, Signal.VehicleSpeed(0f)),
            VehicleEvent(10_000, Signal.VehicleSpeed(0f)),
            VehicleEvent(30_000, Signal.VehicleSpeed(0f)),
        ), expected = Expected())
        var realMs = 0L
        val ticks = mutableListOf<Long>()
        lateinit var playback: FixtureSignalSource
        playback = FixtureSignalSource(scenario, realClockMs = { realMs }, waitFrame = {
            ticks += playback.nowMs()
            realMs += 1000
        })
        assertEquals(3, playback.events().take(3).toList().size)
        // The old optimization jumped from 10s to 27s, swallowing triage at 12/15/20s.
        for (questionTime in listOf(12_000L, 15_000L, 20_000L)) {
            assertTrue(questionTime in ticks, "missing question tick $questionTime")
        }
        assertTrue(ticks.first() >= 7000, "the initial lead-in may still be shortened")
    }
}
