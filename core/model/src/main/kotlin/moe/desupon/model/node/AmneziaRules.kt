package moe.desupon.model.node

/** AmneziaWG settings the bundled device refuses at start or crashes on; shared by the parser, the config rules and the editor. */
object AmneziaRules {
    private const val UINT32_MAX = 0xFFFF_FFFFL

    /** AmneziaWG's documented bound; with Jmax at most [MAX_SIZE] it keeps one handshake's junk to a few megabytes. */
    const val MAX_JUNK_COUNT = 128L

    /** Nothing larger fits in one UDP datagram, and the device allocates whatever it is given. */
    const val MAX_SIZE = 65535L

    private val NUMERIC_TAGS = setOf("r", "rc", "rd", "dz")
    private val PLAIN_TAGS = setOf("c", "t", "d", "ds")
    private val DIGITS = Regex("\\+?[0-9]+")

    /** Why the device would not run these settings, null when it would; [value] reads one `amnezia` key as text, `lo-hi` for a range object. */
    fun violation(value: (String) -> String?): String? {
        fun number(key: String) = value(key)?.trim()?.toLongOrNull() ?: 0L
        if (number("jc") !in 0..MAX_JUNK_COUNT) return "AmneziaWG Jc above $MAX_JUNK_COUNT"
        if (listOf("jmin", "jmax", "s1", "s2", "s3", "s4").any { number(it) !in 0..MAX_SIZE }) return "AmneziaWG size above $MAX_SIZE"
        if (!junkValid(number("jc"), number("jmin"), number("jmax"))) return "AmneziaWG Jmin above Jmax"
        val headers = (1..4).map { n -> value("h$n")?.let { range(it) ?: return "AmneziaWG H$n is not a range" } }
        if (overlap(headers) != null) return "AmneziaWG H1-H4 overlap"
        val chain = (1..5).firstOrNull { n -> value("i$n")?.let { !validChain(it) } == true }
        return chain?.let { "AmneziaWG I$it is not a tag chain" }
    }

    /** A junk packet is Jmin plus a random share of Jmax - Jmin bytes, unsigned, so Jmin above Jmax asks for gigabytes. */
    fun junkValid(jc: Long, jmin: Long, jmax: Long): Boolean = jc <= 0 || jmin <= jmax

    /** `n` or `lo-hi` within uint32, as the core decodes it: no spaces; null when malformed or reversed. */
    fun range(text: String): LongRange? {
        val parts = text.split('-')
        if (parts.size > 2) return null
        val ends = parts.map { p -> p.takeIf(DIGITS::matches)?.toLongOrNull()?.takeIf { it <= UINT32_MAX } ?: return null }
        return if (ends.first() <= ends.last()) ends.first()..ends.last() else null
    }

    /** [range] in the form the core writes back: `n`, or `lo-hi`. */
    fun canonical(range: LongRange): String = if (range.first == range.last) "${range.first}" else "${range.first}-${range.last}"

    /** The first overlapping pair among H1-H4, as 0-based indices; an unset Hn keeps the WireGuard message type n. */
    fun overlap(headers: List<LongRange?>): Pair<Int, Int>? {
        val r = headers.mapIndexed { i, h -> h ?: (i + 1L)..(i + 1L) }
        for (i in r.indices) {
            for (j in i + 1 until r.size) {
                if (r[i].first <= r[j].last && r[j].first <= r[i].last) return i to j
            }
        }
        return null
    }

    /**
     * Whether the device can parse and send [spec] as I1-I5: `<tag [value]>` items with text between them ignored.
     * Line breaks would split the device's line-based config, and negative lengths parse but panic on send.
     */
    fun validChain(spec: String): Boolean {
        if ('\n' in spec || '\r' in spec) return false
        var rest = spec
        while (true) {
            val start = rest.indexOf('<')
            if (start < 0) return true
            val end = rest.indexOf('>', start)
            if (end < 0) return false
            val parts = rest.substring(start + 1, end).map { if (it.isWhitespace()) ' ' else it }.joinToString("").split(' ').filter { it.isNotEmpty() }
            val arg = parts.getOrNull(1).orEmpty()
            val ok = when (parts.firstOrNull()) {
                "b" -> arg.removePrefix("0x").let { hex -> hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it.isHexDigit() } }
                in NUMERIC_TAGS -> DIGITS.matches(arg) && (arg.toLongOrNull() ?: Long.MAX_VALUE) <= MAX_SIZE
                in PLAIN_TAGS -> true
                else -> false
            }
            if (!ok) return false
            rest = rest.substring(end + 1)
        }
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
