package io.github.rkdawn.simpad

import com.tencent.mm.pluginsdk.ui.chat.PerformanceChatFixture
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.Callable

class ChatStackClassifierTest {
    @Test fun unsupportedApiNeverTouchesModernTypes() {
        var legacy = 0
        val classifier = ChatStackClassifier(false, { fail("Must not link API34 types"); false }, { legacy++; true })
        assertTrue(classifier.isChat()); assertTrue(classifier.isChat()); assertEquals(2, legacy)
    }
    @Test fun supportedApiDoesNotBuildLegacyTrace() {
        val classifier = ChatStackClassifier(true, { true }, { fail("No eager trace on success"); false })
        assertTrue(classifier.isChat())
    }
    @Test fun successfulFalseDoesNotTriggerFallback() {
        val classifier = ChatStackClassifier(true, { false }, { fail("False is a valid non-chat result"); true })
        assertFalse(classifier.isChat())
    }
    @Test fun resultIsNotCachedAcrossCalls() {
        var chat = false
        val classifier = ChatStackClassifier(true, { chat }, { false })
        assertFalse(classifier.isChat()); chat = true; assertTrue(classifier.isChat()); chat = false; assertFalse(classifier.isChat())
    }
    @Test fun ordinaryFailureFallsBackAndDoesNotRetryBrokenApi() {
        var modern = 0; var legacy = 0
        val classifier = ChatStackClassifier(true, { modern++; throw UnsupportedOperationException() }, { legacy++; true })
        assertTrue(classifier.isChat()); assertTrue(classifier.isChat())
        assertEquals(1, modern); assertEquals(2, legacy)
    }
    @Test fun linkageFailureFallsBack() {
        val classifier = ChatStackClassifier(true, { throw NoClassDefFoundError("api34") }, { true })
        assertTrue(classifier.isChat()); assertTrue(classifier.isChat())
    }
    @Test fun initializationFailureFallsBack() {
        val classifier = ChatStackClassifier(true, { throw ExceptionInInitializerError(IllegalStateException()) }, { true })
        assertTrue(classifier.isChat())
    }
    @Test fun modernVmFailureIsNotSwallowed() {
        val expected = OutOfMemoryError("test")
        val classifier = ChatStackClassifier(true, { throw expected }, { fail(); false })
        try { classifier.isChat(); fail() } catch (caught: OutOfMemoryError) { assertSame(expected, caught) }
    }
    @Test fun modernThreadDeathIsNotSwallowed() {
        val expected = ThreadDeath()
        val classifier = ChatStackClassifier(true, { throw expected }, { fail(); false })
        try { classifier.isChat(); fail() } catch (caught: ThreadDeath) { assertSame(expected, caught) }
    }
    @Test fun legacyFailureIsNotForgedAsNonChat() {
        val expected = IllegalStateException("legacy")
        val classifier = ChatStackClassifier(false, { false }, { throw expected })
        try { classifier.isChat(); fail() } catch (caught: IllegalStateException) { assertSame(expected, caught) }
    }
    @Test fun legacyVmFailureIsNotSwallowed() {
        val expected = OutOfMemoryError("legacy")
        val classifier = ChatStackClassifier(false, { false }, { throw expected })
        try { classifier.isChat(); fail() } catch (caught: OutOfMemoryError) { assertSame(expected, caught) }
    }
    private fun compare(): Pair<Boolean, Boolean> =
        ModernWeChatChatStack.isChat() to isWeChatChatCall(Thread.currentThread().stackTrace)
    @Test fun realModernAndLegacyAgreeOutsideChatPackage() { assertEquals(false to false, compare()) }
    @Test fun realModernAndLegacyAgreeInsideChatPackage() {
        assertEquals(true to true, PerformanceChatFixture.invoke { compare() })
    }
    @Test fun chatCallerBeyondFirstBatchIsNotMissed() {
        assertEquals(true to true, PerformanceChatFixture.invoke { nonChatNested(70) })
    }
    private fun nonChatNested(depth: Int): Pair<Boolean, Boolean> = if (depth == 0) compare() else nonChatNested(depth - 1)
    @Test fun deepNonChatStackIsNotTruncatedOrMisclassified() { assertEquals(false to false, nonChatNested(100)) }
    @Test fun reflectionPreservesChatClassification() {
        val method = PerformanceChatFixture::class.java.getDeclaredMethod("invoke", kotlin.jvm.functions.Function0::class.java)
        assertEquals(true to true, method.invoke(null, { compare() }))
    }
    @Test fun oneWalkerIsSafeAcrossThreadsAndContexts() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val work = (0 until 32).map { index -> Callable {
                if (index % 2 == 0) PerformanceChatFixture.invoke { compare() } else compare()
            } }
            pool.invokeAll(work).forEachIndexed { index, result -> assertEquals((index % 2 == 0) to (index % 2 == 0), result.get()) }
        } finally { pool.shutdownNow() }
    }
    private class Call(private val original: () -> Any?) : HookCall {
        var calls = 0
        override val receiver: Any? = null
        override val arguments: List<Any?> = emptyList()
        override fun proceed(): Any? { calls++; return original() }
    }
    @Test fun optimizedCallbackCallsOriginalExactlyOnceBeforeDetection() {
        var completed = false
        val callbacks = WeChatCallbacks(chatCall = { assertTrue(completed); true })
        val call = Call { completed = true; true }
        assertEquals(false, callbacks.tablet(call)); assertEquals(1, call.calls)
    }
    @Test fun originalFailurePreventsModernDetection() {
        val expected = IllegalStateException("original")
        val callbacks = WeChatCallbacks(chatCall = { fail("After original failure"); true })
        val call = Call { throw expected }
        try { callbacks.tablet(call); fail() } catch (caught: IllegalStateException) { assertSame(expected, caught) }
        assertEquals(1, call.calls)
    }
    @Test fun optimizedCallbackUsesLegacyResultIfModernBreaks() {
        val classifier = ChatStackClassifier(true, { throw LinkageError() }, { true })
        val call = Call { false }
        assertEquals(false, WeChatCallbacks(chatCall = classifier::isChat).tablet(call)); assertEquals(1, call.calls)
    }
    @Test fun sharedClassPredicateDoesNotBroadenProtectedPackage() {
        listOf("com.tencent.mm.pluginsdk.ui.chatting.ChatUI", "com.tencent.mm.ui.chatting.ChatUI", "com.tencent.mm.pluginsdk.ui.chatty.X").forEach {
            assertFalse(isWeChatChatClassName(it))
        }
        assertTrue(isWeChatChatClassName("com.tencent.mm.pluginsdk.ui.chat"))
        assertTrue(isWeChatChatClassName("com.tencent.mm.pluginsdk.ui.chat.ChatFooter\$Listener"))
    }
}
