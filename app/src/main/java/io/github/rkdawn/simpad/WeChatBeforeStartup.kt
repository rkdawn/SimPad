package io.github.rkdawn.simpad

import java.util.concurrent.atomic.AtomicBoolean

internal data class BeforeStartupObservation(val context: Any, val loader: ClassLoader)

/** Houvven-style before-base-callback coordination, not a proof of pre-delegate construction. */
internal class WeChatBeforeStartup(
    private val capture: (HookCall) -> BeforeStartupObservation,
    private val install: (BeforeStartupObservation) -> Unit,
    private val inputsAfterOriginal: (HookCall, BeforeStartupObservation) -> Boolean,
    private val rollback: () -> Unit,
    private val diagnostic: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) : HookCallback {
    private class Session(val original: HookCall) {
        var observation: BeforeStartupObservation? = null
        var installationAttempted = false
        var installationCompleted = false
    }
    private fun emit(message: String) {
        try { diagnostic(message) } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
        }
    }
    private val rolledBack = AtomicBoolean(false)
    private fun undo(reason: String) {
        if (rolledBack.compareAndSet(false, true)) try { rollback() } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            emit("WECHAT_STARTUP_BEFORE rollback_failed type=${error.javaClass.simpleName}")
        }
        emit("WECHAT_STARTUP_BEFORE rolled_back reason=$reason; RESTART_REQUIRED")
    }
    private val gate = WeChatStartupOnce(true, ready = { value ->
        val session = value as Session
        emit("WECHAT_STARTUP_BEFORE phase=BEFORE_ORIGINAL")
        val observed = capture(session.original)
        session.observation = observed
        session.installationAttempted = true
        install(observed)
        session.installationCompleted = true
        emit("WECHAT_STARTUP_BEFORE before_install_completed")
    }, retire = { undo("later_generation") }, onError = { error ->
        undo("before_install_failed")
        onError(error)
    })

    override fun intercept(call: HookCall): Any? {
        val session = Session(call)
        val wrapped = object : HookCall {
            override val receiver: Any = session
            override val arguments get() = call.arguments
            override fun proceed(): Any? = call.proceed()
        }
        val result = try { gate.intercept(wrapped) } catch (error: Throwable) {
            if (session.installationAttempted) {
                try { undo("original_failed") } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            }
            throw error
        }
        if (session.installationCompleted) {
            emit("WECHAT_STARTUP_BEFORE phase=AFTER_ORIGINAL")
            try {
                val observed = checkNotNull(session.observation)
                check(inputsAfterOriginal(call, observed)) { "Before/after startup inputs or application loader changed" }
                emit("WECHAT_STARTUP_BEFORE after_inputs_confirmed")
            } catch (error: Throwable) {
                undo("after_inputs_changed")
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                try { onError(error) } catch (logging: Throwable) {
                    if (logging is VirtualMachineError || logging is ThreadDeath) throw logging
                }
            }
        }
        return result
    }
}
