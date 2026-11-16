package com.houvven.impad

import com.tencent.mm.fixture.TabletFixture
import com.alibaba.android.dingtalkbase.foldable.TestClassifier
import com.tencent.wework.foundation.logic.Application as WecomApplication
import com.tencent.wework.foundation.impl.WeworkServiceImpl
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Method

class AppBehaviorTest {
    private class Call(override val arguments: List<Any?> = emptyList(), private val original: () -> Any? = { false }) : HookCall {
        var calls = 0
        override val receiver: Any? = null
        override fun proceed(): Any? { calls++; return original() }
    }
    private class FakeView(var visibility: Int = 8)
    private fun method(type: Class<*>, name: String) = type.declaredMethods.single { it.name == name }
    private fun descriptor(method: Method) = DexMethod(method).serialize()
    private fun callbacks(chat: Boolean = false) = WeChatCallbacks(
        stack = { if (chat) arrayOf(StackTraceElement("com.tencent.mm.pluginsdk.ui.chat.ChatFooter", "call", "Source.kt", 1)) else emptyArray() },
        isView = { it is FakeView }, visibility = { (it as FakeView).visibility }, show = { (it as FakeView).visibility = 0 })
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }

    @Before fun reset() { TabletFixture.cache = null; TabletFixture.invocations = 0; TabletFixture.monitor = Any() }
    @Test fun tabletCallbackPreservesOriginalInvocation() { val call = Call(); assertEquals(true, callbacks().tablet(call)); assertEquals(1, call.calls) }
    @Test fun chatCallbackKeepsPhoneLayout() { val call = Call(); assertEquals(false, callbacks(true).tablet(call)); assertEquals(1, call.calls) }
    @Test fun hiddenLoginViewIsShownAfterOriginal() {
        val view = FakeView(); val call = Call(listOf(view)) { assertEquals(8, view.visibility); "original" }
        assertEquals("original", callbacks().login(call)); assertEquals(0, view.visibility); assertEquals(1, call.calls)
    }
    @Test fun invisibleViewIsNotForcedVisible() { val view = FakeView(4); callbacks().login(Call(listOf(view))); assertEquals(4, view.visibility) }
    @Test fun visibleViewIsUnchanged() { val view = FakeView(0); callbacks().login(Call(listOf(view))); assertEquals(0, view.visibility) }
    @Test fun absentViewKeepsOriginalOutcome() { assertEquals("original", callbacks().login(Call { "original" })) }
    @Test fun unrelatedFirstArgumentIsNotModified() { assertEquals("original", callbacks().login(Call(listOf("text")) { "original" })) }
    @Test fun originalLoginExceptionIsPreserved() {
        val view = FakeView(); val expected = IllegalStateException("host failure")
        try { callbacks().login(Call(listOf(view)) { throw expected }); fail() } catch (error: IllegalStateException) { assertSame(expected, error) }
        assertEquals(8, view.visibility)
    }
    @Test fun innerReturnOverrideRunsAfterOriginalWithoutChangingCache() {
        val type = TabletFixture::class.java
        val callback = LegacyWeChatInnerContracts.inner(method(type, "decision"), type.getField("cache"), type.getField("monitor"))
        val call = Call { TabletFixture.decision(Any()) }
        assertEquals(true, callback.intercept(call)); assertEquals(false, TabletFixture.cache); assertEquals(1, TabletFixture.invocations)
    }
    @Test fun innerReturnOverrideWithoutMonitorKeepsOriginalCache() {
        val type = TabletFixture::class.java
        val callback = LegacyWeChatInnerContracts.inner(method(type, "decision"), type.getField("cache"), null)
        assertEquals(true, callback.intercept(Call { TabletFixture.decision(Any()) })); assertEquals(false, TabletFixture.cache)
    }
    @Test fun originalNullCacheStaysNullWhileReturnIsOverridden() {
        val type = TabletFixture::class.java
        val callback = LegacyWeChatInnerContracts.inner(method(type, "decision"), type.getField("cache"), null)
        val call = Call { false }
        assertEquals(true, callback.intercept(call)); assertNull(TabletFixture.cache); assertEquals(1, call.calls)
    }
    @Test fun originalTrueCacheStaysTrue() {
        val type = TabletFixture::class.java
        val callback = LegacyWeChatInnerContracts.inner(method(type, "decision"), type.getField("cache"), null)
        val call = Call { TabletFixture.cache = true; true }
        assertEquals(true, callback.intercept(call)); assertEquals(true, TabletFixture.cache); assertEquals(1, call.calls)
    }
    @Test fun repeatedInnerCallsLeaveFalseCacheUntouched() {
        val type = TabletFixture::class.java
        val callback = LegacyWeChatInnerContracts.inner(method(type, "decision"), type.getField("cache"), null)
        repeat(5) {
            val call = Call { TabletFixture.decision(Any()) }
            assertEquals(true, callback.intercept(call)); assertEquals(false, TabletFixture.cache); assertEquals(1, call.calls)
        }
        assertEquals(5, TabletFixture.invocations)
    }
    @Test fun unusedMonitorValueIsNotReadByReturnOverride() {
        val type = TabletFixture::class.java
        TabletFixture.monitor = null
        val callback = LegacyWeChatInnerContracts.inner(method(type, "decision"), type.getField("cache"), type.getField("monitor"))
        val call = Call { false }
        assertEquals(true, callback.intercept(call)); assertNull(TabletFixture.cache); assertEquals(1, call.calls)
    }
    @Test fun originalFailureRetainsItsCacheSideEffect() {
        val type = TabletFixture::class.java
        val callback = LegacyWeChatInnerContracts.inner(method(type, "decision"), type.getField("cache"), null)
        val expected = IllegalStateException("host failed after cache assignment")
        val call = Call { TabletFixture.cache = false; throw expected }
        try { callback.intercept(call); fail() } catch (error: IllegalStateException) { assertSame(expected, error) }
        assertEquals(false, TabletFixture.cache); assertEquals(1, call.calls)
    }
    @Test fun originalInnerFailureDoesNotChangeCache() {
        val type = TabletFixture::class.java
        val callback = LegacyWeChatInnerContracts.inner(method(type, "decision"), type.getField("cache"), null)
        val expected = IllegalStateException("host failed")
        try { callback.intercept(Call { throw expected }); fail() } catch (error: IllegalStateException) { assertSame(expected, error) }
        assertNull(TabletFixture.cache)
    }
    @Test fun finalCacheIsRejected() { val type = TabletFixture::class.java; rejects { LegacyWeChatInnerContracts.inner(method(type, "decision"), type.getField("finalCache"), null) } }
    @Test fun wrongCacheTypeIsRejected() { val type = TabletFixture::class.java; rejects { LegacyWeChatInnerContracts.inner(method(type, "decision"), type.getField("wrongCache"), null) } }
    @Test fun wrongMonitorTypeIsRejected() { val type = TabletFixture::class.java; rejects { LegacyWeChatInnerContracts.inner(method(type, "decision"), type.getField("cache"), type.getField("wrongCache")) } }
    @Test fun dingtalkCompletePlanBindsAndReturnsTrue() {
        val plan = PlanDescriptor(AppTarget.DINGTALK, mapOf("device" to descriptor(method(TestClassifier::class.java, "isMultiLoginFoldableDevice"))))
        val specs = PlanBinder.bind(plan, javaClass.classLoader!!); val call = Call()
        assertEquals(1, specs.size); assertEquals(true, specs.single().callback.intercept(call)); assertEquals(0, call.calls)
    }
    @Test fun dingtalkWrongStaticnessIsRejected() { rejects { PlanBinder.validateMethods(AppTarget.DINGTALK, mapOf("device" to method(TestClassifier::class.java, "wrongStaticness"))) } }
    @Test fun dingtalkWrongParameterIsRejected() { rejects { PlanBinder.validateMethods(AppTarget.DINGTALK, mapOf("device" to method(TestClassifier::class.java, "wrongArgument"))) } }
    @Test fun wecomCompletePlanBindsAllThreeLayers() {
        val plan = PlanDescriptor(AppTarget.WECOM, linkedMapOf(
            "device_type" to descriptor(method(WecomApplication::class.java, "getDeviceTypeFromLocal")),
            "app_pad" to descriptor(method(WecomApplication::class.java, "isAndroidPad")),
            "service_pad" to descriptor(method(WeworkServiceImpl::class.java, "isAndroidPad"))))
        val specs = PlanBinder.bind(plan, javaClass.classLoader!!)
        assertEquals(3, specs.size); assertEquals(listOf(0x20004, true, true), specs.map { it.callback.intercept(Call()) })
    }
    @Test fun wecomStaticDeviceGetterIsRejected() {
        rejects { PlanBinder.validateMethods(AppTarget.WECOM, mapOf(
            "device_type" to method(WecomApplication::class.java, "wrongDeviceType"),
            "app_pad" to method(WecomApplication::class.java, "isAndroidPad"),
            "service_pad" to method(WeworkServiceImpl::class.java, "isAndroidPad"))) }
    }
    @Test fun wecomPartialPlanIsRejected() { rejects { PlanBinder.validateMethods(AppTarget.WECOM, emptyMap()) } }
}
