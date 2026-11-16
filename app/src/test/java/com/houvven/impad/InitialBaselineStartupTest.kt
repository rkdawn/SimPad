package com.houvven.impad

import com.houvven.impad.fixtures.BaselineTinkerFixtures as F
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class InitialBaselineStartupTest {
    private class Call(private val original: () -> Any?) : HookCall {
        var calls = 0
        override val receiver: Any? = null
        override val arguments = emptyList<Any?>()
        override fun proceed(): Any? { calls++; return original() }
    }
    private fun rejects(action: () -> Unit) { var rejected = false; try { action() } catch (_: Exception) { rejected = true }; assertTrue(rejected) }
    @Test fun originalEarlyOnlyConditionRejectsTheObservedPostOnlyShape() {
        val early = F.PostOnly::class.java.declaredMethods.singleOrNull { it.name == "createInlineFence" && !Modifier.isStatic(it.modifiers) }
        assertNull(early)
        assertEquals(WeChatStartMode.POST_TINKER_ATTACH, WeChatStartupSelector.select(F.PostOnly::class.java).mode)
    }
    @Test fun existingStandardPostEntryIsPreferredOverEarlyEntry() { assertEquals(WeChatStartMode.POST_TINKER_ATTACH, WeChatStartupSelector.select(F.Both::class.java).mode) }
    @Test fun originalKnownEarlyShapeRemainsSupported() { val selected = WeChatStartupSelector.select(F.EarlyOnly::class.java); assertEquals(WeChatStartMode.EARLY_INLINE_FENCE, selected.mode); assertNotNull(selected.loaderField); assertNotNull(selected.fenceField) }
    @Test fun privatePostIsNotMistakenForTheStandardEntry() { rejects { WeChatStartupSelector.select(F.PrivatePost::class.java) } }
    @Test fun staticPostIsNotMistakenForTheStandardEntry() { rejects { WeChatStartupSelector.select(F.StaticPost::class.java) } }
    @Test fun wrongParametersDoNotSelectAnArbitraryOverload() { rejects { WeChatStartupSelector.select(F.WrongParameters::class.java) } }
    @Test fun missingEarlyFieldsRejectsUnsupportedShape() { rejects { WeChatStartupSelector.select(F.MissingEarlyFields::class.java) } }
    @Test fun abstractPostRejectsUnsupportedShape() { rejects { WeChatStartupSelector.select(F.AbstractPost::class.java) } }
    @Test fun postReadyRunsOnlyAfterOriginalReturns() {
        val events = mutableListOf<String>()
        val gate = WeChatStartupOnce(false, { events += "ready" }, {}, { throw AssertionError(it) })
        val call = Call { events += "host"; "result" }
        assertEquals("result", gate.intercept(call)); assertEquals(listOf("host", "ready"), events); assertEquals(1, call.calls)
    }
    @Test fun earlyReadyStillRunsBeforeOriginal() {
        val events = mutableListOf<String>()
        val gate = WeChatStartupOnce(true, { events += "ready" }, {}, { throw AssertionError(it) })
        gate.intercept(Call { events += "host"; null }); assertEquals(listOf("ready", "host"), events)
    }
    @Test fun nestedCallsDoNotInstallMultipleTimes() {
        var ready = 0; var retired = 0
        lateinit var gate: WeChatStartupOnce
        gate = WeChatStartupOnce(false, { ready++ }, { retired++ }, { throw AssertionError(it) })
        val nested = Call { "nested" }
        val outer = Call { gate.intercept(nested); assertEquals(0, ready); "outer" }
        assertEquals("outer", gate.intercept(outer)); assertEquals(1, ready); assertEquals(0, retired)
        assertEquals(1, outer.calls); assertEquals(1, nested.calls)
    }
    @Test fun laterGenerationRetiresExactlyOnceWithoutReinstallation() {
        var ready = 0; var retired = 0
        val gate = WeChatStartupOnce(false, { ready++ }, { retired++ }, { throw AssertionError(it) })
        repeat(3) { gate.intercept(Call { null }) }
        assertEquals(1, ready); assertEquals(1, retired)
    }
    @Test fun originalExceptionIsPreservedAndPostInstallNeverRuns() {
        val error = IllegalStateException("host"); var ready = 0
        val gate = WeChatStartupOnce(false, { ready++ }, {}, { throw AssertionError(it) })
        val call = Call { throw error }
        try { gate.intercept(call); fail() } catch (caught: IllegalStateException) { assertSame(error, caught) }
        assertEquals(1, call.calls); assertEquals(0, ready)
    }
    @Test fun moduleAndLoggerFailuresDoNotCallOriginalTwice() {
        val gate = WeChatStartupOnce(true, { throw IllegalArgumentException("module") }, {}, { throw IllegalStateException("logger") })
        val call = Call { 42 }
        assertEquals(42, gate.intercept(call)); assertEquals(1, call.calls)
    }
}
