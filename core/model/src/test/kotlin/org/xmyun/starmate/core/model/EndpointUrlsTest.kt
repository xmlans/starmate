package org.xmyun.starmate.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointUrlsTest {

    @Test
    fun httpsIsAccepted() {
        assertTrue(EndpointUrls.isAcceptable("https://api.openai.com/v1"))
        assertTrue(EndpointUrls.isAcceptable("  https://api.anthropic.com  "))
        assertTrue(EndpointUrls.isAcceptable("HTTPS://Example.com:8443/v1"))
    }

    @Test
    fun plainHttpIsOnlyAcceptedForThisPhone() {
        assertTrue(EndpointUrls.isAcceptable("http://localhost:11434/v1"))
        assertTrue(EndpointUrls.isAcceptable("http://127.0.0.1:8080"))
        // 局域网和公网的明文地址都不行：key 会被同一个网络上的人看到
        assertFalse(EndpointUrls.isAcceptable("http://192.168.1.10:11434/v1"))
        assertFalse(EndpointUrls.isAcceptable("http://api.example.com/v1"))
    }

    @Test
    fun lookalikeLoopbackHostsAreRejected() {
        assertFalse(EndpointUrls.isAcceptable("http://127.0.0.1.evil.example/v1"))
        assertFalse(EndpointUrls.isAcceptable("http://localhost.evil.example/v1"))
        // @ 前面是用户名，真正的主机是后面那个
        assertFalse(EndpointUrls.isAcceptable("http://localhost@evil.example/v1"))
    }

    @Test
    fun otherSchemesAndGarbageAreRejected() {
        assertFalse(EndpointUrls.isAcceptable(""))
        assertFalse(EndpointUrls.isAcceptable("api.openai.com/v1"))
        assertFalse(EndpointUrls.isAcceptable("ftp://example.com"))
        assertFalse(EndpointUrls.isAcceptable("file:///sdcard/x"))
        assertFalse(EndpointUrls.isAcceptable("https://"))
        assertFalse(EndpointUrls.isAcceptable("https://exa mple.com"))
    }
}
