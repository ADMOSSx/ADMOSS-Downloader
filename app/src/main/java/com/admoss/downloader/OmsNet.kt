package com.admoss.downloader

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** What the sending phone needs to join the receiving phone's private Wi-Fi. Shown as a QR code. */
class OmsCredentials(val ssid: String, val pass: String, val wpa3: Boolean)

/**
 * The radio side. The receiving phone opens a private Wi-Fi network (Wi-Fi Direct group, 5 GHz when the
 * phone supports it, a local-only hotspot when it does not). The sending phone joins it with the details
 * from the QR code. No internet and no router are needed.
 */
object OmsNet {
    private val main = Handler(Looper.getMainLooper())
    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun randomText(n: Int): String {
        val chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return buildString { repeat(n) { append(chars[r.nextInt(chars.length)]) } }
    }

    // ---------- receiving phone: open the private network ----------

    /** Call on the main thread. [onReady] and [onError] are called on the main thread too. */
    @SuppressLint("MissingPermission")
    fun openGroup(ctx: Context, onReady: (OmsCredentials) -> Unit, onError: (String) -> Unit) {
        val app = ctx.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            onError("Turn Wi-Fi on, then try again.")
            return
        }
        val mgr = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        val ch = mgr?.initialize(app, Looper.getMainLooper(), null)
        if (mgr == null || ch == null) {
            openHotspot(app, onReady, onError)
            return
        }
        p2p = mgr
        channel = ch

        // The name must start with DIRECT-xx- for Wi-Fi Direct
        val ssid = "DIRECT-AD-" + randomText(5)
        val pass = randomText(10)

        fun ready(g: WifiP2pGroup?) {
            val name = g?.networkName?.takeIf { it.isNotBlank() } ?: ssid
            val key = g?.passphrase?.takeIf { it.isNotBlank() } ?: pass
            onReady(OmsCredentials(name, key, false))
        }

        fun waitForGroup(tries: Int) {
            mgr.requestGroupInfo(ch) { g ->
                if (g != null && g.isGroupOwner) {
                    ready(g)
                } else if (tries > 0) {
                    main.postDelayed({ waitForGroup(tries - 1) }, 500)
                } else {
                    ready(null)
                }
            }
        }

        fun create(band: Int, onFail: (Int) -> Unit) {
            val cfg = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(pass)
                .enablePersistentMode(false)
                .setGroupOperatingBand(band)
                .build()
            mgr.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    waitForGroup(10)
                }

                override fun onFailure(reason: Int) {
                    onFail(reason)
                }
            })
        }

        fun start() {
            // 5 GHz first (much faster for videos), then whatever the phone supports
            create(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) {
                create(WifiP2pConfig.GROUP_OWNER_BAND_AUTO) { reason ->
                    // Wi-Fi Direct does not work on this phone: try a local hotspot instead
                    openHotspot(app, onReady) { msg -> onError("Wi-Fi Direct failed (code $reason). $msg") }
                }
            }
        }

        // remove an old group first, so the new one can start
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                start()
            }

            override fun onFailure(reason: Int) {
                start()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun openHotspot(app: Context, onReady: (OmsCredentials) -> Unit, onError: (String) -> Unit) {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspot = reservation
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val c = reservation.softApConfiguration
                            val wpa3 = c.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
                            onReady(OmsCredentials(c.ssid.orEmpty(), c.passphrase.orEmpty(), wpa3))
                        } else {
                            @Suppress("DEPRECATION")
                            val c = reservation.wifiConfiguration
                            onReady(OmsCredentials(c?.SSID.orEmpty().trim('"'), c?.preSharedKey.orEmpty().trim('"'), false))
                        }
                    } catch (t: Throwable) {
                        onError("Couldn't read the hotspot details.")
                    }
                }

                override fun onFailed(reason: Int) {
                    onError("Couldn't start a private Wi-Fi (code $reason). Turn on Location and try again.")
                }

                override fun onStopped() {}
            }, main)
        } catch (t: Throwable) {
            onError("Couldn't start a private Wi-Fi: ${t.message ?: "not allowed"}")
        }
    }

    // ---------- sending phone: join it ----------

    private fun hostOf(lp: LinkProperties?): String? {
        if (lp == null) return null
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        if (gateway != null) return gateway
        if (Build.VERSION.SDK_INT >= 30) {
            val dhcp = lp.dhcpServerAddress?.hostAddress
            if (dhcp != null) return dhcp
        }
        return null
    }

    /**
     * Joins the other phone's Wi-Fi. Android shows its own "connect" prompt. [onReady] gets the network and
     * the address of the other phone. It is called on a background thread.
     */
    fun join(ctx: Context, cred: OmsCredentials, onReady: (Network, String) -> Unit, onError: (String) -> Unit) {
        val app = ctx.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val specifier = WifiNetworkSpecifier.Builder().setSsid(cred.ssid)
        if (cred.wpa3) specifier.setWpa3Passphrase(cred.pass) else specifier.setWpa2Passphrase(cred.pass)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier.build())
            .build()

        val started = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun go(network: Network, host: String?) {
                // Wi-Fi Direct groups always use 192.168.49.1 when Android does not tell us the address
                if (started.compareAndSet(false, true)) onReady(network, host ?: "192.168.49.1")
            }

            override fun onAvailable(network: Network) {
                val host = hostOf(cm.getLinkProperties(network))
                if (host != null) {
                    go(network, host)
                } else {
                    main.postDelayed({ go(network, hostOf(cm.getLinkProperties(network))) }, 3000)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val host = hostOf(linkProperties)
                if (host != null) go(network, host)
            }

            override fun onUnavailable() {
                main.post { onError("Couldn't join the other phone. Tap Connect when Android asks, and keep the phones close.") }
            }

            override fun onLost(network: Network) {
                OmsCenter.networkLost()
            }
        }
        netCallback = callback
        try {
            cm.requestNetwork(request, callback, 60_000)
        } catch (t: Throwable) {
            onError("Couldn't join the other phone: ${t.message ?: "not allowed"}")
        }
    }

    // ---------- both phones ----------

    /** Keeps Wi-Fi and the CPU awake while files move. */
    @SuppressLint("WakelockTimeout")
    fun holdLocks(ctx: Context) {
        val app = ctx.applicationContext
        try {
            if (wifiLock == null) {
                val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "admoss:share").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wakeLock == null) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "admoss:share").apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L)
                }
            }
        } catch (t: Throwable) {
            // a transfer still works without them
        }
    }

    fun releaseLocks() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            // ignore
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            // ignore
        }
        wifiLock = null
        wakeLock = null
    }

    /** Leaves the other phone's Wi-Fi and closes the private network. Safe to call at any time. */
    fun close(ctx: Context) {
        val app = ctx.applicationContext
        try {
            netCallback?.let {
                (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            }
        } catch (t: Throwable) {
            // ignore
        }
        netCallback = null
        try {
            hotspot?.close()
        } catch (t: Throwable) {
            // ignore
        }
        hotspot = null
        val m = p2p
        val c = channel
        p2p = null
        channel = null
        if (m != null && c != null) {
            try {
                m.removeGroup(c, null)
            } catch (t: Throwable) {
                // ignore
            }
            // let removeGroup finish before the channel is closed
            main.postDelayed({
                try {
                    c.close()
                } catch (t: Throwable) {
                    // ignore
                }
            }, 1500)
        }
    }
}
