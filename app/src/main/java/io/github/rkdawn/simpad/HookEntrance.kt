package io.github.rkdawn.simpad

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.ConcurrentHashMap

class HookEntrance : XposedModule() {
    private var processName: String? = null
    private val handled = ConcurrentHashMap.newKeySet<String>()
    private var batch: AtomicHookBatch? = null
    private var attempted = false
    private var beforeIdentity: CodeIdentity? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        processName = param.processName
    }
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val app = AppTarget.forProcess(param.packageName, processName) ?: return
        if (!handled.add(param.packageName)) return
        if (app == AppTarget.WECHAT) report(Log.INFO, "WECHAT_MODULE_READY version=${BuildConfig.VERSION_NAME} origin=simpad process=$processName")
        try {
            AppBootstrap.install(this, app, param.classLoader,
                onReady = { context, loader -> install(app, context, loader) },
                onGeneration = ::retire,
                onError = { error -> report(Log.ERROR, "BOOTSTRAP_FAILED ${app.name}: ${error.javaClass.simpleName}: ${error.message}") },
                diagnostic = { message -> report(Log.INFO, message) },
                inputsAfterOriginal = { context, loader -> beforeIdentity?.stillCurrent(context, loader) == true })
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            report(Log.ERROR, "BOOTSTRAP_UNSUPPORTED ${app.name}: ${error.message}")
        }
    }

    @Synchronized private fun install(app: AppTarget, context: Context, loader: ClassLoader) {
        if (attempted) { retire(); return }
        attempted = true
        require(context.packageName == app.packageName) { "Wrong application context" }
        val start = System.nanoTime()
        val processKey = cacheFingerprint(listOf(checkNotNull(processName))).take(16)
        val apkDigests = if (app == AppTarget.WECHAT) try {
            InstalledApkDigestCache(context.getSharedPreferences("SimPad_apk_digests_v1_$processKey", Context.MODE_PRIVATE))
        } catch (error: Exception) {
            report(Log.WARN, "APK_DIGEST_CACHE_UNAVAILABLE ${error.javaClass.simpleName}")
            null
        } else null
        var digestMetrics = CodeDigestMetrics()
        val identity = CodeIdentity.capture(context, loader, apkDigests) { digestMetrics = it }
        val identityMs = (System.nanoTime() - start) / 1_000_000
        if (app == AppTarget.WECHAT) beforeIdentity = identity
        if (app == AppTarget.WECHAT) report(Log.INFO, "WECHAT_INPUT appBuild=${identity.version} codeFiles=${identity.files.size}")
        val cachePrefix = if (app == AppTarget.WECHAT) "SimPad_startup_before_v1" else "SimPad_three_apps_v1"
        val cacheName = "${cachePrefix}_$processKey"
        val cache = PlanCache(context.getSharedPreferences(cacheName, Context.MODE_PRIVATE))
        var hit = false
        var bindNs = 0L
        var resolveNs = 0L
        val cached = try { cache.read(app, identity.fingerprint) } catch (error: Exception) {
            try { cache.invalidate() } catch (_: Exception) { }
            report(Log.WARN, "CACHE_INVALID ${app.name}: ${error.javaClass.simpleName}")
            null
        }
        var plan = cached
        var specs = cached?.let {
            val bindStart = System.nanoTime()
            try { PlanBinder.bind(it, loader).also { hit = true } } catch (error: Exception) {
                try { cache.invalidate() } catch (_: Exception) { }
                report(Log.WARN, "CACHE_BIND_INVALID ${app.name}: ${error.javaClass.simpleName}")
                null
            } finally { bindNs += System.nanoTime() - bindStart }
        }
        if (specs == null) {
            val resolveStart = System.nanoTime()
            try {
                System.loadLibrary("dexkit")
                plan = DexKitBridge.create(loader, true).use {
                    AppRuleResolver(it, if (app == AppTarget.WECHAT) WeChatHostVersion.fromInstalledIdentity(identity.version) else null,
                        { message -> report(Log.INFO, message) }).resolve(app)
                }
            } finally { resolveNs += System.nanoTime() - resolveStart }
            val bindStart = System.nanoTime()
            try { specs = PlanBinder.bind(checkNotNull(plan), loader) }
            finally { bindNs += System.nanoTime() - bindStart }
        }
        val selected = checkNotNull(plan)
        val boundSpecs = checkNotNull(specs)
        if (app == AppTarget.WECHAT) {
            val hookRole = selected.hookTarget ?: "inner"
            report(Log.INFO, "WECHAT_PLAN origin=simpad deviceRole=$hookRole device=${selected.methods.getValue(hookRole)} referenceWrapper=${selected.methods.getValue("tablet")} login=${selected.methods.getValue("login")} hookIds=${boundSpecs.joinToString(",") { it.id }} cacheWrite=false")
        }
        val registrar = HookRegistrar { method, callback ->
            val handle = hook(method).intercept { chain ->
                callback.intercept(object : HookCall {
                    override val receiver get() = chain.thisObject
                    override val arguments get() = chain.args
                    override fun proceed(): Any? = chain.proceed()
                })
            }
            Registration { handle.unhook() }
        }
        val hookStart = System.nanoTime()
        batch = AtomicHookBatch.install(boundSpecs, registrar, { identity.stillCurrent(context, loader) }) { error ->
            report(Log.ERROR, "HOOK_DISABLED ${app.name}: ${error.javaClass.simpleName}; RESTART_REQUIRED")
        }
        val hookMs = (System.nanoTime() - hookStart) / 1_000_000
        if (!hit) try { cache.write(identity.fingerprint, selected) } catch (error: Exception) {
            report(Log.WARN, "CACHE_WRITE_FAILED ${app.name}: ${error.javaClass.simpleName}")
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val performance = if (app == AppTarget.WECHAT) " identityMs=$identityMs apkDigestHits=${digestMetrics.reusedFiles} hashedFiles=${digestMetrics.hashedFiles} hashedBytes=${digestMetrics.hashedBytes} reusedBytes=${digestMetrics.reusedBytes} resolveMs=${resolveNs / 1_000_000} bindMs=${bindNs / 1_000_000} hookMs=$hookMs" else ""
        report(Log.INFO, "INSTALLED ${app.name} process=$processName hooks=${boundSpecs.size} cacheHit=$hit codeFiles=${identity.files.size} startupMs=$elapsedMs$performance")
    }

    @Synchronized private fun retire() {
        val failures = batch?.disableAndUnhook().orEmpty()
        batch = null
        report(Log.ERROR, "RESTART_REQUIRED startup generation changed; rollbackFailures=${failures.size}")
    }
    private fun report(level: Int, message: String) {
        bestEffortDiagnostic { log(level, BuildConfig.APPLICATION_ID, message) }
    }
}
