package com.anharness.app.share

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [T-share-e2e] End-to-end encrypted session-share payloads (P5, dsh-Go
 * share parity).
 *
 * Model: the conversation is encrypted on the phone with a fresh random
 * AES-256-GCM key; ONLY the ciphertext goes to a share host. The key lives
 * in the URL FRAGMENT (`#k=...`) — fragments are never sent to servers, so
 * the host (and anyone watching it) cannot read the content. The recipient
 * decrypts locally in their browser/app.
 *
 * Wire format (all URL-safe base64, no padding):
 *   payload  = base64url( iv(12) || ciphertext+tag )
 *   link     = <viewerUrl>#k=<base64url(key)>&d=<payload>
 *
 * Pure JVM (javax.crypto + java.util.Base64) so the whole contract is
 * unit-testable. AAD binds the payload to the format version so a
 * tampered/truncated blob fails authentication instead of decoding garbage.
 */
object EncryptedShare {

    const val FORMAT_VERSION = "minis-share-v1"
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    data class ShareLink(val key: String, val payload: String) {
        /** Fragment string: k=<key>&d=<payload> (no leading #). */
        fun fragment(): String = "k=$key&d=$payload"
        fun url(viewerUrl: String): String = viewerUrl.trimEnd('/') + "#" + fragment()
    }

    /** Encrypt [plaintext] with a fresh random key. */
    fun encrypt(plaintext: String, random: SecureRandom = SecureRandom()): ShareLink {
        val key = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(FORMAT_VERSION.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = iv + ct
        return ShareLink(
            key = b64encode(key),
            payload = b64encode(blob),
        )
    }

    /**
     * Decrypt a payload with the fragment key. Returns null on ANY failure
     * (bad key, tampered ciphertext, wrong format) — never partial garbage.
     */
    fun decrypt(keyB64: String, payloadB64: String): String? = runCatching {
        val key = b64decode(keyB64)
        require(key.size == KEY_BYTES) { "bad key length" }
        val blob = b64decode(payloadB64)
        // iv + tag is the minimum (empty plaintext is legal).
        require(blob.size >= IV_BYTES + TAG_BITS / 8) { "payload too short" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ct = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(FORMAT_VERSION.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    /** Parse a `k=...&d=...` fragment (with or without leading #). */
    fun parseFragment(fragment: String): ShareLink? {
        val f = fragment.removePrefix("#")
        val parts = f.split("&").mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
        }.toMap()
        val k = parts["k"] ?: return null
        val d = parts["d"] ?: return null
        return ShareLink(k, d)
    }

    // URL-safe base64 without padding (fragment-safe, no +/= escaping).
    internal fun b64encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    internal fun b64decode(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s)
}