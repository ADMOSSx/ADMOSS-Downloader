package com.admoss.downloader

import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLEncoder

/** Everything the QR code carries: the private Wi-Fi, the port of the receiver and the one-time code. */
class OmsLink(val cred: OmsCredentials, val port: Int, val token: String, val name: String)

object OmsQr {

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun payload(c: OmsCredentials, port: Int, token: String, name: String): String =
        "admoss://share?v=1&s=${enc(c.ssid)}&p=${enc(c.pass)}&t=$port&k=${enc(token)}&w=${if (c.wpa3) 3 else 2}&n=${enc(name)}"

    /** Returns null when the text is not an ADMOSS share code. */
    fun parse(text: String): OmsLink? {
        return try {
            val u = Uri.parse(text.trim())
            if (u.scheme != "admoss" || u.host != "share") return null
            val ssid = u.getQueryParameter("s") ?: return null
            val pass = u.getQueryParameter("p") ?: return null
            val port = u.getQueryParameter("t")?.toIntOrNull() ?: return null
            val token = u.getQueryParameter("k") ?: return null
            if (ssid.isBlank() || pass.length < 8 || port !in 1..65535 || token.isBlank()) return null
            val wpa3 = u.getQueryParameter("w") == "3"
            val name = u.getQueryParameter("n").orEmpty().ifBlank { "Another phone" }
            OmsLink(OmsCredentials(ssid, pass, wpa3), port, token, name)
        } catch (e: Throwable) {
            null
        }
    }

    /** Black on white, so every camera can read it. */
    fun bitmap(text: String, size: Int): ImageBitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val w = m.width
            val h = m.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    pixels[y * w + x] = if (m[x, y]) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            bmp.asImageBitmap()
        } catch (e: Throwable) {
            null
        }
    }
}

/** Reads QR codes from the camera preview frames. [onResult] is called on the analysis thread. */
class OmsQrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val source = PlanarYUVLuminanceSource(
                data, plane.rowStride, image.height, 0, 0, image.width, image.height, false
            )
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            val text = result.text
            if (!text.isNullOrBlank()) onResult(text)
        } catch (e: Throwable) {
            // no QR code in this frame
        } finally {
            reader.reset()
            image.close()
        }
    }
}
