package io.github.rkdawn.simpad

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal enum class WeChatStartMode { POST_TINKER_ATTACH, EARLY_INLINE_FENCE }
internal data class WeChatStartEntry(val method: Method, val mode: WeChatStartMode,
                                    val loaderField: Field? = null, val fenceField: Field? = null)

/** Small startup compatibility added to the original baseline; no code-snapshot framework. */
internal object WeChatStartupSelector {
    private fun find(type: Class<*>, name: String, result: String, params: List<String>, allowPrivate: Boolean = false): Method? {
        var owner: Class<*>? = type
        while (owner != null) {
            val declarations = owner.declaredMethods.filter { it.name == name }
            val candidates = declarations.filter {
                !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                    (allowPrivate || !Modifier.isPrivate(it.modifiers)) &&
                    it.returnType.name == result && it.parameterTypes.map { p -> p.name } == params
            }
            if (candidates.size > 1) error("Ambiguous WeChat startup signature")
            if (candidates.isNotEmpty()) return candidates.single().apply { isAccessible = true }
            // Do not bypass a same-signature private/static/abstract shadow to hook a different declaration.
            if (declarations.any { it.parameterTypes.map { p -> p.name } == params }) return null
            owner = owner.superclass
        }
        return null
    }
    fun select(type: Class<*>): WeChatStartEntry {
        val post = find(type, "onBaseContextAttached", "void", listOf("android.content.Context", "long", "long"))
        if (post != null) return WeChatStartEntry(post, WeChatStartMode.POST_TINKER_ATTACH)
        val early = find(type, "createInlineFence", "android.os.Handler", listOf("android.app.Application", "int",
            "java.lang.String", "boolean", "long", "long", "android.content.Intent"), allowPrivate = true)
            ?: error("Unsupported WeChat Tinker bootstrap")
        val owner = early.declaringClass
        val loader = owner.getDeclaredField("mCurrentClassLoader").apply { isAccessible = true }
        val fence = owner.getDeclaredField("mInlineFence").apply { isAccessible = true }
        require(loader.type == ClassLoader::class.java && !Modifier.isStatic(loader.modifiers) &&
            fence.type.name == "android.os.Handler" && !Modifier.isStatic(fence.modifiers)) { "Invalid early startup fields" }
        return WeChatStartEntry(early, WeChatStartMode.EARLY_INLINE_FENCE, loader, fence)
    }
}

/** Nested calls remain transparent; only the outer first call installs the baseline's hooks. */
internal class WeChatStartupOnce(private val beforeOriginal: Boolean,
                                 private val ready: (Any?) -> Unit, private val retire: () -> Unit,
                                 private val onError: (Throwable) -> Unit) : HookCallback {
    private enum class Action { FIRST, PASS, RETIRE }
    private val lock = Any()
    private var entered = false
    private var completed = false
    private var retired = false
    override fun intercept(call: HookCall): Any? {
        val action = synchronized(lock) {
            when {
                !entered -> { entered = true; Action.FIRST }
                !completed || retired -> Action.PASS
                else -> { retired = true; Action.RETIRE }
            }
        }
        try {
            if (action == Action.RETIRE) guarded(retire)
            if (action == Action.FIRST && beforeOriginal) guarded { ready(call.receiver) }
            val result = call.proceed()
            if (action == Action.FIRST && !beforeOriginal) guarded { ready(call.receiver) }
            return result
        } finally {
            if (action == Action.FIRST) synchronized(lock) { completed = true }
        }
    }
    private fun guarded(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            try { onError(error) } catch (report: Throwable) {
                if (report is VirtualMachineError || report is ThreadDeath) throw report
            }
        }
    }
}
