package com.alertbridge.app.ui

import com.alertbridge.engine.model.TriageQ

/** Reject stale callbacks and double taps across a question transition using a real monotonic clock. */
internal class AnswerGate(private val cooldownMs: Long = 700) {
    private var acceptedAt: Long? = null

    fun accept(question: TriageQ, pending: TriageQ?, nowMs: Long): Boolean {
        if (pending != question) return false
        val previous = acceptedAt
        if (previous != null && nowMs - previous < cooldownMs) return false
        acceptedAt = nowMs
        return true
    }

    fun reset() { acceptedAt = null }
}
