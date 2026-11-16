package io.github.rkdawn.simpad

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class MethodGraphTest {
    private val owner = "com.tencent.mm.ui.Tablet"
    private val static = Modifier.PUBLIC or Modifier.STATIC
    private val cache = FieldInfo("Lcom/tencent/mm/ui/Tablet;->cache:Ljava/lang/Boolean;", owner, "java.lang.Boolean", static)
    private val model = FieldInfo("Landroid/os/Build;->MODEL:Ljava/lang/String;", "android.os.Build", "java.lang.String", static or Modifier.FINAL)
    private val lock = FieldInfo("Lcom/tencent/mm/ui/Tablet;->lock:Ljava/lang/Object;", owner, "java.lang.Object", static)
    private val decision = MethodInfo("Lcom/tencent/mm/ui/Tablet;->decision(Ljava/lang/Object;)Z", owner, "decision", listOf("java.lang.Object"), "boolean", static,
        setOf("Lenovo TB-9707F", "X2", "A2"), listOf("Ljava/lang/Integer;->intValue()I"),
        listOf(FieldUse(model, true, false), FieldUse(cache, true, false), FieldUse(cache, false, true)))
    private val wrapper = MethodInfo("Lcom/tencent/mm/ui/Tablet;->isPad()Z", owner, "isPad", emptyList(), "boolean", static,
        invokes = listOf(decision.descriptor))
    private val login = MethodInfo("Lcom/tencent/mm/account/Login;->show(Landroid/view/View;Ljava/lang/Object;)V", "com.tencent.mm.account.Login", "show",
        listOf("android.view.View", "java.lang.Object"), "void", static, setOf("updateLoginAsExdevice"),
        listOf(wrapper.descriptor, "Landroid/view/View;->setVisibility(I)V"))
    private val lenovoCache = cache.copy(descriptor = "Lcom/tencent/mm/ui/Tablet;->lenovoCache:Ljava/lang/Boolean;")
    private val modelGate = MethodInfo("Lcom/tencent/mm/ui/Tablet;->isLenovo()Z", owner, "isLenovo", emptyList(), "boolean", static,
        setOf("Lenovo TB-9707F"), listOf("Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z"),
        listOf(FieldUse(model, true, false), FieldUse(lenovoCache, true, false), FieldUse(lenovoCache, false, true)))
    private val aggregate = decision.copy(strings = setOf("X2", "A2"), invokes = decision.invokes + modelGate.descriptor)
    private fun select(d: MethodInfo = decision, w: MethodInfo = wrapper, l: MethodInfo = login) = WeChatSelector.select(listOf(d), listOf(w), listOf(l))
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }

    @Test fun completeLinkedPlanIsSelected() { assertEquals(setOf("inner", "tablet", "login"), select().methods.keys) }
    @Test fun directWrapperUsesDeclaringClassLock() { assertNull(select().monitorField) }
    @Test fun synchronizedWrapperUsesRecognizedLockField() {
        val plan = select(w = wrapper.copy(fields = listOf(FieldUse(lock, true, false)), opNames = listOf("MONITOR_ENTER", "INVOKE_STATIC", "MONITOR_EXIT")))
        assertEquals(lock.descriptor, plan.monitorField)
    }
    @Test fun duplicatedDexKitReferencesDoNotCreateFalseAmbiguity() {
        assertEquals(select(), WeChatSelector.select(listOf(decision, decision), listOf(wrapper, wrapper), listOf(login, login)))
    }
    @Test fun missingLoginRejectsWholePlan() { rejects { WeChatSelector.select(listOf(decision), listOf(wrapper), emptyList()) } }
    @Test fun unrelatedLoginIsRejected() { rejects { select(l = login.copy(invokes = listOf("Landroid/view/View;->setVisibility(I)V"))) } }
    @Test fun unrelatedWrapperIsRejected() { rejects { select(w = wrapper.copy(owner = "com.tencent.mm.Other")) } }
    @Test fun missingModelMarkerIsRejected() { rejects { select(d = decision.copy(strings = setOf("X2", "A2"))) } }
    @Test fun modelFieldMustActuallyBeRead() { rejects { select(d = decision.copy(fields = decision.fields.filter { it.field != model })) } }
    @Test fun widthUnboxingMustBePresent() { rejects { select(d = decision.copy(invokes = emptyList())) } }
    @Test fun decisionMustBeStatic() { rejects { select(d = decision.copy(modifiers = Modifier.PUBLIC)) } }
    @Test fun decisionCannotTakePrimitiveParameter() { rejects { select(d = decision.copy(parameters = listOf("int"))) } }
    @Test fun finalCacheCannotBeChanged() {
        val fields = decision.fields.map { if (it.field == cache) it.copy(field = cache.copy(modifiers = static or Modifier.FINAL)) else it }
        rejects { select(d = decision.copy(fields = fields)) }
    }
    @Test fun cacheMustBeReadAndWritten() { rejects { select(d = decision.copy(fields = decision.fields.filterNot { it.write })) } }
    @Test fun multipleBooleanCachesAreRejected() {
        val second = cache.copy(descriptor = "Lcom/tencent/mm/ui/Tablet;->other:Ljava/lang/Boolean;")
        rejects { select(d = decision.copy(fields = decision.fields + listOf(FieldUse(second, true, false), FieldUse(second, false, true)))) }
    }
    @Test fun monitorWithoutKnownFieldIsRejected() { rejects { select(w = wrapper.copy(opNames = listOf("MONITOR_ENTER", "MONITOR_EXIT"))) } }
    @Test fun unmatchedMonitorExitIsRejected() { rejects { select(w = wrapper.copy(fields = listOf(FieldUse(lock, true, false)), opNames = listOf("MONITOR_ENTER"))) } }
    @Test fun ambiguousMonitorsAreRejected() {
        val second = lock.copy(descriptor = "Lcom/tencent/mm/ui/Tablet;->other:Ljava/lang/Object;")
        rejects { select(w = wrapper.copy(fields = listOf(FieldUse(lock, true, false), FieldUse(second, true, false)), opNames = listOf("MONITOR_ENTER", "MONITOR_EXIT"))) }
    }
    @Test fun multipleCompleteLoginPathsAreRejected() {
        rejects { WeChatSelector.select(listOf(decision), listOf(wrapper), listOf(login, login.copy(descriptor = "Lcom/tencent/mm/account/Login2;->show(Landroid/view/View;Ljava/lang/Object;)V"))) }
    }
    @Test fun hiddenButtonLegacyAnchorIsAcceptedOnlyWhenLinked() { assertEquals(select(), select(l = login.copy(strings = setOf("loginAsOtherDeviceBtn")))) }
    @Test fun unknownLoginAnchorIsRejected() { rejects { select(l = login.copy(strings = setOf("unrelated"))) } }
    @Test fun conflictingSameDescriptorIsRejected() {
        rejects { WeChatSelector.select(listOf(decision, decision.copy(strings = setOf("different implementation"))), listOf(wrapper), listOf(login)) }
    }
    @Test fun splitModelGateAggregatePlanIsAccepted() {
        assertEquals(select(), WeChatSelector.select(listOf(aggregate), listOf(wrapper), listOf(login), listOf(modelGate)))
    }
    @Test fun aggregateWithoutModelGateIsRejected() { rejects { WeChatSelector.select(listOf(aggregate), listOf(wrapper), listOf(login)) } }
    @Test fun unlinkedModelGateIsRejected() {
        rejects { WeChatSelector.select(listOf(aggregate.copy(invokes = decision.invokes)), listOf(wrapper), listOf(login), listOf(modelGate)) }
    }
    @Test fun instanceModelGateIsRejected() {
        rejects { WeChatSelector.select(listOf(aggregate), listOf(wrapper), listOf(login), listOf(modelGate.copy(modifiers = Modifier.PUBLIC))) }
    }
    @Test fun modelGateWithoutModelReadIsRejected() {
        rejects { WeChatSelector.select(listOf(aggregate), listOf(wrapper), listOf(login), listOf(modelGate.copy(fields = modelGate.fields.filter { it.field != model }))) }
    }
    @Test fun modelGateWithoutCacheWriteIsRejected() {
        rejects { WeChatSelector.select(listOf(aggregate), listOf(wrapper), listOf(login), listOf(modelGate.copy(fields = modelGate.fields.filterNot { it.write }))) }
    }
    @Test fun modelGateWithoutStringComparisonIsRejected() {
        rejects { WeChatSelector.select(listOf(aggregate), listOf(wrapper), listOf(login), listOf(modelGate.copy(invokes = emptyList()))) }
    }
    @Test fun multipleLinkedModelGatesAreRejected() {
        val second = modelGate.copy(descriptor = "Lcom/tencent/mm/ui/Tablet;->isLenovo2()Z", name = "isLenovo2")
        rejects { WeChatSelector.select(listOf(aggregate.copy(invokes = aggregate.invokes + second.descriptor)), listOf(wrapper), listOf(login), listOf(modelGate, second)) }
    }
    @Test fun sameDescriptorWithDifferentDexIdentityIsRejected() {
        val items = listOf("same" to 1L, "same" to 2L)
        rejects { uniqueDefinitionReferences(items, { it.first }, { it.second }, "test") }
    }
    @Test fun repeatedReferenceToSameDexIdentityIsDeduplicated() {
        val item = "same" to 1L
        assertEquals(listOf(item), uniqueDefinitionReferences(listOf(item, item), { it.first }, { it.second }, "test"))
    }
    @Test fun loginMustTakeAView() { rejects { select(l = login.copy(parameters = listOf("java.lang.String", "java.lang.Object"))) } }
}
