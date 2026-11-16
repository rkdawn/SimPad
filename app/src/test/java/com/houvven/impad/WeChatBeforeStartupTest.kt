package com.houvven.impad

import org.junit.Assert.*
import org.junit.Test

class WeChatBeforeStartupTest {
    private class Call(private val host: () -> Any?) : HookCall {
        var calls = 0
        override val receiver: Any = Any()
        override val arguments: List<Any?> = listOf(Any())
        override fun proceed(): Any? { calls++; return host() }
    }
    private val loader = javaClass.classLoader!!
    private fun observation(call: HookCall) = BeforeStartupObservation(call.arguments.first()!!, loader)
    @Test fun installsBeforeOriginalAndChecksOnlyAfterReturn() {
        val events = mutableListOf<String>()
        val experiment = WeChatBeforeStartup(::observation, { events += "install" }, { _, _ -> events += "check"; true }, { events += "undo" })
        val call = Call { events += "host"; "result" }
        assertEquals("result", experiment.intercept(call)); assertEquals(listOf("install", "host", "check"), events); assertEquals(1, call.calls)
    }
    @Test fun capturesTheBaseContextArgumentRatherThanChangingTheHostReceiver() {
        val call = Call { null }; var captured: Any? = null
        WeChatBeforeStartup(::observation, { captured = it.context }, { _, _ -> true }, {}).intercept(call)
        assertSame(call.arguments.first(), captured); assertNotSame(call.receiver, captured)
    }
    @Test fun changedAfterLoaderOrInputsRollsBackWithoutAfterReinstallation() {
        var installed = 0; var undo = 0; var errors = 0
        val experiment = WeChatBeforeStartup(::observation, { installed++ }, { _, _ -> false }, { undo++ }, onError = { errors++ })
        val call = Call { 7 }
        assertEquals(7, experiment.intercept(call)); assertEquals(1, call.calls); assertEquals(1, installed); assertEquals(1, undo); assertEquals(1, errors)
    }
    @Test fun afterInputCheckExceptionKeepsOriginalOutcome() {
        var undo = 0
        val experiment = WeChatBeforeStartup(::observation, {}, { _, _ -> throw IllegalStateException("check") }, { undo++ })
        val call = Call { "host" }; assertEquals("host", experiment.intercept(call)); assertEquals(1, undo); assertEquals(1, call.calls)
    }
    @Test fun originalFailureRetiresBeforeHooksAndPreservesTheSameException() {
        val failure = IllegalArgumentException("host"); var undo = 0
        val experiment = WeChatBeforeStartup(::observation, {}, { _, _ -> true }, { undo++ })
        val call = Call { throw failure }
        try { experiment.intercept(call); fail() } catch (caught: IllegalArgumentException) { assertSame(failure, caught) }
        assertEquals(1, undo); assertEquals(1, call.calls)
    }
    @Test fun installAndHostFailuresDoNotUnhookTheSameBatchTwice() {
        var undo = 0; val failure = IllegalStateException("host")
        val experiment = WeChatBeforeStartup(::observation, { throw IllegalArgumentException("install") }, { _, _ -> true }, { undo++ })
        try { experiment.intercept(Call { throw failure }); fail() } catch (caught: IllegalStateException) { assertSame(failure, caught) }
        assertEquals(1, undo)
    }
    @Test fun invalidBeforeContextDoesNotSkipOrRepeatOriginal() {
        var installs = 0
        val experiment = WeChatBeforeStartup({ throw IllegalArgumentException("context") }, { installs++ }, { _, _ -> true }, {}, onError = { throw IllegalStateException("logger") })
        val call = Call { 1 }; assertEquals(1, experiment.intercept(call)); assertEquals(1, call.calls); assertEquals(0, installs)
    }
    @Test fun nestedStartupCallsRemainTransparent() {
        var installs = 0; var undo = 0
        lateinit var experiment: WeChatBeforeStartup
        experiment = WeChatBeforeStartup(::observation, { installs++ }, { _, _ -> true }, { undo++ })
        val inner = Call { 2 }; val outer = Call { experiment.intercept(inner); 3 }
        assertEquals(3, experiment.intercept(outer)); assertEquals(1, installs); assertEquals(0, undo); assertEquals(1, outer.calls); assertEquals(1, inner.calls)
    }
    @Test fun laterGenerationDoesNotReinstallBeforeOrAfter() {
        var installs = 0; var undo = 0
        val experiment = WeChatBeforeStartup(::observation, { installs++ }, { _, _ -> true }, { undo++ })
        repeat(3) { experiment.intercept(Call { null }) }
        assertEquals(1, installs); assertEquals(1, undo)
    }
    @Test fun diagnosticFailureDoesNotChangeOriginalOrInstallation() {
        var installs = 0
        val experiment = WeChatBeforeStartup(::observation, { installs++ }, { _, _ -> true }, {}, diagnostic = { throw IllegalArgumentException("log") })
        val call = Call { 42 }; assertEquals(42, experiment.intercept(call)); assertEquals(1, call.calls); assertEquals(1, installs)
    }
}
