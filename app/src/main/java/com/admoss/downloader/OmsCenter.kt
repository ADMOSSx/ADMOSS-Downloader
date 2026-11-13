package com.admoss.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class OmsPhase {
    IDLE,
    PREPARING,
    WAITING,
    JOINING,
    CONNECTING,
    REQUEST,
    TRANSFERRING,
    DONE,
    FAILED,
    REJECTED,
    CANCELLED
}

internal fun omsSize(bytes: Long): String = when {
    bytes >= (1L shl 30) -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= (1L shl 20) -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format(Locale.US, "%d KB", bytes / 1024L)
    else -> "$bytes B"
}

/** Counts the bytes of a transfer and tells the screen about every 300 ms (with a smoothed speed). */
private class OmsMeter(private val post: (Long, Long) -> Unit) {
    private var done = 0L
    private var lastTime = 0L
    private var lastBytes = 0L
    private var speed = 0.0

    fun add(n: Int) {
        done += n
        val now = System.nanoTime()
        if (now - lastTime >= 300_000_000L) tick(now)
    }

    private fun tick(now: Long) {
        if (lastTime != 0L) {
            val seconds = (now - lastTime) / 1e9
            if (seconds > 0) {
                val instant = (done - lastBytes) / seconds
                speed = if (speed == 0.0) instant else speed * 0.6 + instant * 0.4
            }
        }
        lastTime = now
        lastBytes = done
        post(done, speed.toLong())
    }

    fun flush() {
        post(done, speed.toLong())
    }
}

/**
 * Offline media sharing: the state the screens show, and the work of sending and receiving.
 * Compose state is only changed on the main thread; background threads go through [ui].
 */
object OmsCenter {
    var phase by mutableStateOf(OmsPhase.IDLE)

