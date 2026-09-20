package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 采配コンシェルジュのMD3ポップアップ。
 * Jevは判断だけに使い、文章は文章LLM、実行は確定コードが行う。
 * 編集系は変更前後の比較ポップアップで見せてから保存する。
 * 提案と実行は履歴に残り、ビフォーアフターの再表示・適用・取消を何度でも行える。
 */
internal object JevConciergeDialog {
    /** チャット1行分。planは編集提案、recordIdは履歴との紐付け */
    private sealed class Msg {
        class User(val text: String) : Msg()

        class Assistant(val text: String, val detail: String?, val plan: Plan? = null) : Msg() {
            var applied = false
            var recordId: String? = null
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
        val record: HistoryRecord? = null,
        val dismissAfter: Boolean = false
    )

    /** 定型フォームからの指定。対象まで決まっていればJevの判断を飛ばす */
    private data class Forced(
        val tool: ConciergeTool,
        val targetKey: String?,
        val thumbKind: ThumbKind?,
        val thumbCategory: String?
    )

    private const val DRAFT_TRIES = 2
    private const val NEW_MARK = "（新規作成）"
    private const val EMPTY_MARK = "（空）"
    private const val SUMMARY_CLIP = 120

    fun show(activity: AppCompatActivity, entry: ConciergeEntry, host: ConciergeHost) {
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_concierge)
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val list = view.findViewById<RecyclerView>(R.id.rv_concierge)
        val input = view.findViewById<EditText>(R.id.et_concierge_input)
        val status = view.findViewById<TextView>(R.id.tv_concierge_status)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.progress_concierge)
        val send = view.findViewById<MaterialButton>(R.id.btn_concierge_send)
        val formButton = view.findViewById<MaterialButton>(R.id.btn_concierge_form)
        val historyButton = view.findViewById<MaterialButton>(R.id.btn_concierge_history)
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

        // 履歴側での適用・取消をチャット表示へ反映する
        fun syncChat() {
            msgs.forEach { msg ->
                if (msg is Msg.Assistant && msg.recordId != null) {
                    val record = HistoryStore.get(activity, msg.recordId!!)
                    msg.applied = record?.status == HistoryStatus.APPLIED
                }
            }
            list.adapter?.notifyDataSetChanged()
        }

