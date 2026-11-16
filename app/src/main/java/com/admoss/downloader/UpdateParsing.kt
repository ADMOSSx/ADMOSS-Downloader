package com.admoss.downloader

/** Pure parsing helpers kept separate from Android update/network orchestration. */
internal object UpdateParsing {
    /** "1.10.0" is newer than "1.9.5". */
    /** "1.10.0" is newer than "1.9.5". */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V").split(".")
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Turns the GitHub release text (markdown, badges, tables) into short plain text. */
    fun cleanNotes(body: String): String {
        val lines = ArrayList<String>()
        val separator = Regex("^\\|?[\\s:|-]+\\|?$")
        val source = body.replace("\r", "").lines()
        for ((idx, raw) in source.withIndex()) {
            var l = raw.trim()
            if (l.isEmpty()) {
                if (lines.isNotEmpty() && lines.last().isNotEmpty()) lines.add("")
                continue
            }
            if (l.startsWith("![") || l.contains("img.shields.io") || l.startsWith("---") || l.startsWith("```")) continue
            if (separator.matches(l)) continue // table separator row
            // the header row of a table (the line right before the separator row) is only labels
            if (l.startsWith("|") && idx + 1 < source.size && separator.matches(source[idx + 1].trim())) continue
            l = l.replace(Regex("<[^>]+>"), "")
            l = l.replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            l = l.replace(Regex("^#{1,6}\\s*"), "").replace("**", "").replace("`", "")
            if (l.startsWith("|")) {
                val cells = l.trim('|').split("|").map { it.trim() }.filter { it.isNotEmpty() }
                l = "• " + cells.joinToString("  ")
            } else if (l.startsWith("- ") || l.startsWith("* ")) {
                l = "• " + l.drop(2)
            }
            l = l.trim()
            if (l.isEmpty() || l == "•") continue
            lines.add(l)
        }
        val text = lines.joinToString("\n").trim()
        return if (text.length > 900) text.take(900).trimEnd() + "…" else text
    }


}
