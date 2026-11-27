package io.github.rkdawn.simpad

import java.security.MessageDigest

internal enum class AppTarget(val packageName: String) {
    WECHAT("com.tencent.mm"), DINGTALK("com.alibaba.android.rimet"), WECOM("com.tencent.wework");
    companion object {
        fun forProcess(packageName: String, processName: String?): AppTarget? {
            val app = entries.singleOrNull { it.packageName == packageName } ?: return null
            return app.takeIf {
                processName == packageName || (app == WECOM &&
                    processName?.startsWith("$packageName:") == true && processName.length > packageName.length + 1)
            }
        }
    }
}

internal const val RULE_REVISION = "simpad-formal-three-points-v1"
internal const val WECHAT_RULE_REVISION = "simpad-wechat-history-login-v3"
internal fun planRuleRevision(app: AppTarget): String =
    if (app == AppTarget.WECHAT) WECHAT_RULE_REVISION else RULE_REVISION
internal fun planRuleRevision(packageName: String): String =
    if (packageName == AppTarget.WECHAT.packageName) WECHAT_RULE_REVISION else RULE_REVISION
internal val PRIMITIVES = setOf("boolean", "byte", "char", "short", "int", "long", "float", "double", "void")

internal fun isWeChatChatClassName(name: String): Boolean =
    name == "com.tencent.mm.pluginsdk.ui.chat" || name.startsWith("com.tencent.mm.pluginsdk.ui.chat.")

internal fun isWeChatChatCall(stack: Array<StackTraceElement>): Boolean =
    stack.any { isWeChatChatClassName(it.className) }

internal fun cacheFingerprint(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach {
        val bytes = it.toByteArray(Charsets.UTF_8)
        digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal data class PlanDescriptor(
    val app: AppTarget,
    val methods: Map<String, String>,
    val cacheField: String? = null,
    val monitorField: String? = null,
    val hookTarget: String? = null,
) {
    fun validate() {
        val expected = when (app) {
            AppTarget.WECHAT -> setOf("inner", "tablet", "login")
            AppTarget.DINGTALK -> setOf("device")
            AppTarget.WECOM -> setOf("device_type", "app_pad", "service_pad")
        }
        require(methods.keys == expected) { "Incomplete $app hook plan" }
        require(methods.values.distinct().size == methods.size) { "Hook targets must be distinct" }
        require(methods.values.all { it.length in 1..2048 && it.startsWith('L') && "->" in it }) { "Invalid method descriptor" }
        if (app == AppTarget.WECHAT) {
            require(cacheField != null && cacheField.endsWith(":Ljava/lang/Boolean;")) { "Missing WeChat Boolean cache" }
            require(monitorField == null || monitorField.endsWith(":Ljava/lang/Object;")) { "Invalid WeChat monitor" }
            require(hookTarget == null || hookTarget in setOf("inner", "tablet")) { "Invalid WeChat hook target" }
        } else require(cacheField == null && monitorField == null && hookTarget == null) { "Unexpected cache fields" }
    }

    fun encode(): Map<String, String> {
        validate()
        return buildMap {
            put("revision", planRuleRevision(app))
            put("app", app.name)
            methods.forEach { (role, descriptor) -> put("method.$role", descriptor) }
            cacheField?.let { put("cacheField", it) }
            monitorField?.let { put("monitorField", it) }
            if (app == AppTarget.WECHAT) put("hookTarget", hookTarget ?: "inner")
        }
    }

    companion object {
        fun decode(app: AppTarget, values: Map<String, String>): PlanDescriptor {
            require(values["revision"] == planRuleRevision(app) && values["app"] == app.name) { "Cache rule changed" }
            require(values.size <= 9 && values.values.all { it.length <= 2048 }) { "Oversized cache record" }
            require(values.keys.all { it in setOf("revision", "app", "cacheField", "monitorField", "hookTarget") || it.startsWith("method.") }) { "Unknown cache key" }
            require(app != AppTarget.WECHAT || values["hookTarget"] != null) { "WeChat cache has no hook target" }
            return PlanDescriptor(app, values.filterKeys { it.startsWith("method.") }.mapKeys { it.key.removePrefix("method.") },
                values["cacheField"], values["monitorField"], values["hookTarget"]).also { it.validate() }
        }
    }
}
