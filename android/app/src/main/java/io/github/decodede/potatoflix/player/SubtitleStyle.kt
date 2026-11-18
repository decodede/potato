package io.github.decodede.potatoflix.player

/**
 * How subtitles look (Settings → the player's "Subtitle style") and the clean-up applied to subtitle files:
 * closed captions, ads and capitals. ExoPlayer draws the subtitles (its SubtitleView), so the look becomes a
 * CaptionStyleCompat; [shift] moves a file's cue times (the subtitle sync feature).
 */
object SubtitleStyle {
    /** A snapshot of the style settings (plain values, so it can be tested and compared). */
    data class Look(
        val size: String = "NORMAL",
        val color: String = "#ffffff",
        val font: String = "DEFAULT",
        val bold: Boolean = false,
        val edge: String = "OUTLINE",
        val outline: Int = 4,
        val outlineColor: String = "#000000",
        val background: Boolean = false,
        val backgroundColor: String = "#000000",
        val backgroundOpacity: Int = 60,
        val height: Int = 0,
    )

    /** Text clean-up of subtitle files (only files: subtitles inside the video can't be changed). */
    data class Clean(
        val noCaptions: Boolean = false,
        val noAds: Boolean = true,
        val uppercase: Boolean = false,
        val italic: Boolean = false,
    ) {
        val active get() = noCaptions || noAds || uppercase || italic
        val key get() = (if (noCaptions) "c" else "") + (if (noAds) "a" else "") + (if (uppercase) "u" else "") + (if (italic) "i" else "")
    }

    /** The style chosen in Settings. */
    fun current() = io.github.decodede.potatoflix.Settings.let { s ->
        Look(s.subtitleSize, s.subtitleColor, s.subtitleFont, s.subtitleBold, s.subtitleEdge, s.subtitleOutline.takeIf { it > 0 } ?: 4,
            s.subtitleOutlineColor, s.subtitleBackground, s.subtitleBackgroundColor, s.subtitleBackgroundOpacity, s.subtitleHeight)
    }
    fun currentClean() = io.github.decodede.potatoflix.Settings.let { s ->
        Clean(s.subtitleNoCaptions, s.subtitleNoAds, s.subtitleUppercase, s.subtitleItalic)
    }

    val sizes = listOf("TINY" to "Tiny", "SMALL" to "Small", "NORMAL" to "Normal", "LARGE" to "Large", "HUGE" to "Huge")
    val colors = listOf("#ffffff" to "White", "#ffeb3b" to "Yellow", "#7fdbff" to "Cyan", "#8bc34a" to "Green", "#ff9800" to "Orange", "#ff80ab" to "Pink", "#000000" to "Black")
    val fonts = listOf("DEFAULT" to "Default", "SERIF" to "Serif", "MONO" to "Monospace", "CONDENSED" to "Condensed")
    val edges = listOf("OUTLINE" to "Outline", "SHADOW" to "Shadow", "BOTH" to "Outline + shadow", "NONE" to "None")
    val outlines = listOf(2 to "Thin", 4 to "Normal", 6 to "Thick")
    val heights = listOf(0 to "Bottom", 1 to "Raised", 2 to "Higher", 3 to "High")

    /** The preview's font size in sp for a ~1080-line picture shown on a phone or TV. */
    fun previewSp(size: String) = when (size) { "TINY" -> 13f; "SMALL" -> 16f; "LARGE" -> 23f; "HUGE" -> 30f; else -> 19f }

    /** Subtitle text size as a fraction of the picture height (ExoPlayer's SubtitleView). */
    fun fractionalSize(size: String) = when (size) { "TINY" -> 0.040f; "SMALL" -> 0.046f; "LARGE" -> 0.075f; "HUGE" -> 0.095f; else -> 0.0533f }

    /** Fraction of the remaining height the subtitles are lifted by [Look.height]. */
    fun bottomPaddingFraction(height: Int) = when (height) { 1 -> 0.08f; 2 -> 0.18f; 3 -> 0.28f; else -> 0f }

    fun rgb(hex: String): Int = hex.removePrefix("#").toIntOrNull(16)?.and(0xffffff) ?: 0xffffff
    fun argb(hex: String, percent: Int): Int {
        val a = (percent.coerceIn(0, 100) * 255 / 100) shl 24
        return a or rgb(hex)
    }

