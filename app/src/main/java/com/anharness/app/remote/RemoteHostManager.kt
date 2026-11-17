package com.anharness.app.remote

import android.content.Context
import android.content.SharedPreferences
import com.anharness.app.util.EncryptedPrefsFactory
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * [T-remote-ssh] Remote SSH host registry (P4).
 *
 * Host records (no secrets) live in a plain prefs file as JSON; passwords /
 * private keys live in a SEPARATE encrypted prefs file (same pattern as
 * ProviderRepository's apikey store). The two are keyed by host id.
 *
 * TOFU: the first successful connection pins the server's SHA-256 host-key
 * fingerprint; later connections refuse a changed fingerprint (MITM guard).
 * The pin is stored on the host record and can be cleared explicitly.
 *
 * A storage seam ([storeOverride]) lets JVM tests inject an in-memory map
 * instead of Android SharedPreferences.
 */
class RemoteHostManager(private val context: Context) {

    /** Storage seam for tests: id → host JSON. Null → use real prefs. */
    internal var storeOverride: MutableMap<String, String>? = null

    /** Secret seam for tests: key → secret. Null → use encrypted prefs. */
    internal var secretOverride: MutableMap<String, String>? = null

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    // [T-remote-ssh] SSH secrets are high-sensitivity key material: use the
    // fail-closed store so a Keystore failure refuses storage instead of
    // silently writing the private key in cleartext (safeCreate's fallback).
    private val secrets: SharedPreferences by lazy {
        EncryptedPrefsFactory.createFailClosed(context, SECRETS_NAME)
    }

    // ── host CRUD ────────────────────────────────────────────────────────

    fun list(): List<RemoteHostConfig.Host> {
        val override = storeOverride
        val raw = if (override != null) override[KEY_HOSTS]
            else prefs.getString(KEY_HOSTS, null)
        if (raw == null) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): RemoteHostConfig.Host? = list().firstOrNull { it.id == id }

    /**
     * Add or update a host. [secret] (password or private key) is stored
     * encrypted, keyed by id; pass null to keep the existing secret.
     * Returns the stored host (with a generated id when [id] is null).
     */
    fun upsert(
        id: String?,
        label: String,
        hostname: String,
        port: Int,
        username: String,
        authMethod: RemoteHostConfig.AuthMethod,
        secret: String?,
    ): RemoteHostConfig.Host {
        RemoteHostConfig.validate(hostname, port, username)?.let {
            throw IllegalArgumentException(it)
        }
        val hostId = id ?: UUID.randomUUID().toString()
        val existing = get(hostId)
        val host = RemoteHostConfig.Host(
            id = hostId,
            label = label.ifBlank { "$username@$hostname" },
            hostname = hostname,
            port = port,
            username = username,
            authMethod = authMethod,
            pinnedFingerprint = existing?.pinnedFingerprint,
        )
        val all = list().filter { it.id != hostId } + host
        writeHosts(all)
        if (secret != null) writeSecret(hostId, secret)
        return host
    }

    fun delete(id: String) {
        writeHosts(list().filter { it.id != id })
        deleteSecret(id)
    }

    // ── secrets ──────────────────────────────────────────────────────────

    fun loadSecret(id: String): String? {
        val override = secretOverride
        return if (override != null) override[secretKey(id)]
            else secrets.getString(secretKey(id), null)
    }

    private fun writeSecret(id: String, secret: String) {
        val override = secretOverride
        if (override != null) override[secretKey(id)] = secret
        else secrets.edit().putString(secretKey(id), secret).apply()
    }

    private fun deleteSecret(id: String) {
        val override = secretOverride
        if (override != null) override.remove(secretKey(id))
        else secrets.edit().remove(secretKey(id)).apply()
    }

    private fun secretKey(id: String) = "secret_$id"

    // ── TOFU pin ─────────────────────────────────────────────────────────

    fun pinFingerprint(id: String, fingerprint: String) {
        val host = get(id) ?: return
        writeHosts(list().map { if (it.id == id) host.copy(pinnedFingerprint = fingerprint) else it })
    }

    fun clearPin(id: String) {
        val host = get(id) ?: return
        writeHosts(list().map { if (it.id == id) host.copy(pinnedFingerprint = null) else it })
    }

    // ── persistence helpers ──────────────────────────────────────────────

    private fun writeHosts(hosts: List<RemoteHostConfig.Host>) {
        val arr = JSONArray()
        hosts.forEach { arr.put(toJson(it)) }
        val raw = arr.toString()
        val override = storeOverride
        if (override != null) override[KEY_HOSTS] = raw
        else prefs.edit().putString(KEY_HOSTS, raw).apply()
    }

    private fun toJson(h: RemoteHostConfig.Host) = JSONObject().apply {
        put("id", h.id)
        put("label", h.label)
        put("hostname", h.hostname)
        put("port", h.port)
        put("username", h.username)
        put("authMethod", h.authMethod.name)
        put("pinnedFingerprint", h.pinnedFingerprint ?: JSONObject.NULL)
    }

    private fun fromJson(o: JSONObject) = RemoteHostConfig.Host(
        id = o.getString("id"),
        label = o.optString("label"),
        hostname = o.getString("hostname"),
        port = o.optInt("port", RemoteHostConfig.DEFAULT_PORT),
        username = o.getString("username"),
        authMethod = runCatching {
            RemoteHostConfig.AuthMethod.valueOf(o.optString("authMethod", "PASSWORD"))
        }.getOrDefault(RemoteHostConfig.AuthMethod.PASSWORD),
        pinnedFingerprint = o.optString("pinnedFingerprint").takeIf { it.isNotEmpty() && it != "null" },
    )

    companion object {
        private const val PREFS_NAME = "remote_hosts"
        private const val SECRETS_NAME = "remote_host_secrets"
        private const val KEY_HOSTS = "hosts"
    }
}