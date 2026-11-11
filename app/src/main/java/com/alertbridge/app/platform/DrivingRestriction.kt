package com.alertbridge.app.platform

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 차량용 Android의 주행 중 UX 제한 상태.
 * 제한 중에는 입력이 많은 화면(보험 등록, 로직 뷰)을 막고, 사고 대응 화면만 단순하게 보여 준다.
 * 휴대폰이거나 차량 서비스에 붙지 못하면 제한 없음(false)으로 본다. 그때는 화면이 차속(엔진의 vehicleMoving)으로 판단한다.
 */
class DrivingRestriction(context: Context) {
    private val _restricted = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted

    @Volatile private var car: Car? = null
    @Volatile private var manager: CarUxRestrictionsManager? = null

    init {
        // 기다리지 않는 연결을 쓴다. 기다리는 createCar는 서비스에 못 붙으면 메인 스레드를 5초 잡는다.
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) && Build.VERSION.SDK_INT >= 30) {
            // 연결과 매니저 조회는 모두 차량 서비스 전용 스레드에서 한다 (메인 스레드에서 하면 ANR).
            CarThread.post {
                car = Car.createCar(context, CarThread.handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                    if (!ready) { _restricted.value = false; return@createCar }
                    // android.car는 이 콜백을 항상 메인 스레드로 보낸다. 바인더 호출은 다시 전용 스레드로 넘긴다.
                    CarThread.post {
                        val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        apply(m.currentCarUxRestrictions)
                        m.registerListener { apply(it) }
                        manager = m
                    }
                }
            }
        }
    }

    private fun apply(r: CarUxRestrictions?) {
        _restricted.value = r?.isRequiresDistractionOptimization == true
    }

    fun release() {
        CarThread.post {
            runCatching { manager?.unregisterListener() }
            runCatching { car?.disconnect() }
        }
    }
}
