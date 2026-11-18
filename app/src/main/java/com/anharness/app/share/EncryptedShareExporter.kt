package com.anharness.app.share

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.anharness.app.data.db.ChatSessionEntity
import com.anharness.app.data.repository.ChatRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-share-e2e] Builds a self-decrypting HTML share file (P5).
 *
 * The transcript is AES-256-GCM encrypted on-device ([EncryptedShare]);
 * the file embeds ONLY the ciphertext plus a WebCrypto decryptor page.
 * The key is returned separately (copied to the clipboard by the caller)
 * and never written into the file — sharing the file alone reveals
 * nothing. The recipient opens the HTML, pastes the key, and the browser
 * decrypts locally; no server is involved at all.
 *
 * dsh parity note: dsh uploads ciphertext to a share worker and puts the
 * key in the URL fragment. We keep the same E2E property (key never
 * travels with the ciphertext) without requiring a hosted worker — the
 * file itself is the transport. A hosted-link mode can reuse
 * [EncryptedShare] unchanged later.
 */
object EncryptedShareExporter {

    /** Max transcript chars included (bounds file size; noted in the page). */
    const val MAX_TRANSCRIPT_CHARS = 400_000

    data class Result(val uri: Uri, val key: String, val truncated: Boolean)

    suspend fun export(
        context: Context,
        session: ChatSessionEntity,
        repository: ChatRepository,
    ): Result = withContext(Dispatchers.IO) {
        val transcript = buildTranscript(session, repository)
        val truncated = transcript.length > MAX_TRANSCRIPT_CHARS
        val text = if (truncated) transcript.take(MAX_TRANSCRIPT_CHARS) + "\n\n[transcript truncated]" else transcript
        val link = EncryptedShare.encrypt(text)
        val html = viewerHtml(session.title ?: "Conversation", link.payload)
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safeTitle = (session.title ?: "conversation")
            .replace(Regex("[^A-Za-z0-9_-]+"), "_").take(64).ifEmpty { "conversation" }
        val file = File(sharedDir, "$safeTitle-${session.id.take(8)}-encrypted.html")
        file.writeText(html, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Result(uri, link.key, truncated)
    }

    private suspend fun buildTranscript(session: ChatSessionEntity, repository: ChatRepository): String {
        val sb = StringBuilder()
        sb.append(session.title ?: "Conversation").append("\n\n")
        val total = repository.messageCount(session.id)
        var offset = 0
        while (offset < total && sb.length < MAX_TRANSCRIPT_CHARS + 1024) {
            val batch = repository.loadMessagePageRaw(session.id, offset, 200)
            if (batch.isEmpty()) break
            for (msg in batch) {
                val role = if (msg.role == "user") "You" else "Assistant"
                val text = plainText(msg.partsJson)
                if (text.isNotBlank()) sb.append(role).append(": ").append(text).append("\n\n")
            }
            offset += batch.size
            if (batch.size < 200) break
        }
        return sb.toString()
    }

    private fun plainText(partsJson: String): String = runCatching {
        val arr = JSONArray(partsJson)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("type") == "text") {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(obj.optString("text", ""))
            }
        }
        sb.toString()
    }.getOrDefault("")

    /**
     * Self-decrypting viewer. The payload is URL-safe base64 (charset
     * [A-Za-z0-9_-]) so embedding it in a JS string literal is safe with
     * no escaping. WebCrypto AES-GCM, AAD = format version, iv = first 12
     * bytes — byte-compatible with [EncryptedShare].
     */
    internal fun viewerHtml(title: String, payloadB64: String): String {
        val safeTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$safeTitle — encrypted share</title>
<style>
 body{font-family:system-ui,sans-serif;max-width:760px;margin:2rem auto;padding:0 1rem;background:#111;color:#eee}
 h1{font-size:1.2rem} input{width:100%;padding:.6rem;font-size:1rem;box-sizing:border-box}
 button{margin-top:.6rem;padding:.6rem 1.2rem;font-size:1rem;cursor:pointer}
 pre{white-space:pre-wrap;word-break:break-word;background:#1b1b1b;padding:1rem;border-radius:8px;display:none}
 .err{color:#f66;display:none} .hint{color:#999;font-size:.85rem}
</style>
</head>
<body>
<h1>$safeTitle</h1>
<p class="hint">This conversation is end-to-end encrypted. Paste the key you received separately to decrypt it locally in your browser — nothing is sent to any server.</p>
<input id="k" type="password" placeholder="Decryption key" autocomplete="off">
<button id="go">Decrypt</button>
<p class="err" id="err">Wrong key or damaged file.</p>
<pre id="out"></pre>
<script>
const PAYLOAD="$payloadB64";
const AAD="minis-share-v1";
function b64d(s){s=s.replace(/-/g,"+").replace(/_/g,"/");while(s.length%4)s+="=";const bin=atob(s);const a=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)a[i]=bin.charCodeAt(i);return a;}
async function go(){
 const err=document.getElementById("err"),out=document.getElementById("out");
 err.style.display="none";out.style.display="none";
 try{
  const key=b64d(document.getElementById("k").value.trim());
  const blob=b64d(PAYLOAD);
  const iv=blob.slice(0,12),ct=blob.slice(12);
  const ck=await crypto.subtle.importKey("raw",key,{name:"AES-GCM"},false,["decrypt"]);
  const pt=await crypto.subtle.decrypt({name:"AES-GCM",iv:iv,additionalData:new TextEncoder().encode(AAD),tagLength:128},ck,ct);
  out.textContent=new TextDecoder().decode(pt);out.style.display="block";
 }catch(e){err.style.display="block";}
}
document.getElementById("go").addEventListener("click",go);
document.getElementById("k").addEventListener("keydown",e=>{if(e.key==="Enter")go();});
</script>
</body>
</html>"""
    }
}