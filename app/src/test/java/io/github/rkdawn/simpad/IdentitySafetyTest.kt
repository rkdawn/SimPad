package io.github.rkdawn.simpad

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException

class IdentitySafetyTest {
    private val loader = javaClass.classLoader!!
    @Test fun identityCheckDoesNotSwallowVirtualMachineErrors() {
        val expected = OutOfMemoryError("identity fatal")
        val context = object : ContextWrapper(null) { override fun getClassLoader(): ClassLoader = throw expected }
        try { CodeIdentity("id", emptyList(), "none").stillCurrent(context, loader); fail("Fatal identity error must propagate") }
        catch (caught: OutOfMemoryError) { assertSame(expected, caught) }
    }
    @Test fun identityCheckDoesNotSwallowThreadDeath() {
        val expected = ThreadDeath()
        val context = object : ContextWrapper(null) { override fun getClassLoader(): ClassLoader = throw expected }
        try { CodeIdentity("id", emptyList(), "none").stillCurrent(context, loader); fail("Thread termination must propagate") }
        catch (caught: ThreadDeath) { assertSame(expected, caught) }
    }
    @Test fun ordinaryIdentityFailureReturnsFalse() {
        val context = object : ContextWrapper(null) { override fun getClassLoader(): ClassLoader = throw IllegalStateException("metadata unavailable") }
        assertFalse(CodeIdentity("id", emptyList(), "none").stillCurrent(context, loader))
    }
    @Test fun reflectedFatalIdentityErrorRemainsFatal() {
        val expected = OutOfMemoryError("reflected fatal")
        val context = object : ContextWrapper(null) { override fun getClassLoader(): ClassLoader = throw InvocationTargetException(expected) }
        try { CodeIdentity("id", emptyList(), "none").stillCurrent(context, loader); fail() }
        catch (caught: OutOfMemoryError) { assertSame(expected, caught) }
    }
    @Test fun reflectedOrdinaryIdentityErrorInvalidatesOnlyIdentity() {
        val context = object : ContextWrapper(null) { override fun getClassLoader(): ClassLoader = throw InvocationTargetException(IllegalStateException("metadata")) }
        assertFalse(CodeIdentity("id", emptyList(), "none").stillCurrent(context, loader))
    }
    private fun codeFile(): File {
        val root = File(System.getProperty("java.io.tmpdir"), "identity-safety").apply { mkdirs() }
        return File.createTempFile("code-", ".apk", root).apply { writeText("complete-code") }
    }
    @Test fun partialContentHashCannotBeAcceptedOrPublished() {
        val file = codeFile(); val stamp = FileStamp.read(file.path); var writes = 0
        val session = InstalledApkDigestCache({ null }, { writes++ }).begin(listOf("scope"))
        try {
            collectCodeDigests(listOf(stamp), setOf(stamp.path), session, { true }, { CodeFileDigest("0".repeat(64), stamp.length - 1) })
            session.publish(); fail("A partial content read must be rejected")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, writes)
    }
    @Test fun oversizedContentHashCannotBeAcceptedOrPublished() {
        val file = codeFile(); val stamp = FileStamp.read(file.path); var writes = 0
        val session = InstalledApkDigestCache({ null }, { writes++ }).begin(listOf("scope"))
        try {
            collectCodeDigests(listOf(stamp), setOf(stamp.path), session, { true }, { CodeFileDigest("0".repeat(64), stamp.length + 1) })
            session.publish(); fail("A read beyond the observed stamp must be rejected")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, writes)
    }
}
