package io.github.rkdawn.simpad

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.query.FindMethod
import java.lang.reflect.Modifier
import java.util.Locale

internal class AppRuleResolver(private val bridge: DexKitBridge,
                               private val version: WeChatHostVersion? = null,
                               private val diagnostic: (String) -> Unit = {}) {
    fun resolve(app: AppTarget): PlanDescriptor = when (app) {
        AppTarget.WECHAT -> wechat()
        AppTarget.DINGTALK -> dingtalk()
        AppTarget.WECOM -> wecom()
    }

    private fun wechat(): PlanDescriptor {
        val decisions = bridge.findMethod {
            searchPackages("com.tencent.mm")
            matcher { returnType("boolean"); paramCount(1); usingEqStrings("X2", "A2") }
        }.filter { Modifier.isStatic(it.modifiers) }.let { uniqueDefinitions(it, "WeChat decision") }
        val wrappers = decisions.flatMap { it.callers }.filter {
            Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "boolean"
        }.let { uniqueDefinitions(it, "WeChat wrapper") }
        val modelGates = decisions.flatMap { it.invokes }.filter {
            Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "boolean" &&
                "Lenovo TB-9707F" in it.usingStrings
        }.let { uniqueDefinitions(it, "WeChat model gate") }
        val loginCandidates = listOf("updateLoginAsExdevice", "loginAsOtherDeviceBtn").flatMap { anchor ->
            bridge.findMethod(wechatLoginQuery(anchor))
        }.let { uniqueDefinitions(it, "WeChat login") }
        diagnostic("WECHAT_CANDIDATES profile=${WeChatProfiles.forVersion(version)?.id ?: "baseline-generic"} inner=${decisions.size} wrapper=${wrappers.size} login=${loginCandidates.size} modelGate=${modelGates.size}")
        return WeChatSelector.select(decisions.map(::describe), wrappers.map(::describe),
            loginCandidates.map(::describe), modelGates.map(::describe), version)
    }

    private fun uniqueDefinitions(methods: List<MethodData>, label: String): List<MethodData> =
        uniqueDefinitionReferences(methods, { it.descriptor }, { it.getEncodeId() }, label)

    private fun dingtalk(): PlanDescriptor {
        val matches = bridge.findMethod {
            searchPackages("com.alibaba.android.dingtalkbase.foldable")
            matcher {
                paramTypes("android.app.Activity"); returnType("boolean")
                usingStrings("isMultiLoginFoldableDevice")
            }
        }.filter { Modifier.isStatic(it.modifiers) }
        require(matches.size == 1) { "DingTalk multi-login predicate expected one match; found ${matches.size}" }
        return PlanDescriptor(AppTarget.DINGTALK, mapOf("device" to matches.single().descriptor)).also { it.validate() }
    }

    private fun wecom(): PlanDescriptor {
        fun target(owner: String, method: String, result: String, static: Boolean): String {
            val matches = bridge.findMethod {
                matcher { declaredClass(owner); name(method); paramCount(0); returnType(result) }
            }.filter { Modifier.isStatic(it.modifiers) == static }
            require(matches.size == 1) { "WeCom $method expected one match; found ${matches.size}" }
            return matches.single().descriptor
        }
        val application = "com.tencent.wework.foundation.logic.Application"
        return PlanDescriptor(AppTarget.WECOM, linkedMapOf(
            "device_type" to target(application, "getDeviceTypeFromLocal", "int", false),
            "app_pad" to target(application, "isAndroidPad", "boolean", true),
            "service_pad" to target("com.tencent.wework.foundation.impl.WeworkServiceImpl", "isAndroidPad", "boolean", false),
        )).also { it.validate() }
    }

    private fun describe(data: MethodData): MethodInfo = MethodInfo(
        descriptor = data.descriptor, owner = data.className, name = data.methodName,
        parameters = data.paramTypeNames, returnType = data.returnTypeName, modifiers = data.modifiers,
        strings = data.usingStrings.toSet(), invokes = data.invokes.map { it.descriptor },
        fields = data.usingFields.map { use ->
            val field = use.field
            val kind = use.usingType.toString()
            FieldUse(FieldInfo(field.descriptor, field.className, field.typeName, field.modifiers),
                read = kind.equals("Read", true), write = kind.equals("Write", true))
        },
        opNames = data.opNames.map { it.replace('-', '_').uppercase(Locale.ROOT) },
    )
}

/** Preserve shape and anchors, but permit root-obfuscated login helpers. */
internal fun wechatLoginQuery(anchor: String): FindMethod = FindMethod.create().apply {
    matcher { returnType("void"); paramCount(2); usingStrings(anchor) }
}
