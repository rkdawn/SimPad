package io.github.rkdawn.simpad

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
    fun login(call: HookCall, includeInvisible: Boolean = false): Any? {
        val result = call.proceed()
        val view = call.arguments.firstOrNull()?.takeIf(isView) ?: return result
        reveal(view, includeInvisible)
        return result
    }

    fun history(call: HookCall, findButton: (Any?) -> Any?, hasClick: (Any) -> Boolean,
                diagnostic: (String) -> Unit): Any? {
        val result = call.proceed() // Let the host and the rest of the interceptor chain finish binding the button.
        val button = findButton(call.receiver)
        if (button != null && hasClick(button)) {
            reveal(button, includeInvisible = true)
            bestEffortDiagnostic { diagnostic("WECHAT_LOGIN_HISTORY nativeButton=true visibility=${visibility(button)}") }
        } else {
            bestEffortDiagnostic { diagnostic("WECHAT_LOGIN_HISTORY nativeButton=false") }
        }
        return result
    }

    private fun reveal(view: Any, includeInvisible: Boolean) {
        val current = visibility(view)
        if (current == View.GONE || (includeInvisible && current == View.INVISIBLE)) show(view)
    }
}
