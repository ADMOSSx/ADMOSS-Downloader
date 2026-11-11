package com.admoss.downloader

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.concurrent.thread

enum class ShareKind { PHOTO, VIDEO, MUSIC }

/** uri = where the file is now (source when sending, saved location when receiving). */
class ShareFile(val uri: Uri?, val name: String, val size: Long, val kind: ShareKind, val mime: String)

class NearbyDevice(val name: String, val info: NsdServiceInfo)

enum class SharePhase { IDLE, WAITING, CONNECTING, REQUEST, TRANSFERRING, DONE, FAILED }

/**
 * Offline transfer of photos, videos and music between two phones. No internet, no server.
 *
 * Protocol (one TCP connection): [magic][device name][count], then per file [name][size][mime].
 * The receiver answers 1 (accept) or 0 (decline), the raw bytes follow, and the receiver ends with a 1.
 */
object ShareCenter {
    private const val MAGIC = 0x41444D53
    private const val BUF = 256 * 1024
    private const val SERVICE_TYPE = "_admoss._tcp."

    var phase by mutableStateOf(SharePhase.IDLE)
    var sending by mutableStateOf(true)
    var qr by mutableStateOf<String?>(null)
    var hotspotFailed by mutableStateOf(false)
    var status by mutableStateOf("")
    var peerName by mutableStateOf("")
    var picked by mutableStateOf<List<ShareFile>>(emptyList())   // chosen on the Send screen
    var files by mutableStateOf<List<ShareFile>>(emptyList())    // files of the current transfer
    var fileIndex by mutableStateOf(0)
    var totalBytes by mutableStateOf(0L)
    var doneBytes by mutableStateOf(0L)
    var speed by mutableStateOf(0L)
    var eta by mutableStateOf(-1L)
    var error by mutableStateOf<String?>(null)
    var nearby by mutableStateOf<List<NearbyDevice>>(emptyList())

    @Volatile private var cancelled = false
    @Volatile private var accepted = false
    @Volatile private var decision: CountDownLatch? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var appCtx: Context? = null
    private val nearbyLock = Any()
    private val main = Handler(Looper.getMainLooper())

    fun deviceName(): String = Build.MODEL ?: "Android"

    fun kindOf(mime: String): ShareKind? = when {
        mime.startsWith("image/") -> ShareKind.PHOTO
        mime.startsWith("video/") -> ShareKind.VIDEO
        mime.startsWith("audio/") -> ShareKind.MUSIC
        else -> null
    }

