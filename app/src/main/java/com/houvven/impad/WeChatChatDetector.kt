package com.houvven.impad

import android.annotation.TargetApi
import android.os.Build
import java.lang.StackWalker
import java.util.function.Function
import java.util.function.Predicate
import java.util.stream.Stream

/** Cache only API availability, never a context-sensitive chat result or a thread's stack. */
internal class ChatStackClassifier(
    modernSupported: Boolean,
    private val modern: () -> Boolean,
    private val legacy: () -> Boolean,
) {
    @Volatile private var useModern = modernSupported

    fun isChat(): Boolean {
        if (useModern) {
            try { return modern() }
            catch (_: Exception) { useModern = false }
            catch (_: LinkageError) { useModern = false }
        }
        // Failure of the real fallback is handled by the existing hook transaction, not forged as false.
        return legacy()
    }
}

internal object WeChatChatDetector {
    private val classifier = ChatStackClassifier(
        Build.VERSION.SDK_INT >= 34,
        { ModernWeChatChatStack.isChat() },
        { isWeChatChatCall(Thread.currentThread().stackTrace) },
    )
    fun isChat(): Boolean = classifier.isChat()
}

/** Isolate API-34 types from the legacy path. No reflection, hidden API or class-name guessing. */
@TargetApi(34)
internal object ModernWeChatChatStack {
    // Show reflection/hidden frames so package matching does not silently lose a chat caller.
    private val walker = StackWalker.getInstance(StackWalker.Option.SHOW_HIDDEN_FRAMES)
    private val chatFrame = Predicate<StackWalker.StackFrame> { isWeChatChatClassName(it.className) }
    private val matchFrames = Function<Stream<StackWalker.StackFrame>, Boolean> { it.anyMatch(chatFrame) }

    fun isChat(): Boolean = walker.walk(matchFrames)
}
