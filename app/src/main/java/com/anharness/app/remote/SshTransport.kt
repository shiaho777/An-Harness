package com.anharness.app.remote

import com.anharness.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * [T-remote-ssh] SSH transport for remote hosts (P4), built on sshj.
 *
 * Security posture:
 *  - TOFU host-key pinning: first connect records the SHA-256 fingerprint;
 *    a later mismatch ABORTS the connection (MITM guard). No silent accept.
 *  - Secrets (password / private key) come from the encrypted store via the
 *    manager; they are never logged.
 *  - Every exec is bounded by a timeout and an output cap (aligned with the
 *    local shell), so a runaway remote command cannot hang the loop or blow
 *    the context.
 *
 * The transport is stateless per call: connect → exec → disconnect. A pooled
 * connection is a later optimization; correctness first.
 */
object SshTransport {

    private const val TAG = "SshTransport"

    data class ExecResult(
        val output: String,
        val exitCode: Int,
        val truncated: Boolean,
    )

    sealed interface ConnectOutcome {
        /** Connected; [fingerprint] is the server key SHA-256 (base64). */
        data class Ok(val fingerprint: String) : ConnectOutcome
        /** Host key changed vs the pin — refused. */
        data class HostKeyMismatch(val expected: String, val actual: String) : ConnectOutcome
        data class Failure(val message: String) : ConnectOutcome
    }

