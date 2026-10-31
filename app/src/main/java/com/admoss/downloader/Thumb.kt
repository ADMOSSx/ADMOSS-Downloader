package com.admoss.downloader

import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cover image loaded from a saved file, with a gradient + icon while there is none. */
@Composable
fun Thumb(
    path: String?,
    modifier: Modifier = Modifier,
    @DrawableRes fallback: Int = R.drawable.ic_play,
    iconSize: Dp = 22.dp
) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                } catch (e: Throwable) {
                    null
                }
            }
        }
    }

    Box(
        modifier.background(Brush.horizontalGradient(listOf(Color(0xFF00B8D4), Color(0xFF7C4DFF)))),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(fallback),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
