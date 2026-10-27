package com.renamer

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

/** SSH tunnel engine for SlipNet profiles (standalone SSH
 *  and, later, the SSH layer of DNSTT+SSH / VayDNS+SSH /
 *  Slipstream+SSH / NaiveProxy+SSH).
 *  Uses JSch, a standard pure-Java SSH client: it opens
 *  an SSH session to the profile's server and creates a
 *  local dynamic (SOCKS5) forwarding on 127.0.0.1.
 *  The app's existing Xray TUN then routes traffic
 *  through that local SOCKS proxy (see SlipNetActivity:
 *  a socks://127.0.0.1:<port> config fed to
 *  VpnConfigBuilder), exactly like SlipNet's own
 *  SSH bridge, which also ends in a local SOCKS proxy
 *  feeding its TUN. Runs on a background thread only:
 *  JSch connect() blocks. */
object SlipNetSsh {
    var session: Session? = null
        private set
    var localPort: Int = 0
        private set

    fun start(host: String, port: Int, user: String, password: String, keyPem: String, keyPass: String, onLog: (String) -> Unit): Int {
        stop()
        val jsch = JSch()
        if (keyPem.isNotBlank()) {
            try {
                val keyBytes = keyPem.toByteArray(Charsets.UTF_8)
                val passBytes = if (keyPass.isNotBlank()) keyPass.toByteArray(Charsets.UTF_8) else null
                jsch.addIdentity("slipnet", keyBytes, null, passBytes)
            } catch (e: Exception) {
                onLog("SSH key error: ${e.message}")
                throw e
            }
        }
        val s = jsch.getSession(user, host, port)
        if (keyPem.isBlank()) s.setPassword(password)
        s.setConfig("StrictHostKeyChecking", "no")
        // Prefer modern, widely accepted algorithms first.
        s.setConfig("kex", "curve25519-sha256,ecdh-sha2-nistp256,ecdh-sha2-nistp384,diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256")
        s.setConfig("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa")
        s.setConfig("cipher.s2c", "aes128-gcm@openssh.com,aes128-ctr,aes256-ctr,chacha20-poly1305@openssh.com")
        s.setConfig("cipher.c2s", "aes128-gcm@openssh.com,aes128-ctr,aes256-ctr,chacha20-poly1305@openssh.com")
        s.connect(15000)
        // JSch has no built-in dynamic forwarding, so
        // run our own SOCKS5 server (SlipNetSocks) whose
        // connections open as SSH direct-tcpip channels.
        localPort = 10801
        SlipNetSocks.start(s, localPort)
        session = s
        onLog("SSH connected, local SOCKS on 127.0.0.1:$localPort")
        return localPort
    }

    fun stop() {
        SlipNetSocks.stop()
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        localPort = 0
    }

    fun isRunning(): Boolean = try { session?.isConnected == true } catch (_: Exception) { false }
}
