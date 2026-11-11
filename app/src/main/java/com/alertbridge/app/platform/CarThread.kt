package com.alertbridge.app.platform

import android.os.Handler
import android.os.HandlerThread

/**
 * 차량 서비스(android.car) 호출 전용 스레드.
 * createCar의 연결 콜백과 getCarManager는 바인더 호출이라, 차량 서비스가 느리거나 응답하지 않으면 오래 막힌다.
 * 메인 스레드에서 하면 화면이 멈추고 ANR로 앱이 종료되므로(Pleos 에뮬레이터에서 실제로 확인) 모두 이 스레드에서 한다.
 */
object CarThread {
    private val thread = HandlerThread("car-service").apply { start() }
    val handler: Handler = Handler(thread.looper)

    fun post(block: () -> Unit) {
        handler.post { runCatching(block) }
    }
}
