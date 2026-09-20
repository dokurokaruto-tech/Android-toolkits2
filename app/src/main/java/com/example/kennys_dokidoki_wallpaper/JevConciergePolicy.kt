package com.example.kennys_dokidoki_wallpaper

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * 采配コンシェルジュの純ロジック層。
 * Jevへの判断リクエスト生成・応答解析・文章LLMへの指示文のみを担当する。
 * ネットワークと適用はJevConciergeTools、UIはJevConciergeDialogが担う。
 *
 * 役割分担:
 *   Jev (判断API) = Choiceで「どの機能か」「どの対象か」「変更案は妥当か」を選ぶだけ。文章は書かせない。
 *   文章LLM (chat/completions) = 変更案の文章・要素名の抽出・使い方の回答を書く。判断はさせない。
 *   確定コード = 候補の絞り込み・閾値判定・保存。Jevの確信度が低ければ人に聞く。
 */
internal enum class ConciergeTool(val id: String, val label: String) {
    EDIT_CARD("edit_card", "カード編集"),
    EDIT_TAG("edit_tag", "タグ編集"),
    NEW_ELEMENT("new_element", "新規要素の作成"),
    APPLY_PRESET("apply_preset", "プリセット適用"),
    SELECT_CARDS("select_cards", "生成カードの選択"),
    FILTER_IMAGES("filter_images", "画像の絞り込み"),
    START_GENERATION("start_generation", "生成開始"),
    TALK("talk", "会話");
}

/** Jevが選んだ機能とその確信度 */
internal data class ConciergeRoute(val tool: ConciergeTool, val confidence: Double)

/** Jevが選んだ対象。indexがnull = none(該当なし) */
internal data class ConciergePick(val index: Int?, val probability: Double)

/** 判断対象の候補1件。keyは呼び出し側が対象へ戻すための識別子 */
internal data class ConciergeCandidate(val key: String, val name: String, val hint: String)

/** 生成カード選択の重ね方。追加か入れ替えか */
internal enum class CardSelectionMode {
    ADD,
    REPLACE;
}

internal object JevConciergePolicy {
    /** 入出力ともに無料の最新版エイリアスを既定にする */
    const val DEFAULT_JEV = "~typesafe/jev-latest"
    const val KEY_JEV = "concierge_jev_model"
    const val KEY_WRITER = "concierge_writer_model"

    /** 機能の確信度がこれ未満なら、推測せず人に聞き返す */
    const val TOOL_MIN_CONF = 0.45

    /** 対象の確率がこれ未満なら、特定失敗として聞き返す */
    const val TARGET_MIN_PROB = 0.40

    /** 変更案の妥当性がこれ未満なら、要確認の警告付きで提示する */
    const val VERIFY_MIN_PROB = 0.65

    /** 「このカードを含めるか」の採用ライン */
    const val SELECT_MIN_PROB = 0.60

    /** Choiceの選択肢上限。Jevの上限255より十分小さく抑える */
    const val MAX_OPTIONS = 60

    /** 一括選択の候補上限。1リクエスト内の質問数を抑える */
    const val SELECT_MAX_OPTIONS = 30

    /** 判断材料に入れる直近の往復数 */
    const val HISTORY_TURNS = 2

    const val MAX_TEXT = 20_000

    private const val NONE = "none"
    private const val CATALOG_CLIP = 200

    /** 判断モデルはJev系に限定する。判断APIは文章LLMを受け付けない */
    fun jevModel(raw: String): String {
        val id = JevElementPolicy.model(raw)
        require(id.contains("jev", ignoreCase = true)) {
            "判断モデルはJev系（名前にjevを含むもの）を選んでください。"
        }
        return id
    }

    // ---------- 機能の振り分け ----------

