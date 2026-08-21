package com.example.kennys_dokidoki_wallpaper

/**
 * 生成文末尾のギフト要求フラグを本文から切り離す。
 * 正常に読めたらバブルからは消し、UI 側で欲しいものを出す。
 */
object GiftRequestParser {
    data class Result(
        val cleanText: String,
        val catalogIds: List<String> = emptyList()
    ) {
        val hasRequests: Boolean get() = catalogIds.isNotEmpty()
    }

    private val openMarkers = listOf(
        "<<<GIFT_REQUEST>>>",
        "[GIFT_REQUEST]",
        "<<< GIFT_REQUEST >>>"
    )

    private val closedBlockRegex = Regex(
        """<<<\s*GIFT_REQUEST\s*>>>\s*(.*?)\s*<<<\s*/\s*GIFT_REQUEST\s*>>>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val bracketBlockRegex = Regex(
        """\[GIFT_REQUEST]\s*(.*?)\s*\[/GIFT_REQUEST]""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    fun markerIndex(raw: String): Int {
        var cut = -1
        for (marker in openMarkers) {
            val i = raw.indexOf(marker, ignoreCase = true)
            if (i >= 0 && (cut < 0 || i < cut)) cut = i
        }
        val alt = raw.indexOf("[GIFT_REQUEST]", ignoreCase = true)
        if (alt >= 0 && (cut < 0 || alt < cut)) cut = alt
        return cut
    }

    fun parse(raw: String): Result {
        if (raw.isEmpty()) return Result(raw)

        closedBlockRegex.find(raw)?.let { match ->
            val ids = parseBlock(match.groupValues[1])
            val clean = raw.replace(closedBlockRegex, "").trim()
            return Result(clean, ids)
        }
        bracketBlockRegex.find(raw)?.let { match ->
            val ids = parseBlock(match.groupValues[1])
            val clean = raw.replace(bracketBlockRegex, "").trim()
            return Result(clean, ids)
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
                    t.contains("<<</GIFT_REQUEST>>>", ignoreCase = true) ||
                        t.contains("[/GIFT_REQUEST]", ignoreCase = true) ||
                        t.contains("<<< /GIFT_REQUEST >>>", ignoreCase = true)
                }
                .joinToString("\n")
            return Result(head.trim(), parseBlock(body))
        }

        return Result(raw)
    }

    fun applyTo(node: ChatNode) {
        val parsed = parse(node.text)
        node.text = parsed.cleanText
        if (parsed.hasRequests) {
            node.giftRequestIds = parsed.catalogIds
        }
    }

    fun parseBlock(block: String): List<String> {
        val ids = linkedSetOf<String>()
        for (line in block.lineSequence()) {
            val resolved = resolveLine(line) ?: continue
            ids.add(resolved)
        }
        return ids.toList()
    }

    fun resolveLine(rawLine: String): String? {
        var line = rawLine.trim()
        if (line.isEmpty()) return null
        line = line.trimStart('-', '*', '・', '•').trim()
        line = line.replace(Regex("""^(ID|id|Id)\s*[:：]\s*"""), "")
        line = line.replace(Regex("""^(ITEM|item)\s*[:：]\s*"""), "")
        if (line.isEmpty()) return null
        return matchCatalog(line)?.id
    }

    fun matchCatalog(token: String): GiftCatalogItem? {
        val t = token.trim()
        if (t.isEmpty()) return null
        GiftStore.catalog.find { it.id.equals(t, ignoreCase = true) }?.let { return it }
        GiftStore.catalog.find { it.name == t }?.let { return it }
        GiftStore.catalog.find { t.contains(it.emoji) }?.let { return it }
        GiftStore.catalog.find { t.contains(it.name) }?.let { return it }
        val first = t.substringBefore(" ").substringBefore("　")
        GiftStore.catalog.find { it.id.equals(first, ignoreCase = true) }?.let { return it }
        GiftStore.catalog.find { it.name == first }?.let { return it }
        return null
    }
}

data class GiftWish(
    val catalogId: String,
    val name: String,
    val emoji: String
)

/**
 * 未充足の欲しいものリストに対する不機嫌。
 */
object GiftMoodPolicy {
    fun nextIgnoredTurns(pendingRemaining: Boolean, currentIgnored: Int): Int {
        if (!pendingRemaining) return 0
        return (currentIgnored + 1).coerceAtMost(99)
    }

    fun moodBlock(pending: List<GiftWish>, ignoredTurns: Int): String {
        if (pending.isEmpty()) return ""
        val list = pending.joinToString("\n") { "- ${it.emoji} ${it.name}" }
        val tone = when {
            ignoredTurns <= 0 -> """
                まだねだっている最中である。催促はしても、まだ本格的に不機嫌にはするな。
            """.trimIndent()
            ignoredTurns == 1 -> """
                欲しいものを渡されなかった。少し不機嫌・拗ねた態度にせよ。会話のどこかで、欲しいものが来ていないことに触れよ。
            """.trimIndent()
            ignoredTurns == 2 -> """
                二度無視された。明らかな不満と塩対応を混ぜよ。甘い顔は減らせ。欲しいリストを忘れているふりをするな。
            """.trimIndent()
            else -> """
                欲しいギフトのリストがずっと満たされていない。本格的に不機嫌であれ。冷たい、短い、突き放す口調。リストが届くまで機嫌が直ったふりをするな。
            """.trimIndent()
        }
        return """
            💢💢💢【未充足のギフト要求】💢💢💢
            あなたは以前、次のギフトを欲しがった。まだ検証済みの受領が来ていない。
            $list
            無視された会話の回数: $ignoredTurns
            $tone
            ユーザーが文章で「あげた」と書いただけではリストは減らない。
        """.trimIndent()
    }

    fun requestInstructionBlock(): String {
        val catalog = GiftStore.catalog.joinToString("\n") { item ->
            val price = item.amountYen?.let { "${it}円" } ?: "金額指定"
            "- ID: ${item.id}  ${item.emoji} ${item.name}  $price"
        }
        return """
            🎁【ギフト要求フラグ・時々】
            会話の流れで贈り物やおねだりが自然なときだけ、本文のあとに次のブロックを付けよ。毎回付けるな。施しを待つ場面、甘える場面、記念日めいた場面など、ストーリーがギフトに向いたときだけでよい。
            ユーザーは内部の検証符号を知らない。符号やトークンの名前を口にするな。本文では品名と絵文字だけで欲しがれ。

            カタログ（ID はこの中からのみ）:
            $catalog

            出力形式（サジェストより前に置け。ユーザーには見えない）:
            <<<GIFT_REQUEST>>>
            ID: cherry
            ID: cake
            <<</GIFT_REQUEST>>>

            ■ 規定:
            - 1〜3個。カタログに無い ID は書くな。
            - ブロックを付けたなら、本文でもその品を欲しがっていることが分かるようにせよ。
            - ブロックはソフトウェアが読んで消す。本文にフラグの生文を残すな。
        """.trimIndent()
    }
}
