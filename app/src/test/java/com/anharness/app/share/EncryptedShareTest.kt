package com.anharness.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-share-e2e] E2E share crypto contract. Pure JVM (javax.crypto).
 */
class EncryptedShareTest {

    @Test
    fun `round-trip recovers plaintext including unicode`() {
        val text = "User: 你好\nAssistant: Hello! 🔐 multi-line\n\twith tabs"
        val link = EncryptedShare.encrypt(text)
        assertEquals(text, EncryptedShare.decrypt(link.key, link.payload))
    }

    @Test
    fun `fresh key and iv per encryption`() {
        val a = EncryptedShare.encrypt("same text")
        val b = EncryptedShare.encrypt("same text")
        assertNotEquals(a.key, b.key)
        assertNotEquals(a.payload, b.payload)
    }

    @Test
    fun `wrong key fails to null`() {
        val link = EncryptedShare.encrypt("secret")
        val other = EncryptedShare.encrypt("secret")
        assertNull(EncryptedShare.decrypt(other.key, link.payload))
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val link = EncryptedShare.encrypt("secret message")
        val blob = EncryptedShare.b64decode(link.payload)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        val tampered = EncryptedShare.b64encode(blob)
        assertNull(EncryptedShare.decrypt(link.key, tampered))
    }

    @Test
    fun `truncated payload fails`() {
        val link = EncryptedShare.encrypt("secret")
        assertNull(EncryptedShare.decrypt(link.key, link.payload.take(10)))
        assertNull(EncryptedShare.decrypt(link.key, ""))
    }

    @Test
    fun `malformed key fails`() {
        val link = EncryptedShare.encrypt("secret")
        assertNull(EncryptedShare.decrypt("not-base64!!", link.payload))
        assertNull(EncryptedShare.decrypt(EncryptedShare.b64encode(ByteArray(16)), link.payload))
    }

    @Test
    fun `fragment is url-safe and parseable`() {
        val link = EncryptedShare.encrypt("x".repeat(5000))
        val frag = link.fragment()
        assertFalse("no + in url-safe b64", frag.contains("+"))
        assertFalse("no / in url-safe b64", frag.contains("/"))
        // The k=/d= separators legitimately contain '='; the base64 VALUES
        // must be padding-free.
        assertFalse("no padding in key", link.key.contains("="))
        assertFalse("no padding in payload", link.payload.contains("="))
        val url = link.url("https://share.example/v/")
        assertTrue(url.startsWith("https://share.example/v#k="))
        val parsed = EncryptedShare.parseFragment(url.substringAfter("#"))!!
        assertEquals("x".repeat(5000), EncryptedShare.decrypt(parsed.key, parsed.payload))
    }

    @Test
    fun `parseFragment tolerates leading hash and rejects missing parts`() {
        val link = EncryptedShare.encrypt("hi")
        assertEquals(link, EncryptedShare.parseFragment("#" + link.fragment()))
        assertNull(EncryptedShare.parseFragment("k=abc"))
        assertNull(EncryptedShare.parseFragment("d=abc"))
        assertNull(EncryptedShare.parseFragment(""))
    }

    @Test
    fun `empty plaintext round-trips`() {
        val link = EncryptedShare.encrypt("")
        assertEquals("", EncryptedShare.decrypt(link.key, link.payload))
    }
}