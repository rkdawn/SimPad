package io.github.rkdawn.simpad

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Retired no-cache inner contract retained only for historical regression comparison; never packaged. */
internal object LegacyWeChatInnerContracts {
    fun inner(method: Method, cache: Field, monitor: Field?): HookCallback {
        require(cache.declaringClass == method.declaringClass && Modifier.isStatic(cache.modifiers) &&
            !Modifier.isFinal(cache.modifiers) && cache.type == java.lang.Boolean::class.java)
        require(monitor == null || (monitor.declaringClass == method.declaringClass &&
            Modifier.isStatic(monitor.modifiers) && monitor.type == Any::class.java))
        cache.isAccessible = true
        monitor?.isAccessible = true
        return HookCallback { call -> call.proceed(); true }
    }
}
