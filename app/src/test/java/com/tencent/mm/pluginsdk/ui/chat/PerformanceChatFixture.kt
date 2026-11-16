package com.tencent.mm.pluginsdk.ui.chat

/** Test-only callers under the exact protected package; never packaged in the module. */
object PerformanceChatFixture {
    @JvmStatic fun <T> invoke(action: () -> T): T = action()
    @JvmStatic fun nested(depth: Int, action: () -> Pair<Boolean, Boolean>): Pair<Boolean, Boolean> =
        if (depth == 0) action() else nested(depth - 1, action)
}