        list.layoutManager = LinearLayoutManager(activity)
        val adapter = ConciergeAdapter(msgs) { msg ->
            val recordId = msg.recordId
            if (recordId != null) {
                openRecordDiff(activity, host, recordId) { syncChat() }
            } else {
                openDiff(activity, host, msg) { syncChat() }
            }
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

        fun execute(wish: String, displayWish: String, forced: Forced?) {
            if (busy || wish.isEmpty()) {
                return
            }
            input.setText("")
            busy = true
            setStatus(if (forced == null) "Jevが依頼を判断中…" else "フォーム指定で実行中…")
            progress.show()
            send.isEnabled = false
            msgs += Msg.User(displayWish)
            adapter.notifyDataSetChanged()

            val screen = entry.label
            val history = turns.toList()
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val outcome = runCatching {
                    serve(activity, host, wish, screen, history, forced, ::setStatus)
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
                        rememberTurn("user", displayWish)
                        rememberTurn("assistant", result.text)
                        val assistantMsg = Msg.Assistant(result.text, result.detail, result.plan)
                        result.record?.let {
                            HistoryStore.add(activity, it)
                            assistantMsg.recordId = it.id
                            assistantMsg.applied = it.status == HistoryStatus.APPLIED
                        }
                        msgs += assistantMsg
                        adapter.notifyDataSetChanged()
                        if (result.plan != null) {
                            val pending = pendingRecord(activity, result.plan, result.detail)
                            HistoryStore.add(activity, pending)
                            assistantMsg.recordId = pending.id
                            openDiff(activity, host, assistantMsg) { syncChat() }
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

        fun send() {
            val wish = input.text.toString().trim()
            execute(wish, wish, null)
        }

        historyButton.setOnClickListener { openHistory(activity, host) { syncChat() } }
        formButton.setOnClickListener {
            if (!busy) {
                openForm(activity) { wish, forced, displayWish -> execute(wish, displayWish, forced) }
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
        forced: Forced?,
        setStatus: (String?) -> Unit
    ): Outcome {
        val settings = JevConciergeTools.settings(activity)
        val cards = JevGenieTools.cardRefs()
        val tags = JevGenieTools.tagRefs()
        val presets = PresetManager.presets.toList()

        val route = forced?.let { ConciergeRoute(it.tool, 1.0) } ?: JevConciergePolicy.parseRoute(
            JevConciergeTools.decide(
                activity, settings.endpoint,
                JevConciergePolicy.routeBody(
                    wish, screen, history,
                    Triple(cards.size, tags.size, presets.size), settings.jev
                )
            )
        )
        if (forced == null && route.confidence < JevConciergePolicy.TOOL_MIN_CONF) {
            return Outcome(
                "依頼の種類を特定できませんでした。できることを挙げるので、もう少し具体的に教えてください。\n" +
                    "・カード編集（例: 制服カードをお腹の素肌が見えるようにして）\n" +
                    "・タグ編集（例: 笑顔タグの文章を優しい感じに直して）\n" +
                    "・新規作成（例: 照れ顔という要素を作って）\n" +
                    "・プリセット適用・生成カードの選択・画像の絞り込み・サムネイル生成・生成開始",
                "判断保留 · Jev確信度${percent(route.confidence)}"
            )
        }
        val outcome = when (route.tool) {
            ConciergeTool.EDIT_CARD -> editCard(activity, wish, screen, settings, cards, forced?.targetKey, setStatus)
            ConciergeTool.EDIT_TAG -> editTag(activity, wish, screen, settings, tags, forced?.targetKey, setStatus)
            ConciergeTool.NEW_ELEMENT -> newElement(activity, wish, settings, setStatus)
            ConciergeTool.APPLY_PRESET -> applyPreset(activity, host, wish, screen, settings, presets, forced?.targetKey)
            ConciergeTool.SELECT_CARDS -> selectCards(activity, host, wish, settings, cards, setStatus)
            ConciergeTool.FILTER_IMAGES -> filterImages(activity, host, wish, screen, settings, tags, forced?.targetKey)
            ConciergeTool.START_GENERATION -> startGeneration(activity, host)
            ConciergeTool.THUMBNAILS -> thumbnails(activity, host, wish, screen, settings, forced, setStatus)
            ConciergeTool.TALK -> talk(activity, wish, history, settings, setStatus)
        }
        if (forced == null || outcome.detail == null) {
            return outcome
        }
        return outcome.copy(detail = "${outcome.detail}（フォーム）")
    }

    private suspend fun pickTarget(
        activity: AppCompatActivity,
        settings: JevConciergeTools.Settings,
        wish: String,
        screen: String,
        kindLabel: String,
        ranked: List<ConciergeCandidate>
    ): ConciergePick = JevConciergePolicy.parseTarget(
        JevConciergeTools.decide(
            activity, settings.endpoint,
            JevConciergePolicy.targetBody(wish, screen, kindLabel, ranked, settings.jev)
        ),
        ranked.size
    )

    private suspend fun editCard(
        activity: AppCompatActivity,
        wish: String,
        screen: String,
        settings: JevConciergeTools.Settings,
        cards: List<JevGenieCardRef>,
        forcedKey: String?,
        setStatus: (String?) -> Unit
    ): Outcome {
        val label = ConciergeTool.EDIT_CARD.label
        if (cards.isEmpty()) {
            return Outcome("プロンプトカードがまだありません。生成画面の＋ボタンから先に作ってください。", null)
        }
        val target: JevGenieCardRef
        val targetConf: Double
        if (forcedKey != null) {
            target = cards.firstOrNull { it.id == forcedKey }
                ?: return Outcome("指定のカードが見つかりませんでした。一覧から選び直してください。", "🛠 $label · 対象なし")
            targetConf = 1.0
        } else {
            val ranked = JevConciergePolicy.rankCandidates(
                wish, cards.map { ConciergeCandidate(it.id, it.label, "${it.category} :: ${it.mainPrompt}") }
            )
            setStatus("対象のカードを特定中…")
            val pick = pickTarget(activity, settings, wish, screen, "プロンプトカード", ranked)
            target = pick.index?.let { index -> cards.firstOrNull { it.id == ranked[index].key } }
                ?: return Outcome(
                    "『$wish』に合うカードが見つかりませんでした。カード名を教えてください。",
                    "🛠 $label · 対象を特定できず"
                )
            if (pick.probability < JevConciergePolicy.TARGET_MIN_PROB) {
                return Outcome(
                    "『$wish』に合うカードが見つかりませんでした。カード名を教えてください。",
                    "🛠 $label · 対象を特定できず"
                )
            }
            targetConf = pick.probability
        }

        // 文章案はLLMに書かせ、Jevは採用可否だけを判定する
        var bestMain = ""
        var bestNegative = target.negativePrompt
        var bestScore = -1.0
        for (i in 0 until DRAFT_TRIES) {
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
            "🛠 $label · Jev確信度${percent(targetConf)}",
            Plan.EditCard(target, bestMain, bestNegative, targetConf, bestScore)
        )
    }

    private suspend fun editTag(
        activity: AppCompatActivity,
        wish: String,
        screen: String,
        settings: JevConciergeTools.Settings,
        tags: List<JevGenieTagRef>,
        forcedKey: String?,
        setStatus: (String?) -> Unit
    ): Outcome {
        val label = ConciergeTool.EDIT_TAG.label
        if (tags.isEmpty()) {
            return Outcome("タグがまだありません。タグ画面の＋ボタンから先に作ってください。", null)
        }
        val target: JevGenieTagRef
        val targetConf: Double
        if (forcedKey != null) {
            target = tags.firstOrNull { it.name == forcedKey }
                ?: return Outcome("指定のタグが見つかりませんでした。一覧から選び直してください。", "🛠 $label · 対象なし")
            targetConf = 1.0
        } else {
            val ranked = JevConciergePolicy.rankCandidates(
                wish, tags.map { ConciergeCandidate(it.name, it.name, it.text) }
            )
            setStatus("対象のタグを特定中…")
            val pick = pickTarget(activity, settings, wish, screen, "タグ", ranked)
            target = pick.index?.let { index -> tags.firstOrNull { it.name == ranked[index].key } }
                ?: return Outcome(
                    "『$wish』に合うタグが見つかりませんでした。タグ名を教えてください。",
                    "🛠 $label · 対象を特定できず"
                )
            if (pick.probability < JevConciergePolicy.TARGET_MIN_PROB) {
                return Outcome(
                    "『$wish』に合うタグが見つかりませんでした。タグ名を教えてください。",
                    "🛠 $label · 対象を特定できず"
                )
            }
            targetConf = pick.probability
        }

        var bestText = ""
        var bestScore = -1.0
        for (i in 0 until DRAFT_TRIES) {
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
            "🛠 $label · Jev確信度${percent(targetConf)}",
            Plan.EditTag(target, bestText, targetConf, bestScore)
        )
    }

    private suspend fun newElement(
        activity: AppCompatActivity,
        wish: String,
        settings: JevConciergeTools.Settings,
        setStatus: (String?) -> Unit
    ): Outcome {
        val label = ConciergeTool.NEW_ELEMENT.label
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
                "🛠 $label · カテゴリー未判定"
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
            "🛠 $label · カテゴリー確信度${percentOrDash(cardCategory.probability)}・${percentOrDash(tagCategory.probability)}",
            Plan.NewElement(draft, cardCategory.probability, tagCategory.probability)
        )
    }

    private suspend fun applyPreset(
        activity: AppCompatActivity,
        host: ConciergeHost,
        wish: String,
        screen: String,
        settings: JevConciergeTools.Settings,
        presets: List<Preset>,
        forcedKey: String?
    ): Outcome {
        val label = ConciergeTool.APPLY_PRESET.label
        if (presets.isEmpty()) {
            return Outcome("プリセットがありません。生成画面で今の状態を保存してから使ってください。", null)
        }
        val preset: Preset
        val targetConf: Double
        if (forcedKey != null) {
            preset = presets.firstOrNull { it.id == forcedKey }
                ?: return Outcome("指定のプリセットが見つかりませんでした。一覧から選び直してください。", "🛠 $label · 対象なし")
            targetConf = 1.0
        } else {
            val ranked = JevConciergePolicy.rankCandidates(
                wish, presets.map {
                    ConciergeCandidate(it.id, it.name, "${it.category} :: カード${it.activePromptStates.size}枚")
                }
            )
            val pick = pickTarget(activity, settings, wish, screen, "プリセット", ranked)
            preset = pick.index?.let { index -> presets.firstOrNull { it.id == ranked[index].key } }
                ?: return Outcome("合うプリセットが見つかりませんでした。プリセット名を教えてください。", "🛠 $label · 対象を特定できず")
            if (pick.probability < JevConciergePolicy.TARGET_MIN_PROB) {
                return Outcome("合うプリセットが見つかりませんでした。プリセット名を教えてください。", "🛠 $label · 対象を特定できず")
            }
            targetConf = pick.probability
        }
        val prev = withContext(Dispatchers.Main) { host.builderSnapshot() }
        withContext(Dispatchers.Main) { host.applyPreset(preset) }
        val after = withContext(Dispatchers.Main) { host.selectionSnapshot() }
        val detail = "🛠 $label · Jev確信度${percent(targetConf)}"
        return Outcome(
            "プリセット『${preset.name}』を適用しました。履歴から元に戻せます。",
            detail,
            record = HistoryRecord(
                id = HistoryStore.newId(),
                time = System.currentTimeMillis(),
                tool = ConciergeTool.APPLY_PRESET,
                title = preset.name,
                detail = detail,
                status = HistoryStatus.APPLIED,
                reversible = true,
                rows = listOf(
                    HistoryRow("プリセット", "―", preset.name),
                    HistoryRow("選択カード", selSummary(prev.selection.keys), selSummary(after.keys))
                ),
                payload = HistoryPayload.preset(preset.id, prev)
            )
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
        val label = ConciergeTool.SELECT_CARDS.label
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
                "🛠 $label · 該当なし"
            )
        }
        val mode = JevConciergePolicy.selectionMode(wish)
        val prev = withContext(Dispatchers.Main) { host.selectionSnapshot() }
        val ids = picked.map { ranked[it].key }
        withContext(Dispatchers.Main) { host.selectCards(ids, mode) }
        val names = picked.map { ranked[it].name }
        val redo = mode == CardSelectionMode.REPLACE
        val afterKeys = if (redo) ids.toSet() else prev.keys + ids.toSet()
        val detail = "🛠 $label · Jev判定"
        return Outcome(
            "${names.size}枚を${if (redo) "選択し直しました" else "選択しました"}: ${names.joinToString("、")}。",
            detail,
            record = HistoryRecord(
                id = HistoryStore.newId(),
                time = System.currentTimeMillis(),
                tool = ConciergeTool.SELECT_CARDS,
                title = names.joinToString("、"),
                detail = detail,
                status = HistoryStatus.APPLIED,
                reversible = true,
                rows = listOf(HistoryRow("選択カード", selSummary(prev.keys), selSummary(afterKeys))),
                payload = HistoryPayload.select(ids, mode, prev)
            )
        )
    }

    private suspend fun filterImages(
        activity: AppCompatActivity,
        host: ConciergeHost,
        wish: String,
        screen: String,
        settings: JevConciergeTools.Settings,
        tags: List<JevGenieTagRef>,
        forcedKey: String?
    ): Outcome {
        val label = ConciergeTool.FILTER_IMAGES.label
        if (tags.isEmpty()) {
            return Outcome("タグがまだありません。タグ画面の＋ボタンから先に作ってください。", null)
        }
        val tag: String
        val targetConf: Double
        if (forcedKey != null) {
            tag = tags.firstOrNull { it.name == forcedKey }?.name
                ?: return Outcome("指定のタグが見つかりませんでした。一覧から選び直してください。", "🛠 $label · 対象なし")
            targetConf = 1.0
        } else {
            val ranked = JevConciergePolicy.rankCandidates(
                wish, tags.map { ConciergeCandidate(it.name, it.name, it.text) }
            )
            val pick = pickTarget(activity, settings, wish, screen, "タグ", ranked)
            tag = pick.index?.let { ranked[it].key }
                ?: return Outcome("『$wish』に合うタグが見つかりませんでした。タグ名を教えてください。", "🛠 $label · 対象を特定できず")
            if (pick.probability < JevConciergePolicy.TARGET_MIN_PROB) {
                return Outcome("『$wish』に合うタグが見つかりませんでした。タグ名を教えてください。", "🛠 $label · 対象を特定できず")
            }
            targetConf = pick.probability
        }
        val prev = withContext(Dispatchers.Main) { host.filterSnapshot() }
        withContext(Dispatchers.Main) { host.filterImages(tag) }
        val detail = "🛠 $label · Jev確信度${percent(targetConf)}"
        return Outcome(
            "タグ『$tag』で絞り込みました。",
            detail,
            record = HistoryRecord(
                id = HistoryStore.newId(),
                time = System.currentTimeMillis(),
                tool = ConciergeTool.FILTER_IMAGES,
                title = tag,
                detail = detail,
                status = HistoryStatus.APPLIED,
                reversible = true,
                rows = listOf(HistoryRow("絞り込み", prev.target ?: "すべて", tag)),
                payload = HistoryPayload.filter(tag, prev)
            ),
            dismissAfter = true
        )
    }

    private suspend fun startGeneration(activity: AppCompatActivity, host: ConciergeHost): Outcome {
        val label = ConciergeTool.START_GENERATION.label
        if (GenerationProgressManager.state.value.isGenerating) {
            return Outcome("生成中です。終わるまでお待ちください。", null)
        }
        if (!JevConciergeTools.generationReady()) {
            return Outcome("生成するカードが選ばれていません。『○○を選んで』と頼むか、生成画面でカードを選んでください。", null)
        }
        val count = withContext(Dispatchers.Main) {
            val selected = host.selectionSnapshot().size
            host.startGeneration()
            selected
        }
        val detail = "🛠 $label"
        return Outcome(
            "生成を始めました。",
            detail,
            record = HistoryRecord(
                id = HistoryStore.newId(),
                time = System.currentTimeMillis(),
                tool = ConciergeTool.START_GENERATION,
                title = "画像生成",
                detail = detail,
                status = HistoryStatus.APPLIED,
                reversible = false,
                rows = listOf(HistoryRow("生成", "―", "開始（選択${count}件）")),
                payload = HistoryPayload.empty()
            ),
            dismissAfter = true
        )
    }

    private suspend fun thumbnails(
        activity: AppCompatActivity,
        host: ConciergeHost,
        wish: String,
        screen: String,
        settings: JevConciergeTools.Settings,
        forced: Forced?,
        setStatus: (String?) -> Unit
    ): Outcome {
        val label = ConciergeTool.THUMBNAILS.label
        val forcedKind = forced?.thumbKind
        val kind = if (forcedKind != null) {
            forcedKind
        } else {
            setStatus("種類を判断中…")
            JevConciergePolicy.parseKind(
                JevConciergeTools.decide(
                    activity, settings.endpoint,
                    JevConciergePolicy.thumbKindBody(wish, settings.jev)
                )
            )
        }
        val categories = (if (kind == ThumbKind.CARD) PromptCardManager.categoryOrder else PresetManager.categoryOrder).toList()
        if (categories.isEmpty()) {
            return Outcome("カテゴリーがありません。先にカードやプリセットを作ってください。", null)
        }
        val forcedCategory = forced?.thumbCategory?.takeIf { it in categories }
        val category: String
        if (forcedCategory != null) {
            category = forcedCategory
        } else {
            setStatus("カテゴリーを判断中…")
            val ranked = JevConciergePolicy.rankCandidates(
                wish, categories.map { ConciergeCandidate(it, it, "") }
            )
            val pick = pickTarget(activity, settings, wish, screen, "カテゴリー", ranked)
            val found = pick.index?.let { ranked[it].key }
            if (found == null || pick.probability < JevConciergePolicy.TARGET_MIN_PROB) {
                return Outcome(
                    "どのカテゴリーか分かりませんでした。『○○のサムネイルを作って』のように教えてください。",
                    "🛠 $label · 対象を特定できず"
                )
            }
            category = found
        }
        withContext(Dispatchers.Main) {
            host.openBulkThumbnails(kind, category) { count ->
                if (count > 0) {
                    val detail = "🛠 $label"
                    HistoryStore.add(
                        activity,
                        HistoryRecord(
                            id = HistoryStore.newId(),
                            time = System.currentTimeMillis(),
                            tool = ConciergeTool.THUMBNAILS,
                            title = "${kind.label} / $category",
                            detail = detail,
                            status = HistoryStatus.APPLIED,
                            reversible = false,
                            rows = listOf(HistoryRow("サムネイル生成", "―", "${count}件を開始")),
                            payload = HistoryPayload.empty()
                        )
                    )
                }
            }
        }
        return Outcome(
            "『$category』のサムネイル対象選びを開きました。作りたいものを選んで開始してください。",
            "🛠 $label",
            dismissAfter = true
        )
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

    /** 選択中カードの要約。履歴のビフォーアフター用 */
    private fun selSummary(ids: Collection<String>): String {
        if (ids.isEmpty()) {
            return "選択なし"
        }
        val names = ids.mapNotNull { id ->
            PromptCardManager.promptCards.firstOrNull { it.id == id }?.label
        }
        if (names.isEmpty()) {
            return "${ids.size}件"
        }
        val joined = names.joinToString("、")
        return if (joined.length <= SUMMARY_CLIP) joined else joined.take(SUMMARY_CLIP) + "…"
    }

    // ---------- 提案の履歴化 ----------

    private fun pendingRecord(activity: Context, plan: Plan, detail: String?): HistoryRecord {
        val tool = when (plan) {
            is Plan.EditCard -> ConciergeTool.EDIT_CARD
            is Plan.EditTag -> ConciergeTool.EDIT_TAG
            is Plan.NewElement -> ConciergeTool.NEW_ELEMENT
        }
        val payload = when (plan) {
            is Plan.EditCard -> HistoryPayload.card(plan.target, plan.newMain, plan.newNegative)
            is Plan.EditTag -> HistoryPayload.tag(plan.target, plan.newText)
            is Plan.NewElement -> HistoryPayload.element(plan.draft)
        }
        return HistoryRecord(
            id = HistoryStore.newId(),
            time = System.currentTimeMillis(),
            tool = tool,
            title = planTitle(activity, plan),
            detail = detail.orEmpty(),
            status = HistoryStatus.PENDING,
            reversible = true,
            rows = planRows(plan),
            payload = payload
        )
    }

    private fun planTitle(activity: Context, plan: Plan): String = when (plan) {
        is Plan.EditCard -> "${plan.target.category} / ${plan.target.label}"
        is Plan.EditTag -> activity.getString(R.string.genie_diff_tag, plan.target.name)
        is Plan.NewElement -> "新規要素『${plan.draft.name}』"
    }

    private fun planRows(plan: Plan): List<HistoryRow> = when (plan) {
        is Plan.EditCard -> buildList {
            add(HistoryRow(JevGeniePolicy.FIELD_MAIN, plan.target.mainPrompt.ifEmpty { EMPTY_MARK }, plan.newMain))
            if (plan.target.negativePrompt != plan.newNegative) {
                add(
                    HistoryRow(
                        JevGeniePolicy.FIELD_NEGATIVE,
                        plan.target.negativePrompt.ifEmpty { EMPTY_MARK }, plan.newNegative
                    )
                )
            }
        }
        is Plan.EditTag -> listOf(
            HistoryRow(JevGeniePolicy.FIELD_TEXT, plan.target.text.ifEmpty { EMPTY_MARK }, plan.newText)
        )
        is Plan.NewElement -> listOf(
            HistoryRow("カードのカテゴリー", NEW_MARK, withConf(plan.draft.cardCategory, plan.cardConf)),
            HistoryRow("タグのカテゴリー", NEW_MARK, withConf(plan.draft.tagCategory, plan.tagConf)),
            HistoryRow(JevGeniePolicy.FIELD_MAIN, NEW_MARK, plan.draft.text.main),
            HistoryRow(
                JevGeniePolicy.FIELD_NEGATIVE, NEW_MARK,
                plan.draft.text.negative.ifEmpty { EMPTY_MARK }
            ),
            HistoryRow("chat_instruction", NEW_MARK, plan.draft.text.chat)
        )
    }

    // ---------- 変更前後の比較ポップアップ ----------

    private fun openDiff(
        activity: AppCompatActivity,
        host: ConciergeHost,
        msg: Msg.Assistant,
        onChanged: () -> Unit
    ) {
        val plan = msg.plan ?: return
        val recordId = msg.recordId ?: return
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_concierge_diff)
        view.findViewById<TextView>(R.id.tv_concierge_diff_target).text = planTitle(activity, plan)
        val rows = view.findViewById<LinearLayout>(R.id.ll_concierge_diff_rows)
        planRows(plan).forEach { addRow(rows, it.label, it.before, it.after) }
        val warning = view.findViewById<TextView>(R.id.tv_concierge_diff_warning)
        when (plan) {
            is Plan.EditCard -> showWarning(warning, plan.verify)
            is Plan.EditTag -> showWarning(warning, plan.verify)
            is Plan.NewElement -> warning.visibility = View.GONE
        }

        val dialog = Md3PopupDialog.show(activity, view)
        view.findViewById<MaterialButton>(R.id.btn_concierge_diff_cancel).setOnClickListener {
            Toast.makeText(activity, R.string.concierge_cancelled, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_concierge_diff_save).setOnClickListener {
            val record = HistoryStore.get(activity, recordId)
            val saved = record != null && HistoryExecute.apply(activity, host, record)
            if (saved) {
                HistoryStore.update(activity, record!!.copy(status = HistoryStatus.APPLIED))
                msg.applied = true
                Toast.makeText(activity, R.string.concierge_saved, Toast.LENGTH_SHORT).show()
                onChanged()
            } else {
                Toast.makeText(activity, R.string.concierge_apply_failed, Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
    }

    // ---------- 履歴 ----------

    private fun openHistory(activity: AppCompatActivity, host: ConciergeHost, onChanged: () -> Unit) {
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_concierge_history)
        val list = view.findViewById<RecyclerView>(R.id.rv_concierge_history)
        val empty = view.findViewById<TextView>(R.id.tv_history_empty)
        list.layoutManager = LinearLayoutManager(activity)
        lateinit var adapter: HistoryAdapter
        fun render() {
            val records = HistoryStore.load(activity)
            adapter.submit(records)
            empty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            list.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        }
        adapter = HistoryAdapter(
            mutableListOf(),
            onView = { record -> openRecordDiff(activity, host, record.id) { render(); onChanged() } },
            onToggle = { record ->
                if (toggleRecord(activity, host, record)) {
                    render()
                    onChanged()
                }
            }
        )
        list.adapter = adapter
        render()
        val dialog = Md3PopupDialog.show(activity, view)
        view.findViewById<MaterialButton>(R.id.btn_history_close).setOnClickListener { dialog.dismiss() }
    }

    /** 履歴1件の適用・取消・やり直し。状態が変わったらtrue */
    private fun toggleRecord(activity: AppCompatActivity, host: ConciergeHost, record: HistoryRecord): Boolean {
        val current = HistoryStore.get(activity, record.id)
        if (current == null) {
            Toast.makeText(activity, R.string.concierge_history_missing, Toast.LENGTH_SHORT).show()
            return false
        }
        val (next, done) = when (current.status) {
            HistoryStatus.PENDING -> HistoryStatus.APPLIED to HistoryExecute.apply(activity, host, current)
            HistoryStatus.APPLIED -> {
                if (!current.reversible) {
                    return false
                }
                HistoryStatus.REVERTED to HistoryExecute.revert(activity, host, current)
            }
            HistoryStatus.REVERTED -> HistoryStatus.APPLIED to HistoryExecute.apply(activity, host, current)
        }
        if (!done) {
            Toast.makeText(activity, R.string.concierge_history_failed, Toast.LENGTH_SHORT).show()
            return false
        }
        HistoryStore.update(activity, current.copy(status = next))
        Toast.makeText(
            activity,
            if (next == HistoryStatus.REVERTED) R.string.concierge_history_reverted_msg else R.string.concierge_history_applied_msg,
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    /** 履歴からのビフォーアフター表示。適用・取消もここで行える */
    private fun openRecordDiff(
        activity: AppCompatActivity,
        host: ConciergeHost,
        recordId: String,
        onChanged: () -> Unit
    ) {
        val record = HistoryStore.get(activity, recordId)
        if (record == null) {
            Toast.makeText(activity, R.string.concierge_history_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_concierge_diff)
        view.findViewById<TextView>(R.id.tv_concierge_diff_target).text = record.title
        view.findViewById<TextView>(R.id.tv_concierge_diff_warning).visibility = View.GONE
        val rows = view.findViewById<LinearLayout>(R.id.ll_concierge_diff_rows)
        record.rows.forEach { addRow(rows, it.label, it.before, it.after) }

        val dialog = Md3PopupDialog.show(activity, view)
        view.findViewById<MaterialButton>(R.id.btn_concierge_diff_cancel).apply {
            setText(R.string.concierge_history_close)
            setOnClickListener { dialog.dismiss() }
        }
        val save = view.findViewById<MaterialButton>(R.id.btn_concierge_diff_save)
        val label = toggleLabel(activity, record)
        if (label == null) {
            save.visibility = View.GONE
        } else {
            save.text = label
            save.setOnClickListener {
                if (toggleRecord(activity, host, record)) {
                    onChanged()
                }
                dialog.dismiss()
            }
        }
    }

    /** 履歴の操作ボタン名。操作なしはnull */
    private fun toggleLabel(activity: Context, record: HistoryRecord): String? = when (record.status) {
        HistoryStatus.PENDING -> activity.getString(R.string.concierge_history_apply)
        HistoryStatus.APPLIED -> {
            if (record.reversible) activity.getString(R.string.concierge_history_revert) else null
        }
        HistoryStatus.REVERTED -> activity.getString(R.string.concierge_history_redo)
    }

    private fun statusText(activity: Context, record: HistoryRecord): String = when (record.status) {
        HistoryStatus.PENDING -> activity.getString(R.string.concierge_history_pending)
        HistoryStatus.APPLIED -> activity.getString(
            if (record.reversible) R.string.concierge_history_applied else R.string.concierge_history_done
        )
        HistoryStatus.REVERTED -> activity.getString(R.string.concierge_history_reverted)
    }

    private fun timeText(time: Long): String =
        SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(Date(time))

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

    // ---------- 定型フォーム ----------

    private fun openForm(activity: AppCompatActivity, onSubmit: (String, Forced, String) -> Unit) {
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_concierge_form)
        val toolGroup = view.findViewById<ChipGroup>(R.id.cg_form_tool)
        val kindLabel = view.findViewById<TextView>(R.id.tv_form_kind_label)
        val kindGroup = view.findViewById<ChipGroup>(R.id.cg_form_kind)
        val kindCard = view.findViewById<Chip>(R.id.chip_form_kind_card)
        val targetLabel = view.findViewById<TextView>(R.id.tv_form_target_label)
        val targetDrop = view.findViewById<AutoCompleteTextView>(R.id.dd_form_target)
        val wishLabel = view.findViewById<TextView>(R.id.tv_form_wish_label)
        val wishInput = view.findViewById<EditText>(R.id.et_form_wish)

        val tools = ConciergeTool.entries.filter { it != ConciergeTool.TALK }
        tools.forEach { tool ->
            val chip = Chip(view.context).apply {
                id = View.generateViewId()
                text = tool.label
                tag = tool
                isCheckable = true
            }
            toolGroup.addView(chip)
            if (tool == ConciergeTool.EDIT_CARD) {
                chip.isChecked = true
            }
        }

        var options = listOf<Pair<String, String>>()

        fun selectedTool(): ConciergeTool {
            val checked = toolGroup.checkedChipIds.firstOrNull()
            val tag = if (checked == null) null else toolGroup.findViewById<Chip>(checked)?.tag
            return tools.firstOrNull { it == tag } ?: ConciergeTool.EDIT_CARD
        }

        fun selectedKind(): ThumbKind =
            if (kindGroup.checkedChipId == kindCard.id) ThumbKind.CARD else ThumbKind.PRESET

        fun refreshTargets() {
            val tool = selectedTool()
            options = formOptions(tool, selectedKind())
            targetDrop.setAdapter(
                ArrayAdapter(view.context, android.R.layout.simple_list_item_1, options.map { it.first })
            )
            targetDrop.setText("")
            val showKind = tool == ConciergeTool.THUMBNAILS
            kindLabel.visibility = if (showKind) View.VISIBLE else View.GONE
            kindGroup.visibility = if (showKind) View.VISIBLE else View.GONE
            val showTarget = tool == ConciergeTool.EDIT_CARD || tool == ConciergeTool.EDIT_TAG ||
                tool == ConciergeTool.APPLY_PRESET || tool == ConciergeTool.FILTER_IMAGES ||
                tool == ConciergeTool.THUMBNAILS
            targetLabel.visibility = if (showTarget) View.VISIBLE else View.GONE
            targetDrop.visibility = if (showTarget) View.VISIBLE else View.GONE
            val showWish = tool != ConciergeTool.START_GENERATION
            wishLabel.visibility = if (showWish) View.VISIBLE else View.GONE
            wishInput.visibility = if (showWish) View.VISIBLE else View.GONE
        }

        toolGroup.setOnCheckedStateChangeListener { _, _ -> refreshTargets() }
        kindGroup.setOnCheckedStateChangeListener { _, _ -> refreshTargets() }
        targetDrop.threshold = 0
        targetDrop.setOnClickListener { targetDrop.showDropDown() }
        refreshTargets()

        val dialog = Md3PopupDialog.show(activity, view)
        view.findViewById<MaterialButton>(R.id.btn_form_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btn_form_submit).setOnClickListener {
            val tool = selectedTool()
            val typed = targetDrop.text.toString().trim()
            val key = options.firstOrNull { it.first == typed }?.second
            val wish = wishInput.text.toString().trim()
            if (formNeedWish(tool) && wish.isEmpty()) {
                Toast.makeText(activity, R.string.concierge_form_need_wish, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val effectiveWish = wish.ifEmpty { typed.ifEmpty { "（フォーム指定）" } }
            val forced = if (tool == ConciergeTool.THUMBNAILS) {
                Forced(tool, null, selectedKind(), key)
            } else {
                Forced(tool, key, null, null)
            }
            dialog.dismiss()
            onSubmit(effectiveWish, forced, "【${tool.label}】$effectiveWish")
        }
    }

    /** フォームの対象候補。(表示名, 識別子) */
    private fun formOptions(tool: ConciergeTool, kind: ThumbKind): List<Pair<String, String>> = when (tool) {
        ConciergeTool.EDIT_CARD -> JevGenieTools.cardRefs().map { "${it.label}（${it.category}）" to it.id }
        ConciergeTool.EDIT_TAG -> JevGenieTools.tagRefs().map { it.name to it.name }
        ConciergeTool.APPLY_PRESET -> PresetManager.presets.map { "${it.name}（${it.category}）" to it.id }
        ConciergeTool.FILTER_IMAGES -> JevGenieTools.tagRefs().map { it.name to it.name }
        ConciergeTool.THUMBNAILS -> {
            val categories = if (kind == ThumbKind.CARD) PromptCardManager.categoryOrder else PresetManager.categoryOrder
            categories.map { it to it }
        }
        ConciergeTool.NEW_ELEMENT, ConciergeTool.SELECT_CARDS,
        ConciergeTool.START_GENERATION, ConciergeTool.TALK -> emptyList()
    }

    private fun formNeedWish(tool: ConciergeTool): Boolean = when (tool) {
        ConciergeTool.EDIT_CARD, ConciergeTool.EDIT_TAG,
        ConciergeTool.NEW_ELEMENT, ConciergeTool.SELECT_CARDS -> true
        ConciergeTool.APPLY_PRESET, ConciergeTool.FILTER_IMAGES,
        ConciergeTool.START_GENERATION, ConciergeTool.THUMBNAILS, ConciergeTool.TALK -> false
    }

    /** チャット1行分を描くアダプタ。提案には確認ボタンを添える */
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
                    val showAction = (msg.plan != null && !msg.applied) || msg.recordId != null
                    holder.action.visibility = if (showAction) View.VISIBLE else View.GONE
                    if (msg.recordId != null) {
                        holder.action.setText(R.string.concierge_history_view)
                    } else {
                        holder.action.setText(R.string.concierge_confirm_view)
                    }
                    holder.action.setOnClickListener { onShowPlan(msg) }
                }
            }
        }

        override fun getItemCount() = msgs.size
    }

    /** 履歴一覧のアダプタ */
    private class HistoryAdapter(
        private val records: MutableList<HistoryRecord>,
        private val onView: (HistoryRecord) -> Unit,
        private val onToggle: (HistoryRecord) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tv_history_title)
            val meta: TextView = view.findViewById(R.id.tv_history_meta)
            val viewButton: MaterialButton = view.findViewById(R.id.btn_history_view)
            val toggle: MaterialButton = view.findViewById(R.id.btn_history_toggle)
        }

        fun submit(list: List<HistoryRecord>) {
            records.clear()
            records.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_concierge_history, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val record = records[position]
            holder.title.text = "${record.tool.label} · ${record.title}"
            holder.meta.text = "${timeText(record.time)} · ${statusText(holder.itemView.context, record)}"
            holder.viewButton.setOnClickListener { onView(record) }
            val label = toggleLabel(holder.itemView.context, record)
            if (label == null) {
                holder.toggle.visibility = View.GONE
            } else {
                holder.toggle.visibility = View.VISIBLE
                holder.toggle.text = label
                holder.toggle.setOnClickListener { onToggle(record) }
            }
        }

        override fun getItemCount() = records.size
    }
}
