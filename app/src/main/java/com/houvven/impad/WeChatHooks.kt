package com.houvven.impad

import android.view.View

/** Formal three-point strategy: original C call + chat-aware result; login remains After. */
internal class WeChatCallbacks(
    private val stack: () -> Array<StackTraceElement> = { Thread.currentThread().stackTrace },
    private val isView: (Any) -> Boolean = { it is View },
    private val visibility: (Any) -> Int = { (it as View).visibility },
    private val show: (Any) -> Unit = { (it as View).visibility = View.VISIBLE },
    private val chatCall: () -> Boolean = { isWeChatChatCall(stack()) },
) {
    fun tablet(call: HookCall): Any {
        call.proceed()
        return !chatCall()
    }
    fun login(call: HookCall): Any? {
        val result = call.proceed()
        val view = call.arguments.firstOrNull()?.takeIf(isView) ?: return result
        if (visibility(view) == View.GONE) show(view)
        return result
    }
}
