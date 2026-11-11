package com.alertbridge.app.data

import android.content.Context
import com.alertbridge.engine.model.InsurancePolicy
import kotlinx.serialization.json.Json

/**
 * 가입할 때 등록한 보험 정보. 기기 안에만 저장한다.
 * 나중에 보험 마이데이터나 보험사 제휴 API로 받아 오면 이 저장소만 채우면 된다.
 */
class PolicyStore(context: Context) {
    private val prefs = context.getSharedPreferences("my_policy", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): InsurancePolicy? = prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(InsurancePolicy.serializer(), it) }.getOrNull() }

    fun save(p: InsurancePolicy) = prefs.edit().putString(KEY, json.encodeToString(InsurancePolicy.serializer(), p)).apply()

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object { const val KEY = "policy_v1" }
}
