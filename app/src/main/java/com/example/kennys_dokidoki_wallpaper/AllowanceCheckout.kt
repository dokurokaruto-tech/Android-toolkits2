package com.example.kennys_dokidoki_wallpaper

/**
 * お小遣い（Allowance）の決済・充填ロジックを管理するわ。
 * 所持金が足りないときの提案や、チャット代の計算を行うのじゃ。
 */
object AllowanceCheckout {
    /** 1回のチャットに必要な金額（円） */
    const val CHAT_COST_YEN = 500

    /**
     * 不足分を補いつつ、次の1回分のチャット代(500円)も含めたチャージ額を提案する。
     * お前様がスムーズに会話を続けられるようにのう。
     */
    fun suggestedChargeYen(price: Int, balance: Int): Int {
        val shortfall = (price - balance).coerceAtLeast(0)
        return shortfall + CHAT_COST_YEN
    }

    /**
     * チャット代が払えるかチェックする。
     */
    fun canPayChat(balance: Int, cost: Int = CHAT_COST_YEN): Boolean {
        return balance >= cost
    }

    /**
     * メッセージ本文や選択状態から、どのお小遣いコードを使用するか決定する。
     * UIで選んでいなくても、今キャラが欲しがっている額の在庫があればそれを優先して使うわよ！
     */
    fun pickRedeemCode(
        selectedCode: String?,
        messageText: String,
        unused: List<GiftInstance>,
        pendingYen: Int?
    ): String? {
        // 1. UIで選択されているコードがあれば最優先
        val fromSelection = GiftCrypto.normalizeCode(selectedCode)
        if (fromSelection != null && unused.any { it.publicCode == fromSelection }) {
            return fromSelection
        }

        // 2. メッセージ本文に有効なコードが含まれていればそれを使う
        val fromText = GiftCrypto.extractCodes(messageText).firstOrNull { code ->
            unused.any { it.publicCode == code }
        }
        if (fromText != null) return fromText

        // 3. 特に指定がない場合、今「ほしい」と言っている額と一致する在庫があれば自動でセットする
        if (pendingYen != null) {
            val matching = unused.find { 
                it.catalogId == GiftStore.ALLOWANCE_ID && it.amountYen == pendingYen 
            }
            if (matching != null) return matching.publicCode
        }

        return null
    }
}
