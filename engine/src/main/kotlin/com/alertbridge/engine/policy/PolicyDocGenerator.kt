package com.alertbridge.engine.policy

import com.alertbridge.engine.model.RiskGrade
import com.alertbridge.engine.risk.Fact
import java.io.File

/** policy_v1.json을 사람이 읽는 표로 바꾼다. `./gradlew :engine:generatePolicyDoc` */
fun main(args: Array<String>) {
    val out = File(args.first())
    out.parentFile.mkdirs()
    out.writeText(renderPolicyDoc(PolicyLoader.loadDefault()), Charsets.UTF_8)
    println("written: ${out.absolutePath}")
}

fun renderPolicyDoc(p: PolicyTable): String = buildString {
    appendLine("# 정책 테이블 (자동 생성)")
    appendLine()
    appendLine("`engine/src/main/resources/policy/policy_v1.json`에서 생성했다. 직접 고치지 말고 `./gradlew :engine:generatePolicyDoc`로 다시 만든다.")
    appendLine()
    appendLine("- 버전: `${p.version}`, 시행일: ${p.effectiveDate}")
    appendLine()
    appendLine("## 임계값")
    appendLine()
    appendLine("| 이름 | 값 |")
    appendLine("|---|---|")
    p.thresholds.toString().removePrefix("Thresholds(").removeSuffix(")").split(", ").forEach {
        val (k, v) = it.split("=")
        appendLine("| `$k` | $v |")
    }
    appendLine()
    appendLine("## 트리거 (위가 우선)")
    appendLine()
    appendLine("| ID | 기본 신뢰도 |")
    appendLine("|---|---|")
    p.triggers.forEach { appendLine("| ${it.id} | ${it.confidence} |") }
    appendLine()
    appendLine("## 위험 등급 규칙 (한 줄이라도 맞으면 그 등급)")
    appendLine()
    appendLine("| 등급 | 조건 (모두 참) | 제외 조건 | 이유 코드 |")
    appendLine("|---|---|---|---|")
    p.riskRules.forEach { r ->
        val cond = r.`when`.joinToString(" + ") { it.description }
        val not = r.notAny.joinToString(", ") { it.description }.ifEmpty { "-" }
        appendLine("| ${r.grade} | $cond | $not | `${r.reason}` |")
    }
    appendLine()
    appendLine("## 조치 순서")
    appendLine()
    appendLine("등급이 여럿이면 R0 > R1 > R2 > R3 > R4 순으로 합치고 중복을 제거한다. R0가 있으면 `CALL_119`가 항상 1순위다.")
    appendLine()
    RiskGrade.entries.forEach { g ->
        appendLine("### $g")
        appendLine()
        appendLine("| 순서 | 조치 | 조건 |")
        appendLine("|---|---|---|")
        p.actionOrder[g].orEmpty().forEachIndexed { i, s ->
            val parts = s.`when`.map { it.description } + s.notAny.map { "${it.description} 아님" }
            appendLine("| ${i + 1} | `${s.id}` | ${parts.joinToString(" + ").ifEmpty { "-" }} |")
        }
        appendLine()
    }
    appendLine("## 보험 매칭 (참고 정보)")
    appendLine()
    appendLine("| 조건 | 청구 후보 | 담보가 없을 때 |")
    appendLine("|---|---|---|")
    p.coverageRules.forEach { r ->
        appendLine("| ${r.`when`.joinToString(" + ") { it.description }} | ${r.claim.joinToString(", ")} | ${r.gapIfMissing ?: "-"} |")
    }
    appendLine()
    appendLine("## 도로·차량 조건")
    appendLine()
    appendLine("- 고속도로로 보는 도로: ${p.expresswayRoadTypes.joinToString(", ")}")
    appendLine("- 2504 안내 도로: ${p.tow2504RoadTypes.joinToString(", ")}")
    appendLine("- 2504 안내 차량 크기: ${p.tow2504SizeClasses.joinToString(", ")} (크기 미등록 허용: ${p.thresholds.allow2504WhenSizeUnknown})")
    appendLine("- 경고로 보는 경고등: ${p.telltaleWarnings.joinToString(", ")}")
    appendLine()
    appendLine("## 연락처")
    appendLine()
    p.contacts.forEach { (k, v) -> appendLine("- $k: $v") }
    appendLine()
    appendLine("## 견인 운임표")
    appendLine()
    appendLine("- 출처: ${p.towFareTable.source}")
    appendLine("- 기준일: ${p.towFareTable.asOf.ifEmpty { "미입력" }}")
    appendLine("- 행 수: ${p.towFareTable.rows.size}")
    appendLine()
    appendLine("## 사실(Fact) 목록")
    appendLine()
    appendLine("| 이름 | 뜻 |")
    appendLine("|---|---|")
    Fact.entries.forEach { appendLine("| `${it.name}` | ${it.description} |") }
}
