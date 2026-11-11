package com.alertbridge.agent.stats

import com.alertbridge.engine.model.InsuredCarClass
import com.alertbridge.engine.model.VehicleUse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.YearMonth

/** 공공 통계의 담보 이름 */
enum class StatCoverage(val statName: String) {
    JACHA("자기차량손해"), DAEMUL("대물배상"), DAEIN_1("대인배상1"), DAEIN_2("대인배상2"), JASIN("자기신체사고"),
}

@Serializable
data class StatRow(val ym: String, val use: String, val coverage: String, val carClass: String, val lossKrw: Long, val cases: Long)

/** 담보·종목·차종별 건당 평균 손해액. 개인 견적이 아니라 전체 계약의 평균이다. */
@Serializable
data class LossStat(
    val coverage: String,
    val use: String,
    /** 차종을 모르면 "전체 차종" */
    val carClass: String,
    /** 예: "2026년 1~8월 누계" */
    val period: String,
    val avgLossKrw: Long,
    val cases: Long,
    val source: String,
    /** 방금 API에서 받았으면 true, 앱에 내장된 통계면 false */
    val live: Boolean,
)

class StatTable(val rows: List<StatRow>, val live: Boolean, val source: String = SOURCE) {
    val ym: String? = rows.maxOfOrNull { it.ym }

    /** 차종을 모르면 소형·중형·대형·다인승을 합쳐 평균을 낸다. 건수가 0이면 null */
    fun average(coverage: StatCoverage, use: VehicleUse, carClass: InsuredCarClass): LossStat? {
        val month = ym ?: return null
        val cls = carClass.statName
        val picked = rows.filter {
            it.ym == month && it.use == use.statName && it.coverage == coverage.statName &&
                (if (cls != null) it.carClass == cls else it.carClass in PASSENGER_CLASSES)
        }
        val cases = picked.sumOf { it.cases }
        if (cases <= 0) return null
        val loss = picked.sumOf { it.lossKrw }.coerceAtLeast(0)
        return LossStat(coverage.statName, use.statName, cls ?: "전체 차종", periodLabel(month), loss / cases, cases, source, live)
    }

    companion object {
        const val SOURCE = "금융위원회 자동차보험가입정보(손해상황), 공공데이터포털"
        val PASSENGER_CLASSES = setOf("소형", "중형", "대형", "다인승")

        /** 통계는 연초부터 누계라 "1~M월 누계"로 표시한다 */
        fun periodLabel(ym: String): String {
            val y = ym.take(4)
            val m = ym.drop(4).toIntOrNull() ?: return ym
            return if (m == 1) "${y}년 1월" else "${y}년 1~${m}월 누계"
        }
    }
}

/** 손해 통계를 가져온다. 실패해도 예외 대신 내장 통계를 돌려준다. */
interface ClaimStats {
    suspend fun table(): StatTable
}

/** 앱에 내장한 통계 (2026년 1~8월 누계). 키가 없거나 오프라인일 때 쓴다. */
object SnapshotClaimStats : ClaimStats {
    private val cached: StatTable by lazy {
        val text = SnapshotClaimStats::class.java.getResourceAsStream("/claim_stats_snapshot.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val o = Json.parseToJsonElement(text).jsonObject
        StatTable(Json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(StatRow.serializer()), o["rows"]!!), live = false)
    }

    override suspend fun table() = cached
}

/**
 * 공공데이터포털 손해상황 API. 올해(없으면 작년) 통계를 한 번에 받아 가장 최근 달을 쓴다.
 * 한 번 받으면 앱이 켜져 있는 동안 다시 받지 않는다. 키가 틀리거나 네트워크가 없으면 내장 통계로 대신한다.
 */
class DataGoKrClaimStats(
    private val serviceKey: String,
    private val baseUrl: String = BASE_URL,
    private val now: () -> YearMonth = { YearMonth.now() },
    private val fetch: (String) -> String = ::httpGet,
    private val fallback: ClaimStats = SnapshotClaimStats,
) : ClaimStats {
    private val lock = Mutex()
    private var cached: StatTable? = null

    /** 마지막으로 실패한 이유. 로직 뷰에서 본다 */
    @Volatile var lastError: String? = null
        private set

    override suspend fun table(): StatTable = lock.withLock {
        cached ?: load().also { if (it.live) cached = it }
    }

    private suspend fun load(): StatTable {
        val year = now().year
        for (y in listOf(year, year - 1)) {
            val rows = runCatching { fetchYear(y) }.getOrElse { e ->
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                return fallback.table()
            }
            if (rows.isNotEmpty()) { lastError = null; return StatTable(rows, live = true) }
        }
        lastError = "통계가 비어 있어요"
        return fallback.table()
    }

    private fun fetchYear(year: Int): List<StatRow> {
        val url = "$baseUrl/getLosCircumstance?serviceKey=${URLEncoder.encode(serviceKey, "UTF-8")}" +
            "&pageNo=1&numOfRows=1000&resultType=json&likeIsuCmpyOfrYm=$year"
        return parse(fetch(url))
    }

    companion object {
        const val BASE_URL = "https://apis.data.go.kr/1160100/service/GetFPAtmbInsujoinInfoService"

        /** 응답 JSON을 행으로 바꾼다. 오류 응답이면 예외 */
        fun parse(body: String): List<StatRow> {
            val response = Json.parseToJsonElement(body).jsonObject["response"]?.jsonObject
                ?: error("응답 형식이 달라요: ${body.take(120)}")
            val code = response["header"]?.jsonObject?.get("resultCode")?.jsonPrimitive?.content
            if (code != "00") error("API 오류 $code: ${response["header"]}")
            val items = response["body"]?.jsonObject?.get("items")
            // 결과가 없으면 items가 빈 문자열로 오기도 한다.
            val list = ((items as? JsonObject)?.get("item") as? JsonArray) ?: return emptyList()
            return list.mapNotNull { e ->
                val o = e.jsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.content
                fun n(k: String) = s(k)?.toLongOrNull() ?: 0L
                StatRow(
                    ym = s("isuCmpyOfrYm") ?: return@mapNotNull null,
                    use = s("isuItmsNm").orEmpty(),
                    coverage = s("mogClsfNm").orEmpty(),
                    carClass = s("kncrNm").orEmpty(),
                    lossKrw = n("losAmt"),
                    cases = n("injPtlCnt") + n("dthTotlCnt"),
                )
            }
        }

        private fun httpGet(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 8_000
            return try {
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (code !in 200..299) error("HTTP $code ${text.take(120)}")
                text
            } finally { c.disconnect() }
        }
    }
}
