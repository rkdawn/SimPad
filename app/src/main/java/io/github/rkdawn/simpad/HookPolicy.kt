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
        } else require(cacheField == null && monitorField == null) { "Unexpected cache fields" }
    }

    fun encode(): Map<String, String> {
        validate()
        return buildMap {
            put("revision", RULE_REVISION)
            put("app", app.name)
            methods.forEach { (role, descriptor) -> put("method.$role", descriptor) }
            cacheField?.let { put("cacheField", it) }
            monitorField?.let { put("monitorField", it) }
        }
    }

    companion object {
        fun decode(app: AppTarget, values: Map<String, String>): PlanDescriptor {
            require(values["revision"] == RULE_REVISION && values["app"] == app.name) { "Cache rule changed" }
            require(values.size <= 8 && values.values.all { it.length <= 2048 }) { "Oversized cache record" }
            require(values.keys.all { it in setOf("revision", "app", "cacheField", "monitorField") || it.startsWith("method.") }) { "Unknown cache key" }
            return PlanDescriptor(app, values.filterKeys { it.startsWith("method.") }.mapKeys { it.key.removePrefix("method.") },
                values["cacheField"], values["monitorField"]).also { it.validate() }
        }
    }
}
