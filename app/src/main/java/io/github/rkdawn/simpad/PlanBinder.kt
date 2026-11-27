package io.github.rkdawn.simpad

import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object PlanBinder {
    fun bind(plan: PlanDescriptor, loader: ClassLoader, version: WeChatHostVersion? = null,
             diagnostic: (String) -> Unit = {}): List<HookSpec> {
        plan.validate()
        val methods = plan.methods.mapValues { (_, descriptor) -> DexMethod(descriptor).getMethodInstance(loader) }
        validateMethods(plan.app, methods)
        return when (plan.app) {
            AppTarget.DINGTALK -> listOf(HookSpec("dingtalk_device", methods.getValue("device"), HookCallback { true }))
            AppTarget.WECOM -> listOf(
                HookSpec("wecom_device_type", methods.getValue("device_type"), HookCallback { 0x20004 }),
                HookSpec("wecom_app_pad", methods.getValue("app_pad"), HookCallback { true }),
                HookSpec("wecom_service_pad", methods.getValue("service_pad"), HookCallback { true }),
            )
            AppTarget.WECHAT -> {
                val callbacks = WeChatCallbacks(chatCall = WeChatChatDetector::isChat)
                val history = WeChatProfiles.forVersion(version)?.history
                buildList {
                    add(HookSpec("wechat_device", methods.getValue(plan.hookTarget ?: "inner"), HookCallback(callbacks::tablet)))
                    add(HookSpec("wechat_login", methods.getValue("login"), HookCallback { callbacks.login(it, history != null) }))
                    if (history != null) {
                        val init = DexMethod(history.initializer).getMethodInstance(loader)
                        require(!Modifier.isStatic(init.modifiers) && init.parameterCount == 0 && init.returnType == Void.TYPE &&
                            android.app.Activity::class.java.isAssignableFrom(init.declaringClass)) { "Unexpected history login initializer" }
                        add(HookSpec("wechat_history_login", init, HookCallback { call ->
                            callbacks.history(call, { receiver ->
                                (receiver as? android.app.Activity)?.findViewById<android.view.View>(history.buttonId) as? android.widget.Button
                            }, { (it as android.widget.Button).hasOnClickListeners() }, diagnostic)
                        }))
                    }
                }
            }
        }
    }

    fun validateMethods(app: AppTarget, methods: Map<String, Method>) {
        fun expect(role: String, type: Class<*>, static: Boolean, parameters: (List<String>) -> Boolean): Method {
            val method = methods.getValue(role)
            require(method.returnType == type && Modifier.isStatic(method.modifiers) == static &&
                parameters(method.parameterTypes.map { it.name })) { "Unexpected $app $role method shape" }
            return method
        }
        when (app) {
            AppTarget.DINGTALK -> {
                require(methods.keys == setOf("device"))
                val device = expect("device", Boolean::class.javaPrimitiveType!!, true) { it == listOf("android.app.Activity") }
                require(device.declaringClass.name.startsWith("com.alibaba.android.dingtalkbase.foldable."))
            }
            AppTarget.WECOM -> {
                require(methods.keys == setOf("device_type", "app_pad", "service_pad"))
                val device = expect("device_type", Int::class.javaPrimitiveType!!, false) { it.isEmpty() }
                val pad = expect("app_pad", Boolean::class.javaPrimitiveType!!, true) { it.isEmpty() }
                val service = expect("service_pad", Boolean::class.javaPrimitiveType!!, false) { it.isEmpty() }
                require(device.declaringClass.name == "com.tencent.wework.foundation.logic.Application" && device.name == "getDeviceTypeFromLocal")
                require(pad.declaringClass == device.declaringClass && pad.name == "isAndroidPad")
                require(service.declaringClass.name == "com.tencent.wework.foundation.impl.WeworkServiceImpl" && service.name == "isAndroidPad")
            }
            AppTarget.WECHAT -> {
                require(methods.keys == setOf("inner", "tablet", "login"))
                val inner = expect("inner", Boolean::class.javaPrimitiveType!!, true) { it.size == 1 && it.single() !in PRIMITIVES }
                val tablet = expect("tablet", Boolean::class.javaPrimitiveType!!, true) { it.isEmpty() }
                expect("login", Void.TYPE, true) {
                    it.size == 2 && it.first() in setOf("android.view.View", "android.widget.Button") && it[1] !in PRIMITIVES
                }
                require(inner.declaringClass == tablet.declaringClass) { "WeChat wrapper and decision are unrelated" }
            }
        }
    }
}
