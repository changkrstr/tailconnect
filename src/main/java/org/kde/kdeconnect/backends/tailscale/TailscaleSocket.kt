/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.backends.tailscale

import org.kde.kdeconnect.tsbridge.tsbridge.Conn
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

interface TunneledConn {
    fun read(size: Long): ByteArray?
    fun write(data: ByteArray?)
    fun close()
    fun remoteAddrHost(): String
    fun remoteAddrPort(): Long
}

class GomobileConn(private val conn: Conn) : TunneledConn {
    override fun read(size: Long): ByteArray? = conn.read(size)
    override fun write(data: ByteArray?) = conn.write(data)
    override fun close() = conn.close()
    override fun remoteAddrHost(): String = conn.remoteAddrHost()
    override fun remoteAddrPort(): Long = conn.remoteAddrPort()
}

/**
 * A [java.net.Socket] implementation backed by an in-process Tailscale [TunneledConn].
 * Allows standard TLS / SSLSocket wrapping via [javax.net.ssl.SSLSocketFactory.createSocket].
 */
class TailscaleSocket(
    private val conn: TunneledConn,
    private val host: String,
    private val port: Int
) : Socket() {

    constructor(conn: Conn, host: String, port: Int) : this(GomobileConn(conn), host, port)

    @Volatile
    private var isClosed = false

    private val inStream: InputStream = object : InputStream() {
        private var eof = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n == -1) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (isClosed || eof) return -1
            if (len == 0) return 0
            val bytes = try {
                conn.read(len.toLong())
            } catch (_: Exception) {
                eof = true
                return -1
            }
            if (bytes == null || bytes.isEmpty()) {
                eof = true
                return -1
            }
            val n = minOf(bytes.size, len)
            System.arraycopy(bytes, 0, b, off, n)
            return n
        }
    }

    private val outStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf((b and 0xFF).toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (isClosed) throw IOException("Socket is closed")
            val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            try {
                conn.write(slice)
            } catch (e: Exception) {
                throw IOException("Tailscale write failed: ${e.message}", e)
            }
        }
    }

    override fun getInputStream(): InputStream = inStream
    override fun getOutputStream(): OutputStream = outStream

    override fun getInetAddress(): InetAddress? = try {
        InetAddress.getByName(host)
    } catch (_: Exception) {
        null
    }

    override fun getPort(): Int = port
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getLocalPort(): Int = -1

    override fun isConnected(): Boolean = !isClosed
    override fun isBound(): Boolean = !isClosed
    override fun isClosed(): Boolean = isClosed
    override fun isInputShutdown(): Boolean = isClosed
    override fun isOutputShutdown(): Boolean = isClosed

    override fun close() {
        if (isClosed) return
        isClosed = true
        try {
            conn.close()
        } catch (_: Throwable) {
            // Best effort
        }
    }

    override fun connect(endpoint: SocketAddress?) {
        throw UnsupportedOperationException("TailscaleSocket is pre-connected")
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        throw UnsupportedOperationException("TailscaleSocket is pre-connected")
    }

    override fun bind(bindpoint: SocketAddress?) {
        throw UnsupportedOperationException("TailscaleSocket cannot be bound")
    }

    override fun shutdownInput() {}
    override fun shutdownOutput() {}

    override fun setTcpNoDelay(on: Boolean) {}
    override fun setKeepAlive(on: Boolean) {}
    override fun setSoTimeout(timeout: Int) {}
    override fun setSoLinger(on: Boolean, linger: Int) {}
    override fun setReuseAddress(on: Boolean) {}
    override fun setOOBInline(on: Boolean) {}
    override fun setReceiveBufferSize(size: Int) {}
    override fun setSendBufferSize(size: Int) {}
    override fun setTrafficClass(tc: Int) {}

    override fun getTcpNoDelay(): Boolean = false
    override fun getKeepAlive(): Boolean = false
    override fun getSoTimeout(): Int = 0
    override fun getSoLinger(): Int = -1
    override fun getReuseAddress(): Boolean = false
    override fun getOOBInline(): Boolean = false

    override fun getRemoteSocketAddress(): SocketAddress {
        return try {
            val addr = InetAddress.getByName(host)
            InetSocketAddress(addr, port)
        } catch (_: Exception) {
            InetSocketAddress.createUnresolved(host, port)
        }
    }
}
