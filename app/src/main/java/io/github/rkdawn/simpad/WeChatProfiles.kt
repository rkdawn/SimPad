package io.github.rkdawn.simpad

/** Version-bound compatibility from the user's complete Play 3183 candidate log.
 * This is an explicit, complete target configuration, not a cache-count heuristic.
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
)
internal object WeChatProfiles {
    private val play3183 = WeChatVersionProfile(
        "play-8.0.79-3183", "Lcom/tencent/mm/ui/ok;->C(Lnw5/w0;)Z", "Lcom/tencent/mm/ui/ok;->B()Z",
        "Lfc1/h0;->a(Landroid/view/View;Landroidx/lifecycle/y;)V",
        "Lcom/tencent/mm/ui/ok;->f:Ljava/lang/Boolean;", "Lcom/tencent/mm/ui/j9;->a()Z",
        "Lkq/d0;->m:Ljava/lang/Boolean;",
    )
    fun forVersion(version: WeChatHostVersion?): WeChatVersionProfile? =
        play3183.takeIf { version?.code == 3183L && version.name == "8.0.79" }
}