    fun routeBody(
        wish: String,
        screen: String,
        history: List<Pair<String, String>>,
        counts: Triple<Int, Int, Int>,
        model: String
    ): JSONObject {
        val (cards, tags, presets) = counts
        val state = JSONObject()
            .put("wish", wish)
            .put("screen", screen)
            .put("history", historyText(history))
            .put("library", "cards=$cards tags=$tags presets=$presets")
        val criteria = JSONObject()
            .put(ConciergeTool.EDIT_CARD.id, "既存のプロンプトカードの文章を変える。画像生成用の英語タグの修正")
            .put(ConciergeTool.EDIT_TAG.id, "既存のタグの文章を変える。キャラチャット用の日本語指示の修正")
            .put(ConciergeTool.NEW_ELEMENT.id, "存在しない要素を新しく作る。カードとタグの両方を一括生成")
            .put(ConciergeTool.APPLY_PRESET.id, "保存済みプリセットを適用して生成設定を切り替える")
            .put(ConciergeTool.SELECT_CARDS.id, "生成に使うカードを選ぶ・外す。複数可")
            .put(ConciergeTool.FILTER_IMAGES.id, "タグで画像一覧を絞り込む・探す")
            .put(ConciergeTool.START_GENERATION.id, "いま選んでいる内容で画像生成を始める")
            .put(ConciergeTool.TALK.id, "上記のどれでもない。使い方の質問・雑談・判断できない依頼")
        val question = JSONObject()
            .put("type", "choice")
            .put("instructions", "state.wishを叶える機能を1つ選ぶ。複数に見えても最も中心の1つ。")
            .put("criteria", criteria)
        return JSONObject().put("model", model).put("state", state)
            .put("questions", JSONObject().put("tool", question))
    }

    /** 未知の選択肢が返っても会話に倒して止まらない */
    fun parseRoute(answers: JSONObject): ConciergeRoute {
        val answer = answers.getJSONObject("tool")
        require(answer.getString("type") == "choice") { "判断APIの応答形式が不正です。" }
        val choice = answer.getString("choice")
        val tool = ConciergeTool.entries.firstOrNull { it.id == choice }
        val confidence = confidenceOf(answer, choice)
        if (tool == null) {
            return ConciergeRoute(ConciergeTool.TALK, 0.0)
        }
        return ConciergeRoute(tool, confidence)
    }

    // ---------- 対象の特定 ----------

    fun targetBody(
        wish: String,
        screen: String,
        kindLabel: String,
        candidates: List<ConciergeCandidate>,
        model: String
    ): JSONObject {
        require(candidates.size <= MAX_OPTIONS) { "候補が多すぎます。" }
        val state = JSONObject()
            .put("wish", wish)
            .put("screen", screen)
            .put("catalog", catalogText(candidates))
        val criteria = JSONObject().put(NONE, "該当するものがない、または判断できない")
        candidates.forEachIndexed { index, candidate ->
            criteria.put("c$index", "${candidate.name} :: ${clip(candidate.hint)}")
        }
        val question = JSONObject()
            .put("type", "choice")
            .put("instructions", "state.wishが指す${kindLabel}を1つ選ぶ。なければnone。")
            .put("criteria", criteria)
        return JSONObject().put("model", model).put("state", state)
            .put("questions", JSONObject().put("target", question))
    }

    fun parseTarget(answers: JSONObject, size: Int): ConciergePick {
        val answer = answers.getJSONObject("target")
        require(answer.getString("type") == "choice") { "判断APIの応答形式が不正です。" }
        val choice = answer.getString("choice")
        if (choice == NONE) {
            return ConciergePick(null, confidenceOf(answer, choice))
        }
        val index = choice.removePrefix("c").toIntOrNull()
        require(choice.startsWith("c") && index != null && index in 0 until size) {
            "Jevが候補外を返しました。"
        }
        return ConciergePick(index, confidenceOf(answer, choice))
    }

    // ---------- 変更案の検証 (yes/noもChoiceで聞く) ----------

    fun verifyBody(wish: String, before: String, after: String, model: String): JSONObject {
        val state = JSONObject()
            .put("wish", wish)
            .put("before", clip(before))
            .put("after", clip(after))
        val criteria = JSONObject()
            .put("yes", "変更案は依頼通りで、原文の意図を保っている")
            .put("no", "依頼とずれている、または原文を壊している")
        val question = JSONObject()
            .put("type", "choice")
            .put("instructions", "変更案を採用してよいかを判定する。迷えばno。")
            .put("criteria", criteria)
        return JSONObject().put("model", model).put("state", state)
            .put("questions", JSONObject().put("ok", question))
    }

