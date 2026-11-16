package io.github.rkdawn.simpad

import android.content.SharedPreferences

/** This preference file belongs only to this module; host preferences are never cleared. */
internal class PlanCache(private val prefs: SharedPreferences) {
    fun read(app: AppTarget, fingerprint: String): PlanDescriptor? {
        if (prefs.getString("fingerprint", null) != fingerprint) return null
        val values = prefs.all.filterKeys { it != "fingerprint" }.mapValues { (_, value) ->
            require(value is String) { "Corrupt personal method cache" }
            value
        }
        return PlanDescriptor.decode(app, values)
    }
    fun write(fingerprint: String, plan: PlanDescriptor) {
        val editor = prefs.edit().clear().putString("fingerprint", fingerprint)
        plan.encode().forEach { (key, value) -> editor.putString(key, value) }
        check(editor.commit()) { "Personal method cache could not be saved" }
    }
    fun invalidate() { prefs.edit().clear().apply() }
}
