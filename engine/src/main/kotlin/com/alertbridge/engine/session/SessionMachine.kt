package com.alertbridge.engine.session

import com.alertbridge.engine.model.SessionState
import com.alertbridge.engine.model.SessionState.*
import com.alertbridge.engine.model.StateChange

/**
 * 사건 상태 기계. CONFIRMED로 가는 길은 CONTACT_ATTEMPTED뿐이고,
 * 접수를 시도한 뒤에는 DISMISSED(사고 아님)로 돌아갈 수 없다.
 */
class SessionMachine(startMs: Long) {
    private val log = mutableListOf(StateChange(startMs, CREATED))

    val state: SessionState get() = log.last().state
    val history: List<StateChange> get() = log.toList()
    val isTerminal: Boolean get() = state == CLOSED || state == DISMISSED

    /** 허용되지 않은 전이는 무시하고 false를 돌려준다. */
    fun transition(to: SessionState, tMs: Long): Boolean {
        if (to !in ALLOWED.getValue(state)) return false
        log += StateChange(tMs, to)
        return true
    }

    /** 앞으로만 진행한다. 이미 지난 상태로는 돌아가지 않으며, 중간 단계는 차례로 거친다. */
    fun advanceTo(target: SessionState, tMs: Long) {
        val from = FORWARD.indexOf(state)
        val to = FORWARD.indexOf(target)
        if (from < 0 || to < 0 || to <= from) return
        for (i in from + 1..to) transition(FORWARD[i], tMs)
    }

    companion object {
        private val FORWARD = listOf(CREATED, SAFETY_CHECK, SUPPORT_SELECTED, CONTACT_ATTEMPTED, CONFIRMED, RECOVERY, CLOSED)

        val ALLOWED: Map<SessionState, Set<SessionState>> = mapOf(
            CREATED to setOf(SAFETY_CHECK, DISMISSED),
            SAFETY_CHECK to setOf(SUPPORT_SELECTED, DISMISSED, CLOSED),
            SUPPORT_SELECTED to setOf(CONTACT_ATTEMPTED, DISMISSED, CLOSED),
            CONTACT_ATTEMPTED to setOf(CONFIRMED, CLOSED),
            CONFIRMED to setOf(RECOVERY, CLOSED),
            RECOVERY to setOf(CLOSED),
            CLOSED to emptySet(),
            DISMISSED to emptySet(),
        )
    }
}
