/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.backends.tailscale

import android.content.Context
import android.content.SharedPreferences
import android.net.Network
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.serialization.SerializationException
import org.kde.kdeconnect.DeviceInfo
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.backends.BaseLink
import org.kde.kdeconnect.backends.BaseLinkProvider
import org.kde.kdeconnect.extensions.closeSafe
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.helpers.TrustedDevices
import org.kde.kdeconnect.helpers.readLineBounded
import org.kde.kdeconnect.helpers.security.SslHelper
import org.kde.kdeconnect.tsbridge.tsbridge.ListenerHandle
import org.kde.kdeconnect.tsbridge.tsbridge.UDPConn
import org.kde.kdeconnect.tsbridge.tsbridge.UDPRead
import org.kde.kdeconnect.ui.CustomDevicesActivity
import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HandshakeCompletedEvent
import javax.net.ssl.SSLSocket

class TailscaleLinkProvider(private val context: Context) : BaseLinkProvider() {

    private val visibleDevices = ConcurrentHashMap<String, TailscaleLink>()

    @Volatile
    private var tcpServer: ListenerHandle? = null

    @Volatile
    private var udpServer: UDPConn? = null

    @Volatile
    private var isStopped = true

    private val customDevicePreferences: SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    private val customDeviceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CustomDevicesActivity.KEY_CUSTOM_DEVICE_LIST_PREFERENCE) {
            Log.i(TAG, "Custom devices list changed, broadcasting Tailscale discovery")
            broadcastDiscovery()
        }
    }

    private val stateListener = TailscaleTunnelManager.StateListener { state, localIp, error ->
        if (state == TailscaleTunnelManager.State.ONLINE) {
            Log.i(TAG, "Tailscale tunnel online ($localIp), starting listeners")
            startTailscaleListeners()
            broadcastDiscovery()
        } else {
            Log.i(TAG, "Tailscale tunnel status: $state, stopping listeners")
            stopTailscaleListeners()
        }
    }

    override val name: String = "TailscaleLinkProvider"
    override val priority: Int = 15

    override fun onStart() {
        Log.i(TAG, "onStart")
        isStopped = false
        customDevicePreferences.registerOnSharedPreferenceChangeListener(customDeviceListener)
        TailscaleTunnelManager.addStateListener(stateListener)
        if (TailscaleTunnelManager.isEnabled(context)) {
            TailscaleTunnelManager.start(context)
        }
    }

    override fun onStop() {
        Log.i(TAG, "onStop")
        isStopped = true
        customDevicePreferences.unregisterOnSharedPreferenceChangeListener(customDeviceListener)
        TailscaleTunnelManager.removeStateListener(stateListener)
        stopTailscaleListeners()
    }

    override fun onNetworkChange(network: Network?) {
        if (!isStopped && TailscaleTunnelManager.isEnabled(context)) {
            if (TailscaleTunnelManager.state == TailscaleTunnelManager.State.ONLINE) {
                broadcastDiscovery()
            } else if (TailscaleTunnelManager.state == TailscaleTunnelManager.State.ERROR ||
                       TailscaleTunnelManager.state == TailscaleTunnelManager.State.STOPPED) {
                TailscaleTunnelManager.start(context)
            }
        }
    }

    override fun onConnectionLost(link: BaseLink) {
        val deviceId = link.deviceId
        if (visibleDevices.remove(deviceId, link)) {
            super.onConnectionLost(link)
        }
    }

    @Synchronized
    private fun startTailscaleListeners() {
        if (isStopped) return
        setupTcpListener()
        setupUdpListener()
    }

    @Synchronized
    private fun stopTailscaleListeners() {
        try {
            tcpServer?.close()
        } catch (_: Throwable) {}
        tcpServer = null

        try {
            udpServer?.close()
        } catch (_: Throwable) {}
        udpServer = null
    }

    private fun setupTcpListener() {
        if (tcpServer != null) return
        ThreadHelper.runOnNewThread {
            try {
                val server = TailscaleTunnelManager.listenTcp(TCP_PORT)
                tcpServer = server
                Log.i(TAG, "Tailscale TCP listener bound to port $TCP_PORT")
                while (!isStopped && tcpServer === server) {
                    val conn = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (!isStopped) Log.e(TAG, "Tailscale TCP accept error", e)
                        break
                    }
                    val remoteHost = conn.remoteAddrHost()
                    val remotePort = conn.remoteAddrPort().toInt()
                    val socket = TailscaleSocket(conn, remoteHost, remotePort)
                    ThreadHelper.runOnNewThread {
                        try {
                            tcpPacketReceived(socket)
                        } catch (e: Exception) {
                            socket.closeSafe()
                            Log.e(TAG, "Exception receiving incoming TCP connection over Tailscale", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Tailscale TCP listener", e)
            }
        }
    }

    private fun setupUdpListener() {
        if (udpServer != null) return
        ThreadHelper.runOnNewThread {
            try {
                val udp = TailscaleTunnelManager.listenUdp(UDP_PORT)
                udpServer = udp
                Log.i(TAG, "Tailscale UDP listener bound to port $UDP_PORT")
                while (!isStopped && udpServer === udp) {
                    val packet = try {
                        udp.readFrom(MAX_UDP_PACKET_SIZE.toLong(), 0)
                    } catch (e: Exception) {
                        if (!isStopped) Log.e(TAG, "Tailscale UDP read error", e)
                        break
                    } ?: continue

                    ThreadHelper.runOnNewThread {
                        try {
                            udpPacketReceived(packet)
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception receiving UDP broadcast over Tailscale", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Tailscale UDP listener", e)
            }
        }
    }

    /**
     * Broadcasts discovery to all custom devices configured in "通过 IP 添加设备" (CustomDevicesActivity).
     */
    fun broadcastDiscovery() {
        if (TailscaleTunnelManager.state != TailscaleTunnelManager.State.ONLINE) {
            return
        }

        ThreadHelper.runOnNewThread {
            val customDevices = CustomDevicesActivity.getCustomDeviceList(context)
            Log.i(TAG, "Broadcasting Tailscale discovery to ${customDevices.size} custom device(s)")
            for (device in customDevices) {
                val host = device.host
                if (host.isNotBlank()) {
                    discoverDevice(host)
                }
            }
        }
    }

    fun discoverDevice(targetHost: String) {
        ThreadHelper.runOnNewThread {
            // 1. Send Tailscale UDP discovery packet (standard KDE Connect discovery)
            sendUdpIdentityPacket(targetHost)

            // 2. Direct TCP connection with protocol-compliant SSL server role
            tryDirectTcpConnect(targetHost)
        }
    }

    private fun sendUdpIdentityPacket(targetHost: String) {
        val udp = udpServer ?: return
        val myDeviceInfo = DeviceHelper.getDeviceInfo(context)
        val identity = myDeviceInfo.toIdentityPacket()
        identity["tcpPort"] = TCP_PORT

        val bytes = try {
            identity.serialize().toByteArray(Charsets.UTF_8)
        } catch (e: SerializationException) {
            Log.e(TAG, "Failed to serialize identity packet", e)
            return
        }

        try {
            udp.writeTo(bytes, targetHost, UDP_PORT.toLong())
            Log.i(TAG, "Sent Tailscale UDP identity packet to $targetHost:$UDP_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Tailscale UDP identity to $targetHost", e)
        }
    }

    private fun tryDirectTcpConnect(targetHost: String) {
        ThreadHelper.runOnNewThread {
            var rawSocket: TailscaleSocket? = null
            try {
                Log.i(TAG, "Attempting direct TCP connection to $targetHost:$TCP_PORT over Tailscale")
                rawSocket = TailscaleTunnelManager.dial(targetHost, TCP_PORT, 5000)

                val myDeviceInfo = DeviceHelper.getDeviceInfo(context)
                val myIdentity = myDeviceInfo.toIdentityPacket()
                val out = rawSocket.getOutputStream()
                out.write(myIdentity.serialize().toByteArray(Charsets.UTF_8))
                out.flush()

                // In KDE Connect, the TCP client acts as the SSL server (clientMode = false)
                val sslSocket = SslHelper.convertToSslSocket(
                    context, rawSocket,
                    deviceId = "",
                    isDeviceTrusted = false,
                    clientMode = false
                )

                sslSocket.addHandshakeCompletedListener { event: HandshakeCompletedEvent ->
                    ThreadHelper.runOnNewThread {
                        try {
                            val writer = sslSocket.getOutputStream()
                            writer.write(myIdentity.serialize().toByteArray(Charsets.UTF_8))
                            writer.flush()

                            val line = readLineBounded(sslSocket.getInputStream(), MAX_IDENTITY_PACKET_SIZE)
                            val secureIdentityPacket = NetworkPacket.unserialize(line)
                            if (!DeviceInfo.isValidIdentityPacket(secureIdentityPacket)) {
                                Log.e(TAG, "Secure identity packet isn't valid from direct TCP")
                                sslSocket.closeSafe()
                                return@runOnNewThread
                            }

                            val certificate = event.peerCertificates[0]
                            val deviceInfo = DeviceInfo.fromIdentityPacketAndCert(secureIdentityPacket, certificate)
                            Log.i(TAG, "Direct TCP handshake successful with ${deviceInfo.name} ($targetHost)")
                            addOrUpdateLink(sslSocket, deviceInfo)
                        } catch (e: Exception) {
                            Log.e(TAG, "Direct TCP post-handshake failed with $targetHost", e)
                            sslSocket.closeSafe()
                        }
                    }
                }

                sslSocket.startHandshake()
            } catch (e: Exception) {
                rawSocket?.closeSafe()
                Log.d(TAG, "Direct TCP connection to $targetHost not completed: ${e.message}")
            }
        }
    }

    @WorkerThread
    private fun tcpPacketReceived(socket: TailscaleSocket) {
        val host = socket.inetAddress?.hostAddress ?: "unknown"
        Log.i(TAG, "Tailscale incoming TCP connection received from $host")

        val message = try {
            readLineBounded(socket.getInputStream(), MAX_IDENTITY_PACKET_SIZE)
        } catch (e: Exception) {
            Log.e(TAG, "Exception reading TCP packet from $host", e)
            socket.closeSafe()
            return
        }

        val (identityPacket, deviceTrusted) = unserializeReceivedIdentityPacket(message) ?: run {
            Log.w(TAG, "Invalid identity packet received from $host")
            socket.closeSafe()
            return
        }

        Log.i(TAG, "Identity packet received via Tailscale TCP from ${identityPacket.getString("deviceName")}")

        val targetDeviceId = identityPacket.getStringOrNull("targetDeviceId")
        val targetProtocolVersion = identityPacket.getIntOrNull("targetProtocolVersion")
        if (targetDeviceId != null && targetDeviceId != DeviceHelper.getDeviceId(context)) {
            Log.e(TAG, "Connection request for different device: $targetDeviceId")
            socket.closeSafe()
            return
        }
        if (targetProtocolVersion != null && targetProtocolVersion != DeviceHelper.PROTOCOL_VERSION) {
            Log.e(TAG, "Unsupported protocol version: $targetProtocolVersion")
            socket.closeSafe()
            return
        }

        identityPacketReceived(identityPacket, socket, ConnectionStarted.Locally, deviceTrusted)
    }

    @WorkerThread
    private fun udpPacketReceived(packet: UDPRead) {
        val host = packet.fromHost

        val message = String(packet.data, 0, packet.data.size, Charsets.UTF_8)
        val (identityPacket, deviceTrusted) = unserializeReceivedIdentityPacket(message) ?: return

        Log.i(TAG, "Broadcast identity received from ${identityPacket.getString("deviceName")} at $host")

        val tcpPort = identityPacket.getInt("tcpPort", TCP_PORT)
        var socket: TailscaleSocket? = null
        try {
            socket = TailscaleTunnelManager.dial(host, tcpPort, 10000)

            val myDeviceInfo = DeviceHelper.getDeviceInfo(context)
            val myIdentity = myDeviceInfo.toIdentityPacket()
            myIdentity["targetDeviceId"] = identityPacket.getString("deviceId")
            myIdentity["targetProtocolVersion"] = identityPacket.getString("protocolVersion")

            val out = socket.getOutputStream()
            out.write(myIdentity.serialize().toByteArray(Charsets.UTF_8))
            out.flush()

            identityPacketReceived(identityPacket, socket, ConnectionStarted.Remotely, deviceTrusted)
            socket = null
        } catch (e: Exception) {
            Log.e(TAG, "Exception connecting back to $host:$tcpPort", e)
        } finally {
            socket?.closeSafe()
        }
    }

    private fun unserializeReceivedIdentityPacket(message: String): Pair<NetworkPacket, Boolean>? {
        val identityPacket = try {
            NetworkPacket.unserialize(message)
        } catch (e: SerializationException) {
            Log.w(TAG, "Invalid identity packet: ${e.message}")
            return null
        }

        if (!DeviceInfo.isValidIdentityPacket(identityPacket)) {
            Log.w(TAG, "Invalid identity packet received")
            return null
        }

        val deviceId = identityPacket.getString("deviceId")
        val myId = DeviceHelper.getDeviceId(context)
        if (deviceId == myId) return null

        val deviceTrusted = TrustedDevices.isTrustedDevice(context, deviceId)
        return Pair(identityPacket, deviceTrusted)
    }

    @WorkerThread
    @Throws(IOException::class, CertificateException::class)
    private fun identityPacketReceived(
        identityPacket: NetworkPacket,
        socket: TailscaleSocket,
        connectionStarted: ConnectionStarted,
        deviceTrusted: Boolean
    ) {
        val deviceId = identityPacket.getString("deviceId")
        val protocolVersion = identityPacket.getInt("protocolVersion")

        if (deviceTrusted && !TrustedDevices.isCertificateStored(context, deviceId)) {
            Log.e(TAG, "Device trusted but no cert stored: $deviceId")
            socket.closeSafe()
            return
        }

        Log.i(TAG, "Starting Tailscale SSL handshake with $deviceId (trusted=$deviceTrusted, started=$connectionStarted)")

        val clientMode = (connectionStarted == ConnectionStarted.Locally)
        val sslSocket = SslHelper.convertToSslSocket(context, socket, deviceId, deviceTrusted, clientMode)
        sslSocket.addHandshakeCompletedListener { event: HandshakeCompletedEvent ->
            ThreadHelper.runOnNewThread {
                val mode = if (clientMode) "client" else "server"
                try {
                    val secureIdentityPacket: NetworkPacket?
                    if (protocolVersion >= 8) {
                        val myDeviceInfo = DeviceHelper.getDeviceInfo(context)
                        val myIdentity = myDeviceInfo.toIdentityPacket()

                        val writer = sslSocket.getOutputStream()
                        writer.write(myIdentity.serialize().toByteArray(Charsets.UTF_8))
                        writer.flush()
                        val line = readLineBounded(sslSocket.getInputStream(), MAX_IDENTITY_PACKET_SIZE)
                        secureIdentityPacket = NetworkPacket.unserialize(line)
                        if (!DeviceInfo.isValidIdentityPacket(secureIdentityPacket)) {
                            Log.e(TAG, "Secure identity packet isn't valid")
                            sslSocket.closeSafe()
                            return@runOnNewThread
                        }
                    } else {
                        secureIdentityPacket = identityPacket
                    }
                    val certificate = event.peerCertificates[0]
                    val deviceInfo = DeviceInfo.fromIdentityPacketAndCert(secureIdentityPacket, certificate)
                    Log.i(TAG, "Tailscale handshake as $mode successful with ${deviceInfo.name}")
                    addOrUpdateLink(sslSocket, deviceInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "Tailscale handshake as $mode failed with $deviceId", e)
                    sslSocket.closeSafe()
                }
            }
        }

        sslSocket.startHandshake()
    }

    @WorkerThread
    @Synchronized
    private fun addOrUpdateLink(socket: SSLSocket, deviceInfo: DeviceInfo) {
        var linkCreated = false
        val link = visibleDevices.computeIfAbsent(deviceInfo.id) {
            linkCreated = true
            TailscaleLink(context, this, deviceInfo, socket)
        }

        if (linkCreated) {
            Log.i(TAG, "Creating new Tailscale link for ${deviceInfo.name} (${deviceInfo.id})")
            onConnectionReceived(link)
            link.startListening()
        } else {
            if (link.deviceInfo.certificate != deviceInfo.certificate) {
                Log.e(TAG, "Certificate mismatch for ${deviceInfo.id}, aborting")
                socket.closeSafe()
                return
            }
            Log.i(TAG, "Reusing existing Tailscale link for ${deviceInfo.name} (${deviceInfo.id})")
            link.reset(socket, deviceInfo)
            onDeviceInfoUpdated(deviceInfo)
        }
    }

    private enum class ConnectionStarted {
        Locally, Remotely
    }

    companion object {
        private const val TAG = "KDE/TailscaleLinkProvider"
        const val TCP_PORT: Int = 1716
        const val UDP_PORT: Int = 1716
        private const val MAX_IDENTITY_PACKET_SIZE: Int = 1024 * 512
        private const val MAX_UDP_PACKET_SIZE: Int = 1024 * 512
    }
}
