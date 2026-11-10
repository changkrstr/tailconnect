/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.backends.tailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TailscaleTest {

    private class MockConn(
        private val dataToRead: ByteArray = ByteArray(0),
        private val remoteHost: String = "100.80.70.60",
        private val remotePort: Long = 1716L
    ) : TunneledConn {
        var readOffset = 0
        val written = ByteArrayOutputStream()
        var closed = false

        override fun read(size: Long): ByteArray? {
            if (closed) return null
            if (readOffset >= dataToRead.size) return null
            val len = minOf(size.toInt(), dataToRead.size - readOffset)
            val chunk = dataToRead.copyOfRange(readOffset, readOffset + len)
            readOffset += len
            return chunk
        }

        override fun write(data: ByteArray?) {
            if (data != null && !closed) {
                written.write(data)
            }
        }

        override fun close() {
            closed = true
        }

        override fun remoteAddrHost(): String = remoteHost
        override fun remoteAddrPort(): Long = remotePort
    }

    @Test
    fun testTailscaleSocketReadWrite() {
        val testPayload = "hello kdeconnect tailscale".toByteArray(Charsets.UTF_8)
        val mock = MockConn(dataToRead = testPayload, remoteHost = "100.90.80.70", remotePort = 1716L)

        val socket = TailscaleSocket(mock, "100.90.80.70", 1716)
        assertTrue(socket.isConnected)
        assertFalse(socket.isClosed)

        // Read test
        val buffer = ByteArray(testPayload.size)
        val bytesRead = socket.inputStream.read(buffer)
        assertEquals(testPayload.size, bytesRead)
        assertEquals("hello kdeconnect tailscale", String(buffer, Charsets.UTF_8))

        // Write test
        val toWrite = "response packet".toByteArray(Charsets.UTF_8)
        socket.outputStream.write(toWrite)
        socket.outputStream.flush()
        assertEquals("response packet", mock.written.toString(Charsets.UTF_8))

        // Address test
        assertNotNull(socket.remoteSocketAddress)
        assertEquals(1716, socket.port)

        // Close test
        socket.close()
        assertTrue(socket.isClosed)
        assertTrue(mock.closed)
    }
}
