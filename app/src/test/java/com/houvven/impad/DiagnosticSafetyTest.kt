package com.houvven.impad

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSafetyTest {
    @Test fun ordinaryLoggerFailureDoesNotAbortFollowingBusinessWork() {
        var reached = false
        bestEffortDiagnostic { throw IllegalStateException("logger unavailable") }
        reached = true
        assertTrue(reached)
    }
    @Test fun successfulDiagnosticRunsOnce() {
        var calls = 0
        bestEffortDiagnostic { calls++ }
        assertEquals(1, calls)
    }
    @Test fun fatalLoggerErrorIsNotSwallowed() {
        val expected = OutOfMemoryError("fatal logger")
        try { bestEffortDiagnostic { throw expected }; fail() } catch (caught: OutOfMemoryError) { assertSame(expected, caught) }
    }
    @Test fun threadDeathIsNotSwallowedByDiagnostics() {
        val expected = ThreadDeath()
        try { bestEffortDiagnostic { throw expected }; fail() } catch (caught: ThreadDeath) { assertSame(expected, caught) }
    }
}
