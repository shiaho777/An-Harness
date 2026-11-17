package com.anharness.app.sandbox

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-asset-manifest] Sandbox asset manifest + hash verification (P5
 * engineering parity: dsh-Go's patch-chain + double-hash locking, adapted
 * to our committed assets).
 *
 * The sandbox overlay (assets/default_mount) and the remote session
 * server (assets/remote) are code that runs with agent privileges. A
 * committed manifest pins every file's SHA-256 so:
 *  - build-time generation (scripts/generate_asset_manifest.py) records
 *    the intended bytes;
 *  - runtime verification detects drift (corrupted APK assets, accidental
 *    edits, tampering) BEFORE the overlay is applied.
 *
 * Pure JVM logic (org.json + MessageDigest) — fully unit-testable. The
 * Android-side loader reads assets via AssetManager and feeds bytes here.
 */
object AssetManifest {

    const val MANIFEST_ASSET_PATH = "sandbox-manifest.json"
    const val MANIFEST_VERSION = 1

    data class Entry(val path: String, val sha256: String, val size: Long)

    data class Manifest(val version: Int, val entries: List<Entry>) {
        fun byPath(): Map<String, Entry> = entries.associateBy { it.path }
    }

    sealed interface VerifyResult {
        data object Ok : VerifyResult
        data class Missing(val paths: List<String>) : VerifyResult
        data class Mismatch(val paths: List<String>) : VerifyResult
        data class Extra(val paths: List<String>) : VerifyResult
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Build a manifest from (relative path → bytes) pairs. Deterministic order. */
    fun build(files: Map<String, ByteArray>): Manifest =
        Manifest(
            version = MANIFEST_VERSION,
            entries = files.entries.sortedBy { it.key }.map { (path, bytes) ->
                Entry(path, sha256Hex(bytes), bytes.size.toLong())
            },
        )

    fun encode(manifest: Manifest): String {
        val arr = JSONArray()
        manifest.entries.forEach { e ->
            arr.put(JSONObject().put("path", e.path).put("sha256", e.sha256).put("size", e.size))
        }
        return JSONObject().put("version", manifest.version).put("files", arr).toString(2)
    }

    /** Parse; returns null on malformed/unsupported-version manifests. */
    fun decode(json: String): Manifest? = runCatching {
        val o = JSONObject(json)
        if (o.optInt("version", 0) != MANIFEST_VERSION) return null
        val arr = o.getJSONArray("files")
        val entries = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            Entry(e.getString("path"), e.getString("sha256"), e.getLong("size"))
        }
        Manifest(MANIFEST_VERSION, entries)
    }.getOrNull()

    /**
     * Verify actual files against the manifest. Checks presence, hash, and
     * unexpected extras. First failure class wins (missing > mismatch >
     * extra) so the report is actionable.
     */
    fun verify(manifest: Manifest, actual: Map<String, ByteArray>): VerifyResult {
        val expected = manifest.byPath()
        val missing = expected.keys.filter { it !in actual }
        if (missing.isNotEmpty()) return VerifyResult.Missing(missing.sorted())
        val mismatch = expected.values.filter { e ->
            val bytes = actual[e.path]!!
            bytes.size.toLong() != e.size || sha256Hex(bytes) != e.sha256
        }.map { it.path }
        if (mismatch.isNotEmpty()) return VerifyResult.Mismatch(mismatch.sorted())
        val extra = actual.keys.filter { it !in expected }
        if (extra.isNotEmpty()) return VerifyResult.Extra(extra.sorted())
        return VerifyResult.Ok
    }
}