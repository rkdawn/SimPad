package com.houvven.impad

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StartupCacheScopeTest {
    private val pkg = "com.tencent.mm"
    private val version = "3183:8.0.79:100"
    private class Store {
        val records = linkedMapOf<String, String>()
        var writes = 0
        fun cache() = InstalledApkDigestCache({ records[it] }, { records.clear(); records.putAll(it); writes++ })
    }
    private fun apk(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "startup-scope-tests").apply { mkdirs() }
        return File.createTempFile("base-", ".apk", dir).apply { writeText("verified-code") }
    }
    private fun collect(file: File, store: Store, scope: List<String>, readonly: Boolean = true, hasher: (FileStamp) -> CodeFileDigest = ::hashCodeFile): CodeDigestResult {
        val stamp = FileStamp.read(file.path)
        val session = store.cache().begin(scope)
        val result = collectCodeDigests(listOf(stamp), setOf(stamp.path), session, { readonly }, hasher)
        session.publish()
        return result
    }
    @Test fun productionPlanScopeExcludesDisplayVersionButIncludesExplicitRule() {
        val scope = methodIdentityScope(pkg, version, "rule-A")
        assertEquals(listOf("padbridge-code-plan-v2", "rule-A", pkg, version), scope)
        assertFalse(scope.contains(BuildConfig.VERSION_NAME))
        assertNotEquals(scope, methodIdentityScope(pkg, version, "rule-B"))
    }
    @Test fun digestScopeIsDeterministicAndIndependentOfSelectionRule() {
        assertEquals(installedDigestScope(pkg, version, listOf("b", "a"), "module-A"), installedDigestScope(pkg, version, listOf("a", "b"), "module-A"))
        assertEquals(listOf("padbridge-apk-content-v2", "module-A", pkg, version, "a", "b"), installedDigestScope(pkg, version, listOf("a", "b"), "module-A"))
    }
    @Test fun ruleChangeInvalidatesPlanWithoutRereadingUnchangedApk() {
        val file = apk(); val store = Store(); val paths = setOf(file.canonicalPath)
        val scope = installedDigestScope(pkg, version, paths)
        val first = collect(file, store, scope); val writes = store.writes
        val second = collect(file, store, scope, hasher = { fail("Rule change must not reread unchanged APK content"); hashCodeFile(it) })
        assertEquals(first.values, second.values); assertEquals(0L, second.metrics.hashedBytes); assertEquals(writes, store.writes)
        assertNotEquals(cacheFingerprint(methodIdentityScope(pkg, version, "rule-A") + first.values),
            cacheFingerprint(methodIdentityScope(pkg, version, "rule-B") + second.values))
    }
    @Test fun moduleUpgradeRechecksContentButKeepsTheSamePlanIdentity() {
        val file = apk(); val store = Store(); val paths = setOf(file.canonicalPath)
        val first = collect(file, store, installedDigestScope(pkg, version, paths, "module-A"))
        val second = collect(file, store, installedDigestScope(pkg, version, paths, "module-B"))
        assertEquals(1, second.metrics.hashedFiles)
        assertEquals(first.values, second.values)
        val stamp = FileStamp.read(file.path)
        fun plan(values: List<String>) = cacheFingerprint(methodIdentityScope(pkg, version, "same-rule") +
            listOf(stamp.path, stamp.length.toString(), stamp.modified.toString()) + values)
        assertEquals(plan(first.values), plan(second.values))
    }
    @Test fun hostInstallUpdateStillRehashes() {
        val file = apk(); val store = Store(); val paths = setOf(file.canonicalPath)
        collect(file, store, installedDigestScope(pkg, version, paths))
        assertEquals(1, collect(file, store, installedDigestScope(pkg, "3183:8.0.79:101", paths)).metrics.hashedFiles)
    }
    @Test fun differentHostPackageStillRehashes() {
        val file = apk(); val store = Store(); val paths = setOf(file.canonicalPath)
        collect(file, store, installedDigestScope(pkg, version, paths))
        assertEquals(1, collect(file, store, installedDigestScope("other.package", version, paths)).metrics.hashedFiles)
    }
    @Test fun changedInstallSetStillInvalidatesScope() {
        val file = apk(); val store = Store(); val path = file.canonicalPath
        collect(file, store, installedDigestScope(pkg, version, setOf(path)))
        assertEquals(1, collect(file, store, installedDigestScope(pkg, version, setOf(path, "extra-split.apk"))).metrics.hashedFiles)
    }
    @Test fun writableApkDoesNotGainReuseFromScopeSeparation() {
        val file = apk(); val store = Store(); val scope = installedDigestScope(pkg, version, setOf(file.canonicalPath))
        collect(file, store, scope)
        assertEquals(1, collect(file, store, scope, readonly = false).metrics.hashedFiles)
    }
}
