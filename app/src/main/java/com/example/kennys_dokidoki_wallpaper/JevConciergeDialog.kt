package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 采配コンシェルジュのMD3ポップアップ。
 * Jevは判断だけに使い、文章は文章LLM、実行は確定コードが行う。
 * 編集系は変更前後の比較ポップアップで見せてから保存する。
 */
internal object JevConciergeDialog {
    /** チャット1行分。planは編集提案、appliedは保存済みか */
    private sealed class Msg {
        class User(val text: String) : Msg()

        class Assistant(val text: String, val detail: String?, val plan: Plan? = null) : Msg() {
            var applied = false
        }
    }

    /** 要確認の編集提案。保存は比較ポップアップのOKでのみ行う */
    private sealed class Plan {
        data class EditCard(
            val target: JevGenieCardRef,
            val newMain: String,
            val newNegative: String,
            val confidence: Double,
            val verify: Double
        ) : Plan()

        data class EditTag(
            val target: JevGenieTagRef,
            val newText: String,
            val confidence: Double,
            val verify: Double
        ) : Plan()

        data class NewElement(
            val draft: ElementDraft,
            val cardConf: Double?,
            val tagConf: Double?
        ) : Plan()
    }

    /** 1回の依頼の実行結果。dismissAfterは画面遷移系で使う */
    private data class Outcome(
        val text: String,
        val detail: String?,
        val plan: Plan? = null,
        val dismissAfter: Boolean = false
    )

    private const val DRAFT_TRIES = 2
    private const val NEW_MARK = "（新規作成）"
    private const val EMPTY_MARK = "（空）"

