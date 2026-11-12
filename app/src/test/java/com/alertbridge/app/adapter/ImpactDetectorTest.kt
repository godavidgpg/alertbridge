package com.alertbridge.app.adapter

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 차량 화면은 운전자를 향해 서 있다고 본다: 중력은 기기 +Y(위쪽 반대 방향으로 9.8),
 * 차 앞 = 기기 -Z, 차 오른쪽 = 기기 +X.
 */
class ImpactDetectorTest {
    private val g = ImpactSensorSource.G

    private fun run(samples: List<Triple<Float, Float, Float>>): List<Triple<Float, Float, Float>> {
        val out = mutableListOf<Triple<Float, Float, Float>>()
        val d = ImpactDetector { p, lon, lat -> out += Triple(p, lon, lat) }
        var t = 0L
        // 정지 상태로 중력을 잡는다.
        repeat(100) { d.onSample(0f, g, 0f, t); t += 20 }
        samples.forEach { (x, y, z) -> d.onSample(x, y, z, t); t += 20 }
        repeat(30) { d.onSample(0f, g, 0f, t); t += 20 }
        return out
    }

    @Test fun `뒤에서 받히면 차가 앞으로 밀린다 - 앞뒤 양수`() {
        // 차가 앞으로(기기 -Z) 3G 가속
        val out = run(List(5) { Triple(0f, g, -3f * g) })
        assertEquals(1, out.size)
        val (peak, lon, lat) = out.single()
        assertTrue(peak in 2.9f..3.1f, "peak=$peak")
        assertTrue(lon > 2.9f, "lon=$lon")
        assertTrue(kotlin.math.abs(lat) < 0.1f, "lat=$lat")
    }

    @Test fun `오른쪽에서 부딪히면 왼쪽으로 밀린다 - 좌우 음수`() {
        val out = run(List(5) { Triple(-2f * g, g, 0f) })
        val (_, lon, lat) = out.single()
        assertTrue(lat < -1.9f, "lat=$lat")
        assertTrue(kotlin.math.abs(lon) < 0.1f, "lon=$lon")
    }

    @Test fun `위아래 흔들림(과속방지턱)은 수평 충격이 아니다`() {
        val out = run(List(5) { Triple(0f, g + 2f * g, 0f) })
        assertEquals(0, out.size)
    }

    @Test fun `기준 미만의 흔들림은 보내지 않는다`() {
        val out = run(List(5) { Triple(0f, g, -0.5f * g) })
        assertEquals(0, out.size)
    }
}
