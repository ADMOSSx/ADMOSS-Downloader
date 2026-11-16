package com.admoss.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OmsProtocolTest {
    @Test
    fun addToTotalAcceptsValuesWithinLimits() {
        assertEquals(300L, OmsProtocol.addToTotal(100L, 200L))
    }

    @Test
    fun addToTotalRejectsNegativeValues() {
        assertNull(OmsProtocol.addToTotal(-1L, 1L))
        assertNull(OmsProtocol.addToTotal(0L, -1L))
    }

    @Test
    fun addToTotalRejectsPerFileLimit() {
        assertNull(OmsProtocol.addToTotal(0L, OmsProtocol.MAX_FILE_BYTES + 1L))
    }

    @Test
    fun addToTotalRejectsAggregateLimitWithoutOverflow() {
        assertNull(OmsProtocol.addToTotal(OmsProtocol.MAX_TOTAL_BYTES, 1L))
        assertNull(OmsProtocol.addToTotal(Long.MAX_VALUE, 1L))
    }

    @Test
    fun kindOfMimeRejectsUnsupportedTypes() {
        assertEquals(OmsProtocol.KIND_PHOTO, OmsProtocol.kindOfMime("image/jpeg"))
        assertEquals(OmsProtocol.KIND_VIDEO, OmsProtocol.kindOfMime("video/mp4"))
        assertEquals(OmsProtocol.KIND_MUSIC, OmsProtocol.kindOfMime("audio/mpeg"))
        assertEquals(-1, OmsProtocol.kindOfMime("application/octet-stream"))
        assertEquals(-1, OmsProtocol.kindOfMime(null))
    }

    @Test
    fun cleanNameRemovesPathSeparatorsAndControlCharacters() {
        assertEquals("_secret_.mp4", OmsProtocol.cleanName("../secret?.mp4"))
        assertEquals("file", OmsProtocol.cleanName("..."))
    }

    @Test
    fun fileCountMustBeWithinProtocolBounds() {
        assertEquals(false, OmsProtocol.isValidFileCount(0))
        assertEquals(true, OmsProtocol.isValidFileCount(1))
        assertEquals(true, OmsProtocol.isValidFileCount(OmsProtocol.MAX_FILES))
        assertEquals(false, OmsProtocol.isValidFileCount(OmsProtocol.MAX_FILES + 1))
    }

    @Test
    fun mediaTypeMustMatchItsDeclaredKind() {
        assertEquals(true, OmsProtocol.isValidMediaType(OmsProtocol.KIND_PHOTO, "IMAGE/JPEG"))
        assertEquals(true, OmsProtocol.isValidMediaType(OmsProtocol.KIND_VIDEO, "video/mp4", movie = true))
        assertEquals(false, OmsProtocol.isValidMediaType(OmsProtocol.KIND_PHOTO, "video/mp4"))
        assertEquals(false, OmsProtocol.isValidMediaType(OmsProtocol.KIND_MUSIC, "audio/mpeg", movie = true))
        assertEquals(false, OmsProtocol.isValidMediaType(99, "audio/mpeg"))
    }

    @Test
    fun addToTotalAcceptsExactAggregateBoundary() {
        assertEquals(
            OmsProtocol.MAX_TOTAL_BYTES,
            OmsProtocol.addToTotal(OmsProtocol.MAX_TOTAL_BYTES - 1L, 1L)
        )
    }
}
