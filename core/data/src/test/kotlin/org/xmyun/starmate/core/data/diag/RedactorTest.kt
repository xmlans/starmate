package org.xmyun.starmate.core.data.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun skKeyMasked() {
        assertEquals("sk-a***", Redactor.redact("sk-abcdefgh12345678"))
        assertEquals("key is sk-a*** ok", Redactor.redact("key is sk-abcdefgh12345678 ok"))
    }

    @Test
    fun shortSkPrefixUntouched() {
        assertEquals("sk-abc", Redactor.redact("sk-abc"))
    }

    @Test
    fun bearerTokenMasked() {
        val out = Redactor.redact("Authorization: Bearer abcdef123456")
        assertFalse(out.contains("abcdef123456"))
        assertEquals("Authorization: Bearer abcd***", out)
    }

    @Test
    fun queryKeyMaskedOtherParamsKept() {
        val out = Redactor.redact("https://x/y?key=SECRET123&z=1")
        assertFalse(out.contains("SECRET123"))
        assertEquals("https://x/y?key=SECR***&z=1", out)
    }

    @Test
    fun tavilyApiKeyMasked() {
        val out = Redactor.redact("https://api.example.com/search?q=cat&tavilyApiKey=tvly-SECRETVALUE&z=1")
        assertFalse(out.contains("SECRETVALUE"))
        assertTrue(out.contains("tavilyApiKey=tvly***"))
        assertTrue(out.contains("q=cat"))
        assertTrue(out.endsWith("&z=1"))
    }

    @Test
    fun otherQueryNamesMasked() {
        val out = Redactor.redact("u?api_key=AAAA1111&apikey=BBBB2222&token=CCCC3333")
        assertFalse(out.contains("1111"))
        assertFalse(out.contains("2222"))
        assertFalse(out.contains("3333"))
    }

    @Test
    fun apiKeyHeaderMasked() {
        val out = Redactor.redact("x-api-key: anthropic-secret-xyz")
        assertEquals("x-api-key: anth***", out)
    }

    @Test
    fun jsonFieldMasked() {
        val out = Redactor.redact("""{"api_key":"abcdefghij","model":"gpt"}""")
        assertFalse(out.contains("abcdefghij"))
        assertTrue(out.contains("\"model\":\"gpt\""))
    }

    @Test
    fun plainTextUnchanged() {
        val text = "今天天气不错，聊了 3 轮，model=gpt-4o"
        assertEquals(text, Redactor.redact(text))
    }
}