    /** yesの確率を返す。候補外が返ったら0扱いで要確認に倒す */
    fun parseVerify(answers: JSONObject): Double {
        val answer = answers.optJSONObject("ok") ?: return 0.0
        if (answer.optString("type") != "choice" || answer.optString("choice") != "yes") {
            return 0.0
        }
        return answer.optJSONObject("probabilities")?.optDouble("yes", 0.0)
            ?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0
    }

    // ---------- 一括選択 (候補ごとに含めるかを聞く) ----------

    fun selectBody(
        wish: String,
        candidates: List<ConciergeCandidate>,
        model: String
    ): JSONObject {
        require(candidates.size <= SELECT_MAX_OPTIONS) { "候補が多すぎます。" }
        val state = JSONObject()
            .put("wish", wish)
            .put("catalog", catalogText(candidates))
        val questions = JSONObject()
        candidates.forEachIndexed { index, candidate ->
            questions.put("s$index", JSONObject()
                .put("type", "choice")
                .put("instructions", "「${candidate.name}」を生成選択に含めるか。迷えばno。")
                .put("criteria", JSONObject()
                    .put("yes", "含める。依頼に必要")
                    .put("no", "含めない")))
        }
        return JSONObject().put("model", model).put("state", state).put("questions", questions)
    }

    /** 採用ライン以上の候補インデックスを返す */
    fun parseSelect(answers: JSONObject, size: Int): List<Int> {
        val picked = mutableListOf<Int>()
        for (index in 0 until size) {
            val answer = answers.optJSONObject("s$index") ?: continue
            if (answer.optString("type") != "choice" || answer.optString("choice") != "yes") {
                continue
            }
            val probability = answer.optJSONObject("probabilities")?.optDouble("yes", 0.0)
                ?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0
            if (probability >= SELECT_MIN_PROB) {
                picked += index
            }
        }
        return picked
    }

    private fun confidenceOf(answer: JSONObject, choice: String): Double {
        answer.optDouble("confidence", Double.NaN)
            .takeIf { it.isFinite() && it in 0.0..1.0 }?.let { return it }
        return answer.optJSONObject("probabilities")?.optDouble(choice, Double.NaN)
            ?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0
    }

    // ---------- 候補の事前絞り込み (確定コード) ----------

    /**
     * 依頼文と名前の重なりで候補を並べ替える。
     * 完全一致に近いほど先頭へ。Choiceの選択肢数に収めるための足切り。
     */
    fun rankCandidates(
        wish: String,
        all: List<ConciergeCandidate>,
        limit: Int = MAX_OPTIONS
    ): List<ConciergeCandidate> {
        if (all.size <= limit) {
            return all
        }
        val norm = normalize(wish)
        return all.map { candidate ->
            candidate to score(norm, normalize(candidate.name))
        }.sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
    }

    private fun score(wish: String, name: String): Int {
        if (name.isEmpty()) {
            return 0
        }
        if (wish.contains(name) || name.contains(wish)) {
            return 1000 - name.length
        }
        // 文字バイグラムの重なり。和名の部分一致を拾う
        val wishGrams = wish.windowed(2).toSet()
        if (wishGrams.isEmpty()) {
            return 0
        }
        return name.windowed(2).count { it in wishGrams }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun clip(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= CATALOG_CLIP) oneLine else oneLine.take(CATALOG_CLIP) + "…"
    }

    private fun catalogText(candidates: List<ConciergeCandidate>): String = buildString {
        candidates.forEachIndexed { index, candidate ->
            appendLine("c$index: ${candidate.name} :: ${clip(candidate.hint)}")
        }
    }

    private fun historyText(history: List<Pair<String, String>>): String {
        if (history.isEmpty()) {
            return "(なし)"
        }
        return history.takeLast(HISTORY_TURNS * 2).joinToString("\n") { (role, text) ->
            "$role: ${clip(text)}"
        }
    }

