package com.houvven.impad

import java.lang.reflect.Modifier

internal object LegacyWeChatSelector {
    private val markers = setOf("X2", "A2")
    private const val visibility = "Landroid/view/View;->setVisibility(I)V"

    fun select(decisions: List<MethodInfo>, wrappers: List<MethodInfo>, logins: List<MethodInfo>,
               modelGates: List<MethodInfo> = emptyList()): PlanDescriptor {
        fun consistent(methods: List<MethodInfo>): List<MethodInfo> = methods.distinct().also { unique ->
            require(unique.groupBy { it.descriptor }.values.none { it.size > 1 }) { "Conflicting DEX method definitions" }
        }
        val checkedWrappers = consistent(wrappers)
        val checkedLogins = consistent(logins)
        val checkedModelGates = consistent(modelGates)
        val plans = mutableListOf<PlanDescriptor>()
        consistent(decisions).filter { isDecision(it, checkedModelGates) }.forEach { inner ->
            val cache = inner.fields.map { it.field }.distinctBy { it.descriptor }.filter { field ->
                field.owner == inner.owner && field.type == "java.lang.Boolean" &&
                    Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers) &&
                    inner.fields.any { it.field.descriptor == field.descriptor && it.read } &&
                    inner.fields.any { it.field.descriptor == field.descriptor && it.write }
            }.singleOrNull() ?: return@forEach
            checkedWrappers.filter {
                it.owner == inner.owner && it.isStatic && it.parameters.isEmpty() && it.returnType == "boolean" &&
                    it.invokes.count { called -> called == inner.descriptor } == 1
            }.forEach wrapperLoop@ { outer ->
                val synchronized = outer.opNames.any { it == "MONITOR_ENTER" || it == "MONITOR_EXIT" }
                val locks = outer.fields.filter { it.read }.map { it.field }.distinctBy { it.descriptor }.filter {
                    it.owner == inner.owner && it.type == "java.lang.Object" && Modifier.isStatic(it.modifiers)
                }
                val monitor = if (synchronized) locks.singleOrNull()?.descriptor ?: return@wrapperLoop else null
                if (synchronized && !("MONITOR_ENTER" in outer.opNames && "MONITOR_EXIT" in outer.opNames)) return@wrapperLoop
                checkedLogins.filter {
                    it.isStatic && it.returnType == "void" && it.parameters.size == 2 &&
                        it.parameters.first() in setOf("android.view.View", "android.widget.Button") &&
                        it.parameters[1] !in PRIMITIVES &&
                        it.strings.any { s -> s == "updateLoginAsExdevice" || s == "loginAsOtherDeviceBtn" } &&
                        visibility in it.invokes && it.invokes.count { called -> called == outer.descriptor } == 1
                }.forEach { login ->
                    plans += PlanDescriptor(AppTarget.WECHAT, linkedMapOf("inner" to inner.descriptor,
                        "tablet" to outer.descriptor, "login" to login.descriptor), cache.descriptor, monitor)
                }
            }
        }
        return uniquePlan(plans, "WeChat decision-wrapper-login")
    }

    private fun isDecision(method: MethodInfo, modelGates: List<MethodInfo>): Boolean = method.owner.startsWith("com.tencent.mm.") &&
        method.isStatic && method.returnType == "boolean" && method.parameters.size == 1 &&
        method.parameters.single() !in PRIMITIVES && method.strings.containsAll(markers) &&
        ("Lenovo TB-9707F" in method.strings || modelGates.filter { isModelGate(it) && it.descriptor in method.invokes }.size == 1) &&
        readsModel(method) && "Ljava/lang/Integer;->intValue()I" in method.invokes

    private fun readsModel(method: MethodInfo): Boolean = method.fields.any {
        it.read && it.field.owner == "android.os.Build" && it.field.descriptor.endsWith("->MODEL:Ljava/lang/String;")
    }

    private fun isModelGate(method: MethodInfo): Boolean = method.owner.startsWith("com.tencent.mm.") &&
        method.isStatic && method.returnType == "boolean" && method.parameters.isEmpty() &&
        "Lenovo TB-9707F" in method.strings && readsModel(method) &&
        "Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z" in method.invokes &&
        method.fields.map { it.field }.distinctBy { it.descriptor }.any { field ->
            field.owner == method.owner && field.type == "java.lang.Boolean" &&
                Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers) &&
                method.fields.any { it.field.descriptor == field.descriptor && it.read } &&
                method.fields.any { it.field.descriptor == field.descriptor && it.write }
        }
}