    /** Reads name, size and type of the picked files. Anything that is not a photo, video or song is dropped. */
    fun describe(ctx: Context, uris: List<Uri>): List<ShareFile> {
        val r = ctx.contentResolver
        return uris.mapNotNull { u ->
            val mime = r.getType(u) ?: return@mapNotNull null
            val kind = kindOf(mime) ?: return@mapNotNull null
            var name = "file"
            var size = 0L
            try {
                r.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        size = if (c.isNull(1)) 0L else c.getLong(1)
                    }
                }
            } catch (e: Throwable) {
                // ignore
            }
            if (size <= 0L) {
                size = try {
                    r.openAssetFileDescriptor(u, "r")?.use { it.length } ?: 0L
                } catch (e: Throwable) {
                    0L
                }
            }
            if (size <= 0L) null else ShareFile(u, name, size, kind, mime)
        }
    }

    // ------------------------------------------------------------------ session helpers

    private fun begin(app: Context, asSender: Boolean) {
        release(true)
        cancelled = false
        accepted = false
        appCtx = app
        sending = asSender
        error = null
        qr = null
        hotspotFailed = false
        peerName = ""
        status = ""
        fileIndex = 0
        doneBytes = 0L
        speed = 0L
        eta = -1L
        files = if (asSender) picked else emptyList()
        totalBytes = files.sumOf { it.size }
    }

    @Synchronized
    private fun fail(msg: String) {
        if (phase == SharePhase.DONE || phase == SharePhase.FAILED) return
        error = msg
        phase = SharePhase.FAILED
        release(false)
    }

    private fun release(keepDiscovery: Boolean) {
        try { socket?.close() } catch (e: Throwable) {}
        try { server?.close() } catch (e: Throwable) {}
        try { reservation?.close() } catch (e: Throwable) {}
        socket = null
        server = null
        reservation = null
        val app = appCtx
        if (app != null) {
            netCallback?.let {
                try {
                    (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
                } catch (e: Throwable) {}
            }
            regListener?.let {
                try {
                    (app.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(it)
                } catch (e: Throwable) {}
            }
        }
        netCallback = null
        regListener = null
        if (!keepDiscovery) stopDiscovery()
    }

    /** Leaves the Share flow completely and clears the screen state. */
    fun leave() {
        val clear = phase == SharePhase.DONE && sending
        cancelled = true
        decision?.countDown()
        release(false)
        phase = SharePhase.IDLE
        qr = null
        status = ""
        error = null
        peerName = ""
        files = emptyList()
        fileIndex = 0
        totalBytes = 0L
        doneBytes = 0L
        speed = 0L
        eta = -1L
        nearby = emptyList()
        if (clear) picked = emptyList()
    }

    fun cancelTransfer() {
        cancelled = true
        accepted = false
        decision?.countDown()
        fail("Cancelled")
    }

    private fun friendly(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "The other phone stopped responding."
        is EOFException, is SocketException -> "The connection was interrupted."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
    }

    // ------------------------------------------------------------------ speed meter

    private var sent = 0L
    private var meterT = 0L
    private var meterB = 0L

    private fun beginMeter() {
        sent = 0L
        meterB = 0L
        meterT = System.nanoTime()
        doneBytes = 0L
        speed = 0L
        eta = -1L
    }

    private fun addBytes(n: Int) {
        sent += n
        val now = System.nanoTime()
        if (now - meterT >= 250_000_000L) {
            val inst = ((sent - meterB) * 1_000_000_000.0 / (now - meterT)).toLong()
            speed = if (speed == 0L) inst else (speed * 0.7 + inst * 0.3).toLong()
            meterT = now
            meterB = sent
            doneBytes = sent
            eta = if (speed > 0) (totalBytes - sent) / speed else -1L
        }
    }

    // ------------------------------------------------------------------ RECEIVE

    fun startReceive(ctx: Context) {
        val app = ctx.applicationContext
        begin(app, false)
        phase = SharePhase.WAITING
        status = "Getting ready…"
        val srv = try {
            ServerSocket().apply {
                reuseAddress = true
                receiveBufferSize = 1 shl 20
                bind(InetSocketAddress(0))
            }
        } catch (e: Throwable) {
            fail("Couldn't open a connection: ${friendly(e)}")
            return
        }
        server = srv
        startHotspot(app, srv.localPort)
        registerNsd(app, srv.localPort)
        thread(name = "admoss-share-accept") { acceptLoop(app, srv) }
    }

    @Suppress("DEPRECATION")
    private fun startHotspot(app: Context, port: Int) {
        val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val ssid: String?
                    val pass: String?
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = r.softApConfiguration
                        ssid = c.ssid
                        pass = c.passphrase
                    } else {
                        val c = r.wifiConfiguration
                        ssid = c?.SSID?.trim('"')
                        pass = c?.preSharedKey?.trim('"')
                    }
                    if (ssid.isNullOrBlank() || pass.isNullOrBlank()) {
                        onHotspotFailed()
                        return
                    }
                    qr = JSONObject().put("a", "ADMOSS").put("s", ssid).put("p", pass)
                        .put("port", port).put("n", deviceName()).toString()
                    status = "Scan this code with the other phone"
                }

                override fun onFailed(reason: Int) = onHotspotFailed()
                override fun onStopped() {}
            }, main)
        } catch (e: Throwable) {
            onHotspotFailed()
        }
    }

    private fun onHotspotFailed() {
        hotspotFailed = true
        qr = null
        status = "No code available. Put both phones on the same Wi-Fi: this phone will show up in the other phone's Nearby list. " +
            "To use the code, turn Location on and turn this phone's own hotspot off, then try again."
    }

    private fun registerNsd(app: Context, port: Int) {
        try {
            val nm = app.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = deviceName()
                serviceType = SERVICE_TYPE
                this.port = port
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            regListener = l
            nm.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Throwable) {
            regListener = null
        }
    }

    private fun acceptLoop(app: Context, srv: ServerSocket) {
        try {
            while (!srv.isClosed && !cancelled) {
                val s = srv.accept()
                if (phase != SharePhase.WAITING) {
                    s.close()
                    continue
                }
                if (handleIncoming(app, s)) return
            }
        } catch (e: Throwable) {
            // An old loop must never break a newer session
            if (srv !== server) return
            if (!cancelled && phase == SharePhase.WAITING) fail(friendly(e))
        }
    }

    /** Returns true when the session is over (done or failed), false to keep waiting for another sender. */
    private fun handleIncoming(app: Context, s: Socket): Boolean {
        socket = s
        var requested = false
        try {
            s.soTimeout = 30000
            val inp = DataInputStream(BufferedInputStream(s.getInputStream(), BUF))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            if (inp.readInt() != MAGIC) {
                s.close()
                return false
            }
            val name = inp.readUTF()
            val n = inp.readInt()
            if (n !in 1..5000) {
                s.close()
                return false
            }
            val list = ArrayList<ShareFile>()
            var total = 0L
            repeat(n) {
                val fname = inp.readUTF()
                val size = inp.readLong()
                val mime = inp.readUTF()
                val kind = kindOf(mime) ?: throw IOException("Only photos, videos and music can be received.")
                if (size < 0L) throw IOException("Bad file size.")
                list.add(ShareFile(null, safeFileName(fname), size, kind, mime))
                total += size
            }

            // Ask the user
            sending = false
            peerName = name
            files = list
            totalBytes = total
            fileIndex = 0
            val latch = CountDownLatch(1)
            decision = latch
            accepted = false
            requested = true
            phase = SharePhase.REQUEST
            s.soTimeout = 0
            latch.await(120, TimeUnit.SECONDS)

            if (!accepted || cancelled) {
                try {
                    out.writeByte(0)
                    out.flush()
                } catch (e: Throwable) {}
                s.close()
                if (!cancelled) {
                    files = emptyList()
                    phase = SharePhase.WAITING
                }
                return false
            }
            out.writeByte(1)
            out.flush()
            phase = SharePhase.TRANSFERRING
            val saved = receiveFiles(app, inp, list)
            out.writeByte(1)
            out.flush()
            files = saved
            doneBytes = totalBytes
            phase = SharePhase.DONE
            release(false)
            return true
        } catch (e: Throwable) {
            if (!requested) {
                try { s.close() } catch (x: Throwable) {}
                return false
            }
            fail(if (cancelled) "Cancelled" else friendly(e))
            return true
        }
    }

    fun accept() {
        accepted = true
        decision?.countDown()
    }

    fun decline() {
        accepted = false
        decision?.countDown()
    }

    private fun target(k: ShareKind): Pair<Uri, String> = when (k) {
        ShareKind.PHOTO -> Pair(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Pictures/ADMOSS Share")
        ShareKind.VIDEO -> Pair(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Movies/ADMOSS Share")
        ShareKind.MUSIC -> Pair(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Music/ADMOSS Share")
    }

    private fun safeFileName(n: String): String {
        val base = n.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return base.ifBlank { "file" }.take(120)
    }

    private fun receiveFiles(app: Context, inp: DataInputStream, list: List<ShareFile>): List<ShareFile> {
        val resolver = app.contentResolver
        val buf = ByteArray(BUF)
        val saved = ArrayList<ShareFile>()
        beginMeter()
        for ((i, f) in list.withIndex()) {
            fileIndex = i
            val (collection, folder) = target(f.kind)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, f.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the file.")
            var ok = false
            try {
                val os = resolver.openOutputStream(uri) ?: throw IOException("Could not write the file.")
                os.use {
                    var left = f.size
                    while (left > 0L) {
                        val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) throw EOFException()
                        it.write(buf, 0, r)
                        left -= r
                        addBytes(r)
                    }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                ok = true
                saved.add(ShareFile(uri, f.name, f.size, f.kind, f.mime))
            } finally {
                if (!ok) {
                    try { resolver.delete(uri, null, null) } catch (e: Throwable) {}
                }
            }
        }
        return saved
    }

    // ------------------------------------------------------------------ SEND

    /** [qrText] is the text of the code shown by the receiving phone. */
    fun startSend(ctx: Context, qrText: String) {
        val app = ctx.applicationContext
        begin(app, true)
        val o = try { JSONObject(qrText) } catch (e: Throwable) { null }
        if (o == null || o.optString("a") != "ADMOSS") {
            fail("That is not an ADMOSS code.")
            return
        }
        val ssid = o.optString("s")
        val pass = o.optString("p")
        val port = o.optInt("port")
        peerName = o.optString("n").ifBlank { "the other phone" }
        phase = SharePhase.CONNECTING
        status = "Joining $peerName's network…"

        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pass).build()
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
            var started = false
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (started) return
                    started = true
                    thread(name = "admoss-share-send") {
                        val host = gatewayOf(cm, network)
                        if (host == null) {
                            fail("Couldn't find the other phone.")
                            return@thread
                        }
                        runSend(app, network.socketFactory, host, port)
                    }
                }

                override fun onUnavailable() = fail("Couldn't join the other phone's network.")

                override fun onLost(network: Network) {
                    if (phase == SharePhase.TRANSFERRING || phase == SharePhase.CONNECTING) fail("The connection was lost.")
                }
            }
            netCallback = cb
            cm.requestNetwork(req, cb, main, 30000)
        } catch (e: Throwable) {
            fail("Couldn't connect: ${friendly(e)}")
        }
    }

    private fun gatewayOf(cm: ConnectivityManager, n: Network): String? {
        repeat(12) {
            val lp = cm.getLinkProperties(n)
            if (lp != null) {
                lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                    ?.gateway?.hostAddress?.let { return it }
                lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.address?.let { b ->
                    return "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}.1"
                }
            }
            Thread.sleep(300)
        }
        return null
    }

    // ---- nearby phones on the same network (NSD)

    fun startDiscovery(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        stopDiscovery()
        nearby = emptyList()
        try {
            val nm = app.getSystemService(Context.NSD_SERVICE) as NsdManager
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onServiceFound(i: NsdServiceInfo) {
                    if (!i.serviceType.contains("_admoss")) return
                    synchronized(nearbyLock) {
                        if (nearby.none { it.name == i.serviceName }) nearby = nearby + NearbyDevice(i.serviceName, i)
                    }
                }

                override fun onServiceLost(i: NsdServiceInfo) {
                    synchronized(nearbyLock) { nearby = nearby.filter { it.name != i.serviceName } }
                }

                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, code: Int) {}
                override fun onStopDiscoveryFailed(t: String, code: Int) {}
            }
            discListener = l
            nm.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Throwable) {
            discListener = null
        }
    }

    fun stopDiscovery() {
        val l = discListener ?: return
        discListener = null
        try {
            (appCtx?.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.stopServiceDiscovery(l)
        } catch (e: Throwable) {}
    }

    @Suppress("DEPRECATION")
    fun sendTo(ctx: Context, d: NearbyDevice) {
        val app = ctx.applicationContext
        begin(app, true)
        peerName = d.name
        phase = SharePhase.CONNECTING
        status = "Connecting to ${d.name}…"
        try {
            val nm = app.getSystemService(Context.NSD_SERVICE) as NsdManager
            nm.resolveService(d.info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) = fail("Couldn't reach ${d.name}.")

                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host?.hostAddress
                    if (host == null) {
                        fail("Couldn't reach ${d.name}.")
                        return
                    }
                    stopDiscovery()
                    thread(name = "admoss-share-send") { runSend(app, SocketFactory.getDefault(), host, i.port) }
                }
            })
        } catch (e: Throwable) {
            fail(friendly(e))
        }
    }

    // ---- the actual sending

    private fun connectWithRetry(factory: SocketFactory, host: String, port: Int): Socket {
        var tries = 0
        while (true) {
            val c = factory.createSocket()
            try {
                c.sendBufferSize = 1 shl 20
                c.connect(InetSocketAddress(host, port), 5000)
                return c
            } catch (e: IOException) {
                try { c.close() } catch (x: Throwable) {}
                if (cancelled || ++tries >= 6) throw e
                Thread.sleep(700)
            }
        }
    }

    private fun runSend(app: Context, factory: SocketFactory, host: String, port: Int) {
        try {
            val s = connectWithRetry(factory, host, port)
            socket = s
            if (cancelled) {
                s.close()
                return
            }
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), BUF))
            val inp = DataInputStream(s.getInputStream())
            val list = files

            out.writeInt(MAGIC)
            out.writeUTF(deviceName())
            out.writeInt(list.size)
            for (f in list) {
                out.writeUTF(f.name)
                out.writeLong(f.size)
                out.writeUTF(f.mime)
            }
            out.flush()

            status = "Waiting for $peerName to accept…"
            s.soTimeout = 130000
            if (inp.readByte().toInt() != 1) {
                fail("$peerName declined the files.")
                return
            }
            phase = SharePhase.TRANSFERRING
            beginMeter()
            val buf = ByteArray(BUF)
            for ((i, f) in list.withIndex()) {
                fileIndex = i
                var left = f.size
                val ins = app.contentResolver.openInputStream(f.uri!!) ?: throw IOException("Can't read ${f.name}.")
                ins.use {
                    while (left > 0L) {
                        val r = it.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) throw EOFException()
                        out.write(buf, 0, r)
                        left -= r
                        addBytes(r)
                    }
                }
            }
            out.flush()
            inp.readByte() // the receiver confirms that everything is saved
            doneBytes = totalBytes
            phase = SharePhase.DONE
            release(false)
        } catch (e: Throwable) {
            fail(if (cancelled) "Cancelled" else friendly(e))
        }
    }
}