    // ---------- 文章LLMへの指示書 ----------

    fun chatBody(model: String, system: String, user: String): JSONObject = JSONObject()
        .put("model", JevElementPolicy.writer(model))
        .put("temperature", 0.0)
        .put("stream", false)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user)))

    /** カード書き換え。negativeの変更不要時は空文字で返す決まり */
    const val CARD_REWRITE_SYSTEM =
        "Stable Diffusion用プロンプトの編集者。JSONオブジェクトのみ返す。前後に説明やコードフェンスを付けない。" +
        "必須キーは main_prompt（短い英語タグ、カンマ区切り）。negative_promptは除外タグの変更が必要な時だけ入れ、不要なら空文字。" +
        "依頼箇所だけを変え、関係ない要素・画質タグ・背景・衣装・感情を勝手に追加しない。"

    fun cardRewriteUser(currentMain: String, currentNegative: String, wish: String): String =
        JSONObject()
            .put("current_main_prompt", currentMain)
            .put("current_negative_prompt", currentNegative)
            .put("wish", wish)
            .toString()

    const val TAG_REWRITE_SYSTEM =
        "キャラチャット用指示文の編集者。JSONオブジェクトのみ返す。前後に説明やコードフェンスを付けない。" +
        "必須キーは text（短い日本語指示）。依頼箇所だけを変え、明示されていない人格・関係性・口調は維持する。"

    fun tagRewriteUser(current: String, wish: String): String =
        JSONObject().put("current_text", current).put("wish", wish).toString()

    /** タグ書き換え結果の読み取り。JSON厳守のためフォールバックは持たせない */
    fun parseTagText(raw: String): String {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "応答にJSONがありません。" }
        val text = JSONObject(cleaned.substring(start, end + 1)).optString("text").trim()
        require(text.isNotEmpty() && text.length <= MAX_TEXT) { "変更後のtextが不正です。" }
        return text
    }

    /** 要素名の抽出。Jevは文章を書けないため、抜き出しは文章LLMの仕事 */
    const val NAME_SYSTEM =
        "依頼文から作りたい要素の名前だけを抜き出す。JSONオブジェクトのみ返す。" +
        "必須キーは name（1行の短い名前）。挨拶・説明・コードフェンスを付けない。"

    fun parseName(raw: String): String {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "応答にJSONがありません。" }
        val name = JSONObject(cleaned.substring(start, end + 1)).optString("name").trim()
        return JevElementPolicy.name(name)
    }

    /** 「〜だけ」の指定があれば選択の入れ替え、なければ追加 */
    fun selectionMode(wish: String): CardSelectionMode {
        val replace = listOf("だけ", "のみ", "入れ替え", "入れかえ", "切り替え", "きりかえ", "リセット")
            .any { normalize(wish).contains(it) }
        return if (replace) CardSelectionMode.REPLACE else CardSelectionMode.ADD
    }

    /** 会話用の入力。直近のやり取りを添えて文脈を保つ */
    fun talkUser(history: List<Pair<String, String>>, wish: String): String {
        if (history.isEmpty()) {
            return wish
        }
        return "直近のやり取り:\n${historyText(history)}\n\n質問: $wish"
    }

    /** 会話用のアプリ説明書。実行系の依頼には具体的な頼み方を案内する */
    const val TALK_SYSTEM =
        "あなたは画像生成・壁紙・AIキャラチャットアプリ内の案内役。簡潔な日本語で答える。" +
        "このアプリでできること: 全画像の管理とタグ付け、画像セットの管理、タグ別のキャラチャット用指示文の管理、" +
        "プロンプトカードを組み合わせる画像生成（PC連携）、カード選択の保存プリセット、キャラチャット、壁紙設定。" +
        "コンシェルジュへの依頼例: 「○○カードのプロンプトを〜に変えて」「○○タグの文章を直して」" +
        "「○○という要素を作って」「プリセット○○を適用して」「○○と○○を選んで生成して」「○○の画像を探して」。" +
        "実行が必要な依頼には、対応可否と頼み方の例を短く返す。設定変更や保存の断定はしない。"
}
