package com.renamer

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Minimal SOCKS5 server (TCP CONNECT only, no auth)
 *  feeding a JSch SSH session, exactly the shape
 *  SlipNet's own SSH bridge uses: a local SOCKS proxy
 *  whose connections are opened as SSH direct-tcpip
 *  channels. Xray's socks outbound talks to this
 *  proxy, so the TUN routes through SSH.
 *  UDP ASSOCIATE is not implemented in this build
 *  (TCP browsing / TLS works; UDP-heavy apps may
 *  fall back or fail) — stated honestly, to be added
 *  with the next SSH iteration if the user needs it. */
object SlipNetSocks {
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start(session: Session, port: Int): Int {
        stop()
        val ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        server = ss
        thread = Thread {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    Thread { handle(session, client) }.start()
                } catch (_: Exception) { break }
            }
        }.apply { isDaemon = true; start() }
        return port
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        thread = null
    }

    private fun handle(session: Session, client: Socket) {
        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()
            // Greeting: VER, NMETHODS, METHODS
            val ver = inp.read()
            if (ver != 5) { client.close(); return }
            val n = inp.read()
            inp.readNBytes(n)
            out.write(byteArrayOf(5, 0)); out.flush()
            // Request: VER, CMD, RSV, ATYP, DST, PORT
            val buf = ByteArray(4)
            if (inp.readNBytes(buf, 0, 4) < 4) { client.close(); return }
            val cmd = buf[1].toInt()
            val atyp = buf[3].toInt()
            if (cmd != 1) {
                out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush(); client.close(); return
            }
            val host = when (atyp) {
                1 -> {
                    val b = ByteArray(4); inp.readNBytes(b, 0, 4)
                    InetAddress.getByAddress(b).hostAddress
                }
                3 -> {
                    val len = inp.read()
                    val b = ByteArray(len); inp.readNBytes(b, 0, len)
                    String(b, Charsets.UTF_8)
                }
                4 -> {
                    val b = ByteArray(16); inp.readNBytes(b, 0, 16)
                    InetAddress.getByAddress(b).hostAddress
                }
                else -> { client.close(); return }
            }
            val pb = ByteArray(2); inp.readNBytes(pb, 0, 2)
            val port = ((pb[0].toInt() and 0xFF) shl 8) or (pb[1].toInt() and 0xFF)
            val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(host)
            channel.setPort(port)
            channel.connect(10000)
            val cin = channel.inputStream
            val cout = channel.outputStream
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            val t1 = Thread { pipe(inp, cout) }
            val t2 = Thread { pipe(cin, out) }
            t1.start(); t2.start()
            t1.join(); t2.join()
            try { channel.disconnect() } catch (_: Exception) {}
            client.close()
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(inp: InputStream, out: OutputStream) {
        try {
            val b = ByteArray(8192)
            while (true) {
                val r = inp.read(b)
                if (r < 0) break
                out.write(b, 0, r); out.flush()
            }
        } catch (_: Exception) {}
        try { out.flush() } catch (_: Exception) {}
    }
}
