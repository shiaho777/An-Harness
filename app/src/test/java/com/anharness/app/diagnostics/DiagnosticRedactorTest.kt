package com.anharness.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-diag-redaction] Redaction rule contract. Over-masking is acceptable;
 * leaking a credential is not — each rule has a leak-prevention test.
 */
class DiagnosticRedactorTest {

    private fun redacted(s: String) = DiagnosticRedactor.redact(s)

    @Test
    fun `bearer token masked keeping scheme`() {
        val out = redacted("Authorization: Bearer abc123DEF456xyz")
        assertTrue(out.contains("Bearer"))
        assertFalse(out.contains("abc123DEF456xyz"))
    }

    @Test
    fun `api key assignments masked in log and json shapes`() {
        assertFalse(redacted("api_key=supersecretvalue123").contains("supersecretvalue123"))
        assertFalse(redacted("\"apiKey\": \"supersecretvalue123\"").contains("supersecretvalue123"))
        assertFalse(redacted("password: hunter22long").contains("hunter22long"))
        assertFalse(redacted("refresh_token=rt_abcdef123456").contains("rt_abcdef123456"))
    }

    @Test
    fun `vendor key shapes masked`() {
        assertFalse(redacted("key sk-abc123def456ghi789").contains("sk-abc123def456ghi789"))
        assertFalse(redacted("sk-ant-api03-abcdef123456789").contains("abcdef123456789"))
        assertFalse(redacted("ghp_abcdefghijklmnopqrstuvwxyz123456").contains("ghp_abcdef"))
        assertFalse(redacted("AIzaSyA1234567890abcdefghijklmnopqrstuv").contains("AIzaSyA"))
    }

    @Test
    fun `jwt masked`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc123signature"
        assertFalse(redacted("token=$jwt").contains("eyJhbGci"))
    }

    @Test
    fun `url userinfo masked keeping host`() {
        val out = redacted("connecting to https://alice:s3cretpass@build.example.com/repo")
        assertFalse(out.contains("s3cretpass"))
        assertTrue(out.contains("build.example.com"))
    }

    @Test
    fun `cookie headers masked`() {
        val out = redacted("Set-Cookie: session=abc123; Path=/")
        assertFalse(out.contains("abc123"))
    }

    @Test
    fun `emails masked`() {
        assertFalse(redacted("user alice@example.com reported").contains("alice@example.com"))
    }

    @Test
    fun `phone-like digit runs masked but short numbers kept`() {
        assertFalse(redacted("call +86 138 1234 5678 now").contains("138 1234 5678"))
        assertTrue(redacted("port 8080 count 42 line 123").contains("8080"))
        assertTrue(redacted("exit code 124").contains("124"))
    }

    @Test
    fun `home paths masked keeping structure`() {
        val out = redacted("at /Users/johndoe/Library/logs/app.log")
        assertFalse(out.contains("johndoe"))
        assertTrue(out.contains("/Users/"))
        assertTrue(out.contains("app.log"))
    }

    @Test
    fun `plain diagnostic text survives`() {
        val text = "AgentLoop turn 3 finished, tool shell_execute exit 0, 1523ms"
        assertEquals(text, redacted(text))
    }

    @Test
    fun `redaction is idempotent`() {
        val once = redacted("api_key=supersecretvalue123 user a@b.com")
        assertEquals(once, redacted(once))
    }

    @Test
    fun `forbidden file names excluded`() {
        assertTrue(DiagnosticRedactor.isForbiddenFile("provider_secrets.xml"))
        assertTrue(DiagnosticRedactor.isForbiddenFile("remote_host_secrets.xml"))
        assertTrue(DiagnosticRedactor.isForbiddenFile("id_rsa"))
        assertTrue(DiagnosticRedactor.isForbiddenFile("server.key"))
        assertTrue(DiagnosticRedactor.isForbiddenFile("cert.pem"))
        assertTrue(DiagnosticRedactor.isForbiddenFile("provider-customization.properties"))
        assertTrue(DiagnosticRedactor.isForbiddenFile("env_cookies_123.sh"))
        assertFalse(DiagnosticRedactor.isForbiddenFile("crash-2026.log"))
        assertFalse(DiagnosticRedactor.isForbiddenFile("app.log"))
    }
}