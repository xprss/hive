package io.xprss.hive

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Abstract Unix socket. SO_PEERCRED is checked before accepting a command. */
internal class CommandServer(private val activity: MainActivity) : Closeable {
    private val server = LocalServerSocket(SOCKET_NAME)
    @Volatile private var closed = false
    private val thread = Thread({ acceptLoop() }, "privcam-socket").apply {
        isDaemon = true
        start()
    }

    private fun acceptLoop() {
        while (!closed) {
            try {
                val client = server.accept()
                Thread({ handle(client) }, "privcam-client").apply { isDaemon = true; start() }
            } catch (_: Exception) {
                if (!closed) Thread.sleep(200)
            }
        }
    }

    private fun handle(client: LocalSocket) {
        client.use { socket ->
            try {
                socket.soTimeout = 15_000
                val uid = socket.peerCredentials.uid
                if (uid == Process.myUid() || !activity.isTrustedTermux(uid)) {
                    reply(socket, "ERR Termux non autorizzato. Apri l'app e autorizza Termux.")
                    return
                }
                val command = readCommand(socket)
                if (command !in listOf("start", "end", "status", "camera", "view")) {
                    reply(socket, "ERR Comando sconosciuto")
                    return
                }
                if (command == "status") {
                    reply(socket, "OK ${activity.vault.status()}")
                    return
                }
                val done = CountDownLatch(1)
                var result = "ERR Comando interrotto"
                activity.runOnUiThread {
                    activity.handleCommand(command) { message ->
                        result = message
                        done.countDown()
                    }
                }
                if (done.await(60, TimeUnit.MINUTES)) reply(socket, result)
                else reply(socket, "ERR Timeout")
            } catch (e: Exception) {
                try { reply(socket, "ERR ${e.message ?: "Errore socket"}") } catch (_: Exception) { }
            }
        }
    }

    private fun readCommand(socket: LocalSocket): String {
        val bytes = ArrayList<Byte>()
        val input = socket.inputStream
        while (bytes.size < 64) {
            val ch = input.read()
            if (ch == -1 || ch == 10) break
            bytes.add(ch.toByte())
        }
        require(bytes.size < 64) { "Comando troppo lungo" }
        return String(bytes.toByteArray(), StandardCharsets.US_ASCII).trim()
    }

    private fun reply(socket: LocalSocket, value: String) {
        socket.outputStream.write((value.replace('\n', ' ') + "\n").toByteArray(StandardCharsets.UTF_8))
        socket.outputStream.flush()
    }

    override fun close() {
        closed = true
        server.close()
        thread.interrupt()
    }

    companion object { const val SOCKET_NAME = "io.xprss.hive.privcam.v1" }
}