    fun show(activity: AppCompatActivity, entry: ConciergeEntry, host: ConciergeHost) {
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_concierge)
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val list = view.findViewById<RecyclerView>(R.id.rv_concierge)
        val input = view.findViewById<EditText>(R.id.et_concierge_input)
        val status = view.findViewById<TextView>(R.id.tv_concierge_status)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.progress_concierge)
        val send = view.findViewById<MaterialButton>(R.id.btn_concierge_send)
        val jevChip = view.findViewById<MaterialButton>(R.id.btn_concierge_jev)
        val writerChip = view.findViewById<MaterialButton>(R.id.btn_concierge_writer)

        var jev = prefs.getString(JevConciergePolicy.KEY_JEV, null) ?: JevConciergePolicy.DEFAULT_JEV
        var writer = prefs.getString(JevConciergePolicy.KEY_WRITER, null)
            ?: JevElementPolicy.DEFAULT_WRITER
        val msgs = mutableListOf<Msg>(
            Msg.Assistant(activity.getString(R.string.concierge_greeting, entry.label), null)
        )
        val turns = mutableListOf<Pair<String, String>>()
        var busy = false

        list.layoutManager = LinearLayoutManager(activity)
        val adapter = ConciergeAdapter(msgs) { msg ->
            openDiff(activity, host, msg) { list.adapter?.notifyDataSetChanged() }
        }
        list.adapter = adapter
        val dialog = Md3PopupDialog.show(activity, view)

        fun setStatus(text: String?) {
            activity.runOnUiThread {
                if (text == null) {
                    status.visibility = View.GONE
                } else {
                    status.text = text
                    status.visibility = View.VISIBLE
                }
            }
        }

        fun updateChips() {
            jevChip.text = jev
            writerChip.text = writer
        }
        updateChips()
        jevChip.setOnClickListener {
            JevModelPicker.show(activity, activity.getString(R.string.concierge_pick_jev), jev) { id ->
                runCatching { JevConciergePolicy.jevModel(id) }.onSuccess {
                    jev = it
                    prefs.edit().putString(JevConciergePolicy.KEY_JEV, it).apply()
                    updateChips()
                }.onFailure { error ->
                    Toast.makeText(activity, error.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        writerChip.setOnClickListener {
            JevModelPicker.show(activity, activity.getString(R.string.concierge_pick_writer), writer) { id ->
                runCatching { JevElementPolicy.writer(id) }.onSuccess {
                    writer = it
                    prefs.edit().putString(JevConciergePolicy.KEY_WRITER, it).apply()
                    updateChips()
                }.onFailure { error ->
                    Toast.makeText(activity, error.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        fun rememberTurn(role: String, text: String) {
            turns += role to text
            while (turns.size > JevConciergePolicy.HISTORY_TURNS * 2) {
                turns.removeAt(0)
            }
        }

        fun send() {
            val wish = input.text.toString().trim()
            if (busy || wish.isEmpty()) {
                return
            }
            input.setText("")
            busy = true
            setStatus("Jevが依頼を判断中…")
            progress.show()
            send.isEnabled = false
            msgs += Msg.User(wish)
            adapter.notifyDataSetChanged()

            val screen = entry.label
            val history = turns.toList()
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val outcome = runCatching {
                    serve(activity, host, wish, screen, history, ::setStatus)
                }
                withContext(Dispatchers.Main) {
                    busy = false
                    setStatus(null)
                    progress.hide()
                    send.isEnabled = true
                    if (!dialog.isShowing) {
                        return@withContext
                    }
                    outcome.onSuccess { result ->
                        rememberTurn("user", wish)
                        rememberTurn("assistant", result.text)
                        val assistantMsg = Msg.Assistant(result.text, result.detail, result.plan)
                        msgs += assistantMsg
                        adapter.notifyDataSetChanged()
                        if (result.plan != null) {
                            openDiff(activity, host, assistantMsg) {
                                list.adapter?.notifyDataSetChanged()
                            }
                        }
                        if (result.dismissAfter) {
                            dialog.dismiss()
                        }
                    }.onFailure { error ->
                        msgs += Msg.Assistant(
                            activity.getString(R.string.concierge_failed, error.message ?: ""), null
                        )
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }

        send.setOnClickListener { send() }
    }

    // ---------- 采配本体 (IOスレッド) ----------

    private suspend fun serve(
        activity: AppCompatActivity,
        host: ConciergeHost,
        wish: String,
        screen: String,
        history: List<Pair<String, String>>,
        setStatus: (String?) -> Unit
    ): Outcome {
        val settings = JevConciergeTools.settings(activity)
        val cards = JevGenieTools.cardRefs()
        val tags = JevGenieTools.tagRefs()
        val presets = PresetManager.presets.toList()

        val route = JevConciergePolicy.parseRoute(
            JevConciergeTools.decide(
                activity, settings.endpoint,
                JevConciergePolicy.routeBody(
                    wish, screen, history,
                    Triple(cards.size, tags.size, presets.size), settings.jev
                )
            )
        )
        if (route.confidence < JevConciergePolicy.TOOL_MIN_CONF) {
            return Outcome(
                "依頼の種類を特定できませんでした。できることを挙げるので、もう少し具体的に教えてください。\n" +
                    "・カード編集（例: 制服カードをお腹の素肌が見えるようにして）\n" +
                    "・タグ編集（例: 笑顔タグの文章を優しい感じに直して）\n" +
                    "・新規作成（例: 照れ顔という要素を作って）\n" +
                    "・プリセット適用・生成カードの選択・画像の絞り込み・生成開始",
                "判断保留 · Jev確信度${percent(route.confidence)}"
            )
        }
        return when (route.tool) {
            ConciergeTool.EDIT_CARD -> editCard(activity, wish, screen, settings, cards, setStatus)
            ConciergeTool.EDIT_TAG -> editTag(activity, wish, screen, settings, tags, setStatus)
            ConciergeTool.NEW_ELEMENT -> newElement(activity, wish, settings, setStatus)
            ConciergeTool.APPLY_PRESET -> applyPreset(activity, host, wish, screen, settings, presets)
            ConciergeTool.SELECT_CARDS -> selectCards(activity, host, wish, settings, cards, setStatus)
            ConciergeTool.FILTER_IMAGES -> filterImages(activity, host, wish, screen, settings, tags)
            ConciergeTool.START_GENERATION -> startGeneration(host)
            ConciergeTool.TALK -> talk(activity, wish, history, settings, setStatus)
        }
    }

    private suspend fun editCard(
        activity: AppCompatActivity,
        wish: String,
        screen: String,
        settings: JevConciergeTools.Settings,
        cards: List<JevGenieCardRef>,
        setStatus: (String?) -> Unit
    ): Outcome {
        if (cards.isEmpty()) {
            return Outcome("プロンプトカードがまだありません。生成画面の＋ボタンから先に作ってください。", null)
        }
        val ranked = JevConciergePolicy.rankCandidates(
            wish, cards.map { ConciergeCandidate(it.id, it.label, "${it.category} :: ${it.mainPrompt}") }
        )
        setStatus("対象のカードを特定中…")
        val pick = JevConciergePolicy.parseTarget(
            JevConciergeTools.decide(
                activity, settings.endpoint,
                JevConciergePolicy.targetBody(wish, screen, "プロンプトカード", ranked, settings.jev)
            ),
            ranked.size
        )
        val target = pick.index?.let { index -> cards.firstOrNull { it.id == ranked[index].key } }
        if (target == null || pick.probability < JevConciergePolicy.TARGET_MIN_PROB) {
            return Outcome(
                "『$wish』に合うカードが見つかりませんでした。カード名を教えてください。",
                "🛠 ${ConciergeTool.EDIT_CARD.label} · 対象を特定できず"
            )
        }

        // 文章案はLLMに書かせ、Jevは採用可否だけを判定する
        var bestMain = ""
        var bestNegative = target.negativePrompt
        var bestScore = -1.0
        for (_ in 0 until DRAFT_TRIES) {
            setStatus("変更案を作成中…")
            val draft = runCatching {
                PromptCardAiResponseParser.parse(
                    JevConciergeTools.chat(
                        activity,
                        JevConciergePolicy.chatBody(
                            settings.writer,
                            JevConciergePolicy.CARD_REWRITE_SYSTEM,
                            JevConciergePolicy.cardRewriteUser(
                                target.mainPrompt, target.negativePrompt, wish
                            )
                        )
                    )
                )
            }.getOrNull()
            val main = draft?.mainPrompt?.trim().orEmpty()
            if (main.isEmpty()) {
                continue
            }
            val negative = draft?.negativePrompt?.trim().orEmpty().ifEmpty { target.negativePrompt }
            setStatus("変更案を検証中…")
            val score = JevConciergePolicy.parseVerify(
                JevConciergeTools.decide(
                    activity, settings.endpoint,
                    JevConciergePolicy.verifyBody(wish, target.mainPrompt, main, settings.jev)
                )
            )
            if (score > bestScore) {
                bestScore = score
                bestMain = main
                bestNegative = negative
            }
            if (score >= JevConciergePolicy.VERIFY_MIN_PROB) {
                break
            }
        }
        if (bestMain.isEmpty()) {
            return Outcome("変更案を作れませんでした。文章モデルを変えるか、依頼を言い換えてください。", null)
        }
        return Outcome(
            "『${target.label}』の変更案ができました。内容を確認してください。",
            "🛠 ${ConciergeTool.EDIT_CARD.label} · Jev確信度${percent(pick.probability)}",
            Plan.EditCard(target, bestMain, bestNegative, pick.probability, bestScore)
        )
    }

    private suspend fun editTag(
        activity: AppCompatActivity,
        wish: String,
        screen: String,
        settings: JevConciergeTools.Settings,
        tags: List<JevGenieTagRef>,
        setStatus: (String?) -> Unit
    ): Outcome {
        if (tags.isEmpty()) {
            return Outcome("タグがまだありません。タグ画面の＋ボタンから先に作ってください。", null)
        }
        val ranked = JevConciergePolicy.rankCandidates(
            wish, tags.map { ConciergeCandidate(it.name, it.name, it.text) }
        )
        setStatus("対象のタグを特定中…")
        val pick = JevConciergePolicy.parseTarget(
            JevConciergeTools.decide(
                activity, settings.endpoint,
                JevConciergePolicy.targetBody(wish, screen, "タグ", ranked, settings.jev)
            ),
            ranked.size
        )
        val target = pick.index?.let { index -> tags.firstOrNull { it.name == ranked[index].key } }
        if (target == null || pick.probability < JevConciergePolicy.TARGET_MIN_PROB) {
            return Outcome(
                "『$wish』に合うタグが見つかりませんでした。タグ名を教えてください。",
                "🛠 ${ConciergeTool.EDIT_TAG.label} · 対象を特定できず"
            )
        }

        var bestText = ""
        var bestScore = -1.0
        for (_ in 0 until DRAFT_TRIES) {
            setStatus("変更案を作成中…")
            val draft = runCatching {
                JevConciergePolicy.parseTagText(
                    JevConciergeTools.chat(
                        activity,
                        JevConciergePolicy.chatBody(
                            settings.writer,
                            JevConciergePolicy.TAG_REWRITE_SYSTEM,
                            JevConciergePolicy.tagRewriteUser(target.text, wish)
                        )
                    )
                )
            }.getOrNull().orEmpty()
            if (draft.isEmpty()) {
                continue
            }
            setStatus("変更案を検証中…")
            val score = JevConciergePolicy.parseVerify(
                JevConciergeTools.decide(
                    activity, settings.endpoint,
                    JevConciergePolicy.verifyBody(wish, target.text, draft, settings.jev)
                )
            )
            if (score > bestScore) {
                bestScore = score
                bestText = draft
            }
            if (score >= JevConciergePolicy.VERIFY_MIN_PROB) {
                break
            }
        }
        if (bestText.isEmpty()) {
            return Outcome("変更案を作れませんでした。文章モデルを変えるか、依頼を言い換えてください。", null)
        }
        return Outcome(
            "タグ『${target.name}』の変更案ができました。内容を確認してください。",
            "🛠 ${ConciergeTool.EDIT_TAG.label} · Jev確信度${percent(pick.probability)}",
            Plan.EditTag(target, bestText, pick.probability, bestScore)
        )
    }

    private suspend fun newElement(
        activity: AppCompatActivity,
        wish: String,
        settings: JevConciergeTools.Settings,
        setStatus: (String?) -> Unit
    ): Outcome {
        setStatus("要素名を読み取り中…")
        val name = runCatching {
            JevConciergePolicy.parseName(
                JevConciergeTools.chat(
                    activity,
                    JevConciergePolicy.chatBody(settings.writer, JevConciergePolicy.NAME_SYSTEM, wish)
                )
            )
        }.getOrNull()
        if (name == null) {
            return Outcome("要素名を読み取れませんでした。『○○という要素を作って』のように名前を教えてください。", null)
        }
        if (JevElementService.existingNames().any { JevElementPolicy.sameName(it, name) }) {
            return Outcome("『$name』は既にあります。変えたい場合は『$name を〜に変えて』と頼んでください。", null)
        }
        JevElementService.ensureCategories(activity)
        setStatus("カテゴリーを判断中…")
        val key = JevConciergeTools.apiKey(activity)
        val (cardCategory, tagCategory) = JevElementService.decide(
            settings.endpoint, key, name, JevElementService.catalog(), settings.jev
        )
        if (cardCategory.name == null || tagCategory.name == null) {
            return Outcome(
                "『$name』の置き場所を決められませんでした。タグ画面の「Jevでカードとタグを一括生成」では手動で選べます。",
                "🛠 ${ConciergeTool.NEW_ELEMENT.label} · カテゴリー未判定"
            )
        }
        setStatus("文章を作成中…")
        val text = JevElementService.write(
            JevElementClient.CHAT_ENDPOINT, key, name,
            settings.writer, JevElementService.settings(activity).instruction
        )
        val draft = ElementDraft(
            name = name,
            cardCategory = cardCategory.name,
            tagCategory = tagCategory.name,
            text = text
        )
        return Outcome(
            "『$name』の草案ができました。内容を確認してください。",
            "🛠 ${ConciergeTool.NEW_ELEMENT.label} · カテゴリー確信度${percentOrDash(cardCategory.probability)}・${percentOrDash(tagCategory.probability)}",
            Plan.NewElement(draft, cardCategory.probability, tagCategory.probability)
        )
    }

    private suspend fun applyPreset(
        activity: AppCompatActivity,
        host: ConciergeHost,
        wish: String,
        screen: String,
        settings: JevConciergeTools.Settings,
        presets: List<Preset>
    ): Outcome {
        if (presets.isEmpty()) {
            return Outcome("プリセットがありません。生成画面で今の状態を保存してから使ってください。", null)
        }
        val ranked = JevConciergePolicy.rankCandidates(
            wish, presets.map {
                ConciergeCandidate(it.id, it.name, "${it.category} :: カード${it.activePromptStates.size}枚")
            }
        )
        val pick = JevConciergePolicy.parseTarget(
            JevConciergeTools.decide(
                activity, settings.endpoint,
                JevConciergePolicy.targetBody(wish, screen, "プリセット", ranked, settings.jev)
            ),
            ranked.size
        )
        val preset = pick.index?.let { index -> presets.firstOrNull { it.id == ranked[index].key } }
        if (preset == null || pick.probability < JevConciergePolicy.TARGET_MIN_PROB) {
            return Outcome("合うプリセットが見つかりませんでした。プリセット名を教えてください。", "🛠 ${ConciergeTool.APPLY_PRESET.label} · 対象を特定できず")
        }
        withContext(Dispatchers.Main) { host.applyPreset(preset) }
        return Outcome(
            "プリセット『${preset.name}』を適用しました。生成画面の↩ボタンで元に戻せます。",
            "🛠 ${ConciergeTool.APPLY_PRESET.label} · Jev確信度${percent(pick.probability)}"
        )
    }

    private suspend fun selectCards(
        activity: AppCompatActivity,
        host: ConciergeHost,
        wish: String,
        settings: JevConciergeTools.Settings,
        cards: List<JevGenieCardRef>,
        setStatus: (String?) -> Unit
    ): Outcome {
        if (cards.isEmpty()) {
            return Outcome("プロンプトカードがまだありません。生成画面の＋ボタンから先に作ってください。", null)
        }
        val ranked = JevConciergePolicy.rankCandidates(
            wish,
            cards.map { ConciergeCandidate(it.id, it.label, "${it.category} :: ${it.mainPrompt}") },
            JevConciergePolicy.SELECT_MAX_OPTIONS
        )
        setStatus("含めるカードを判断中…")
        val picked = JevConciergePolicy.parseSelect(
            JevConciergeTools.decide(
                activity, settings.endpoint,
                JevConciergePolicy.selectBody(wish, ranked, settings.jev)
            ),
            ranked.size
        )
        if (picked.isEmpty()) {
            return Outcome(
                "含めるカードを特定できませんでした。具体的な名前を挙げてください（例: 制服と笑顔を選んで）。",
                "🛠 ${ConciergeTool.SELECT_CARDS.label} · 該当なし"
            )
        }
        val mode = JevConciergePolicy.selectionMode(wish)
        withContext(Dispatchers.Main) { host.selectCards(picked.map { ranked[it].key }, mode) }
        val names = picked.map { ranked[it].name }
        val done = mode == CardSelectionMode.REPLACE
        return Outcome(
            "${names.size}枚を${if (done) "選択し直しました" else "選択しました"}: ${names.joinToString("、")}。",
            "🛠 ${ConciergeTool.SELECT_CARDS.label} · Jev判定"
        )
    }

    private suspend fun filterImages(
        activity: AppCompatActivity,
        host: ConciergeHost,
        wish: String,
        screen: String,
        settings: JevConciergeTools.Settings,
        tags: List<JevGenieTagRef>
    ): Outcome {
        if (tags.isEmpty()) {
            return Outcome("タグがまだありません。タグ画面の＋ボタンから先に作ってください。", null)
        }
        val ranked = JevConciergePolicy.rankCandidates(
            wish, tags.map { ConciergeCandidate(it.name, it.name, it.text) }
        )
        val pick = JevConciergePolicy.parseTarget(
            JevConciergeTools.decide(
                activity, settings.endpoint,
                JevConciergePolicy.targetBody(wish, screen, "タグ", ranked, settings.jev)
            ),
            ranked.size
        )
        val tag = pick.index?.let { ranked[it].key }
        if (tag == null || pick.probability < JevConciergePolicy.TARGET_MIN_PROB) {
            return Outcome("『$wish』に合うタグが見つかりませんでした。タグ名を教えてください。", "🛠 ${ConciergeTool.FILTER_IMAGES.label} · 対象を特定できず")
        }
        withContext(Dispatchers.Main) { host.filterImages(tag) }
        return Outcome(
            "タグ『$tag』で絞り込みました。",
            "🛠 ${ConciergeTool.FILTER_IMAGES.label} · Jev確信度${percent(pick.probability)}",
            dismissAfter = true
        )
    }

    private suspend fun startGeneration(host: ConciergeHost): Outcome {
        if (GenerationProgressManager.state.value.isGenerating) {
            return Outcome("生成中です。終わるまでお待ちください。", null)
        }
        if (!JevConciergeTools.generationReady()) {
            return Outcome("生成するカードが選ばれていません。『○○を選んで』と頼むか、生成画面でカードを選んでください。", null)
        }
        withContext(Dispatchers.Main) { host.startGeneration() }
        return Outcome("生成を始めました。", "🛠 ${ConciergeTool.START_GENERATION.label}", dismissAfter = true)
    }

    private suspend fun talk(
        activity: AppCompatActivity,
        wish: String,
        history: List<Pair<String, String>>,
        settings: JevConciergeTools.Settings,
        setStatus: (String?) -> Unit
    ): Outcome {
        setStatus("回答を作成中…")
        val reply = JevConciergeTools.chat(
            activity,
            JevConciergePolicy.chatBody(
                settings.writer, JevConciergePolicy.TALK_SYSTEM, JevConciergePolicy.talkUser(history, wish)
            )
        )
        return Outcome(reply.trim(), "💬 文章モデル")
    }

    private fun percent(value: Double): String = "${(value * 100).toInt()}%"

    private fun percentOrDash(value: Double?): String =
        value?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { percent(it) } ?: "―"

    // ---------- 変更前後の比較ポップアップ ----------

    private fun openDiff(
        activity: AppCompatActivity,
        host: ConciergeHost,
        msg: Msg.Assistant,
        onApplied: () -> Unit
    ) {
        val plan = msg.plan ?: return
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_concierge_diff)
        val rows = view.findViewById<LinearLayout>(R.id.ll_concierge_diff_rows)
        val warning = view.findViewById<TextView>(R.id.tv_concierge_diff_warning)
        when (plan) {
            is Plan.EditCard -> {
                view.findViewById<TextView>(R.id.tv_concierge_diff_target).text =
                    "${plan.target.category} / ${plan.target.label}"
                addRow(rows, JevGeniePolicy.FIELD_MAIN, plan.target.mainPrompt.ifEmpty { EMPTY_MARK }, plan.newMain)
                if (plan.target.negativePrompt != plan.newNegative) {
                    addRow(
                        rows, JevGeniePolicy.FIELD_NEGATIVE,
                        plan.target.negativePrompt.ifEmpty { EMPTY_MARK }, plan.newNegative
                    )
                }
                showWarning(warning, plan.verify)
            }
            is Plan.EditTag -> {
                view.findViewById<TextView>(R.id.tv_concierge_diff_target).text =
                    activity.getString(R.string.genie_diff_tag, plan.target.name)
                addRow(rows, JevGeniePolicy.FIELD_TEXT, plan.target.text.ifEmpty { EMPTY_MARK }, plan.newText)
                showWarning(warning, plan.verify)
            }
            is Plan.NewElement -> {
                view.findViewById<TextView>(R.id.tv_concierge_diff_target).text =
                    "新規要素『${plan.draft.name}』"
                addRow(rows, "カードのカテゴリー", NEW_MARK, withConf(plan.draft.cardCategory, plan.cardConf))
                addRow(rows, "タグのカテゴリー", NEW_MARK, withConf(plan.draft.tagCategory, plan.tagConf))
                addRow(rows, JevGeniePolicy.FIELD_MAIN, NEW_MARK, plan.draft.text.main)
                addRow(
                    rows, JevGeniePolicy.FIELD_NEGATIVE, NEW_MARK,
                    plan.draft.text.negative.ifEmpty { EMPTY_MARK }
                )
                addRow(rows, "chat_instruction", NEW_MARK, plan.draft.text.chat)
            }
        }

        val dialog = Md3PopupDialog.show(activity, view)
        view.findViewById<MaterialButton>(R.id.btn_concierge_diff_cancel).setOnClickListener {
            Toast.makeText(activity, R.string.concierge_cancelled, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_concierge_diff_save).setOnClickListener {
            var saved = false
            runCatching {
                when (plan) {
                    is Plan.EditCard -> {
                        saved = JevConciergeTools.applyCard(activity, plan.target, plan.newMain, plan.newNegative)
                        if (saved) {
                            host.refreshBuilder()
                        }
                    }
                    is Plan.EditTag -> {
                        saved = JevConciergeTools.applyTag(activity, plan.target, plan.newText)
                        if (saved) {
                            host.refreshTags()
                        }
                    }
                    is Plan.NewElement -> {
                        JevElementService.persist(activity, plan.draft)
                        host.refreshBuilder()
                        host.refreshTags()
                        saved = true
                    }
                }
            }
            if (saved) {
                msg.applied = true
                Toast.makeText(activity, R.string.concierge_saved, Toast.LENGTH_SHORT).show()
                onApplied()
            } else {
                Toast.makeText(activity, R.string.concierge_apply_failed, Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
    }

    private fun addRow(rows: LinearLayout, label: String, before: String, after: String) {
        val row = LayoutInflater.from(rows.context).inflate(R.layout.item_concierge_diff_row, rows, false)
        row.findViewById<TextView>(R.id.tv_row_label).text = label
        row.findViewById<TextView>(R.id.tv_row_before).text = before
        row.findViewById<TextView>(R.id.tv_row_after).text = after
        rows.addView(row)
    }

    private fun showWarning(warning: TextView, verify: Double) {
        if (verify >= JevConciergePolicy.VERIFY_MIN_PROB) {
            warning.visibility = View.GONE
            return
        }
        warning.text = "Jevの検証確信度が${percent(verify)}と低めです。内容をよく確認してください。"
        warning.visibility = View.VISIBLE
    }

    private fun withConf(value: String, confidence: Double?): String =
        if (confidence == null) value else "$value（Jev確信度${percentOrDash(confidence)}）"

    /** チャット1行分を描くアダプタ。編集提案には確認ボタンを添える */
    private class ConciergeAdapter(
        private val msgs: List<Msg>,
        private val onShowPlan: (Msg.Assistant) -> Unit
    ) : RecyclerView.Adapter<ConciergeAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val rowUser: View = view.findViewById(R.id.row_user)
            val textUser: TextView = view.findViewById(R.id.tv_concierge_user)
            val rowAssistant: View = view.findViewById(R.id.row_assistant)
            val textAssistant: TextView = view.findViewById(R.id.tv_concierge_assistant)
            val detail: TextView = view.findViewById(R.id.tv_concierge_detail)
            val applied: TextView = view.findViewById(R.id.tv_concierge_applied)
            val action: MaterialButton = view.findViewById(R.id.btn_concierge_action)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_concierge_bubble, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            when (val msg = msgs[position]) {
                is Msg.User -> {
                    holder.rowUser.visibility = View.VISIBLE
                    holder.rowAssistant.visibility = View.GONE
                    holder.textUser.text = msg.text
                }
                is Msg.Assistant -> {
                    holder.rowUser.visibility = View.GONE
                    holder.rowAssistant.visibility = View.VISIBLE
                    holder.textAssistant.text = msg.text
                    if (msg.detail == null) {
                        holder.detail.visibility = View.GONE
                    } else {
                        holder.detail.visibility = View.VISIBLE
                        holder.detail.text = msg.detail
                    }
                    holder.applied.visibility = if (msg.applied) View.VISIBLE else View.GONE
                    holder.action.visibility =
                        if (msg.plan != null && !msg.applied) View.VISIBLE else View.GONE
                    holder.action.setOnClickListener { onShowPlan(msg) }
                }
            }
        }

        override fun getItemCount() = msgs.size
    }
}
