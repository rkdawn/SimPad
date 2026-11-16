package com.houvven.impad

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

internal interface HookCall {
    val receiver: Any?
    val arguments: List<Any?>
    fun proceed(): Any?
}
internal fun interface HookCallback { fun intercept(call: HookCall): Any? }
internal fun interface Registration { fun unhook() }
internal fun interface HookRegistrar { fun register(method: Method, callback: HookCallback): Registration }
internal data class HookSpec(val id: String, val method: Method, val callback: HookCallback)

/** Diagnostics must not disable otherwise valid installation or callbacks. */
internal inline fun bestEffortDiagnostic(action: () -> Unit) {
    try { action() } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
    }
}

/** Records the original outcome, so a failed after-hook never invokes the host twice. */
internal class OnceCall(private val call: HookCall) : HookCall {
    override val receiver get() = call.receiver
    override val arguments get() = call.arguments
    private var called = false
    private var result: Any? = null
    var originalFailure: Throwable? = null
        private set
    override fun proceed(): Any? {
        if (!called) {
            called = true
            try { result = call.proceed() } catch (error: Throwable) { originalFailure = error }
        }
        originalFailure?.let { throw it }
        return result
    }
}

internal class AtomicHookBatch private constructor(
    private val enabled: AtomicBoolean,
    private val registrations: List<Registration>,
) {
    private var rollbackResult: List<Throwable>? = null
    @Synchronized fun disableAndUnhook(): List<Throwable> {
        enabled.set(false)
        rollbackResult?.let { return it }
        val errors = mutableListOf<Throwable>()
        registrations.asReversed().forEach {
            try { it.unhook() } catch (error: Throwable) { errors += error }
        }
        rollbackResult = errors.toList()
        errors.firstOrNull { it is VirtualMachineError || it is ThreadDeath }?.let { throw it }
        return rollbackResult!!
    }

    companion object {
        fun install(specs: List<HookSpec>, registrar: HookRegistrar, stillCurrent: () -> Boolean,
                    onCallbackError: (Throwable) -> Unit): AtomicHookBatch {
            require(specs.isNotEmpty() && specs.map { it.id }.distinct().size == specs.size && specs.none { it.id.isBlank() }) { "Invalid hook batch IDs" }
            require(specs.map { it.method }.distinct().size == specs.size) { "Hook targets overlap" }
            val enabled = AtomicBoolean(false)
            val registrations = mutableListOf<Registration>()
            try {
                specs.forEach { spec ->
                    registrations += registrar.register(spec.method, HookCallback { raw ->
                        if (!enabled.get()) raw.proceed() else {
                            val call = OnceCall(raw)
                            try { spec.callback.intercept(call) } catch (error: Throwable) {
                                if (call.originalFailure === error) throw error
                                enabled.set(false)
                                if (error is VirtualMachineError || error is ThreadDeath) throw error
                                try { onCallbackError(error) } catch (reportError: Throwable) {
                                    if (reportError is VirtualMachineError || reportError is ThreadDeath) throw reportError
                                }
                                call.proceed()
                            }
                        }
                    })
                }
                check(stillCurrent()) { "Startup inputs changed; restart required" }
                return AtomicHookBatch(enabled, registrations.toList()).also { enabled.set(true) }
            } catch (error: Throwable) {
                AtomicHookBatch(enabled, registrations.toList()).disableAndUnhook().forEach { error.addSuppressed(it) }
                throw error
            }
        }
    }
}