    /** 0 home, 1 pick, 2 review, 3 scan, 4 receive */
    var step by mutableStateOf(0)
    var sending by mutableStateOf(true)
    var selected by mutableStateOf<List<OmsMediaItem>>(emptyList())
    var qrText by mutableStateOf<String?>(null)
    var myName by mutableStateOf("")
    var peerName by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var request by mutableStateOf<List<OmsFile>?>(null)
    var fileCount by mutableStateOf(0)
    var fileIndex by mutableStateOf(0)
    var currentName by mutableStateOf("")
    var totalBytes by mutableStateOf(0L)
    var doneBytes by mutableStateOf(0L)
    var speed by mutableStateOf(0L)
    var summary by mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())
    private val decisions = LinkedBlockingQueue<Boolean>()
    private var appCtx: Context? = null

    /** Changes with every new session, so a late callback of an old one is ignored. */
    @Volatile
    private var epoch = 0

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var cancelled = false

    private fun ui(e: Int, block: () -> Unit) {
        main.post { if (e == epoch) block() }
    }

    private fun dead(e: Int): Boolean = cancelled || e != epoch

    fun deviceName(ctx: Context): String = try {
        Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL
    } catch (t: Throwable) {
        Build.MODEL
    }

    // ---------- session control (main thread) ----------

    private fun begin(ctx: Context, isSending: Boolean): Int {
        val app = ctx.applicationContext
        appCtx = app
        stopAll(app)
        cancelled = false
        epoch += 1
        sending = isSending
        qrText = null
        peerName = ""
        message = null
        request = null
        fileCount = 0
        fileIndex = 0
        currentName = ""
        totalBytes = 0L
        doneBytes = 0L
        speed = 0L
        summary = null
        myName = deviceName(app)
        return epoch
    }

    private fun stopAll(app: Context) {
        cancelled = true
        decisions.offer(false)
        endNetwork(app)
    }

    private fun endNetwork(app: Context) {
        try {
            socket?.close()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            server?.close()
        } catch (t: Throwable) {
            // ignore
        }
        socket = null
        server = null
        OmsNet.releaseLocks()
        OmsNet.close(app)
    }

    private fun fail(e: Int, text: String) {
        main.post {
            if (e != epoch) return@post
            message = text
            phase = OmsPhase.FAILED
            appCtx?.let { endNetwork(it) }
        }
    }

    /** The other phone's Wi-Fi went away. Called from the network callback thread. */
    fun networkLost() {
        val e = epoch
        main.post {
            if (e == epoch && sending && (phase == OmsPhase.TRANSFERRING || phase == OmsPhase.CONNECTING)) {
                fail(e, "The Wi-Fi connection to the other phone was lost.")
            }
        }
    }

    fun cancel(ctx: Context) {
        stopAll(ctx.applicationContext)
        epoch += 1
        message = "Transfer cancelled."
        phase = OmsPhase.CANCELLED
    }

    /** Back to the start screen. */
    fun finish(ctx: Context) {
        stopAll(ctx.applicationContext)
        epoch += 1
        phase = OmsPhase.IDLE
        step = 0
        qrText = null
        message = null
        request = null
        summary = null
        selected = emptyList()
    }

    /** After a failure: go back to the scan screen (sender) or make a new code (receiver). */
    fun retry(ctx: Context) {
        stopAll(ctx.applicationContext)
        epoch += 1
        phase = OmsPhase.IDLE
        message = null
        request = null
        qrText = null
        step = if (sending) 3 else 4
    }

    /** The back button / back arrow. */
    fun back(ctx: Context) {
        when (phase) {
            OmsPhase.TRANSFERRING -> {}
            OmsPhase.JOINING, OmsPhase.CONNECTING, OmsPhase.REQUEST -> cancel(ctx)
            OmsPhase.DONE, OmsPhase.FAILED, OmsPhase.REJECTED, OmsPhase.CANCELLED -> finish(ctx)
            OmsPhase.PREPARING, OmsPhase.WAITING -> {
                stopAll(ctx.applicationContext)
                epoch += 1
                phase = OmsPhase.IDLE
                qrText = null
                step = 0
            }
            OmsPhase.IDLE -> step = when (step) {
                3 -> 2
                2 -> 1
                else -> 0
            }
        }
    }

    /** The receiving person tapped Accept or Decline. */
    fun answer(accept: Boolean) {
        decisions.offer(accept)
    }

    fun toggle(item: OmsMediaItem) {
        selected = if (selected.any { it.uri == item.uri }) {
            selected.filter { it.uri != item.uri }
        } else {
            selected + item
        }
    }

    // ---------- receiving ----------

    fun startReceiving(ctx: Context) {
        val app = ctx.applicationContext
        val e = begin(app, false)
        phase = OmsPhase.PREPARING
        val token = OmsNet.randomText(16)
        thread(name = "oms-server") {
            val srv = try {
                ServerSocket(0)
            } catch (t: Throwable) {
                fail(e, "Couldn't get ready to receive: ${t.message ?: "unknown error"}")
                return@thread
            }
            server = srv
            val port = srv.localPort
            ui(e) {
                OmsNet.openGroup(
                    app,
                    onReady = { cred ->
                        if (e == epoch) {
                            qrText = OmsQr.payload(cred, port, token, myName)
                            phase = OmsPhase.WAITING
                            thread(name = "oms-accept") { acceptLoop(app, e, srv, token) }
                        }
                    },
                    onError = { text -> fail(e, text) }
                )
            }
        }
    }

    private fun acceptLoop(app: Context, e: Int, srv: ServerSocket, token: String) {
        while (!dead(e) && !srv.isClosed) {
            val s = try {
                srv.accept()
            } catch (t: Throwable) {
                return
            }
            socket = s
            var ended = false
            try {
                ended = handleIncoming(app, e, s, token)
            } catch (t: Throwable) {
                if (!dead(e)) {
                    fail(e, "The connection was interrupted. Nothing more was saved.")
                    ended = true
                }
            } finally {
                try {
                    s.close()
                } catch (t: Throwable) {
                    // ignore
                }
            }
            if (ended || dead(e)) return
        }
    }

    /** Returns true when the session is over (finished, failed or cancelled). */
    private fun handleIncoming(app: Context, e: Int, s: Socket, token: String): Boolean {
        s.soTimeout = 30_000
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
        val output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))

        if (input.readUTF() != OmsProtocol.MAGIC) return false
        if (input.readUTF() != token) {
            output.writeByte(2)
            output.flush()
            return false
        }
        val peer = input.readUTF().take(60).ifBlank { "Another phone" }
        val count = input.readInt()
        if (count < 1 || count > OmsProtocol.MAX_FILES) {
            output.writeByte(0)
            output.flush()
            return false
        }
        val files = ArrayList<OmsFile>(count)
        var total = 0L
        for (i in 0 until count) {
            val name = OmsProtocol.cleanName(input.readUTF())
            val size = input.readLong()
            val kind = input.readInt()
            val mime = input.readUTF()
            // photos, videos and music only
            if (size < 0 || kind < 0 || kind > 2 || OmsProtocol.kindOfMime(mime) != kind) {
                output.writeByte(0)
                output.flush()
                return false
            }
            files.add(OmsFile(null, name, size, kind, mime))
            total += size
        }

        // ask the person
        decisions.clear()
        val totalBytesNow = total
        ui(e) {
            peerName = peer
            request = files
            fileCount = files.size
            totalBytes = totalBytesNow
            phase = OmsPhase.REQUEST
        }
        val accepted = decisions.poll(60, TimeUnit.SECONDS) == true
        if (dead(e)) return true
        if (!accepted) {
            output.writeByte(0)
            output.flush()
            ui(e) {
                request = null
                phase = OmsPhase.WAITING
            }
            return false
        }
        output.writeByte(1)
        output.flush()

        s.soTimeout = 60_000
        OmsNet.holdLocks(app)
        ui(e) {
            request = null
            doneBytes = 0L
            speed = 0L
            fileIndex = 0
            phase = OmsPhase.TRANSFERRING
        }
        val meter = OmsMeter { d, sp -> ui(e) { doneBytes = d; speed = sp } }
        var photos = 0
        var videos = 0
        var music = 0
        for ((i, f) in files.withIndex()) {
            if (dead(e)) return true
            ui(e) {
                fileIndex = i + 1
                currentName = f.name
            }
            saveOne(app, e, f, input, meter)
            when (f.kind) {
                OmsProtocol.KIND_PHOTO -> photos += 1
                OmsProtocol.KIND_VIDEO -> videos += 1
                else -> music += 1
            }
        }
        meter.flush()
        output.writeByte(1)
        output.flush()

        val parts = ArrayList<String>()
        if (photos > 0) parts.add(if (photos == 1) "1 photo in Pictures/ADMOSS Share" else "$photos photos in Pictures/ADMOSS Share")
        if (videos > 0) parts.add(if (videos == 1) "1 video in Movies/ADMOSS Share" else "$videos videos in Movies/ADMOSS Share")
        if (music > 0) parts.add(if (music == 1) "1 song in Music/ADMOSS Share" else "$music songs in Music/ADMOSS Share")
        ui(e) {
            doneBytes = totalBytesNow
            summary = "Saved " + parts.joinToString(", ")
            phase = OmsPhase.DONE
        }
        // give the last bytes time to reach the sender before the private Wi-Fi closes
        Thread.sleep(1500)
        main.post { if (e == epoch) appCtx?.let { endNetwork(it) } }
        return true
    }

    private fun saveOne(app: Context, e: Int, f: OmsFile, input: DataInputStream, meter: OmsMeter) {
        val resolver = app.contentResolver
        val collection: Uri
        val folder: String
        when (f.kind) {
            OmsProtocol.KIND_PHOTO -> {
                collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                folder = "Pictures/ADMOSS Share"
            }
            OmsProtocol.KIND_VIDEO -> {
                collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                folder = "Movies/ADMOSS Share"
            }
            else -> {
                collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                folder = "Music/ADMOSS Share"
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, f.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create the file")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Couldn't write the file")
            out.use {
                val buf = ByteArray(OmsProtocol.CHUNK)
                var left = f.size
                while (left > 0) {
                    if (dead(e)) throw IOException("Cancelled")
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("The connection closed early")
                    it.write(buf, 0, n)
                    left -= n
                    meter.add(n)
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            // never leave a half file behind
            try {
                resolver.delete(uri, null, null)
            } catch (x: Throwable) {
                // ignore
            }
            throw t
        }
    }

    // ---------- sending ----------

    /** Starts sending [selected] to the phone in the QR code. Returns false when the code is not valid. */
    fun startSending(ctx: Context, qr: String): Boolean {
        val link = OmsQr.parse(qr) ?: return false
        val files = selected
        if (files.isEmpty()) return false
        val app = ctx.applicationContext
        val e = begin(app, true)
        fileCount = files.size
        totalBytes = files.sumOf { it.size }
        peerName = link.name
        message = "Joining ${link.name}…"
        phase = OmsPhase.JOINING
        OmsNet.join(
            app,
            link.cred,
            onReady = { network, host ->
                if (e == epoch) thread(name = "oms-send") { sendAll(app, e, network, host, link, files) }
            },
            onError = { text -> fail(e, text) }
        )
        return true
    }

    private fun friendly(t: Throwable): String = when {
        t is SocketTimeoutException -> "The other phone didn't answer in time."
        t is ConnectException -> "Couldn't reach the other phone. Make sure it is still on the Receive screen."
        t.message == "Cancelled" -> "Transfer cancelled."
        t is IOException -> "The connection to the other phone was lost."
        else -> t.message ?: "The transfer failed."
    }

    private fun sendAll(app: Context, e: Int, network: Network, host: String, link: OmsLink, files: List<OmsMediaItem>) {
        val s = Socket()
        socket = s
        try {
            network.bindSocket(s)
            s.connect(InetSocketAddress(host, link.port), 15_000)
            try {
                s.sendBufferSize = 1 shl 20
            } catch (t: Throwable) {
                // the default is fine
            }
            val total = files.sumOf { it.size }
            ui(e) {
                phase = OmsPhase.CONNECTING
                message = "Waiting for ${link.name} to accept…"
            }
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 8 * 1024))
            out.writeUTF(OmsProtocol.MAGIC)
            out.writeUTF(link.token)
            out.writeUTF(myName.take(60))
            out.writeInt(files.size)
            for (f in files) {
                out.writeUTF(f.name.take(200))
                out.writeLong(f.size)
                out.writeInt(f.kind)
                out.writeUTF(f.mime)
            }
            out.flush()

            s.soTimeout = 90_000
            when (input.readByte().toInt()) {
                1 -> {}
                2 -> {
                    fail(e, "The code didn't match. Ask the other phone for a new QR code.")
                    return
                }
                else -> {
                    ui(e) {
                        message = "${link.name} declined the transfer."
                        phase = OmsPhase.REJECTED
                    }
                    main.post { if (e == epoch) endNetwork(app) }
                    return
                }
            }

            s.soTimeout = 60_000
            OmsNet.holdLocks(app)
            ui(e) {
                doneBytes = 0L
                speed = 0L
                fileIndex = 0
                phase = OmsPhase.TRANSFERRING
            }
            val meter = OmsMeter { d, sp -> ui(e) { doneBytes = d; speed = sp } }
            val buf = ByteArray(OmsProtocol.CHUNK)
            for ((i, f) in files.withIndex()) {
                if (dead(e)) return
                ui(e) {
                    fileIndex = i + 1
                    currentName = f.name
                }
                val stream = app.contentResolver.openInputStream(f.uri) ?: throw IOException("Can't read ${f.name}")
                stream.use { src ->
                    var left = f.size
                    while (left > 0) {
                        if (dead(e)) throw IOException("Cancelled")
                        val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("${f.name} changed while sending")
                        out.write(buf, 0, n)
                        left -= n
                        meter.add(n)
                    }
                }
            }
            out.flush()
            meter.flush()
            input.readByte() // the other phone says everything was saved

            ui(e) {
                doneBytes = total
                summary = "Sent ${files.size} file" + (if (files.size == 1) "" else "s") + "  •  " + omsSize(total)
                phase = OmsPhase.DONE
            }
            main.post { if (e == epoch) endNetwork(app) }
        } catch (t: Throwable) {
            if (!dead(e)) fail(e, friendly(t))
        } finally {
            try {
                s.close()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }
}
