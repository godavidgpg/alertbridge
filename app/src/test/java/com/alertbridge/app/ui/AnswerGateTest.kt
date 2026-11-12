package com.alertbridge.app.ui

import com.alertbridge.engine.model.TriageQ
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnswerGateTest {
    @Test fun `confirmation double tap cannot answer injury`() {
        val gate = AnswerGate()
        assertTrue(gate.accept(TriageQ.IS_ACCIDENT, TriageQ.IS_ACCIDENT, 1000))
        assertFalse(gate.accept(TriageQ.IS_ACCIDENT, TriageQ.INJURY, 1100))
        assertFalse(gate.accept(TriageQ.INJURY, TriageQ.INJURY, 1100))
        assertTrue(gate.accept(TriageQ.INJURY, TriageQ.INJURY, 1700))
    }

    @Test fun `no pending question rejects input and new session resets cooldown`() {
        val gate = AnswerGate()
        assertFalse(gate.accept(TriageQ.INJURY, null, 1000))
        assertTrue(gate.accept(TriageQ.INJURY, TriageQ.INJURY, 1000))
        gate.reset()
        assertTrue(gate.accept(TriageQ.INJURY, TriageQ.INJURY, 1100))
    }
}
