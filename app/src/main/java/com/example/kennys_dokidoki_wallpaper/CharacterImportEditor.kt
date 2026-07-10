package com.example.kennys_dokidoki_wallpaper

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * インポートするキャラクターのメタデータを編集・日本語翻訳するためのダイアログUIよ♡
 */
object CharacterImportEditor {

    fun showImportEditorDialog(
        context: Context,
        charData: CharacterData,
        onSave: (CharacterData) -> Unit
    ) {
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#121214"))
        }

        // スクロールコンテナ
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 説明文
        val descText = TextView(context).apply {
            text = "インポートするキャラクターの情報を確認・編集できるわよ♪\nChubのキャラは英語だから、下のAI翻訳ボタンを押すと、自動的に魅力的な日本語に翻訳・リライトしてくれるわよ！"
            setTextColor(Color.parseColor("#E8EAED"))
            textSize = 14f
            setPadding(0, 0, 0, 24)
        }
        container.addView(descText)

        // フィールドたち
        val etName = createField(context, container, "キャラクター名", charData.name, false)
        val etFirstMes = createField(context, container, "最初のメッセージ (Greeting)", charData.firstMes, true)
        val etDesc = createField(context, container, "説明・プロフィール (Description)", charData.description, true)
        val etPersonality = createField(context, container, "性格設定 (Personality)", charData.personality, true)
        val etScenario = createField(context, container, "シチュエーション (Scenario)", charData.scenario, true)
        val etSystemPrompt = createField(context, container, "システムプロンプト (System Prompt)", charData.systemPrompt, true)

        // 翻訳ボタン (グラデーション調)
        val translateButton = Button(context).apply {
            text = "💡 AIに日本語翻訳を頼む"
            setTextColor(Color.WHITE)
            textSize = 15f
            val grad = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#C58AF9"))
            ).apply {
                cornerRadius = 24f
            }
            background = grad
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 32)
            }
        }

        // ボタンはコンテナの最上部に配置
        container.addView(translateButton, 1)

        scrollView.addView(container)
        rootLayout.addView(scrollView)

        val dialog = AlertDialog.Builder(context, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("🎭 キャラクター定義の編集・翻訳")
            .setView(rootLayout)
            .setCancelable(false)
            .setPositiveButton("この内容で保存") { _, _ ->
                val edited = CharacterData(
                    name = etName.text.toString().trim(),
                    description = etDesc.text.toString().trim(),
                    personality = etPersonality.text.toString().trim(),
                    firstMes = etFirstMes.text.toString().trim(),
                    scenario = etScenario.text.toString().trim(),
                    systemPrompt = etSystemPrompt.text.toString().trim()
                )
                onSave(edited)
            }
            .setNegativeButton("キャンセル", null)
            .create()

        translateButton.setOnClickListener {
            val currentData = CharacterData(
                name = etName.text.toString().trim(),
                description = etDesc.text.toString().trim(),
                personality = etPersonality.text.toString().trim(),
                firstMes = etFirstMes.text.toString().trim(),
                scenario = etScenario.text.toString().trim(),
                systemPrompt = etSystemPrompt.text.toString().trim()
            )

            // 翻訳ローディング
            val loadingLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(50, 50, 50, 50)
                setBackgroundColor(Color.parseColor("#1E1E24"))
            }
            val pb = ProgressBar(context).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#C58AF9"))
            }
            val loadingText = TextView(context).apply {
                text = "キャラクターを魅力的な日本語に翻訳中...\n（これには10秒〜20秒ほどかかるわよ）"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 0)
            }
            loadingLayout.addView(pb)
            loadingLayout.addView(loadingText)

            val loadingDialog = AlertDialog.Builder(context)
                .setView(loadingLayout)
                .setCancelable(false)
                .create()
            loadingDialog.show()

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val translated = LlmTranslationHelper.translateCharacter(context, currentData)
                    etName.setText(translated.name)
                    etFirstMes.setText(translated.firstMes)
                    etDesc.setText(translated.description)
                    etPersonality.setText(translated.personality)
                    etScenario.setText(translated.scenario)
                    etSystemPrompt.setText(translated.systemPrompt)
                    Toast.makeText(context, "日本語にリライトできたわよ！確認してみてね♡", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "翻訳中にエラーが発生したわ: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    loadingDialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun createField(
        context: Context,
        container: LinearLayout,
        labelText: String,
        initialValue: String,
        multiLine: Boolean
    ): EditText {
        val ll = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }

        val label = TextView(context).apply {
            text = labelText
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        ll.addView(label)

        val et = EditText(context).apply {
            setText(initialValue)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(16, 16, 16, 16)
            
            // 背景
            val gd = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 12f
                setStroke(2, Color.parseColor("#334155"))
            }
            background = gd

            if (multiLine) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                maxLines = 8
                gravity = Gravity.TOP or Gravity.START
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
                maxLines = 1
            }
        }
        ll.addView(et)
        container.addView(ll)
        return et
    }
}
