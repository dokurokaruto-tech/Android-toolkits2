package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
 * アプリ内アシスタント『ジーニー』のMD3ポップアップ。
 * チャット形式で願いを受け取り、編集計画は変更前後の比較ポップアップで見せてから保存する。
 */
internal object JevGenieDialog {
    /** チャット1行分。planは編集提案、appliedは保存済みか。サブクラスの実効可視性はMsgに従う。 */
    private sealed class Msg {
        class User(val text: String) : Msg()

        class Assistant(val text: String, val plan: JevGeniePlan? = null) : Msg() {
            var applied = false
        }
    }

    /** 履歴は直近8エントリ(往復4回分)だけLLMに送る */
    private const val HISTORY_LIMIT = 8

    fun show(activity: AppCompatActivity) {
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_jev_genie)
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val list = view.findViewById<RecyclerView>(R.id.rv_genie)
        val input = view.findViewById<EditText>(R.id.et_genie_input)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.progress_genie)
        val send = view.findViewById<MaterialButton>(R.id.btn_genie_send)
        val modelChip = view.findViewById<MaterialButton>(R.id.btn_genie_model)

        var model = prefs.getString(JevGeniePolicy.MODEL_KEY, null) ?: JevGeniePolicy.DEFAULT_MODEL
        val msgs = mutableListOf<Msg>()
        val turns = mutableListOf<Pair<String, String>>()
        var busy = false

        list.layoutManager = LinearLayoutManager(activity)
        val adapter = GenieAdapter(msgs) { msg ->
            openDiff(activity, msg) { list.adapter?.notifyDataSetChanged() }
        }
        list.adapter = adapter
        val dialog = Md3PopupDialog.show(activity, view)

        fun updateModelChip() {
            modelChip.text = model
        }
        updateModelChip()
        modelChip.setOnClickListener {
            JevModelPicker.show(activity, activity.getString(R.string.genie_model_select), model) { id ->
                model = id
                prefs.edit().putString(JevGeniePolicy.MODEL_KEY, id).apply()
                updateModelChip()
            }
        }

        fun rememberTurn(role: String, text: String) {
            turns += role to text
            while (turns.size > HISTORY_LIMIT) {
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
            progress.show()
            send.isEnabled = false
            msgs += Msg.User(wish)
            adapter.notifyDataSetChanged()

            val cards = JevGenieTools.cardRefs()
            val tags = JevGenieTools.tagRefs()
            val body = JevGeniePolicy.requestBody(
                model, JevGeniePolicy.systemPrompt(cards, tags), turns, wish)
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val outcome = runCatching {
                    JevGeniePolicy.parse(JevGenieTools.request(activity, body), cards, tags)
                }
                withContext(Dispatchers.Main) {
                    busy = false
                    progress.hide()
                    send.isEnabled = true
                    if (!dialog.isShowing) {
                        return@withContext
                    }
                    outcome.onSuccess { plan ->
                        rememberTurn("user", wish)
                        rememberTurn("assistant", plan.reply)
                        val assistantMsg = Msg.Assistant(plan.reply, plan)
                        msgs += assistantMsg
                        adapter.notifyDataSetChanged()
                        // 編集案は即座に変更前後の比較ポップアップへ
                        if (plan !is JevGeniePlan.Talk) {
                            openDiff(activity, assistantMsg) { list.adapter?.notifyDataSetChanged() }
                        }
                    }.onFailure { error ->
                        msgs += Msg.Assistant(
                            activity.getString(R.string.genie_failed, error.message ?: ""))
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }

        send.setOnClickListener { send() }
    }

    /** 変更前後を見比べる確認ポップアップ。保存でツール層を通じて既存データへ適用する。 */
    private fun openDiff(activity: AppCompatActivity, msg: Msg.Assistant, onApplied: () -> Unit) {
        val plan = msg.plan ?: return
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_genie_diff)
        view.findViewById<TextView>(R.id.tv_diff_target).text = when (plan) {
            is JevGeniePlan.EditCard -> "${plan.target.category} / ${plan.target.label}"
            is JevGeniePlan.EditTag -> activity.getString(R.string.genie_diff_tag, plan.target.name)
            is JevGeniePlan.Talk -> return
        }
        val mainLabel = view.findViewById<TextView>(R.id.tv_diff_main_label)
        val before = view.findViewById<TextView>(R.id.tv_diff_before)
        val after = view.findViewById<TextView>(R.id.tv_diff_after)
        val negativeRow = view.findViewById<View>(R.id.row_negative)
        when (plan) {
            is JevGeniePlan.EditCard -> {
                mainLabel.text = JevGeniePolicy.FIELD_MAIN
                before.text = plan.target.mainPrompt.ifEmpty { "（空）" }
                after.text = plan.newMain
                val negativeChanged = plan.target.negativePrompt != plan.newNegative
                negativeRow.visibility = if (negativeChanged) View.VISIBLE else View.GONE
                if (negativeChanged) {
                    view.findViewById<TextView>(R.id.tv_diff_negative_label).text =
                        JevGeniePolicy.FIELD_NEGATIVE
                    view.findViewById<TextView>(R.id.tv_diff_negative_before).text =
                        plan.target.negativePrompt.ifEmpty { "（空）" }
                    view.findViewById<TextView>(R.id.tv_diff_negative_after).text = plan.newNegative
                }
            }
            is JevGeniePlan.EditTag -> {
                mainLabel.text = JevGeniePolicy.FIELD_TEXT
                before.text = plan.target.text.ifEmpty { "（空）" }
                after.text = plan.newText
            }
            is JevGeniePlan.Talk -> return
        }

        val dialog = Md3PopupDialog.show(activity, view)
        view.findViewById<MaterialButton>(R.id.btn_diff_cancel).setOnClickListener {
            Toast.makeText(activity, R.string.genie_cancelled, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_diff_save).setOnClickListener {
            val saved = when (plan) {
                is JevGeniePlan.EditCard -> JevGenieTools.applyCard(activity, plan)
                is JevGeniePlan.EditTag -> JevGenieTools.applyTag(activity, plan)
                is JevGeniePlan.Talk -> false
            }
            if (saved) {
                msg.applied = true
                Toast.makeText(activity, R.string.genie_saved, Toast.LENGTH_SHORT).show()
                onApplied()
            } else {
                Toast.makeText(activity, R.string.genie_apply_failed, Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
    }

    /** ジーニーのチャット1行分を描くアダプタ。編集提案には確認ボタンを添える。 */
    private class GenieAdapter(
        private val msgs: List<Msg>,
        private val onShowPlan: (Msg.Assistant) -> Unit
    ) : RecyclerView.Adapter<GenieAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val rowUser: View = view.findViewById(R.id.row_user)
            val textUser: TextView = view.findViewById(R.id.tv_genie_user)
            val rowAssistant: View = view.findViewById(R.id.row_assistant)
            val textAssistant: TextView = view.findViewById(R.id.tv_genie_assistant)
            val applied: TextView = view.findViewById(R.id.tv_genie_applied)
            val confirm: MaterialButton = view.findViewById(R.id.btn_genie_confirm)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_genie_bubble, parent, false)
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
                    holder.applied.visibility = if (msg.applied) View.VISIBLE else View.GONE
                    holder.confirm.visibility =
                        if (msg.plan != null && !msg.applied) View.VISIBLE else View.GONE
                    holder.confirm.setOnClickListener { onShowPlan(msg) }
                }
            }
        }

        override fun getItemCount() = msgs.size
    }
}
