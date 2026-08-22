package com.example.kennys_dokidoki_wallpaper

/**
 * 生成文末尾のサジェストブロックを本文から切り離す。
 * ストリーム中はタグ以降を画面に出さず、完了時に A/B/C ボタンへ載せる。
 */
object ChatSuggestionParser {
    data class Result(
        val cleanText: String,
        val suggestionA: String? = null,
        val suggestionB: String? = null,
        val suggestionC: String? = null
    ) {
        val hasSuggestions: Boolean
            get() = !suggestionA.isNullOrBlank() || !suggestionB.isNullOrBlank() || !suggestionC.isNullOrBlank()
    }

    private val openMarkers = listOf(
        "<<<SUGGESTIONS>>>",
        "[SUGGESTIONS]",
        "<<< SUGGESTIONS >>>"
    )

    private val closedBlockRegex = Regex(
        """<<<\s*SUGGESTIONS\s*>>>\s*(.*?)\s*<<<\s*/\s*SUGGESTIONS\s*>>>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val bracketBlockRegex = Regex(
        """\[SUGGESTIONS]\s*(.*?)\s*\[/SUGGESTIONS]""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val lineA = Regex("""^[\s*\-]*\**[aA1]\**[\s.:：)\-]+(.*)$""")
    private val lineB = Regex("""^[\s*\-]*\**[bB2]\**[\s.:：)\-]+(.*)$""")
    private val lineC = Regex("""^[\s*\-]*\**[cC3]\**[\s.:：)\-]+(.*)$""")

    fun markerIndex(raw: String): Int {
        var cut = -1
        for (marker in openMarkers) {
            val i = raw.indexOf(marker, ignoreCase = true)
            if (i >= 0 && (cut < 0 || i < cut)) cut = i
        }
        val alt = raw.indexOf("[SUGGESTIONS]", ignoreCase = true)
        if (alt >= 0 && (cut < 0 || alt < cut)) cut = alt
        return cut
    }

    /** 生成中の吹き出し用。タグ以降（未完了ブロック含む）は出さない。 */
    fun visibleText(raw: String): String {
        val cut = markerIndex(raw)
        return if (cut >= 0) raw.substring(0, cut).trimEnd() else raw
    }

    fun parse(raw: String): Result {
        if (raw.isEmpty()) return Result(raw)

        closedBlockRegex.find(raw)?.let { match ->
            val parsed = parseBlock(match.groupValues[1])
            val clean = raw.replace(closedBlockRegex, "").trim()
            return Result(clean, parsed.first, parsed.second, parsed.third)
        }
        bracketBlockRegex.find(raw)?.let { match ->
            val parsed = parseBlock(match.groupValues[1])
            val clean = raw.replace(bracketBlockRegex, "").trim()
            return Result(clean, parsed.first, parsed.second, parsed.third)
        }

        val cut = markerIndex(raw)
        if (cut >= 0) {
            val head = raw.substring(0, cut).trimEnd()
            val tail = raw.substring(cut)
            val body = tail
                .lineSequence()
                .drop(1)
                .filterNot { line ->
                    val t = line.trim()
                    t.contains("<<</SUGGESTIONS>>>", ignoreCase = true) ||
                        t.contains("[/SUGGESTIONS]", ignoreCase = true) ||
                        t.contains("<<< /SUGGESTIONS >>>", ignoreCase = true)
                }
                .joinToString("\n")
            val parsed = parseBlock(body)
            return Result(head.trim(), parsed.first, parsed.second, parsed.third)
        }

        return Result(raw)
    }

    fun applyTo(node: ChatNode) {
        val parsed = parse(node.text)
        node.text = parsed.cleanText
        if (parsed.hasSuggestions) {
            node.suggestionA = parsed.suggestionA
            node.suggestionB = parsed.suggestionB
            node.suggestionC = parsed.suggestionC
        }
    }

    internal fun parseBlock(block: String): Triple<String?, String?, String?> {
        var a: String? = null
        var b: String? = null
        var c: String? = null
        for (line in block.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            when {
                a == null && lineA.find(trimmed) != null ->
                    a = cleanChoice(lineA.find(trimmed)!!.groupValues[1])
                b == null && lineB.find(trimmed) != null ->
                    b = cleanChoice(lineB.find(trimmed)!!.groupValues[1])
                c == null && lineC.find(trimmed) != null ->
                    c = cleanChoice(lineC.find(trimmed)!!.groupValues[1])
            }
        }
        return Triple(a, b, c)
    }

    private fun cleanChoice(value: String): String {
        return value.trim().trim('"').trim('「', '」').trim()
    }
}
