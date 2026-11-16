package io.github.rkdawn.simpad

import android.content.Context
import android.os.Build
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/** Metadata errors invalidate an identity, but fatal VM/thread errors must remain fatal. */
internal inline fun nonFatalIdentityCheck(check: () -> Boolean): Boolean = try { check() } catch (error: Exception) {
    val target = (error as? InvocationTargetException)?.targetException
    if (target is VirtualMachineError || target is ThreadDeath) throw target
    false
}

internal data class FileStamp(val path: String, val length: Long, val modified: Long) {
    companion object {
        fun read(path: String): FileStamp {
            val file = File(path).canonicalFile
            require(file.isFile && file.canRead() && file.length() > 0) { "Unreadable startup code file: ${file.name}" }
            return FileStamp(file.path, file.length(), file.lastModified())
        }
    }
    fun stillCurrent(): Boolean = nonFatalIdentityCheck { read(path) == this }
}

/** Preserve file identities; reuse only unchanged, read-only installed APK digests. */
internal data class CodeIdentity(val fingerprint: String, val files: List<FileStamp>, val version: String) {
    fun stillCurrent(context: Context, loader: ClassLoader): Boolean = nonFatalIdentityCheck {
        context.classLoader === loader && version == installedVersion(context) &&
            startupPaths(context, loader).map(FileStamp::read) == files
    }

    companion object {
        fun capture(context: Context, loader: ClassLoader, apkCache: InstalledApkDigestCache? = null,
                    onMetrics: (CodeDigestMetrics) -> Unit = {}): CodeIdentity {
            val version = installedVersion(context)
            val files = startupPaths(context, loader).map(FileStamp::read)
            val scope = methodIdentityScope(context.packageName, version)
            val installed = if (apkCache == null) emptySet() else
                (listOf(requireNotNull(context.applicationInfo.sourceDir)) + context.applicationInfo.splitSourceDirs.orEmpty())
                    .map { File(it).canonicalPath }.toSet()
            val session = apkCache?.begin(installedDigestScope(context.packageName, version, installed))
            val result = collectCodeDigests(files, installed, session)
            val parts = scope + files.zip(result.values).flatMap { (file, digest) ->
                listOf(file.path, file.length.toString(), file.modified.toString(), digest)
            }
            val identity = CodeIdentity(cacheFingerprint(parts), files, version)
            check(identity.stillCurrent(context, loader)) { "Startup inputs changed; restart required" }
            session?.publish()
            onMetrics(result.metrics)
            return identity
        }
    }
}

/** Selection changes invalidate plans; display-only module versions do not change host code. */
internal fun methodIdentityScope(packageName: String, installedVersion: String, revision: String = RULE_REVISION): List<String> =
    listOf("simpad-code-plan-v1", revision, packageName, installedVersion)

/** Keep a fresh content check on module upgrades; rule-only changes do not change file bytes. */
internal fun installedDigestScope(packageName: String, installedVersion: String, installedPaths: Collection<String>,
                                  moduleVersion: String = BuildConfig.VERSION_NAME): List<String> =
    listOf("simpad-apk-content-v1", moduleVersion, packageName, installedVersion) + installedPaths.sorted()

internal data class CodeFileDigest(val value: String, val bytesRead: Long)
internal data class CodeDigestMetrics(
    val hashedFiles: Int = 0, val hashedBytes: Long = 0,
    val reusedFiles: Int = 0, val reusedBytes: Long = 0,
)
internal data class CodeDigestResult(val values: List<String>, val metrics: CodeDigestMetrics)

/** The seam keeps eligibility, content reads and change detection directly testable. */
internal fun collectCodeDigests(
    files: List<FileStamp>, installedApks: Set<String>, session: InstalledApkDigestCache.Session?,
    readOnly: (FileStamp) -> Boolean = { !File(it.path).canWrite() },
    hasher: (FileStamp) -> CodeFileDigest = ::hashCodeFile,
): CodeDigestResult {
    var hashedFiles = 0
    var hashedBytes = 0L
    var reusedFiles = 0
    var reusedBytes = 0L
    val values = files.map { stamp ->
        val eligible = session != null && stamp.path in installedApks && stamp.path.endsWith(".apk", true) && readOnly(stamp)
        val cached = if (eligible) session!!.cached(stamp) else null
        val digest = if (cached != null) {
            reusedFiles++
            reusedBytes += stamp.length
            cached
        } else {
            val actual = hasher(stamp)
            require(isSha256(actual.value) && actual.bytesRead == stamp.length) { "Incomplete or invalid code digest" }
            hashedFiles++
            hashedBytes += actual.bytesRead
            if (eligible) session!!.remember(stamp, actual.value)
            actual.value
        }
        require(stamp.stillCurrent()) { "Startup code changed during fingerprinting" }
        digest
    }
    return CodeDigestResult(values, CodeDigestMetrics(hashedFiles, hashedBytes, reusedFiles, reusedBytes))
}

internal fun hashCodeFile(stamp: FileStamp): CodeFileDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    File(stamp.path).inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            bytes += count
        }
    }
    return CodeFileDigest(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, bytes)
}

private fun installedVersion(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    return "$code:${info.versionName}:${info.lastUpdateTime}"
}

private fun startupPaths(context: Context, readyLoader: ClassLoader): List<String> {
    val paths = linkedSetOf<String>()
    paths += requireNotNull(context.applicationInfo.sourceDir)
    context.applicationInfo.splitSourceDirs.orEmpty().forEach { paths += it }
    val seen = Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())
    var loader: ClassLoader? = readyLoader
    while (loader != null && seen.add(loader)) {
        require(!loader.javaClass.name.contains("InMemoryDexClassLoader")) { "In-memory code identity unsupported; fail closed" }
        val pathList = fieldInHierarchy(loader.javaClass, "pathList")?.get(loader)
        if (pathList != null) {
            val elements = requireNotNull(fieldInHierarchy(pathList.javaClass, "dexElements")?.get(pathList) as? Array<*>)
            elements.filterNotNull().forEach { element ->
                val dexFile = fieldInHierarchy(element.javaClass, "dexFile")?.get(element)
                if (dexFile != null) {
                    val name = dexFile.javaClass.getMethod("getName").invoke(dexFile) as? String
                    require(!name.isNullOrBlank()) { "Missing active DEX path" }
                    paths += name
                }
            }
        }
        loader = loader.parent
    }
    return paths.map { File(it).canonicalPath }.distinct()
}

internal fun fieldInHierarchy(type: Class<*>, name: String): Field? {
    var current: Class<*>? = type
    while (current != null) {
        try { return current.getDeclaredField(name).apply { isAccessible = true } }
        catch (_: NoSuchFieldException) { current = current.superclass }
    }
    return null
}
