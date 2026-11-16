package com.houvven.impad

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** File/cache unit tests; readOnly is injected, not an Android permission simulation. */
class InstalledApkDigestCacheTest {
    private val scope = listOf("rule-v1", "module-1.7", "com.tencent.mm", "3183:8.0.79:100")
    private class Store {
        val values = linkedMapOf<String, String>()
        var writes = 0
        fun cache() = InstalledApkDigestCache({ values[it] }, { entries -> values.clear(); values.putAll(entries); writes++ })
    }
    private fun file(suffix: String = ".apk", content: String = "code-content"): File {
        val root = File(System.getProperty("java.io.tmpdir"), "apk-digest-tests").apply { mkdirs() }
        return File.createTempFile("code-", suffix, root).apply { writeText(content) }
    }
    private fun collect(store: Store, files: List<File>, installed: Set<String> = files.map { it.canonicalPath }.toSet(),
                        parts: List<String> = scope, readOnly: (FileStamp) -> Boolean = { true },
                        hasher: (FileStamp) -> CodeFileDigest = ::hashCodeFile): CodeDigestResult {
        val session = store.cache().begin(parts + installed.sorted())
        val result = collectCodeDigests(files.map { FileStamp.read(it.path) }, installed, session, readOnly, hasher)
        session.publish()
        return result
    }
    @Test fun firstCaptureReadsAndStoresOnce() {
        val store = Store(); val apk = file(); var reads = 0
        val result = collect(store, listOf(apk), hasher = { reads++; hashCodeFile(it) })
        assertEquals(1, reads); assertEquals(1, store.writes); assertEquals(1, result.metrics.hashedFiles)
        assertEquals(apk.length(), result.metrics.hashedBytes); assertEquals(0, result.metrics.reusedFiles)
    }
    @Test fun warmCaptureDoesNotReadContentOrWriteCache() {
        val store = Store(); val apk = file(); val cold = collect(store, listOf(apk)); val writes = store.writes
        val warm = collect(store, listOf(apk), hasher = { fail("Warm installed APK must not be reopened"); hashCodeFile(it) })
        assertEquals(cold.values, warm.values); assertEquals(writes, store.writes)
        assertEquals(0L, warm.metrics.hashedBytes); assertEquals(0, warm.metrics.hashedFiles)
        assertEquals(1, warm.metrics.reusedFiles); assertEquals(apk.length(), warm.metrics.reusedBytes)
    }
    @Test fun cachedAndFullContentFingerprintsAgree() {
        val store = Store(); val apks = listOf(file(), file(content = "split"))
        val cold = collect(store, apks); val warm = collect(store, apks)
        val stamps = apks.map { FileStamp.read(it.path) }
        val full = collectCodeDigests(stamps, emptySet(), null)
        fun fp(values: List<String>) = cacheFingerprint(scope + stamps.zip(values).flatMap { (s, d) -> listOf(s.path, s.length.toString(), s.modified.toString(), d) })
        assertEquals(fp(full.values), fp(cold.values)); assertEquals(fp(full.values), fp(warm.values))
    }
    @Test fun writableInstalledApkAlwaysUsesFullHash() {
        val store = Store(); val apk = file(); collect(store, listOf(apk))
        val result = collect(store, listOf(apk), readOnly = { false })
        assertEquals(1, result.metrics.hashedFiles); assertEquals(0, result.metrics.reusedFiles)
    }
    @Test fun realWritableFileIsRejectedByDefaultPolicy() {
        val store = Store(); val apk = file(); assertTrue(apk.canWrite())
        val session = store.cache().begin(scope)
        val result = collectCodeDigests(listOf(FileStamp.read(apk.path)), setOf(apk.canonicalPath), session)
        session.publish(); assertEquals(1, result.metrics.hashedFiles); assertEquals(0, store.writes)
    }
    @Test fun dexEvenInInstalledSetIsNotCached() {
        val store = Store(); val dex = file(".dex")
        repeat(2) { assertEquals(1, collect(store, listOf(dex)).metrics.hashedFiles) }
        assertEquals(0, store.writes)
    }
    @Test fun readonlyPatchOutsideInstalledSetIsAlwaysHashed() {
        val store = Store(); val apk = file(); val patch = file(".apk", "patch")
        collect(store, listOf(apk, patch), setOf(apk.canonicalPath))
        val warm = collect(store, listOf(apk, patch), setOf(apk.canonicalPath))
        assertEquals(1, warm.metrics.hashedFiles); assertEquals(1, warm.metrics.reusedFiles)
        assertEquals(patch.length(), warm.metrics.hashedBytes)
    }
    @Test fun mixedInputsReuseApkButReadPatch() {
        val store = Store(); val apk = file(); val dex = file(".dex", "runtime-dex")
        collect(store, listOf(apk, dex), setOf(apk.canonicalPath))
        val result = collect(store, listOf(apk, dex), setOf(apk.canonicalPath))
        assertEquals(CodeDigestMetrics(1, dex.length(), 1, apk.length()), result.metrics)
    }
    @Test fun lengthChangeRehashesTheFile() {
        val store = Store(); val apk = file(); val first = collect(store, listOf(apk)); apk.appendText("-update")
        val next = collect(store, listOf(apk)); assertEquals(1, next.metrics.hashedFiles); assertNotEquals(first.values, next.values)
    }
    @Test fun modifiedTimeChangeRehashesUnchangedContent() {
        val store = Store(); val apk = file(); val first = collect(store, listOf(apk))
        assertTrue(apk.setLastModified(apk.lastModified() + 6000))
        val next = collect(store, listOf(apk)); assertEquals(1, next.metrics.hashedFiles); assertEquals(first.values, next.values)
    }
    @Test fun differentPathDoesNotReuseDigestSlot() {
        val store = Store(); val apk = file(); collect(store, listOf(apk)); val second = file(content = apk.readText())
        assertEquals(1, collect(store, listOf(second)).metrics.hashedFiles)
    }
    private fun scopeChange(index: Int, replacement: String) {
        val store = Store(); val apk = file(); collect(store, listOf(apk))
        val changed = scope.toMutableList().apply { this[index] = replacement }
        assertEquals(1, collect(store, listOf(apk), parts = changed).metrics.hashedFiles)
    }
    @Test fun arbitraryCallerScopePrefixChangeRehashes() { scopeChange(0, "rule-v2") }
    @Test fun arbitraryCallerScopeValueChangeRehashes() { scopeChange(1, "module-1.8") }
    @Test fun targetPackageChangeRehashes() { scopeChange(2, "com.tencent.mm.other") }
    @Test fun installedVersionChangeRehashes() { scopeChange(3, "3184:8.0.80:101") }
    @Test fun installTimeChangeRehashesSameVersion() { scopeChange(3, "3183:8.0.79:101") }
    @Test fun splitSetChangeInvalidatesApkScope() {
        val store = Store(); val base = file(); collect(store, listOf(base)); val split = file(content = "new-split")
        val next = collect(store, listOf(base, split)); assertEquals(2, next.metrics.hashedFiles); assertEquals(2, store.values.size)
    }
    @Test fun corruptedShortRecordFallsBackToFullHash() {
        val store = Store(); val apk = file(); collect(store, listOf(apk)); store.values[store.values.keys.first()] = "broken"
        assertEquals(1, collect(store, listOf(apk)).metrics.hashedFiles)
    }
    @Test fun malformedDigestFallsBackToFullHash() {
        val store = Store(); val apk = file(); collect(store, listOf(apk)); val key = store.values.keys.first()
        store.values[key] = store.values.getValue(key).take(65) + "z".repeat(64)
        assertEquals(1, collect(store, listOf(apk)).metrics.hashedFiles)
    }
    @Test fun invalidDelimiterFallsBackToFullHash() {
        val store = Store(); val apk = file(); collect(store, listOf(apk)); val key = store.values.keys.first()
        store.values[key] = store.values.getValue(key).replace(':', '|')
        assertEquals(1, collect(store, listOf(apk)).metrics.hashedFiles)
    }
    @Test fun cacheReadExceptionFallsBackWithoutDoubleHash() {
        val apk = file(); var reads = 0
        val cache = InstalledApkDigestCache({ throw IllegalStateException("prefs") }, {})
        val session = cache.begin(scope)
        val result = collectCodeDigests(listOf(FileStamp.read(apk.path)), setOf(apk.canonicalPath), session, { true }, { reads++; hashCodeFile(it) })
        assertEquals(1, reads); assertEquals(1, result.metrics.hashedFiles)
    }
    @Test fun cacheWriteFailureDoesNotChangeDigestOutcome() {
        val apk = file(); val cache = InstalledApkDigestCache({ null }, { throw IllegalStateException("disk") })
        val session = cache.begin(scope)
        val result = collectCodeDigests(listOf(FileStamp.read(apk.path)), setOf(apk.canonicalPath), session, { true })
        session.publish(); assertEquals(hashCodeFile(FileStamp.read(apk.path)).value, result.values.single())
    }
    @Test fun inputChangeDuringContentReadRejectsCaptureAndDoesNotSave() {
        val store = Store(); val apk = file(); val session = store.cache().begin(scope); val stamp = FileStamp.read(apk.path)
        try {
            collectCodeDigests(listOf(stamp), setOf(apk.canonicalPath), session, { true }, { s -> hashCodeFile(s).also { apk.appendText("changed") } })
            fail("Changed input must not be accepted")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, store.writes)
    }
    @Test fun inputChangeDuringCachedReadRejectsCapture() {
        val store = Store(); val apk = file(); collect(store, listOf(apk)); val stamp = FileStamp.read(apk.path)
        val session = InstalledApkDigestCache({ key -> store.values[key].also { apk.appendText("changed") } }, {}).begin(scope + listOf(apk.canonicalPath))
        try { collectCodeDigests(listOf(stamp), setOf(apk.canonicalPath), session, { true }); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun contentReadFailureKeepsOriginalExceptionAndNoRetry() {
        val store = Store(); val apk = file(); val expected = IOException("source"); var reads = 0
        try { collect(store, listOf(apk), hasher = { reads++; throw expected }); fail() }
        catch (error: IOException) { assertSame(expected, error) }
        assertEquals(1, reads); assertEquals(0, store.writes)
    }
    @Test fun invalidHasherResultIsRejected() {
        val store = Store(); val apk = file()
        try { collect(store, listOf(apk), hasher = { CodeFileDigest("invalid", 1) }); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(0, store.writes)
    }
    @Test fun cachedDigestDoesNotBypassActualFileStampRecheck() {
        val store = Store(); val apk = file(); collect(store, listOf(apk)); val before = FileStamp.read(apk.path)
        apk.appendText("different")
        try { collectCodeDigests(listOf(before), setOf(apk.canonicalPath), store.cache().begin(scope + listOf(apk.canonicalPath)), { true }); fail() }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun successfulPublicationIsIdempotent() {
        val store = Store(); val apk = file(); val session = store.cache().begin(scope)
        session.remember(FileStamp.read(apk.path), hashCodeFile(FileStamp.read(apk.path)).value)
        session.publish(); session.publish(); assertEquals(1, store.writes)
    }
    @Test fun shaFormatIsStrictAndBounded() {
        assertTrue(isSha256("a".repeat(64))); assertTrue(isSha256("0".repeat(64)))
        assertFalse(isSha256("a".repeat(63))); assertFalse(isSha256("A".repeat(64))); assertFalse(isSha256("g".repeat(64)))
    }
}
