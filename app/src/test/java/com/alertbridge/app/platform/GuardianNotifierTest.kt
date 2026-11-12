package com.alertbridge.app.platform

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GuardianNotifierTest {
    @Test fun `recording a mock notification never counts as delivery`() {
        val recorded = mutableListOf<String>()
        val notifier = FakeGuardianNotifier { _, message -> recorded += message }
        assertFalse(notifier.notify("01000000000", "test"))
        assertEquals(listOf("test"), recorded)
    }
}
