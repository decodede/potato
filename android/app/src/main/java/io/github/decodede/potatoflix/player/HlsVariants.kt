package io.github.decodede.potatoflix.player

/**
 * The qualities inside one HLS source (a "master" playlist: 1440p, 1080p, 720p, 480p…), read for the Quality
 * menu. The player itself adapts or caps the video size through track selection; nothing is rewritten or proxied.
 */
object HlsVariants {
    data class Variant(val height: Int?, val bandwidth: Long?, val url: String, internal val info: String) {
        val label: String get() = height?.let { if (it >= 2160) "4K ($it" + "p)" else "${it}p" } ?: bandwidth?.let { "${it / 1000} kbps" } ?: "Quality"
    }

    data class Master(val variants: List<Variant>, val separateAudio: Boolean, internal val lines: List<String>, internal val base: String)

    private val attr = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    /** The master's qualities, best first (one per height), or null when [text] isn't a master with two or more. */
    fun parse(text: String, baseUrl: String): Master? {
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        val lines = text.lines().map { it.trim() }
        val found = mutableListOf<Variant>()
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val a = attr.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
            val height = a["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull()
            found += Variant(height, a["BANDWIDTH"]?.toLongOrNull() ?: a["AVERAGE-BANDWIDTH"]?.toLongOrNull(), resolve(baseUrl, uri), line)
        }
        val variants = found.groupBy { it.height ?: -(it.bandwidth ?: 0).toInt() }.values
            .map { same -> same.maxBy { it.bandwidth ?: 0 } }
            .sortedWith(compareByDescending<Variant> { it.height ?: 0 }.thenByDescending { it.bandwidth ?: 0 })
        if (variants.size < 2) return null
        val separateAudio = lines.any { it.startsWith("#EXT-X-MEDIA:") && "TYPE=AUDIO" in it && "URI=" in it }
        return Master(variants, separateAudio, lines, baseUrl)
    }

    private fun resolve(base: String, uri: String): String = runCatching { java.net.URI(base).resolve(uri.replace(" ", "%20")).toString() }.getOrDefault(uri)
}
