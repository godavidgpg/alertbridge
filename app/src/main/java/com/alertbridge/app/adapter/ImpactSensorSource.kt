package com.alertbridge.app.adapter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.alertbridge.engine.model.CapabilityKey
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.VehicleEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

/**
 * 차량 화면 기기의 가속도 센서로 순간 충격을 잰다. Pleos Vehicle SDK에는 충격·가속도 API가 없어서 기기 센서를 쓴다.
 *
 * - 중력은 저역 통과로 추정해 빼고, 수평 성분만 본다(과속방지턱 같은 위아래 흔들림은 제외).
 * - [CAPTURE_G] 이상이 되면 [WINDOW_MS] 동안 최대값을 모아 [Signal.ImpactAccel] 하나로 보낸다. 사고 여부는 엔진이 정한다.
 * - 방향: 차량 화면은 운전자를 향해 서 있다고 본다. 기기 -Z = 차 앞, +X = 차 오른쪽.
 *   TODO(VERIFY): 실차의 화면 장착 각도로 확인. 장착 방향을 모르는 기기라면 방향을 null로 보내도록 바꾼다.
 */
class ImpactSensorSource(private val context: Context) : SignalSource {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val accel: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override suspend fun capabilities(): CapabilityMap =
        CapabilityMap(mapOf(CapabilityKey.IMPACT_SENSOR to (accel != null)))

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun events(): Flow<VehicleEvent> = callbackFlow {
        val sensor = accel
        if (sensor == null || manager == null) {
            awaitClose { }
            return@callbackFlow
        }
        val thread = HandlerThread("impact-sensor").apply { start() }
        val detector = ImpactDetector { peak, lon, lat -> trySend(VehicleEvent(nowMs(), Signal.ImpactAccel(peak, lon, lat))) }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = detector.onSample(e.values[0], e.values[1], e.values[2], e.timestamp / 1_000_000)
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(thread.looper))
        awaitClose {
            manager.unregisterListener(listener)
            thread.quitSafely()
        }
    }

    companion object {
        const val CAPTURE_G = 0.8f
        const val WINDOW_MS = 250L
        const val COOLDOWN_MS = 2000L
        const val G = 9.80665f
    }
}

/**
 * 가속도 샘플(m/s², 기기 좌표)에서 충격 하나를 뽑는다. 안드로이드와 무관하게 단위 테스트할 수 있게 분리했다.
 * [emit]은 (최대 수평 G, 앞뒤 G, 좌우 G). 앞뒤 양수 = 차가 앞으로 밀림, 좌우 양수 = 오른쪽으로 밀림.
 */
class ImpactDetector(private val emit: (Float, Float, Float) -> Unit) {
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var gravityReady = false
    private var windowStart: Long? = null
    private var best = Triple(0f, 0f, 0f)
    private var lastEmit = Long.MIN_VALUE / 2

    fun onSample(x: Float, y: Float, z: Float, tMs: Long) {
        if (!gravityReady) { gx = x; gy = y; gz = z; gravityReady = true; return }
        val gNorm = sqrt(gx * gx + gy * gy + gz * gz).takeIf { it > 1f } ?: return
        val ux = gx / gNorm; val uy = gy / gNorm; val uz = gz / gNorm
        // 중력을 뺀 가속도의 수평 성분
        var lx = x - gx; var ly = y - gy; var lz = z - gz
        val vert = lx * ux + ly * uy + lz * uz
        lx -= vert * ux; ly -= vert * uy; lz -= vert * uz
        val mag = sqrt(lx * lx + ly * ly + lz * lz) / ImpactSensorSource.G

        // 차 앞 = 기기 -Z, 차 오른쪽 = 기기 +X를 수평면에 투영한 방향
        val (fx, fy, fz) = horizontal(0f, 0f, -1f, ux, uy, uz)
        val (rx, ry, rz) = horizontal(1f, 0f, 0f, ux, uy, uz)
        val lon = (lx * fx + ly * fy + lz * fz) / ImpactSensorSource.G
        val lat = (lx * rx + ly * ry + lz * rz) / ImpactSensorSource.G

        val start = windowStart
        if (start == null) {
            if (mag >= ImpactSensorSource.CAPTURE_G && tMs - lastEmit >= ImpactSensorSource.COOLDOWN_MS) {
                windowStart = tMs
                best = Triple(mag, lon, lat)
            } else {
                // 충격 구간 밖에서만 중력 추정을 갱신한다 (충격이 중력 추정을 끌고 가지 않게).
                val a = 0.02f
                gx += a * (x - gx); gy += a * (y - gy); gz += a * (z - gz)
            }
            return
        }
        if (mag > best.first) best = Triple(mag, lon, lat)
        if (tMs - start >= ImpactSensorSource.WINDOW_MS) {
            emit(best.first, best.second, best.third)
            lastEmit = tMs
            windowStart = null
        }
    }

    private fun horizontal(x: Float, y: Float, z: Float, ux: Float, uy: Float, uz: Float): Triple<Float, Float, Float> {
        val d = x * ux + y * uy + z * uz
        val hx = x - d * ux; val hy = y - d * uy; val hz = z - d * uz
        val n = sqrt(hx * hx + hy * hy + hz * hz).takeIf { it > 1e-3f } ?: return Triple(0f, 0f, 0f)
        return Triple(hx / n, hy / n, hz / n)
    }
}
