package io.github.rkdawn.simpad

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HookPolicyTest {
    private val inner = "Lcom/tencent/mm/ui/Tablet;->decision(Ljava/lang/Object;)Z"
    private val outer = "Lcom/tencent/mm/ui/Tablet;->isPad()Z"
    private val login = "Lcom/tencent/mm/account/Login;->show(Landroid/view/View;Ljava/lang/Object;)V"
    private val cache = "Lcom/tencent/mm/ui/Tablet;->cached:Ljava/lang/Boolean;"
    private fun plan() = PlanDescriptor(AppTarget.WECHAT, linkedMapOf("inner" to inner, "tablet" to outer, "login" to login), cache, hookTarget = "inner")
    private fun frame(name: String) = StackTraceElement(name, "call", "Source.kt", 1)
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }

    @Test fun onlyThreeMainProcessesAreRouted() {
        assertEquals(3, AppTarget.entries.size)
        AppTarget.entries.forEach { assertEquals(it, AppTarget.forProcess(it.packageName, it.packageName)) }
    }
    @Test fun qqAndXhsAreNeverRouted() {
        assertNull(AppTarget.forProcess("com.tencent.mobileqq", "com.tencent.mobileqq"))
        assertNull(AppTarget.forProcess("com.xingin.xhs", "com.xingin.xhs"))
    }
    @Test fun customWecomIsNotRouted() { assertNull(AppTarget.forProcess("com.airchina.wecompro", "com.airchina.wecompro")) }
    @Test fun wechatAndDingtalkSecondaryProcessesAreSkipped() {
        listOf(AppTarget.WECHAT, AppTarget.DINGTALK).forEach { assertNull(AppTarget.forProcess(it.packageName, "${it.packageName}:push")) }
    }
    @Test fun wecomSecondaryProcessesAreRouted() { assertEquals(AppTarget.WECOM, AppTarget.forProcess("com.tencent.wework", "com.tencent.wework:wengine")) }
    @Test fun malformedWecomProcessIsSkipped() { assertNull(AppTarget.forProcess("com.tencent.wework", "com.tencent.wework:")) }
    @Test fun unknownProcessIsSkipped() { assertNull(AppTarget.forProcess(AppTarget.WECHAT.packageName, null)) }
    @Test fun similarPackageIsNotRouted() { assertNull(AppTarget.forProcess("com.tencent.mm.fake", "com.tencent.mm.fake")) }
    @Test fun chatExceptionMatchesNestedClasses() { assertTrue(isWeChatChatCall(arrayOf(frame("com.tencent.mm.pluginsdk.ui.chat.ChatFooter\$Listener")))) }
    @Test fun similarChatPackageDoesNotMatch() { assertFalse(isWeChatChatCall(arrayOf(frame("com.tencent.mm.pluginsdk.ui.chatting.ChatUI")))) }
    @Test fun emptyStackIsNotChat() { assertFalse(isWeChatChatCall(emptyArray())) }
    @Test fun cacheFingerprintIsStableAndLengthDelimited() {
        assertEquals(cacheFingerprint(listOf("a", "bc")), cacheFingerprint(listOf("a", "bc")))
        assertNotEquals(cacheFingerprint(listOf("a", "bc")), cacheFingerprint(listOf("ab", "c")))
    }
    @Test fun ruleChangeChangesFingerprint() { assertNotEquals(cacheFingerprint(listOf("v1", "apk")), cacheFingerprint(listOf("v2", "apk"))) }
    @Test fun patchChangeChangesFingerprint() { assertNotEquals(cacheFingerprint(listOf("apk", "patch1")), cacheFingerprint(listOf("apk", "patch2"))) }
    @Test fun chineseAndApostrophePathsAreSupported() { assertEquals(64, cacheFingerprint(listOf("D:/项目/I'm Pad", "版本一")).length) }
    @Test fun completePlanRoundTrips() { assertEquals(plan(), PlanDescriptor.decode(AppTarget.WECHAT, plan().encode())) }
    @Test fun planRevisionIsScopedByHostApplication() {
        val hostVersion = "3200:8.0.80:1795004632090"
        assertEquals("simpad-code-plan-v1", methodIdentityScope(AppTarget.DINGTALK.packageName, hostVersion)[0])
        assertEquals(RULE_REVISION, methodIdentityScope(AppTarget.DINGTALK.packageName, hostVersion)[1])
        assertEquals("simpad-wechat-history-login-v3", methodIdentityScope(AppTarget.WECHAT.packageName, hostVersion)[1])
        assertEquals("inner", plan().encode()["hookTarget"])
    }
    @Test fun unknownWechatHookTargetIsRejected() { rejects { plan().copy(hookTarget = "login").validate() } }
    @Test fun nonWechatHookTargetIsRejected() { rejects { PlanDescriptor(AppTarget.DINGTALK, mapOf("device" to inner), hookTarget = "inner").validate() } }
    @Test fun staleWechatRuleRevisionInvalidatesCachedPlan() {
        rejects { PlanDescriptor.decode(AppTarget.WECHAT, plan().encode() + ("revision" to "simpad-wechat-wrapper-target-v2")) }
        rejects { PlanDescriptor.decode(AppTarget.WECHAT, plan().encode() + ("revision" to RULE_REVISION)) }
    }
    @Test fun staleNonWechatRuleRevisionIsUnchanged() {
        val dingtalk = PlanDescriptor(AppTarget.DINGTALK, mapOf("device" to inner))
        assertEquals(dingtalk, PlanDescriptor.decode(AppTarget.DINGTALK, dingtalk.encode()))
    }
    @Test fun missingRoleIsRejected() { rejects { plan().copy(methods = plan().methods - "login").validate() } }
    @Test fun missingWechatCacheIsRejected() { rejects { plan().copy(cacheField = null).validate() } }
    @Test fun overlappingMethodsAreRejected() { rejects { plan().copy(methods = mapOf("inner" to inner, "tablet" to inner, "login" to login)).validate() } }
    @Test fun wrongAppCacheIsRejected() { rejects { PlanDescriptor.decode(AppTarget.WECOM, plan().encode()) } }
    @Test fun staleRuleCacheIsRejected() { rejects { PlanDescriptor.decode(AppTarget.WECHAT, plan().encode() + ("revision" to "old")) } }
    @Test fun unknownCacheKeyIsRejected() { rejects { PlanDescriptor.decode(AppTarget.WECHAT, plan().encode() + ("unexpected" to "value")) } }
    @Test fun invalidMonitorIsRejected() { rejects { plan().copy(monitorField = "LTest;->lock:I").validate() } }
    @Test fun nonWechatFieldsAreRejected() { rejects { PlanDescriptor(AppTarget.DINGTALK, mapOf("device" to inner), cache).validate() } }
    @Test fun oversizedCacheValueIsRejected() { rejects { PlanDescriptor.decode(AppTarget.WECHAT, plan().encode() + ("app" to "x".repeat(3000))) } }
    @Test fun fileStampDetectsCodeChange() {
        val root = File(System.getProperty("java.io.tmpdir"), "simpad-test-files").apply { mkdirs() }
        val file = File.createTempFile("code-", ".dex", root)
        file.writeText("original")
        val stamp = FileStamp.read(file.path)
        assertTrue(stamp.stillCurrent())
        file.appendText("-changed")
        assertFalse(stamp.stillCurrent())
    }
}
