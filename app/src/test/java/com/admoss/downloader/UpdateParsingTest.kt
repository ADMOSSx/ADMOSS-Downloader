package com.admoss.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateParsingTest {
    @Test
    fun comparesNumericVersionSegments() {
        assertTrue(UpdateParsing.isNewer("1.10.0", "1.9.5"))
        assertTrue(UpdateParsing.isNewer("v2.0.0", "1.99.99"))
        assertFalse(UpdateParsing.isNewer("1.9.5", "1.10.0"))
        assertFalse(UpdateParsing.isNewer("1.2.0", "1.2"))
    }

    @Test
    fun cleansReleaseMarkdown() {
        val notes = UpdateParsing.cleanNotes(
            "# What's new\n\n- **Faster downloads**\n- [Read more](https://example.com)\n"
        )
        assertTrue(notes.contains("What's new"))
        assertTrue(notes.contains("Faster downloads"))
        assertTrue(notes.contains("Read more"))
        assertFalse(notes.contains("https://example.com"))
        assertFalse(notes.contains("**"))
    }

    @Test
    fun truncatesVeryLongReleaseNotes() {
        val result = UpdateParsing.cleanNotes("A".repeat(1200))
        assertEquals(901, result.length)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun acceptsOnlyTrustedHttpsGitHubUrls() {
        assertTrue(UpdateParsing.isTrustedGitHubUrl("https://api.github.com/repos/owner/repo/releases/latest"))
        assertTrue(UpdateParsing.isTrustedGitHubUrl("https://github.com/owner/repo/releases/download/v1/app.apk"))
        assertFalse(UpdateParsing.isTrustedGitHubUrl("http://github.com/owner/repo"))
        assertFalse(UpdateParsing.isTrustedGitHubUrl("https://github.com.evil.example/file.apk"))
        assertFalse(UpdateParsing.isTrustedGitHubUrl("https://example.com/file.apk"))
        assertFalse(UpdateParsing.isTrustedGitHubUrl("https://github.com:8443/file.apk"))
        assertFalse(UpdateParsing.isTrustedGitHubUrl("https://user@github.com/file.apk"))
    }

    @Test
    fun allowsOnlyKnownGitHubAssetRedirectHosts() {
        assertTrue(UpdateParsing.isTrustedGitHubResponseUrl("https://release-assets.githubusercontent.com/file"))
        assertTrue(UpdateParsing.isTrustedGitHubResponseUrl("https://objects.githubusercontent.com/file"))
        assertFalse(UpdateParsing.isTrustedGitHubResponseUrl("https://attacker.example/file"))
        assertFalse(UpdateParsing.isTrustedGitHubResponseUrl("http://release-assets.githubusercontent.com/file"))
    }
}
