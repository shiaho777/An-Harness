package com.anharness.app.remote.session

import com.anharness.app.remote.RemoteHostConfig
import com.anharness.app.remote.RemoteHostManager
import com.anharness.app.remote.SshTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * [T-remote-session] SSH-channel implementation of [SessionTransport] (P4
 * slice 4). The remote host runs a session-server process that speaks
 * [RemoteSessionProtocol] over stdin/stdout; this transport opens an SSH
 * exec channel to it and pumps newline-delimited JSON frames both ways.
 *
 * Security: rides the SAME encrypted, TOFU-pinned SSH connection as
 * remote_shell — host-key verification and credentials are handled by
 * [SshTransport]'s connection helpers. A Noise/WebRTC transport can replace
 * this class later without touching [RemoteSessionClient].
 *
 * The server command is configurable (default `minis-session-server`); the
 * host must have it installed. This slice provides the client + protocol;
 * the server binary is a separate deliverable (documented in the roadmap).
 */
class SshSessionTransport(
    private val host: RemoteHostConfig.Host,
    private val secret: String,
    private val serverCommand: String = DEFAULT_SERVER_COMMAND,
    private val timeoutS: Int = RemoteHostConfig.DEFAULT_TIMEOUT_S,
) : SessionTransport {

    private var client: SSHClient? = null
    private var session: Session? = null
    private var command: Session.Command? = null
    private var writer: BufferedWriter? = null
    private val incoming = Channel<RemoteSessionProtocol.Frame>(Channel.UNLIMITED)
    @Volatile private var highestSeq: Long = -1L
    @Volatile private var pumpThread: Thread? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        // Reuse the verified connection path (TOFU + auth) from SshTransport.
        val c = SshTransport.openVerifiedClient(host, secret, timeoutS)
        client = c
        val s = c.startSession()
        session = s
        val cmd = s.exec(serverCommand)
        command = cmd
        writer = BufferedWriter(OutputStreamWriter(cmd.outputStream, Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(cmd.inputStream, Charsets.UTF_8))
        // Pump frames on a dedicated thread; closing the channel completes
        // the flow so the client's run()/reconnect() collect returns.
        val t = Thread {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val frame = RemoteSessionProtocol.decodeFrame(line) ?: continue
                    if (frame.seq > highestSeq) highestSeq = frame.seq
                    incoming.trySend(frame)
                }
            } catch (_: Exception) {
                // link dropped — fall through to close
            } finally {
                incoming.close()
            }
        }
        t.isDaemon = true
        t.start()
        pumpThread = t
    }

    override fun frames(): Flow<RemoteSessionProtocol.Frame> = incoming.consumeAsFlow()

    override suspend fun send(request: RemoteSessionProtocol.Request) = withContext(Dispatchers.IO) {
        val w = writer ?: throw IllegalStateException("not connected")
        w.write(RemoteSessionProtocol.encodeRequest(request))
        w.write("\n")
        w.flush()
    }

    override suspend fun serverSeq(): Long = highestSeq

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { command?.close() }
        runCatching { session?.close() }
        runCatching { client?.disconnect() }
        runCatching { pumpThread?.interrupt() }
        incoming.close()
        client = null; session = null; command = null; writer = null; pumpThread = null
    }

    companion object {
        const val DEFAULT_SERVER_COMMAND = "minis-session-server"
    }
}