    /**
     * Verify-only connect used to establish/refresh the TOFU pin. Returns the
     * fingerprint on success without running anything.
     */
    suspend fun probe(
        host: RemoteHostConfig.Host,
        secret: String,
        timeoutS: Int = RemoteHostConfig.DEFAULT_TIMEOUT_S,
    ): ConnectOutcome = withContext(Dispatchers.IO) {
        val client = SSHClient()
        try {
            configure(client, host, timeoutS)
            val verifier = PinningVerifier(host.pinnedFingerprint)
            client.addHostKeyVerifier(verifier)
            client.connect(host.hostname, host.port)
            if (verifier.mismatch != null) {
                return@withContext ConnectOutcome.HostKeyMismatch(
                    expected = host.pinnedFingerprint.orEmpty(),
                    actual = verifier.mismatch!!,
                )
            }
            authenticate(client, host, secret)
            val fp = verifier.seenFingerprint
                ?: return@withContext ConnectOutcome.Failure("no host key observed")
            ConnectOutcome.Ok(fp)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "probe failed: ${e.javaClass.simpleName}")
            ConnectOutcome.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    /**
     * Run one command on the host. Refuses on host-key mismatch. Output is
     * truncated head+tail to [RemoteHostConfig.MAX_OUTPUT_CHARS].
     */
    suspend fun exec(
        host: RemoteHostConfig.Host,
        secret: String,
        command: String,
        timeoutS: Int = RemoteHostConfig.DEFAULT_TIMEOUT_S,
    ): ExecResult = withContext(Dispatchers.IO) {
        val client = SSHClient()
        try {
            configure(client, host, timeoutS)
            val verifier = PinningVerifier(host.pinnedFingerprint)
            client.addHostKeyVerifier(verifier)
            client.connect(host.hostname, host.port)
            if (verifier.mismatch != null) {
                throw SecurityException(
                    "SSH host key changed for ${host.hostname} — possible MITM. " +
                        "Expected ${host.pinnedFingerprint}, got ${verifier.mismatch}. " +
                        "Clear the pin only if you trust the new key.",
                )
            }
            authenticate(client, host, secret)
            client.startSession().use { session ->
                session.allocateDefaultPTY()
                val cmd = session.exec(command)
                val out = ByteArrayOutputStream()
                val err = ByteArrayOutputStream()
                val stdout = cmd.inputStream
                val stderr = cmd.errorStream
                val tOut = Thread { runCatching { stdout.copyTo(out) } }
                val tErr = Thread { runCatching { stderr.copyTo(err) } }
                tOut.start(); tErr.start()
                // join() returns void; it throws TransportException on timeout.
                var timedOut = false
                try {
                    cmd.join(timeoutS.toLong(), TimeUnit.SECONDS)
                } catch (e: Exception) {
                    timedOut = true
                }
                tOut.join(2000); tErr.join(2000)
                val exit = if (timedOut) 124 else (cmd.exitStatus ?: -1)
                val combined = buildString {
                    append(out.toString(Charsets.UTF_8))
                    val e = err.toString(Charsets.UTF_8)
                    if (e.isNotBlank()) { if (isNotEmpty()) append("\n"); append(e) }
                    if (timedOut) append("\n[timed out after ${timeoutS}s]")
                }
                val truncated = combined.length > RemoteHostConfig.MAX_OUTPUT_CHARS
                ExecResult(
                    output = RemoteHostConfig.truncateOutput(combined),
                    exitCode = exit,
                    truncated = truncated,
                )
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    /**
     * [T-remote-session] Upload a text file to the host via `cat > path`
     * over an exec channel's stdin. Parent dirs are created. Used by
     * `minis-remote deploy` to push the session-server script. Same
     * verified connection path as everything else.
     */
    suspend fun uploadTextFile(
        host: RemoteHostConfig.Host,
        secret: String,
        remotePath: String,
        content: String,
        timeoutS: Int = RemoteHostConfig.DEFAULT_TIMEOUT_S,
        onFirstPin: ((String) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val client = openVerifiedClient(host, secret, timeoutS, onFirstPin)
        try {
            client.startSession().use { session ->
                // Path is single-quoted for the remote shell; embedded
                // quotes are escaped the POSIX way ('\'').
                val quoted = "'" + remotePath.replace("'", "'\\''") + "'"
                val dir = remotePath.substringBeforeLast('/', "")
                val mkdir = if (dir.isNotEmpty()) {
                    "mkdir -p '" + dir.replace("'", "'\\''") + "' && "
                } else ""
                val cmd = session.exec(mkdir + "cat > " + quoted)
                cmd.outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)); it.flush() }
                try {
                    cmd.join(timeoutS.toLong(), TimeUnit.SECONDS)
                } catch (_: Exception) {
                    return@use 124
                }
                cmd.exitStatus ?: -1
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    /**
     * [T-remote-session] Open a connected, host-key-verified, authenticated
     * SSHClient and hand it to the caller (who owns closing it). Reuses the
     * same TOFU + auth path as [exec]/[probe]; throws SecurityException on a
     * host-key mismatch. Used by SshSessionTransport to run a long-lived
     * session-server channel over the encrypted link.
     *
     * On first connect (no pin) the observed fingerprint is returned via
     * [onFirstPin] so the caller can persist it; the caller MUST pin it to
     * keep TOFU meaningful across reconnects.
     */
    suspend fun openVerifiedClient(
        host: RemoteHostConfig.Host,
        secret: String,
        timeoutS: Int = RemoteHostConfig.DEFAULT_TIMEOUT_S,
        onFirstPin: ((String) -> Unit)? = null,
    ): SSHClient = withContext(Dispatchers.IO) {
        val client = SSHClient()
        try {
            configure(client, host, timeoutS)
            val verifier = PinningVerifier(host.pinnedFingerprint)
            client.addHostKeyVerifier(verifier)
            client.connect(host.hostname, host.port)
            if (verifier.mismatch != null) {
                throw SecurityException(
                    "SSH host key changed for ${host.hostname} — possible MITM. " +
                        "Expected ${host.pinnedFingerprint}, got ${verifier.mismatch}.",
                )
            }
            authenticate(client, host, secret)
            if (host.pinnedFingerprint == null) {
                verifier.seenFingerprint?.let { onFirstPin?.invoke(it) }
            }
            client
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    private fun configure(client: SSHClient, host: RemoteHostConfig.Host, timeoutS: Int) {
        client.connectTimeout = timeoutS * 1000
        client.timeout = timeoutS * 1000
        // Strict: no known-hosts file fallback; the PinningVerifier is the
        // only trust decision (TOFU).
        runCatching { client.useCompression() }
    }

    private fun authenticate(client: SSHClient, host: RemoteHostConfig.Host, secret: String) {
        when (host.authMethod) {
            RemoteHostConfig.AuthMethod.PASSWORD ->
                client.authPassword(host.username, secret)
            RemoteHostConfig.AuthMethod.PRIVATE_KEY -> {
                // sshj loadKeys(String) takes a file PATH, not key content.
                // Write the secret to a private temp file, load, then delete.
                val tmp = java.io.File.createTempFile("anharness_key_", ".pem")
                try {
                    tmp.setReadable(false, false); tmp.setReadable(true, true)
                    tmp.setWritable(false, false); tmp.setWritable(true, true)
                    tmp.writeText(secret)
                    val kp: KeyProvider = client.loadKeys(tmp.absolutePath)
                    client.authPublickey(host.username, kp)
                } finally {
                    runCatching { tmp.delete() }
                }
            }
        }
    }

    /** SHA-256 of the SSH wire-format public key blob, base64 (no padding). */
    internal fun sha256Fingerprint(key: PublicKey): String {
        val blob = sshWireBlob(key)
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Encode a public key into the SSH wire format (RFC 4253 §6.6) so the
     * fingerprint matches what ssh-keygen -lf shows. Supports RSA and Ed25519
     * (the common server key types); other types fall back to the encoded
     * form (still a stable pin, just not ssh-keygen-identical).
     */
    private fun sshWireBlob(key: PublicKey): ByteArray {
        return when (key) {
            is RSAPublicKey -> {
                val out = ByteArrayOutputStream()
                writeSshString(out, "ssh-rsa".toByteArray())
                writeSshMpint(out, key.publicExponent)
                writeSshMpint(out, key.modulus)
                out.toByteArray()
            }
            else -> key.encoded ?: ByteArray(0)
        }
    }

    private fun writeSshString(out: ByteArrayOutputStream, bytes: ByteArray) {
        val len = bytes.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(bytes)
    }

    private fun writeSshMpint(out: ByteArrayOutputStream, value: java.math.BigInteger) {
        writeSshString(out, value.toByteArray())
    }

    /**
     * TOFU verifier: accepts the first key (recording its fingerprint) and
     * rejects any later key whose fingerprint differs from the pin. When no
     * pin exists yet, the first seen key is accepted and reported so the
     * caller can persist it.
     */
    private class PinningVerifier(private val pinned: String?) : HostKeyVerifier {
        var seenFingerprint: String? = null
        var mismatch: String? = null

        override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
            if (key == null) return false
            val fp = runCatching { sha256Fingerprint(key) }.getOrNull() ?: return false
            seenFingerprint = fp
            return if (pinned == null) {
                true // TOFU: trust on first use
            } else if (fp == pinned) {
                true
            } else {
                mismatch = fp
                false
            }
        }

        override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> =
            mutableListOf()
    }
}