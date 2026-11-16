package com.houvven.impad

import android.content.SharedPreferences

/** Stores only digests of immutable installed APKs; no host/account state. */
internal class InstalledApkDigestCache(
    private val load: (String) -> String?,
    private val save: (Map<String, String>) -> Unit,
) {
    constructor(prefs: SharedPreferences) : this(
        { key -> prefs.getString(key, null) },
        { values ->
            val editor = prefs.edit().clear()
            values.forEach { (key, value) -> editor.putString(key, value) }
            editor.apply()
        },
    )

    fun begin(scope: List<String>): Session = Session(cacheFingerprint(listOf("installed-apk-digest-v1") + scope), load, save)

    internal class Session(
        private val scope: String,
        private val load: (String) -> String?,
        private val save: (Map<String, String>) -> Unit,
    ) {
        private val records = linkedMapOf<String, String>()
        private var changed = false
        private fun slot(stamp: FileStamp) = cacheFingerprint(listOf(stamp.path))
        private fun signature(stamp: FileStamp) = cacheFingerprint(listOf(scope, stamp.path, stamp.length.toString(), stamp.modified.toString()))

        fun cached(stamp: FileStamp): String? {
            val key = slot(stamp)
            val record = try { load(key) } catch (_: Exception) { null } ?: return null
            if (record.length != 129 || record[64] != ':' || record.substring(0, 64) != signature(stamp)) return null
            val digest = record.substring(65)
            if (!isSha256(digest)) return null
            records[key] = record
            return digest
        }

        fun remember(stamp: FileStamp, digest: String) {
            require(isSha256(digest)) { "Invalid APK digest" }
            records[slot(stamp)] = "${signature(stamp)}:$digest"
            changed = true
        }

        /** Called only after capture's file and loader checks succeed. Hits do not write. */
        fun publish() {
            if (!changed) return
            try {
                save(records.toMap())
                changed = false
            } catch (_: Exception) { /* Optional optimization; retry next startup. */ }
        }
    }
}

internal fun isSha256(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
