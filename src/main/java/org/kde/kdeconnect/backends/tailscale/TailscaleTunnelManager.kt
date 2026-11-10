/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.backends.tailscale

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kde.kdeconnect.tsbridge.tsbridge.ListenerHandle
import org.kde.kdeconnect.tsbridge.tsbridge.Tsbridge
import org.kde.kdeconnect.tsbridge.tsbridge.TunnelHandle
import org.kde.kdeconnect.tsbridge.tsbridge.UDPConn
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

object TailscaleTunnelManager {
    private const val TAG = "KDE/TailscaleManager"

    const val KEY_TAILSCALE_ENABLED = "tailscale_enabled"
    const val KEY_TAILSCALE_AUTHKEY = "tailscale_authkey"
    const val KEY_TAILSCALE_CONTROL_URL = "tailscale_control_url"
    const val KEY_TAILSCALE_HOSTNAME = "tailscale_hostname"
    const val KEY_TAILSCALE_CUSTOM_IPS = "tailscale_custom_ips"

    enum class State {
        STOPPED,
        CONNECTING,
        ONLINE,
        ERROR
    }

    fun interface StateListener {
        fun onStateChanged(state: State, localIp: String?, error: String?)
    }

    private val stateListeners = CopyOnWriteArrayList<StateListener>()

    @Volatile
    var state: State = State.STOPPED
        private set

    @Volatile
    var localIp: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var tunnel: TunnelHandle? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private var startJob: Job? = null

    fun addStateListener(listener: StateListener) {
        stateListeners.add(listener)
        listener.onStateChanged(state, localIp, lastError)
    }

    fun removeStateListener(listener: StateListener) {
        stateListeners.remove(listener)
    }

    private fun notifyStateChanged() {
        for (listener in stateListeners) {
            try {
                listener.onStateChanged(state, localIp, lastError)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying state listener", e)
            }
        }
    }

    fun isEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_TAILSCALE_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(KEY_TAILSCALE_ENABLED, enabled)
        }
    }

    fun getAuthKey(context: Context): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_TAILSCALE_AUTHKEY, "")?.trim() ?: ""
    }

    fun setAuthKey(context: Context, key: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY_TAILSCALE_AUTHKEY, key.trim())
        }
    }

    fun getControlUrl(context: Context): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_TAILSCALE_CONTROL_URL, "")?.trim() ?: ""
    }

    fun setControlUrl(context: Context, url: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY_TAILSCALE_CONTROL_URL, url.trim())
        }
    }

    fun getHostname(context: Context): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_TAILSCALE_HOSTNAME, "tailconnect-android")?.trim() ?: "tailconnect-android"
    }

    fun setHostname(context: Context, hostname: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY_TAILSCALE_HOSTNAME, hostname.trim())
        }
    }

    fun getCustomIps(context: Context): List<String> {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_TAILSCALE_CUSTOM_IPS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun addCustomIp(context: Context, ip: String) {
        val cleaned = ip.trim()
        if (cleaned.isBlank()) return
        val current = getCustomIps(context).toMutableList()
        if (!current.contains(cleaned)) {
            current.add(cleaned)
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString(KEY_TAILSCALE_CUSTOM_IPS, current.joinToString(","))
            }
        }
    }

    fun removeCustomIp(context: Context, ip: String) {
        val cleaned = ip.trim()
        val current = getCustomIps(context).toMutableList()
        if (current.remove(cleaned)) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString(KEY_TAILSCALE_CUSTOM_IPS, current.joinToString(","))
            }
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (!isEnabled(context)) {
            Log.i(TAG, "Tailscale is disabled in preferences")
            return
        }

        if (state == State.ONLINE && tunnel != null) {
            Log.i(TAG, "Tailscale tunnel already active")
            return
        }

        val authKey = getAuthKey(context)
        val stateDir = File(context.filesDir, "tailscale").apply { mkdirs() }
        val hasExistingState = stateDir.listFiles()?.isNotEmpty() == true

        if (authKey.isEmpty() && !hasExistingState) {
            val msg = "Tailscale Auth Key is required for first connect"
            Log.w(TAG, msg)
            state = State.ERROR
            lastError = msg
            notifyStateChanged()
            return
        }

        state = State.CONNECTING
        lastError = null
        notifyStateChanged()

        val hostname = getHostname(context)
        val controlUrl = getControlUrl(context)

        startJob?.cancel()
        startJob = scope.launch {
            try {
                Log.i(TAG, "Starting tsnet tunnel: hostname=$hostname, controlUrl=$controlUrl")
                val handle = Tsbridge.startTunnel(authKey, stateDir.absolutePath, hostname, controlUrl)
                tunnel = handle
                val ip = handle.localIPv4()
                localIp = ip
                state = State.ONLINE
                lastError = null
                Log.i(TAG, "Tailscale tunnel connected successfully! IP: $ip")
                notifyStateChanged()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Tailscale tunnel", t)
                try { tunnel?.close() } catch (_: Throwable) {}
                tunnel = null
                localIp = null
                state = State.ERROR
                lastError = t.message ?: "Unknown error starting Tailscale"
                notifyStateChanged()
            }
        }
    }

    @Synchronized
    fun stop() {
        startJob?.cancel()
        startJob = null
        try {
            tunnel?.close()
        } catch (_: Throwable) {}
        tunnel = null
        localIp = null
        state = State.STOPPED
        lastError = null
        notifyStateChanged()
        Log.i(TAG, "Tailscale tunnel stopped")
    }

    fun getTunnel(): TunnelHandle? = tunnel

    fun dial(host: String, port: Int, timeoutMs: Int = 10000): TailscaleSocket {
        val t = tunnel ?: throw IOException("Tailscale tunnel is not running")
        val conn = t.dial(host, port.toLong(), timeoutMs.toLong())
        return TailscaleSocket(conn, host, port)
    }

    fun listenTcp(port: Int): ListenerHandle {
        val t = tunnel ?: throw IOException("Tailscale tunnel is not running")
        return t.listenTCP(port.toLong())
    }

    fun listenUdp(port: Int): UDPConn {
        val t = tunnel ?: throw IOException("Tailscale tunnel is not running")
        return t.listenUDP(port.toLong())
    }
}
