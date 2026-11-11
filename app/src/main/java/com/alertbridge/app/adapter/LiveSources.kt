package com.alertbridge.app.adapter

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.util.Log
import android.content.pm.PackageManager
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import com.alertbridge.engine.model.CapabilityKey
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.VehicleEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 여러 입력(GPS, 차속, Pleos 차량)을 하나로 묶는다.
 * 같은 신호를 여러 곳이 말하면 true(지원) > false(미지원) > null(알 수 없음) 순으로 정한다.
 */
class CompositeSignalSource(
    private val sources: List<SignalSource>,
    private val clock: () -> Long = System::currentTimeMillis,
) : SignalSource {
    override suspend fun capabilities(): CapabilityMap = merge(sources.map { it.capabilities() })

    override fun events(): Flow<VehicleEvent> = sources.map { it.events() }.merge()

    override fun nowMs(): Long = clock()

    companion object {
        fun merge(maps: List<CapabilityMap>): CapabilityMap {
            val keys = maps.flatMap { it.supported.keys }.toSet()
            return CapabilityMap(keys.associateWith { k ->
                val values = maps.mapNotNull { m -> if (k in m.supported) m.supported[k] else NOT_SAID }
                when {
                    true in values -> true
                    false in values -> false
                    else -> null
                }
            })
        }

        private val NOT_SAID: Boolean? = null
    }
}

/**
 * 차속: Android 차량 표준 속성 PERF_VEHICLE_SPEED (m/s → km/h).
 * 차량용 Android가 아니면 미지원(false)이다. 권한이 없으면 알 수 없음(null)이다.
 */
class CarSpeedSource(private val context: Context) : SignalSource {
    val isAutomotive: Boolean get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    private fun hasPermission() = context.checkSelfPermission(SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override suspend fun capabilities(): CapabilityMap = CapabilityMap(
        mapOf(CapabilityKey.VEHICLE_SPEED to if (!isAutomotive) false else if (hasPermission()) true else null)
    )

    override fun nowMs(): Long = System.currentTimeMillis()

    // android.car 클래스는 차량용 Android에만 있으므로 isAutomotive가 참일 때만 건드린다.
    // registerCallback은 API 36에서 deprecated지만 minSdk 28 차량을 지원하려면 이 API를 써야 한다.
    @Suppress("DEPRECATION")
    override fun events(): Flow<VehicleEvent> = callbackFlow {
        if (!isAutomotive || !hasPermission()) {
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                (value.value as? Float)?.let { trySend(VehicleEvent(nowMs(), Signal.VehicleSpeed(metersPerSecondToKmh(it)))) }
            }
            override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit // 오류를 0km/h로 바꾸지 않는다.
        }
        var manager: CarPropertyManager? = null
        var car: Car? = null
        // 연결·구독은 차량 서비스 전용 스레드에서 한다. 메인 스레드에서 하면 서비스가 느릴 때 ANR이 난다.
        // 붙지 못하면 차속은 계속 "알 수 없음"이고, 0km/h로 바꾸지 않는다.
        if (android.os.Build.VERSION.SDK_INT >= 30) com.alertbridge.app.platform.CarThread.post {
            car = Car.createCar(context, com.alertbridge.app.platform.CarThread.handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (!ready) return@createCar
                // android.car는 이 콜백을 항상 메인 스레드로 보낸다. 바인더 호출은 다시 전용 스레드로 넘긴다.
                com.alertbridge.app.platform.CarThread.post {
                    runCatching {
                        val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                        m.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
                        manager = m
                    }.onFailure { Log.w(TAG, "차속 구독 실패", it) }
                }
            }
        }
        awaitClose {
            com.alertbridge.app.platform.CarThread.post {
                runCatching { manager?.unregisterCallback(callback) }
                runCatching { car?.disconnect() }
            }
        }
    }

    companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        private const val TAG = "CarSpeedSource"
        fun metersPerSecondToKmh(v: Float): Float = v * 3.6f
    }
}

/**
 * 실제 Pleos 차량 서비스에 붙는다. 서비스 앱이 설치된 기기에서만 만든다.
 * TODO(VERIFY): Vehicle.initialize()가 비동기로 서비스에 붙는지 실차에서 확인. 붙기 전 check 호출은 오류(null)로 처리된다.
 */
class PleosLiveSource private constructor(private val context: Context) : SignalSource {
    // SDK 초기화·조회·등록은 모두 Pleos 서비스와의 바인더 호출이다. 서비스가 느리거나 재시작 중이면 오래 막히므로
    // 메인 스레드에서 하지 않는다 (Pleos 에뮬레이터에서 메인 스레드 호출로 ANR이 나는 것을 확인).
    private val lock = Any()
    private var vehicle: Vehicle? = null
    private var adapter: PleosSignalSource? = null

    /** 처음 쓸 때 IO 스레드에서 연결한다. 실패하면 null이고, 차량 신호 없이 계속한다. */
    private fun connect(): PleosSignalSource? = synchronized(lock) {
        adapter ?: runCatching {
            val v = Vehicle(context).also { it.initialize() }
            vehicle = v
            PleosSignalSource(PleosVehicleApis.from(v)).also { adapter = it }
        }.onFailure { Log.w("PleosLiveSource", "Pleos 차량 서비스 연결 실패", it) }.getOrNull()
    }

    override suspend fun capabilities(): CapabilityMap =
        withContext(Dispatchers.IO) { connect()?.capabilities() } ?: CapabilityMap()

    override fun events(): Flow<VehicleEvent> = callbackFlow {
        val unregister = connect()?.register { trySend(it) }
        awaitClose {
            kotlin.concurrent.thread(name = "pleos-release", isDaemon = true) {
                runCatching { unregister?.invoke() }
                runCatching { synchronized(lock) { vehicle?.release(); vehicle = null; adapter = null } }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun nowMs(): Long = System.currentTimeMillis()

    companion object {
        const val SERVICE_PACKAGE = "ai.pleos.playground.service.vehicle"

        fun isServiceInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(SERVICE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        /** 서비스 앱이 설치돼 있으면 만든다. 실제 연결은 [capabilities]·[events]에서 IO 스레드로 한다. */
        fun tryCreate(context: Context): PleosLiveSource? =
            if (isServiceInstalled(context)) PleosLiveSource(context.applicationContext) else null
    }
}
