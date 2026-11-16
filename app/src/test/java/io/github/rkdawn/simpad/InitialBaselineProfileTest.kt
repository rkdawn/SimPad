package io.github.rkdawn.simpad

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

/** Exact original selector is compiled test-only as LegacyWeChatSelector. No 1.3.0 code is used. */
class InitialBaselineProfileTest {
    private val version = WeChatHostVersion(3183, "8.0.79")
    private val profile = WeChatProfiles.forVersion(version)!!
    private fun facts(): Map<String, MethodInfo> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/wechat-play-3183.tsv"))
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
        WeChatSelector.select(listOf(f.getValue("inner")), listOf(f.getValue("wrapper")), listOf(f.getValue("login")), listOf(f.getValue("modelGate")), v)
    private fun changed(role: String, change: (MethodInfo) -> MethodInfo) = facts().toMutableMap().apply { this[role] = change(getValue(role)) }
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }

    @Test fun originalUnmodifiedSelectorReproducesTheCombinedFailure() {
        val f = facts()
        rejects { LegacyWeChatSelector.select(listOf(f.getValue("inner")), listOf(f.getValue("wrapper")), listOf(f.getValue("login")), listOf(f.getValue("modelGate"))) }
    }
    @Test fun originalModelOwnerRestrictionFailsEvenWithOnlyTheResultCache() {
        val f = changed("inner") { it.copy(fields = it.fields.filter { use -> use.field.type != "java.lang.Boolean" || use.field.descriptor == profile.cache }) }
        rejects { LegacyWeChatSelector.select(listOf(f.getValue("inner")), listOf(f.getValue("wrapper")), listOf(f.getValue("login")), listOf(f.getValue("modelGate"))) }
        assertEquals(profile.cache, select(f).cacheField)
    }
    @Test fun originalSelectorStillFailsTheDoubleOwnCacheAfterModelOwnerIsAdjusted() {
        val f = changed("modelGate") { gate -> gate.copy(fields = gate.fields.map { use ->
            if (use.field.descriptor == profile.modelCache) use.copy(field = use.field.copy(owner = gate.owner,
                descriptor = "Lcom/tencent/mm/ui/j9;->m:Ljava/lang/Boolean;")) else use
        }) }
        rejects { LegacyWeChatSelector.select(listOf(f.getValue("inner")), listOf(f.getValue("wrapper")), listOf(f.getValue("login")), listOf(f.getValue("modelGate"))) }
    }
    @Test fun completePhoneMetadataSelectsAllThreeTargetsAndOnlyKnownResultField() {
        val selected = select()
        assertEquals(mapOf("inner" to profile.inner, "tablet" to profile.tablet, "login" to profile.login), selected.methods)
        assertEquals(profile.cache, selected.cacheField); assertNull(selected.monitorField)
    }
    @Test fun auxiliaryCacheOrderingDoesNotChangeTheSelectedField() {
        assertEquals(profile.cache, select(changed("inner") { it.copy(fields = it.fields.reversed()) }).cacheField)
    }
    @Test fun multipleAuxiliaryCachesAreNotForcedToBecomeResultFields() {
        val f = facts(); val caches = f.getValue("inner").fields.map { it.field }.distinctBy { it.descriptor }.filter {
            it.type == "java.lang.Boolean" && Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers)
        }
        assertEquals(4, caches.size); assertEquals(profile.cache, select().cacheField)
    }
    @Test fun profileIsNeverAppliedToAnotherVersionCode() { assertNull(WeChatProfiles.forVersion(WeChatHostVersion(3182, "8.0.79"))); rejects { select(v = WeChatHostVersion(3182, "8.0.79")) } }
    @Test fun profileIsNeverAppliedToAnotherVersionName() { assertNull(WeChatProfiles.forVersion(WeChatHostVersion(3183, "8.0.78"))); rejects { select(v = WeChatHostVersion(3183, "8.0.78")) } }
    @Test fun noVersionDoesNotSilentlyApplyTheKnownMapping() { assertNull(WeChatProfiles.forVersion(null)); rejects { select(v = null) } }
    @Test fun installedIdentityParsingUsesActualCodeAndName() { assertEquals(version, WeChatHostVersion.fromInstalledIdentity("3183:8.0.79:1793850630736")) }
    @Test fun malformedInstalledIdentityDoesNotGuessTheVersion() { assertNull(WeChatHostVersion.fromInstalledIdentity("8.0.79")); assertNull(WeChatHostVersion.fromInstalledIdentity("abc:8.0.79:123")) }
    @Test fun missingKnownCacheDoesNotFallBackToAuxiliaryField() { rejects { select(changed("inner") { it.copy(fields = it.fields.filterNot { use -> use.field.descriptor == profile.cache }) }) } }
    @Test fun resultCacheMustStillBeWritten() { rejects { select(changed("inner") { it.copy(fields = it.fields.filterNot { use -> use.field.descriptor == profile.cache && use.write }) }) } }
    @Test fun resultCacheMustStillBeRead() { rejects { select(changed("inner") { it.copy(fields = it.fields.filterNot { use -> use.field.descriptor == profile.cache && use.read }) }) } }
    @Test fun finalKnownCacheIsRejected() { rejects { select(changed("inner") { it.copy(fields = it.fields.map { use -> if (use.field.descriptor == profile.cache) use.copy(field = use.field.copy(modifiers = use.field.modifiers or Modifier.FINAL)) else use }) }) } }
    @Test fun primitiveKnownCacheIsRejected() { rejects { select(changed("inner") { it.copy(fields = it.fields.map { use -> if (use.field.descriptor == profile.cache) use.copy(field = use.field.copy(type = "boolean")) else use }) }) } }
    @Test fun wrongInnerDescriptorCannotUseTheMapping() { rejects { select(changed("inner") { it.copy(descriptor = "Lcom/tencent/mm/ui/ok;->D(Lnw5/w0;)Z") }) } }
    @Test fun wrongWrapperDescriptorCannotUseTheMapping() { rejects { select(changed("wrapper") { it.copy(descriptor = "Lcom/tencent/mm/ui/ok;->A()Z") }) } }
    @Test fun wrongLoginDescriptorCannotUseTheMapping() { rejects { select(changed("login") { it.copy(descriptor = "Lfc1/h0;->b(Landroid/view/View;Landroidx/lifecycle/y;)V") }) } }
    @Test fun missingLoginLinkRejectsTheWholeProfile() { rejects { select(changed("login") { it.copy(invokes = it.invokes.filterNot { call -> call == profile.tablet }) }) } }
    @Test fun missingModelHelperLinkRejectsTheWholeProfile() { rejects { select(changed("inner") { it.copy(invokes = it.invokes.filterNot { call -> call == profile.modelGate }) }) } }
    @Test fun wrongModelCacheDoesNotBorrowAnotherWritableHelperField() { rejects { select(changed("modelGate") { it.copy(fields = it.fields.map { use -> if (use.field.descriptor == profile.modelCache) use.copy(field = use.field.copy(descriptor = "Lkq/d0;->z:Ljava/lang/Boolean;")) else use }) }) } }
    @Test fun missingLoginAnchorIsNotExcusedByTheVersionName() { rejects { select(changed("login") { it.copy(strings = emptySet()) }) } }
    @Test fun duplicateDefinitionConflictsStillRejectTheProfile() {
        val f = facts(); val inner = f.getValue("inner")
        rejects { WeChatSelector.select(listOf(inner, inner.copy(strings = setOf("conflict"))), listOf(f.getValue("wrapper")), listOf(f.getValue("login")), listOf(f.getValue("modelGate")), version) }
    }
}
