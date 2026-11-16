package com.houvven.impad

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

class AtomicHookBatchTest {
    private val first = Fixture::class.java.getDeclaredMethod("first")
    private val second = Fixture::class.java.getDeclaredMethod("second")
    private fun spec(id: String = "first", method: Method = first, result: Any? = true) = HookSpec(id, method, HookCallback { result })
    private class Call(private val original: () -> Any? = { false }) : HookCall {
        var calls = 0
        override val receiver: Any? = null
        override val arguments = emptyList<Any?>()
        override fun proceed(): Any? { calls++; return original() }
    }
    private class Registrar : HookRegistrar {
        val callbacks = linkedMapOf<Method, HookCallback>()
        val removed = mutableListOf<Method>()
        var failAt: Method? = null
        var removalFailure = false
        var duringRegistration: ((HookCallback) -> Unit)? = null
        override fun register(method: Method, callback: HookCallback): Registration {
            if (method == failAt) error("registration failed")
            callbacks[method] = callback
            duringRegistration?.invoke(callback)
            return Registration { removed += method; if (removalFailure) error("unhook failed") }
        }
    }
    private class Fixture { fun first() = false; fun second() = false }

    @Test fun completeBatchEnablesCallbacksTogether() {
        val registrar = Registrar()
        AtomicHookBatch.install(listOf(spec(), spec("second", second)), registrar, { true }) { fail("Unexpected callback error") }
        val call = Call()
        assertEquals(true, registrar.callbacks.getValue(first).intercept(call))
        assertEquals(0, call.calls)
    }
    @Test fun registeredHooksStayTransparentBeforeCommit() {
        val registrar = Registrar()
        registrar.duringRegistration = { callback -> val call = Call(); assertEquals(false, callback.intercept(call)); assertEquals(1, call.calls) }
        AtomicHookBatch.install(listOf(spec(), spec("second", second)), registrar, { true }) { fail() }
    }
    @Test fun partialRegistrationFailureRollsBack() {
        val registrar = Registrar().apply { failAt = second }
        try { AtomicHookBatch.install(listOf(spec(), spec("second", second)), registrar, { true }) {}; fail() } catch (_: IllegalStateException) {}
        assertEquals(listOf(first), registrar.removed)
        assertEquals(false, registrar.callbacks.getValue(first).intercept(Call()))
    }
    @Test fun staleInputsRollbackAllHooksInReverseOrder() {
        val registrar = Registrar()
        try { AtomicHookBatch.install(listOf(spec(), spec("second", second)), registrar, { false }) {}; fail() } catch (_: IllegalStateException) {}
        assertEquals(listOf(second, first), registrar.removed)
    }
    @Test fun rollbackFailureIsPreserved() {
        val registrar = Registrar().apply { failAt = second; removalFailure = true }
        try { AtomicHookBatch.install(listOf(spec(), spec("second", second)), registrar, { true }) {}; fail() }
        catch (error: IllegalStateException) { assertEquals(1, error.suppressed.size) }
    }
    @Test fun rollbackIsIdempotentAndDisablesCallbacks() {
        val registrar = Registrar()
        val batch = AtomicHookBatch.install(listOf(spec()), registrar, { true }) {}
        assertTrue(batch.disableAndUnhook().isEmpty())
        assertTrue(batch.disableAndUnhook().isEmpty())
        assertEquals(listOf(first), registrar.removed)
        assertEquals(false, registrar.callbacks.getValue(first).intercept(Call()))
    }
    @Test fun duplicateIdsRejectedBeforeRegistration() {
        val registrar = Registrar()
        try { AtomicHookBatch.install(listOf(spec(), spec("first", second)), registrar, { true }) {}; fail() } catch (_: IllegalArgumentException) {}
        assertTrue(registrar.callbacks.isEmpty())
    }
    @Test fun overlappingTargetsRejectedBeforeRegistration() {
        val registrar = Registrar()
        try { AtomicHookBatch.install(listOf(spec(), spec("second", first)), registrar, { true }) {}; fail() } catch (_: IllegalArgumentException) {}
        assertTrue(registrar.callbacks.isEmpty())
    }
    @Test fun emptyBatchRejected() { try { AtomicHookBatch.install(emptyList(), Registrar(), { true }) {}; fail() } catch (_: IllegalArgumentException) {} }
    @Test fun failedAfterCallbackReturnsOriginalWithoutDoubleInvocation() {
        val registrar = Registrar()
        var errors = 0
        val callback = HookCallback { it.proceed(); error("personal callback failed") }
        AtomicHookBatch.install(listOf(HookSpec("first", first, callback), spec("second", second)), registrar, { true }) { errors++ }
        val call = Call { "original" }
        assertEquals("original", registrar.callbacks.getValue(first).intercept(call))
        assertEquals(1, call.calls)
        assertEquals(1, errors)
        assertEquals(false, registrar.callbacks.getValue(second).intercept(Call()))
    }
    @Test fun originalExceptionIsNotSwallowedOrRetried() {
        val registrar = Registrar()
        val original = IllegalStateException("host failure")
        AtomicHookBatch.install(listOf(HookSpec("first", first, HookCallback { it.proceed() })), registrar, { true }) { fail("Original error is not a callback error") }
        val call = Call { throw original }
        try { registrar.callbacks.getValue(first).intercept(call); fail() } catch (error: IllegalStateException) { assertSame(original, error) }
        assertEquals(1, call.calls)
    }
    @Test fun onceCallReusesNullOutcome() { val call = Call { null }; val once = OnceCall(call); assertNull(once.proceed()); assertNull(once.proceed()); assertEquals(1, call.calls) }
    @Test fun onceCallPreservesOriginalExceptionIdentity() {
        val expected = UnsupportedOperationException()
        val call = Call { throw expected }; val once = OnceCall(call)
        repeat(2) { try { once.proceed(); fail() } catch (error: UnsupportedOperationException) { assertSame(expected, error) } }
        assertEquals(1, call.calls)
    }
}
