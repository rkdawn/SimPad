package io.github.rkdawn.simpad

import android.app.Application
import android.content.Context
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier

/** Validated WeChat startup-before coordination; other app bootstrap is unchanged. */
internal object AppBootstrap {
    fun install(module: XposedModule, app: AppTarget, loader: ClassLoader,
                onReady: (Context, ClassLoader) -> Unit, onGeneration: () -> Unit,
                onError: (Throwable) -> Unit, diagnostic: (String) -> Unit = {},
                inputsAfterOriginal: (Context, ClassLoader) -> Boolean = { _, _ -> true }) {
        val tinker = try { loader.loadClass("com.tencent.tinker.loader.app.TinkerApplication") }
            catch (_: ClassNotFoundException) { null }
        if (tinker != null && app == AppTarget.WECHAT) {
            val entry = WeChatStartupSelector.select(tinker)
            require(entry.mode == WeChatStartMode.POST_TINKER_ATTACH) { "WeChat startup-before requires the standard base-context callback" }
            diagnostic("WECHAT_BOOTSTRAP mode=PRE_TINKER_BASE_CALLBACK owner=${entry.method.declaringClass.name} method=${entry.method.name}")
            val gate = WeChatBeforeStartup(capture = { call ->
                val base = requireNotNull(call.arguments.firstOrNull() as? Context) { "Missing base Context before original" }
                val application = requireNotNull(call.receiver as? Context) { "Missing Application receiver" }
                require(base.packageName == app.packageName && application.packageName == app.packageName) { "Wrong Before startup context" }
                val current = base.classLoader
                check(application.classLoader === current) { "Before Application and base Context loaders differ" }
                diagnostic("WECHAT_READY mode=PRE_TINKER_BASE_CALLBACK loader=${current.javaClass.name}")
                BeforeStartupObservation(base, current)
            }, install = { observed -> onReady(observed.context as Context, observed.loader) },
                inputsAfterOriginal = { call, observed ->
                    val application = call.receiver as Context
                    application.classLoader === observed.loader && inputsAfterOriginal(observed.context as Context, observed.loader)
                }, rollback = onGeneration, diagnostic = diagnostic, onError = onError)
            module.hook(entry.method).intercept { chain ->
                gate.intercept(object : HookCall {
                    override val receiver get() = chain.thisObject
                    override val arguments get() = chain.args
                    override fun proceed(): Any? = chain.proceed()
                })
            }
        } else if (tinker != null && app == AppTarget.WECOM) {
            val method = tinker.declaredMethods.singleOrNull {
                it.name == "onBaseContextAttached" && !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                    it.parameterTypes.map { p -> p.name } == listOf("android.content.Context", "long", "long")
            } ?: error("Unsupported WeCom Tinker bootstrap; fail closed")
            val lock = Any()
            var entered = false
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val first = synchronized(lock) { if (entered) false else { entered = true; true } }
                guarded(onError) {
                    if (!first) onGeneration() else {
                        val context = chain.thisObject as Context
                        onReady(context, context.classLoader)
                    }
                }
                result
            }
        } else {
            val method = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            val lock = Any()
            var entered = false
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val context = chain.thisObject as? Application
                if (context?.packageName == app.packageName) {
                    val first = synchronized(lock) { if (entered) false else { entered = true; true } }
                    guarded(onError) { if (first) onReady(context, context.classLoader) else onGeneration() }
                }
                result
            }
        }
    }

    private inline fun guarded(report: (Throwable) -> Unit, action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            report(error)
        }
    }
}
