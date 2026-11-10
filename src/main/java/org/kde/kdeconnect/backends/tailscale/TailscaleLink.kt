/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.backends.tailscale

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceInfo
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.backends.BaseLink
import org.kde.kdeconnect.backends.BaseLinkProvider
import org.kde.kdeconnect.extensions.closeSafe
import org.kde.kdeconnect.helpers.LineTooLongException
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.helpers.readLineBounded
import org.kde.kdeconnect.helpers.security.SslHelper
import org.kde.kdeconnect.tsbridge.tsbridge.ListenerHandle
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

class TailscaleLink(
    context: Context,
    linkProvider: BaseLinkProvider,
    override var deviceInfo: DeviceInfo,
    @Volatile private var socket: SSLSocket
) : BaseLink(context, linkProvider) {

    override val name = "TailscaleLink"

    override fun disconnect() {
        Log.i(TAG, "Disconnect socket: ${socket.hashCode()}")
        try {
            socket.close()
        } catch (e: IOException) {
            Log.e(TAG, "Disconnect error", e)
        }
    }

    fun reset(newSocket: SSLSocket, newDeviceInfo: DeviceInfo) {
        deviceInfo = newDeviceInfo
        val oldSocket = socket
        socket = newSocket
        oldSocket.closeSafe()
        startListening()
    }

    fun startListening() {
        val currentSocket = socket
        ThreadHelper.runOnNewThread {
            try {
                val stream = BufferedInputStream(currentSocket.inputStream)
                while (true) {
                    val packet: String = try {
                        readLineBounded(stream, MAX_PACKET_SIZE)
                    } catch (_: LineTooLongException) {
                        continue
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (packet.isEmpty()) {
                        continue
                    }
                    val np = NetworkPacket.unserialize(packet)
                    receivedNetworkPacket(np)
                }
            } catch (e: Exception) {
                Log.i(TAG, "Socket closed: ${currentSocket.hashCode()}. Reason: ${e.message}")
                currentSocket.closeSafe()

                try { Thread.sleep(300) } catch (_: InterruptedException) { }

                val thereIsANewSocket = (currentSocket !== socket)
                if (!thereIsANewSocket) {
                    Log.i(TAG, "Socket closed and there's no new socket, disconnecting device")
                    linkProvider.onConnectionLost(this@TailscaleLink)
                }
            }
        }
    }

    @WorkerThread
    override fun sendPacket(
        np: NetworkPacket,
        callback: Device.SendPacketStatusCallback,
        sendPayloadFromSameThread: Boolean
    ): Boolean {
        var payloadTransferStarted = false
        var tsListener: ListenerHandle? = null
        try {
            if (np.hasPayload()) {
                tsListener = TailscaleTunnelManager.listenTcp(0)
                np.payloadTransferInfo = buildJsonObject {
                    put("port", JsonPrimitive(tsListener.port()))
                }
            }

            try {
                val writer: OutputStream = socket.outputStream
                writer.write(np.serialize().toByteArray(Charsets.UTF_8))
                writer.flush()
            } catch (e: Exception) {
                disconnect()
                tsListener?.close()
                throw e
            }

            if (tsListener != null) {
                val listenerRef = tsListener
                if (sendPayloadFromSameThread) {
                    payloadTransferStarted = true
                    sendPayload(np, callback, listenerRef)
                } else {
                    ThreadHelper.runOnNewThread {
                        try {
                            sendPayload(np, callback, listenerRef)
                        } catch (e: IOException) {
                            Log.e(TAG, "Async sendPayload failed for packet of type ${np.type}", e)
                        }
                    }
                    payloadTransferStarted = true
                }
            }

            if (!np.isCanceled) {
                callback.onSuccess()
            }
            return true
        } catch (e: Exception) {
            callback.onFailure(e)
            return false
        } finally {
            if (!payloadTransferStarted) {
                np.payload?.close()
                tsListener?.close()
            }
        }
    }

    @Throws(IOException::class)
    private fun sendPayload(
        np: NetworkPacket,
        callback: Device.SendPacketStatusCallback,
        listener: ListenerHandle
    ) {
        val payload = np.payload!!
        var payloadSocket: Socket? = null
        try {
            if (np.isCanceled) return

            val inputStream = payload.inputStream
                ?: throw IOException("Payload for packet ${np.type} has no InputStream")

            val rawConn = listener.accept()
            val rawSocket = TailscaleSocket(rawConn, rawConn.remoteAddrHost(), rawConn.remoteAddrPort().toInt())

            payloadSocket = SslHelper.convertToSslSocket(
                context, rawSocket, deviceId,
                isDeviceTrusted = true,
                clientMode = false
            )

            val outputStream = payloadSocket.outputStream
            Log.i(TAG, "Beginning to send payload over Tailscale for ${np.type}")
            val buffer = ByteArray(PAYLOAD_BUFFER_SIZE)
            val size = np.payloadSize
            var bytesRead = 0
            var progress: Long = 0
            var timeSinceLastUpdate: Long = -1
            while (!np.isCanceled && (inputStream.read(buffer).also { bytesRead = it }) != -1) {
                progress += bytesRead
                outputStream.write(buffer, 0, bytesRead)
                if (size > 0) {
                    if (timeSinceLastUpdate + 500 < SystemClock.elapsedRealtime()) {
                        val percent = ((100 * progress) / size)
                        callback.onPayloadProgressChanged(percent.toInt())
                        timeSinceLastUpdate = SystemClock.elapsedRealtime()
                    }
                }
            }
            outputStream.flush()
            Log.i(TAG, "Finished sending payload over Tailscale ($progress bytes written)")
        } catch (e: CertificateException) {
            Log.e(TAG, "Payload SSLSocket failed", e)
            throw IOException("Payload SSL socket failed", e)
        } catch (e: SSLHandshakeException) {
            Log.e(TAG, "Payload SSLSocket failed", e)
            throw IOException("Payload SSL socket failed", e)
        } finally {
            listener.close()
            payloadSocket?.closeSafe()
            payload.close()
        }
    }

    private fun receivedNetworkPacket(np: NetworkPacket) {
        if (np.hasPayloadTransferInfo()) {
            var payloadSocket: Socket? = null
            try {
                val tcpPort = np.payloadTransferInfo.getValue("port").jsonPrimitive.int
                val deviceAddress = socket.remoteSocketAddress as InetSocketAddress
                val host = deviceAddress.hostString ?: deviceAddress.address.hostAddress

                val rawSocket = TailscaleTunnelManager.dial(host, tcpPort, 15000)
                payloadSocket = SslHelper.convertToSslSocket(
                    context, rawSocket, deviceId,
                    isDeviceTrusted = true,
                    clientMode = true
                )
                np.payload = NetworkPacket.Payload(payloadSocket, np.payloadSize)
            } catch (e: Exception) {
                payloadSocket?.closeSafe()
                Log.e(TAG, "Exception connecting to payload remote socket over Tailscale", e)
            }
        }

        packetReceived(np)
    }

    companion object {
        private const val TAG = "KDE/TailscaleLink"
        private const val MAX_PACKET_SIZE: Int = 32 * 1024 * 1024
        private const val PAYLOAD_BUFFER_SIZE: Int = 256 * 1024
    }
}
