package io.github.rkdawn.simpad

import java.lang.reflect.Modifier

internal data class FieldInfo(val descriptor: String, val owner: String, val type: String, val modifiers: Int)
internal data class FieldUse(val field: FieldInfo, val read: Boolean, val write: Boolean)
internal data class MethodInfo(
    val descriptor: String, val owner: String, val name: String, val parameters: List<String>,
    val returnType: String, val modifiers: Int, val strings: Set<String> = emptySet(),
    val invokes: List<String> = emptyList(), val fields: List<FieldUse> = emptyList(),
    val opNames: List<String> = emptyList(),
) {
    val isStatic get() = Modifier.isStatic(modifiers)
}

/** A bounded relationship selector, not a "first matching string wins" selector. */
internal object WeChatSelector {
    private val markers = setOf("X2", "A2")
    private const val visibility = "Landroid/view/View;->setVisibility(I)V"

    fun select(decisions: List<MethodInfo>, wrappers: List<MethodInfo>, logins: List<MethodInfo>,
               modelGates: List<MethodInfo> = emptyList(), version: WeChatHostVersion? = null): PlanDescriptor {
        fun consistent(methods: List<MethodInfo>): List<MethodInfo> = methods.distinct().also { unique ->
            require(unique.groupBy { it.descriptor }.values.none { it.size > 1 }) { "Conflicting DEX method definitions" }
        }
        val checkedWrappers = consistent(wrappers)
        val checkedLogins = consistent(logins)
        val checkedModelGates = consistent(modelGates)
        val profile = WeChatProfiles.forVersion(version)
        val plans = mutableListOf<PlanDescriptor>()
        consistent(decisions).filter { isDecision(it, checkedModelGates) && (profile == null || it.descriptor == profile.inner) }.forEach { inner ->
            if (profile != null) {
                val gate = checkedModelGates.singleOrNull { it.descriptor == profile.modelGate && isModelGate(it) }
                    ?: return@forEach
                if (inner.invokes.count { it == gate.descriptor } != 1 ||
                    gate.fields.none { it.field.descriptor == profile.modelCache && it.read &&
                        it.field.type == "java.lang.Boolean" && Modifier.isStatic(it.field.modifiers) && !Modifier.isFinal(it.field.modifiers) } ||
                    gate.fields.none { it.field.descriptor == profile.modelCache && it.write }) return@forEach
            }
            val eligible = inner.fields.map { it.field }.distinctBy { it.descriptor }.filter { field ->
                field.owner == inner.owner && field.type == "java.lang.Boolean" &&
                    Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers) &&
                    inner.fields.any { it.field.descriptor == field.descriptor && it.read } &&
                    inner.fields.any { it.field.descriptor == field.descriptor && it.write }
            }
            val cache = if (profile == null) eligible.singleOrNull() else eligible.singleOrNull { it.descriptor == profile.cache }
            if (cache == null) return@forEach
            checkedWrappers.filter {
                (profile == null || it.descriptor == profile.tablet) &&
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
                    (profile == null || it.descriptor == profile.login) &&
                    it.isStatic && it.returnType == "void" && it.parameters.size == 2 &&
                        it.parameters.first() in setOf("android.view.View", "android.widget.Button") &&
                        it.parameters[1] !in PRIMITIVES &&
                        it.strings.any { s -> s == "updateLoginAsExdevice" || s == "loginAsOtherDeviceBtn" } &&
                        visibility in it.invokes && it.invokes.count { called -> called == outer.descriptor } == 1
                }.forEach { login ->
                    plans += PlanDescriptor(AppTarget.WECHAT, linkedMapOf("inner" to inner.descriptor,
                        "tablet" to outer.descriptor, "login" to login.descriptor), cache.descriptor, monitor,
                        profile?.deviceHookRole)
                }
            }
        }
        return uniquePlan(plans, "WeChat decision-wrapper-login profile=${profile?.id ?: "baseline-generic"}")
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
            field.type == "java.lang.Boolean" &&
                Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers) &&
                method.fields.any { it.field.descriptor == field.descriptor && it.read } &&
                method.fields.any { it.field.descriptor == field.descriptor && it.write }
        }
}

internal fun <T> uniqueDefinitionReferences(items: List<T>, descriptor: (T) -> String,
                                            definitionId: (T) -> Long, label: String): List<T> {
    val unique = items.distinctBy(definitionId)
    require(unique.groupBy(descriptor).values.none { it.size > 1 }) { "$label has overlapping DEX definitions; fail closed" }
    return unique
}

internal fun uniquePlan(plans: List<PlanDescriptor>, label: String): PlanDescriptor {
    val unique = plans.distinct()
    require(unique.size == 1) { "$label expected one complete plan; found ${unique.size}" }
    return unique.single().also { it.validate() }
}
