package io.github.rkdawn.simpad

import com.tencent.mm.fixture.TabletFixture
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.luckypray.dexkit.wrap.DexMethod

class FormalThreePointTest {
    private class Call(private val host: () -> Any? = { false }) : HookCall {
        var calls = 0
        override val receiver: Any? = null
        override val arguments: List<Any?> = emptyList()
        override fun proceed(): Any? { calls++; return host() }
    }
    private fun method(name: String) = TabletFixture::class.java.declaredMethods.single { it.name == name }
    private fun desc(name: String) = DexMethod(method(name)).serialize()
    private fun plan() = PlanDescriptor(AppTarget.WECHAT, linkedMapOf("inner" to desc("decision"),
        "tablet" to desc("wrapper"), "login" to desc("login")),
        "Lcom/tencent/mm/fixture/TabletFixture;->cache:Ljava/lang/Boolean;", hookTarget = "inner")
    @Before fun reset() { TabletFixture.cache = null; TabletFixture.invocations = 0; TabletFixture.monitor = Any() }
    @Test fun innerHookIsBypassedAfterWrapperCachesFalse() {
        val spec = PlanBinder.bind(plan(), javaClass.classLoader!!).first()
        val first = Call { TabletFixture.decision(null) }
        assertEquals(true, spec.callback.intercept(first)); assertEquals(false, TabletFixture.cache)
        assertEquals(false, TabletFixture.wrapper())
        assertEquals(1, TabletFixture.invocations)
    }
    @Test fun cachedWrapperHookKeepsTabletResultAfterOriginalCachesFalse() {
        val wrapperPlan = plan().copy(hookTarget = "tablet")
        val spec = PlanBinder.bind(wrapperPlan, javaClass.classLoader!!).first()
        assertEquals(method("wrapper"), spec.method)
        repeat(3) {
            val call = Call { TabletFixture.wrapper() }
            assertEquals(true, spec.callback.intercept(call)); assertEquals(1, call.calls)
            assertEquals(false, TabletFixture.cache)
        }
        assertEquals(1, TabletFixture.invocations)
    }
    @Test fun bindsOnlyCAndLoginRatherThanB() {
        val specs = PlanBinder.bind(plan(), javaClass.classLoader!!)
        assertEquals(listOf("wechat_device", "wechat_login"), specs.map { it.id })
        assertEquals(method("decision"), specs[0].method); assertEquals(method("login"), specs[1].method)
        assertFalse(specs.any { it.method == method("wrapper") })
    }
    @Test fun actualBoundCOverridesReturnWithoutChangingOriginalCache() {
        TabletFixture.cache = null
        val spec = PlanBinder.bind(plan(), javaClass.classLoader!!).first()
        val call = Call { TabletFixture.decision(null) }
        assertEquals(true, spec.callback.intercept(call)); assertEquals(false, TabletFixture.cache); assertEquals(1, call.calls)
    }
    @Test fun unusedCacheMetadataIsNotReflectivelyBoundAtRuntime() {
        val metadataOnly = plan().copy(cacheField = "Lcom/tencent/mm/fixture/TabletFixture;->nonexistentMetadata:Ljava/lang/Boolean;")
        assertEquals(2, PlanBinder.bind(metadataOnly, javaClass.classLoader!!).size)
    }
    @Test fun CChatResultIsFalseButItsOriginalStillExecutesOnce() {
        val callbacks = WeChatCallbacks(stack = { arrayOf(StackTraceElement("com.tencent.mm.pluginsdk.ui.chat.ChatFooter", "draw", "Code.kt", 1)) })
        val call = Call { TabletFixture.cache = false; true }
        assertEquals(false, callbacks.tablet(call)); assertEquals(false, TabletFixture.cache); assertEquals(1, call.calls)
    }
    @Test fun COtherCallKeepsTabletResultAndOriginalSideEffects() {
        val callbacks = WeChatCallbacks(stack = { arrayOf(StackTraceElement("com.tencent.mm.ui.LauncherUI", "create", "Code.kt", 1)) })
        val call = Call { TabletFixture.cache = false; false }
        assertEquals(true, callbacks.tablet(call)); assertEquals(false, TabletFixture.cache); assertEquals(1, call.calls)
    }
    @Test fun COriginalFailurePropagatesBeforeStackOrResultOverride() {
        val expected = IllegalStateException("original")
        val callbacks = WeChatCallbacks(stack = { fail("Stack lookup must not run after a failed original"); emptyArray() })
        val call = Call { throw expected }
        try { callbacks.tablet(call); fail() } catch (caught: IllegalStateException) { assertSame(expected, caught) }
        assertEquals(1, call.calls)
    }
}
