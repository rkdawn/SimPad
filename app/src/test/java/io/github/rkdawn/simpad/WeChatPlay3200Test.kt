package io.github.rkdawn.simpad

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

/** Facts exported from the user's complete Play 8.0.80/3200 APK, not renamed 3183 facts. */
class WeChatPlay3200Test {
    private val version = WeChatHostVersion(3200, "8.0.80")
    private val cache = "Lcom/tencent/mm/ui/vk;->g:Ljava/lang/Boolean;"
    private val auxiliary = "Lcom/tencent/mm/ui/vk;->v:Ljava/lang/Boolean;"
    private val expected = mapOf(
        "inner" to "Lcom/tencent/mm/ui/vk;->b(Lpw5/w0;)Z",
        "tablet" to "Lcom/tencent/mm/ui/vk;->C()Z",
        "login" to "Lwc1/h0;->a(Landroid/view/View;Landroidx/lifecycle/y;)V",
    )
    private fun facts(): Map<String, MethodInfo> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/wechat-play-3200.tsv"))
        val rows = stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.map { it.split('\t') }
        return listOf("inner", "wrapper", "login", "modelGate").associateWith { role ->
            val items = rows.filter { it[1] == role }; val fact = items.single { it[0] == "FACT" }
            MethodInfo(fact[2], fact[3], fact[2].substringAfter("->").substringBefore('('),
                fact[5].takeIf { it.isNotEmpty() }?.split(',') ?: emptyList(), fact[6], fact[4].toInt(),
                items.filter { it[0] == "STRING" }.map { it[2] }.toSet(),
                items.filter { it[0] == "CALL" }.map { it[2] },
                items.filter { it[0] == "FIELD" }.map { FieldUse(FieldInfo(it[2], it[3], it[4], it[5].toInt()), it[6].toBooleanStrict(), it[7].toBooleanStrict()) },
                items.filter { it[0] == "OP" }.map { it[2] })
        }
    }
    private fun select(f: Map<String, MethodInfo> = facts(), v: WeChatHostVersion? = version) =
        WeChatSelector.select(listOf(f.getValue("inner")), listOf(f.getValue("wrapper")),
            listOf(f.getValue("login")), listOf(f.getValue("modelGate")), v)
    private fun changed(role: String, change: (MethodInfo) -> MethodInfo) = facts().toMutableMap().apply { this[role] = change(getValue(role)) }
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }

    @Test fun genericSelectorReproducesTheActualZeroPlanFailure() {
        try { select(v = null); fail("Expected zero complete plans") } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.endsWith("found 0"))
        }
    }
    @Test fun play3202SelectsTheSameValidatedGraphWithItsOwnProfile() {
        val newer = WeChatHostVersion(3202, "8.0.80")
        val profile = requireNotNull(WeChatProfiles.forVersion(newer))
        assertEquals("play-8.0.80-3202", profile.id)
        val plan = select(v = newer)
        assertEquals(expected, plan.methods); assertEquals(cache, plan.cacheField); assertEquals("tablet", plan.hookTarget)
        rejects { select(changed("login") { it.copy(invokes = emptyList()) }, newer) }
    }
    @Test fun onlyExactPlay3200And3202ProfilesEnableHistoryLogin() {
        for (code in listOf(3200L, 3202L)) {
            val history = requireNotNull(WeChatProfiles.forVersion(WeChatHostVersion(code, "8.0.80"))?.history)
            assertEquals("Lcom/tencent/mm/plugin/account/ui/LoginHistoryUI;->initView()V", history.initializer)
            assertEquals(0x7f0a4363, history.buttonId)
        }
        for (other in listOf(WeChatHostVersion(3183, "8.0.79"), WeChatHostVersion(3201, "8.0.80"), WeChatHostVersion(3202, "8.0.81"))) {
            assertNull(WeChatProfiles.forVersion(other)?.history)
        }
    }
    @Test fun removingOnlyTheTclAuxiliaryFieldMakesGenericSelectionSucceed() {
        val selected = select(changed("inner") { it.copy(fields = it.fields.filterNot { use -> use.field.descriptor == auxiliary }) }, null)
        assertEquals(expected, selected.methods); assertEquals(cache, selected.cacheField)
    }
    @Test fun onlyTwoWritableBooleanCachesBelongToTheDecisionClass() {
        val inner = facts().getValue("inner")
        assertEquals(setOf(cache, auxiliary), inner.fields.map { it.field }.filter {
            it.owner == inner.owner && it.type == "java.lang.Boolean" && Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers)
        }.map { it.descriptor }.toSet())
    }
    @Test fun actualApkMetadataSelectsTheCompleteKnownPlan() {
        val selected = select()
        assertEquals(expected, selected.methods); assertEquals(cache, selected.cacheField)
        assertEquals("Lcom/tencent/mm/ui/vk;->f:Ljava/lang/Object;", selected.monitorField)
    }
    @Test fun profileContainsTheActualLenovoHelperAndCache() {
        val profile = requireNotNull(WeChatProfiles.forVersion(version))
        assertEquals("play-8.0.80-3200", profile.id)
        assertEquals("Lcom/tencent/mm/ui/n9;->a()Z", profile.modelGate)
        assertEquals("Loq/d0;->m:Ljava/lang/Boolean;", profile.modelCache)
        assertEquals("tablet", profile.deviceHookRole)
    }
    @Test fun oldPhoneProfileStillHooksInnerDecision() {
        assertEquals("inner", WeChatProfiles.forVersion(WeChatHostVersion(3183, "8.0.79"))!!.deviceHookRole)
    }
    @Test fun fieldOrderingDoesNotSelectTheAuxiliaryCache() {
        assertEquals(cache, select(changed("inner") { it.copy(fields = it.fields.reversed()) }).cacheField)
    }
    @Test fun missingResultCacheCannotFallBackToTheTclCache() {
        rejects { select(changed("inner") { it.copy(fields = it.fields.filterNot { use -> use.field.descriptor == cache }) }) }
    }
    @Test fun missingResultCacheReadIsRejected() {
        rejects { select(changed("inner") { it.copy(fields = it.fields.filterNot { use -> use.field.descriptor == cache && use.read }) }) }
    }
    @Test fun missingResultCacheWriteIsRejected() {
        rejects { select(changed("inner") { it.copy(fields = it.fields.filterNot { use -> use.field.descriptor == cache && use.write }) }) }
    }
    @Test fun finalResultCacheIsRejected() {
        rejects { select(changed("inner") { it.copy(fields = it.fields.map { use -> if (use.field.descriptor == cache) use.copy(field = use.field.copy(modifiers = use.field.modifiers or Modifier.FINAL)) else use }) }) }
    }
    @Test fun alteredDecisionCannotUseTheMapping() {
        rejects { select(changed("inner") { it.copy(descriptor = "Lcom/tencent/mm/ui/vk;->x(Lpw5/w0;)Z") }) }
    }
    @Test fun alteredWrapperCannotUseTheMapping() {
        rejects { select(changed("wrapper") { it.copy(descriptor = "Lcom/tencent/mm/ui/vk;->D()Z") }) }
    }
    @Test fun alteredLoginCannotUseTheMapping() {
        rejects { select(changed("login") { it.copy(descriptor = "Lwc1/h0;->b(Landroid/view/View;Landroidx/lifecycle/y;)V") }) }
    }
    @Test fun brokenLoginLinkIsRejected() {
        rejects { select(changed("login") { it.copy(invokes = it.invokes.filterNot { call -> call == expected.getValue("tablet") }) }) }
    }
    @Test fun missingVisibilityCallIsRejected() {
        rejects { select(changed("login") { it.copy(invokes = it.invokes.filterNot { call -> call == "Landroid/view/View;->setVisibility(I)V" }) }) }
    }
    @Test fun brokenModelHelperLinkIsRejected() {
        rejects { select(changed("inner") { it.copy(invokes = it.invokes.filterNot { call -> call == "Lcom/tencent/mm/ui/n9;->a()Z" }) }) }
    }
    @Test fun modelCacheMustStillBeWritten() {
        rejects { select(changed("modelGate") { it.copy(fields = it.fields.filterNot { use -> use.field.descriptor == "Loq/d0;->m:Ljava/lang/Boolean;" && use.write }) }) }
    }
    @Test fun synchronizedWrapperStillRequiresItsKnownMonitor() {
        rejects { select(changed("wrapper") { it.copy(fields = it.fields.filterNot { use -> use.field.type == "java.lang.Object" }) }) }
    }
    @Test fun profileDoesNotApplyToAnotherVersionCode() {
        assertNull(WeChatProfiles.forVersion(WeChatHostVersion(3199, "8.0.80")))
        rejects { select(v = WeChatHostVersion(3199, "8.0.80")) }
    }
    @Test fun profileDoesNotApplyToAnotherVersionName() {
        assertNull(WeChatProfiles.forVersion(WeChatHostVersion(3200, "8.0.79")))
        rejects { select(v = WeChatHostVersion(3200, "8.0.79")) }
    }
    @Test fun oldProfileCannotBeBorrowedForTheNewApk() { rejects { select(v = WeChatHostVersion(3183, "8.0.79")) } }
    @Test fun duplicateDefinitionsStillFailClosed() {
        val f = facts(); val inner = f.getValue("inner")
        rejects { WeChatSelector.select(listOf(inner, inner.copy(strings = setOf("conflict"))), listOf(f.getValue("wrapper")), listOf(f.getValue("login")), listOf(f.getValue("modelGate")), version) }
    }
    @Test fun installedIdentityParsesTheActualNewCodeAndName() {
        assertEquals(version, WeChatHostVersion.fromInstalledIdentity("3200:8.0.80:1795004632090"))
    }
}
