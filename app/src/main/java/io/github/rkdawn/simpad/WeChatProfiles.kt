package io.github.rkdawn.simpad

/** Version-bound compatibility from complete Play host evidence.
 * These are explicit target configurations, not cache-count heuristics.
 * Unknown versions never inherit these obfuscated descriptors.
 */
internal data class WeChatHostVersion(val code: Long, val name: String) {
    companion object {
        fun fromInstalledIdentity(identity: String): WeChatHostVersion? {
            val fields = identity.split(':', limit = 3)
            if (fields.size != 3) return null
            return fields[0].toLongOrNull()?.let { WeChatHostVersion(it, fields[1]) }
        }
    }
}
internal data class WeChatVersionProfile(
    val id: String, val inner: String, val tablet: String, val login: String,
    val cache: String, val modelGate: String, val modelCache: String,
    val deviceHookRole: String = "inner",
    val history: HistoryLoginTarget? = null,
)
internal data class HistoryLoginTarget(val initializer: String, val buttonId: Int)
internal object WeChatProfiles {
    private val play3183 = WeChatVersionProfile(
        "play-8.0.79-3183", "Lcom/tencent/mm/ui/ok;->C(Lnw5/w0;)Z", "Lcom/tencent/mm/ui/ok;->B()Z",
        "Lfc1/h0;->a(Landroid/view/View;Landroidx/lifecycle/y;)V",
        "Lcom/tencent/mm/ui/ok;->f:Ljava/lang/Boolean;", "Lcom/tencent/mm/ui/j9;->a()Z",
        "Lkq/d0;->m:Ljava/lang/Boolean;",
    )
    // The result is g; v is only the TCL characteristics cache in the same decision.
    private val play3200 = WeChatVersionProfile(
        "play-8.0.80-3200", "Lcom/tencent/mm/ui/vk;->b(Lpw5/w0;)Z", "Lcom/tencent/mm/ui/vk;->C()Z",
        "Lwc1/h0;->a(Landroid/view/View;Landroidx/lifecycle/y;)V",
        "Lcom/tencent/mm/ui/vk;->g:Ljava/lang/Boolean;", "Lcom/tencent/mm/ui/n9;->a()Z",
        "Loq/d0;->m:Ljava/lang/Boolean;", deviceHookRole = "tablet",
        history = HistoryLoginTarget("Lcom/tencent/mm/plugin/account/ui/LoginHistoryUI;->initView()V", 0x7f0a4363),
    )
    // The selected tablet graph, login initializer and button ID match in the two Play builds.
    private val play3202 = play3200.copy(id = "play-8.0.80-3202")
    fun forVersion(version: WeChatHostVersion?): WeChatVersionProfile? = when {
        version?.code == 3183L && version.name == "8.0.79" -> play3183
        version?.code == 3200L && version.name == "8.0.80" -> play3200
        version?.code == 3202L && version.name == "8.0.80" -> play3202
        else -> null
    }
}
