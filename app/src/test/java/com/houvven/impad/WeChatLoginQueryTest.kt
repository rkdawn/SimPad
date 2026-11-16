package com.houvven.impad

import org.junit.Assert.*
import org.junit.Test

/** Tests the actual DexKit query sent by AppRuleResolver; no Android native calls. */
class WeChatLoginQueryTest {
    private fun permitsObfuscatedOwner(anchor: String) {
        val query = wechatLoginQuery(anchor)
        assertTrue("Login query must not exclude a root-obfuscated owner such as fc1.h0", query.searchPackages.isNullOrEmpty())
        assertTrue("Login query must not exclude a root-obfuscated package", query.excludePackages.isNullOrEmpty())
    }
    @Test fun loginTraceQueryPermitsRootObfuscatedOwner() { permitsObfuscatedOwner("updateLoginAsExdevice") }
    @Test fun legacyButtonQueryPermitsRootObfuscatedOwner() { permitsObfuscatedOwner("loginAsOtherDeviceBtn") }
    @Test fun queryStillUsesMatcherAndEnumeratesAllCandidates() {
        val query = wechatLoginQuery("updateLoginAsExdevice")
        assertNotNull(query.matcher)
        assertFalse(query.findFirst)
    }
}
