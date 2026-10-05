package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONObject

internal object ConciergeEditorTools {
    data class Proposal(val draft: ConciergeEditorPolicy.Draft, val confidence: Double)

    suspend fun propose(
        context: Context,
        before: ConciergeEditorPolicy.Draft,
        wish: String,
        history: String
    ): Proposal {
        val settings = JevConciergeTools.settings(context)
        val card = before.kind == ConciergeEditorPolicy.Kind.CARD
        val system = if (card) {
            "Stable Diffusion用プロンプトの編集者。JSONのみ返す。必須キーはmain_promptとnegative_prompt。" +
                "両方に変更後の全文を入れる。変更不要な欄は原文をそのまま返す。" +
                "依頼箇所だけを変え、関係ない要素を追加しない。"
        } else {
            JevConciergePolicy.TAG_REWRITE_SYSTEM
        }
        val request = ConciergeEditorPolicy.context(before) +
            "\n直近の相談（現在の編集欄を優先）:\n$history\n今回の依頼:\n$wish"
        val raw = JevConciergeTools.chat(context, JevConciergePolicy.chatBody(settings.writer, system, request))
        val after = if (card) {
            val json = JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            require(json.opt("main_prompt") is String && json.opt("negative_prompt") is String) {
                "変更案の形式が不正です。もう一度依頼してください。"
            }
            before.copy(main = json.getString("main_prompt"), negative = json.getString("negative_prompt"))
        } else {
            before.copy(main = JevConciergePolicy.parseTagText(raw))
        }
        require(after.main.isNotBlank() && after.main.length <= JevConciergePolicy.MAX_TEXT &&
            after.negative.length <= JevConciergePolicy.MAX_TEXT) { "変更案が空か、長すぎます。" }
        val confidence = JevConciergePolicy.parseVerify(
            JevConciergeTools.decide(context, settings.endpoint, JevConciergePolicy.verifyBody(
                wish, ConciergeEditorPolicy.context(before), ConciergeEditorPolicy.context(after), settings.jev
            ))
        )
        return Proposal(after, confidence)
    }
}
