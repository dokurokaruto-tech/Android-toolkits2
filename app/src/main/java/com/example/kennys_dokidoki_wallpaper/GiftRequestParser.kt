package com.example.kennys_dokidoki_wallpaper

/**
 * 生成文末尾の現金要求フラグを本文から切り離す。
 * 正常に読めたらバブルからは消し、入力枠上に金額を出す。
 */
object GiftRequestParser {
    data class Result(
        val cleanText: String,
        val amountYen: Int? = null
    ) {
        val hasRequests: Boolean get() = amountYen != null
    }

    private val openMarkers = listOf(
        "<<<ALLOWANCE_REQUEST>>>",
        "[ALLOWANCE_REQUEST]",
        "<<< ALLOWANCE_REQUEST >>>",
        "<<<GIFT_REQUEST>>>",
        "[GIFT_REQUEST]",
        "<<< GIFT_REQUEST >>>"
    )

    private val closedBlockRegex = Regex(
        """<<<\s*(?:ALLOWANCE_REQUEST|GIFT_REQUEST)\s*>>>\s*(.*?)\s*<<<\s*/\s*(?:ALLOWANCE_REQUEST|GIFT_REQUEST)\s*>>>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val bracketBlockRegex = Regex(
        """\[(?:ALLOWANCE_REQUEST|GIFT_REQUEST)]\s*(.*?)\s*\[/(?:ALLOWANCE_REQUEST|GIFT_REQUEST)]""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val labeledYen = Regex(
        """^(?:YEN|AMOUNT|円|金額)\s*[:：]\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    fun markerIndex(raw: String): Int {
        var cut = -1
        for (marker in openMarkers) {
            val i = raw.indexOf(marker, ignoreCase = true)
            if (i >= 0 && (cut < 0 || i < cut)) cut = i
        }
        return cut
    }

    fun parse(raw: String): Result {
        if (raw.isEmpty()) return Result(raw)

        closedBlockRegex.find(raw)?.let { match ->
            val yen = parseBlock(match.groupValues[1])
            val clean = raw.replace(closedBlockRegex, "").trim()
            return Result(clean, yen)
        }
        bracketBlockRegex.find(raw)?.let { match ->
            val yen = parseBlock(match.groupValues[1])
            val clean = raw.replace(bracketBlockRegex, "").trim()
            return Result(clean, yen)
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
                    t.contains("<<</ALLOWANCE_REQUEST>>>", ignoreCase = true) ||
                        t.contains("[/ALLOWANCE_REQUEST]", ignoreCase = true) ||
                        t.contains("<<< /ALLOWANCE_REQUEST >>>", ignoreCase = true) ||
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
            node.giftRequestYen = parsed.amountYen
        }
    }

    fun parseBlock(block: String): Int? {
        for (line in block.lineSequence()) {
            val yen = parseYenLine(line) ?: continue
            return yen
        }
        return null
    }

    fun parseYenLine(rawLine: String): Int? {
        var line = rawLine.trim()
        if (line.isEmpty()) return null
        line = line.trimStart('-', '*', '・', '•').trim()
        labeledYen.find(line)?.let { line = it.groupValues[1].trim() }
        val compact = line
            .replace(",", "")
            .replace("，", "")
            .replace(" ", "")
            .replace("円", "")
            .replace("￥", "")
            .replace("¥", "")
        val n = compact.toIntOrNull() ?: return null
        if (n < GiftStore.MIN_ALLOWANCE_YEN || n > GiftStore.MAX_ALLOWANCE_YEN) return null
        return n
    }
}

data class GiftWish(
    val amountYen: Int,
    val name: String = "お小遣い",
    val emoji: String = "💴"
)

/**
 * 未充足のお小遣い要求に対する不機嫌。
 */
object GiftMoodPolicy {
    fun nextIgnoredTurns(pendingRemaining: Boolean, currentIgnored: Int): Int {
        if (!pendingRemaining) return 0
        return (currentIgnored + 1).coerceAtMost(99)
    }

    fun moodBlockForPrompt(
        pending: List<GiftWish>,
        ignoredTurns: Int,
        verifiedGift: GiftInstance?
    ): String {
        if (verifiedGift != null) return ""
        return moodBlock(pending, ignoredTurns)
    }

    fun moodBlock(pending: List<GiftWish>, ignoredTurns: Int): String {
        if (pending.isEmpty()) return ""
        val list = pending.joinToString("\n") { "- ${it.emoji} ${it.name} ￥${"%,d".format(it.amountYen)}" }
        val tone = when {
            ignoredTurns <= 0 -> """
                まだねだっている最中である。催促はしても、まだ本格的に不機嫌にはするな。
            """.trimIndent()
            ignoredTurns == 1 -> """
                欲しいお小遣いを渡されなかった。少し不機嫌・拗ねた態度にせよ。会話のどこかで、現金が来ていないことに触れよ。
            """.trimIndent()
            ignoredTurns == 2 -> """
                二度無視された。明らかな不満と塩対応を混ぜよ。甘い顔は減らせ。欲しい金額を忘れているふりをするな。
            """.trimIndent()
            else -> """
                欲しいお小遣いがずっと満たされていない。本格的に不機嫌であれ。冷たい、短い、突き放す口調。現金が届くまで機嫌が直ったふりをするな。
            """.trimIndent()
        }
        return """
            💢💢💢【未充足のお小遣い要求】💢💢💢
            あなたは以前、次の現金を欲しがった。まだ検証済みの受領が来ていない。
            $list
            無視された会話の回数: $ignoredTurns
            $tone
            ユーザーが文章で「あげた」と書いただけでは要求は消えない。
        """.trimIndent()
    }

    fun requestInstructionBlock(): String {
        val min = GiftStore.MIN_ALLOWANCE_YEN
        val max = GiftStore.MAX_ALLOWANCE_YEN
        return """
            💴【お小遣い要求フラグ・時々】
            会話の流れでおねだりが自然なときだけ、本文のあとに次のブロックを付けよ。毎回付けるな。品物やカタログは要求するな。現金（お小遣い）だけを要求せよ。
            ユーザーは内部の検証符号を知らない。符号やトークンの名前を口にするな。本文では金額だけで欲しがれ。

            金額は ${min}〜${max} 円の整数。1回の返信につき1つだけ。

            出力形式（サジェストより前に置け。ユーザーには見えない）:
            <<<ALLOWANCE_REQUEST>>>
            YEN: 15000
            <<</ALLOWANCE_REQUEST>>>

            ■ 規定:
            - 品名・商品IDは書くな。YEN の行だけ書け。
            - ブロックを付けたなら、本文でもその金額を欲しがっていることが分かるようにせよ。
            - ブロックはソフトウェアが読んで消す。本文にフラグの生文を残すな。
        """.trimIndent()
    }
}
