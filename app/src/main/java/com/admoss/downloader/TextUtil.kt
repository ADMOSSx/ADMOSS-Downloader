package com.admoss.downloader

/** Shortens text without ever cutting an emoji (a surrogate pair) in half. */
fun cutText(text: String, max: Int): String {
    val cps = text.codePoints().toArray()
    return if (cps.size <= max) text else String(cps, 0, max)
}
