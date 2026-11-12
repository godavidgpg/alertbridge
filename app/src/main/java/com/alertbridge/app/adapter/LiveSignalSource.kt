package com.alertbridge.app.adapter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.alertbridge.engine.model.CapabilityKey
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.RoadType
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.VehicleEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 실제 위치 API(android.location.LocationManager)를 엔진 입력으로 바꾼다.
 *
 * GPS는 좌표·오차만 안다. 고속도로인지는 모르므로 roadType = UNKNOWN, 신뢰도 0으로 넣는다.
 * 그러면 엔진은 R1·2504 판단에 도로 종류를 쓰지 않고 "고속도로인가요?"를 묻는다.
 * 도로 종류는 지도 데이터(NaviHelper 등)를 붙일 때 채운다. TODO(VERIFY)
 *
 * 나머지 차량 신호는 PleosSignalSource 몫이라 capability를 넣지 않는다 (= 알 수 없음).
 */
/**
 * @param gpsSpeedWhen 참이면 GPS 속도도 차속으로 보낸다. 차량 서비스 차속이 들어오지 않을 때만 켠다
 *   (두 차속이 섞이면 GPS 지연 때문에 급정거를 잘못 볼 수 있다).
 */
class LiveSignalSource(
    private val context: Context,
    private val gpsSpeedWhen: () -> Boolean = { false },
) : SignalSource {

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override suspend fun capabilities(): CapabilityMap = CapabilityMap(
        buildMap {
            put(CapabilityKey.LOCATION, hasPermission())
            // GPS 속도를 대신 쓸 수 있으면 차속을 "지원"으로 알린다. 아니면 말하지 않는다(다른 입력이 정한다).
            if (hasPermission() && gpsSpeedWhen()) put(CapabilityKey.VEHICLE_SPEED, true)
        }
    )

    override fun nowMs(): Long = System.currentTimeMillis()

    @SuppressLint("MissingPermission") // hasPermission()으로 확인한다.
    override fun events(): Flow<VehicleEvent> = callbackFlow {
        if (!hasPermission()) {
            awaitClose { }
            return@callbackFlow
        }
        val manager = context.getSystemService(LocationManager::class.java)
        var address: String? = null
        var geocodedAt: Location? = null

        fun send(l: Location) {
            trySend(
                VehicleEvent(
                    nowMs(),
                    Signal.Location(
                        lat = l.latitude,
                        lng = l.longitude,
                        accuracyM = if (l.hasAccuracy()) l.accuracy else null,
                        roadType = RoadType.UNKNOWN,
                        roadTypeConfidence = 0f,
                        address = address,
                    ),
                )
            )
        }

        val listener = LocationListener { l ->
            send(l)
            if (l.hasSpeed() && gpsSpeedWhen()) trySend(VehicleEvent(nowMs(), Signal.VehicleSpeed(l.speed * 3.6f)))
            // 역지오코딩은 네트워크를 쓰므로 100m 이상 움직였을 때만 다시 한다.
            if (geocodedAt?.let { it.distanceTo(l) < GEOCODE_MIN_MOVE_M } != true) {
                geocodedAt = l
                launch {
                    reverseGeocode(l)?.let {
                        address = it
                        send(l)
                    }
                }
            }
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { manager.isProviderEnabled(it) }
        providers.forEach { p ->
            manager.getLastKnownLocation(p)?.let(listener::onLocationChanged)
            manager.requestLocationUpdates(p, UPDATE_MS, 0f, listener, Looper.getMainLooper())
        }
        awaitClose { manager.removeUpdates(listener) }
    }

    @Suppress("DEPRECATION") // 비동기 API는 API 33부터라 minSdk 28에서 쓸 수 없다.
    private suspend fun reverseGeocode(l: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            Geocoder(context, java.util.Locale.KOREA).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.getAddressLine(0)
        } catch (_: java.io.IOException) {
            null // 네트워크가 없으면 좌표만으로 진행한다.
        }
    }

    private companion object {
        const val UPDATE_MS = 1_000L
        const val GEOCODE_MIN_MOVE_M = 100f
    }
}