    /** The style ExoPlayer's SubtitleView paints with ([look]). */
    fun cueStyle(look: Look): androidx.media3.ui.CaptionStyleCompat {
        val typeface = when (look.font) {
            "SERIF" -> if (look.bold) android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD) else android.graphics.Typeface.SERIF
            "MONO" -> if (look.bold) android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD) else android.graphics.Typeface.MONOSPACE
            "CONDENSED" -> if (look.bold) android.graphics.Typeface.create(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL), android.graphics.Typeface.BOLD) else android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
            else -> if (look.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        val outline = look.edge == "OUTLINE" || look.edge == "BOTH"
        val shadow = look.edge == "SHADOW" || look.edge == "BOTH"
        val edgeType = when {
            outline -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
            shadow -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            else -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
        }
        val edgeColor = if (outline) rgb(look.outlineColor) or (0xff shl 24) else android.graphics.Color.BLACK
        return androidx.media3.ui.CaptionStyleCompat(
            rgb(look.color) or (0xff shl 24),
            if (look.background) argb(look.backgroundColor, look.backgroundOpacity) else android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            edgeType,
            edgeColor,
            typeface,
        )
    }

    private val timing = Regex("""-->""")
    private val bracketed = Regex("""\[[^\]\n]*]|\([^)\n]*\)""")
    private val music = Regex("""^[\s\-–]*[♪♫#*]+.*$|^.*[♪♫]+[\s\-–]*$""")
    private val speaker = Regex("""^(\s*(?:<[^>]+>\s*)*[-–]?\s*)[A-Z][A-Z0-9 .'&#-]{0,30}:\s+""")
    private val tags = Regex("""<[^>]*>""")
    private val ad = Regex(
        """(?i)(\b(subtitles?|subs|synced?|sync|resync(ed)?|corrected|ripped|captions?|captioned|translated|translation|timing|encoded|transcript)\s*(and\s*\w+\s*)?(by|:)\s*\S)""" +
            """|opensubtitles|addic7ed|subscene|podnapisi|yts\.|yify|\bwww\.|https?://|\.(com|org|net|io|tv|me)\b""" +
            """|downloaded from|support us|become (a )?vip|advertise your|rate this subtitle|osdb|subdl""",
    )

    private fun plain(line: String) = line.replace(tags, "").replace(Regex("""^[\s\-–]+|[\s\-–]+$"""), "")

    /** One cue's text lines after the clean-up; empty = drop the cue. */
    internal fun cleanLines(lines: List<String>, c: Clean): List<String> {
        var out = lines
        if (c.noAds) out = out.filter { !ad.containsMatchIn(plain(it)) }
        if (c.noCaptions) out = out.map { l ->
            if (music.matches(plain(l))) "" else l.replace(bracketed, "").replace(speaker, "$1").replace(Regex("""\s{2,}"""), " ")
                .replace(Regex("""^((?:<[^>]+>)*)\s+"""), "$1").replace(Regex("""\s+(</[^>]+>)"""), "$1").trimEnd()
        }
        out = out.filter { plain(it).isNotEmpty() }
        if (c.uppercase) out = out.map { l ->
            buildString {
                var i = 0
                tags.findAll(l).forEach { m -> append(l.substring(i, m.range.first).uppercase()); append(m.value); i = m.range.last + 1 }
                append(l.substring(i).uppercase())
            }
        }
        if (c.italic && out.isNotEmpty()) out = out.map { if (it.contains("<i>", true)) it else "<i>$it</i>" }
        return out
    }

    /**
     * Cleans an SRT or WebVTT file's text ([c]); other formats (ASS/SSA, which carry their own look) are
     * returned unchanged. SRT cues are numbered again after cues are dropped.
     */
    fun clean(text: String, c: Clean): String {
        if (!c.active) return text
        val t = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        if (t.trimStart().startsWith("[Script Info]", true) || t.contains("\n[Events]")) return text
        val vtt = t.trimStart().startsWith("WEBVTT")
        val blocks = t.split(Regex("""\n[ \t]*\n+"""))
        val out = StringBuilder()
        var n = 0
        for (b in blocks) {
            val lines = b.trim('\n').split('\n')
            val at = lines.indexOfFirst { timing.containsMatchIn(it) }
            if (at < 0) { if (b.isNotBlank()) out.append(b.trim('\n')).append("\n\n"); continue }
            val text2 = cleanLines(lines.drop(at + 1), c)
            if (text2.isEmpty()) continue
            n++
            val head = lines.take(at)
            if (!vtt) out.append(n).append('\n') else head.forEach { out.append(it).append('\n') }
            out.append(lines[at]).append('\n')
            text2.forEach { out.append(it).append('\n') }
            out.append('\n')
        }
        return out.toString()
    }

    private val cueTime = Regex("""(\d{1,2}:)?\d{1,2}:\d{2}[,.]\d{1,3}""")

    private fun timeToMs(t: String): Long {
        val sep = if (',' in t) ',' else '.'
        val head = t.substringBeforeLast(sep)
        val fraction = t.substringAfterLast(sep)
        val parts = head.split(':')
        val (h, m, sec) = when (parts.size) {
            3 -> Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            2 -> Triple(0, parts[0].toInt(), parts[1].toInt())
            else -> Triple(0, 0, parts[0].toInt())
        }
        return (((h * 3600L + m * 60L + sec) * 1000L) + fraction.padEnd(3, '0').take(3).toLong())
    }

    private fun msToTime(ms: Long, sep: Char, digits: Int): String {
        val total = ms / 1000
        val fraction = (ms % 1000).toString().padStart(3, '0').take(digits)
        return "%02d:%02d:%02d%c%s".format(total / 3600, (total % 3600) / 60, total % 60, sep, fraction)
    }

    private val assTag = Regex("""\{[^}\n]*\}""")
    private val assLineBreak = Regex("""\\[Nn]""")
    private val assTime = Regex("""(?:(\d+):)?(\d+):(\d+)[\.,](\d{1,3})""")

    /** Converts a standard ASS/SSA file to SubRip text. Returns null if no Dialogue cues are found. */
    internal fun assToSrt(text: String, offsetMs: Long = 0L): String? {
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').lines()
        val out = StringBuilder()
        var n = 0
        for (line in lines) {
            val marker = line.indexOf("Dialogue:", ignoreCase = true)
            if (marker < 0) continue
            val parts = line.substring(marker + "Dialogue:".length).split(',', limit = 10)
            if (parts.size < 10) continue
            val start = assTime.matchEntire(parts[1].trim()) ?: continue
            val end = assTime.matchEntire(parts[2].trim()) ?: continue
            fun timeToMs(m: MatchResult): Long {
                val h = m.groupValues[1].toLongOrNull() ?: 0L
                val min = m.groupValues[2].toLongOrNull() ?: 0L
                val sec = m.groupValues[3].toLongOrNull() ?: 0L
                val frac = m.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                return ((h * 3600 + min * 60 + sec) * 1000 + frac)
            }
            val body = parts[9]
                .replace(assTag, "")
                .replace(assLineBreak, "\n")
                .replace("\\h", " ")
                .trim()
            if (body.isBlank()) continue
            val from = (timeToMs(start) + offsetMs).coerceAtLeast(0)
            val to = (timeToMs(end) + offsetMs).coerceAtLeast(0)
            if (to <= from) continue
            n++
            out.append(n).append('\n')
                .append(msToTime(from, ',', 3)).append(" --> ").append(msToTime(to, ',', 3)).append('\n')
                .append(body).append("\n\n")
        }
        return out.takeIf { n > 0 }?.toString()
    }

    /** Moves every cue of an SRT/WebVTT file by [offsetMs] (the subtitle sync feature); clamped at zero. */
    fun shift(text: String, offsetMs: Long): String {
        if (offsetMs == 0L) return text
        val t = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return t.lineSequence().joinToString("\n") { line ->
            if (!line.contains("-->")) line else cueTime.replace(line) { m ->
                val raw = m.value
                val sep = if (',' in raw) ',' else '.'
                val digits = raw.substringAfterLast(sep).length
                msToTime((timeToMs(raw) + offsetMs).coerceAtLeast(0), sep, digits)
            }
        }
    }

    /** Subtitle file bytes → text: UTF-8 (with or without BOM), UTF-16, else the detected or a Western charset. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
            return String(bytes, Charsets.UTF_16)
        val utf8 = Charsets.UTF_8.newDecoder()
        runCatching { return utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        val detected = runCatching {
            val d = org.mozilla.universalchardet.UniversalDetector()
            d.handleData(bytes, 0, bytes.size); d.dataEnd()
            d.detectedCharset?.let { java.nio.charset.Charset.forName(it) }
        }.getOrNull()
        return String(bytes, detected ?: java.nio.charset.Charset.forName("windows-1252"))
    }
}
