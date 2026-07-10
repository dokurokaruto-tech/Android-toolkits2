package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class TapSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tap_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "タップ操作のカスタム設定"
        toolbar.setNavigationOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.settings_container)

        // 2〜6タップの設定行を動的に生成
        for (tapCount in 2..6) {
            val defaultAction = if (tapCount == 2) "NEXT_IMAGE" else if (tapCount == 3) "NEXT_SET" else "NONE"
            container.addView(createTapSettingRow("action_tap_$tapCount", "画面を${tapCount}回タップしたとき", defaultAction))
            container.addView(createDivider())
        }

        // ダブルタップ＋長押しの設定行を追加
        container.addView(createTapSettingRow("action_tap_2_hold", "ダブルタップして長押ししたとき", "NONE"))
        container.addView(createDivider())

        // トリプルタップ＋長押しの設定行を追加
        container.addView(createTapSettingRow("action_tap_3_hold", "トリプルタップして長押ししたとき", "NONE"))
        container.addView(createDivider())

        // 1秒長押しの設定行を追加
        container.addView(createTapSettingRow("action_hold_2s", "画面を1秒間長押ししたとき", "NONE"))
        container.addView(createDivider())
    }

    private fun createTapSettingRow(prefKey: String, title: String, defaultAction: String): View {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 24, 16, 24)
            isClickable = true
            
            // リップルエフェクト
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        
        val summaryView = TextView(this).apply {
            val currentAction = prefs.getString(prefKey, defaultAction) ?: defaultAction
            text = getActionDisplayName(currentAction)
            textSize = 14f
            setTextColor(Color.parseColor("#8AB4F8"))
            setPadding(0, 8, 0, 0)
        }

        container.addView(titleView)
        container.addView(summaryView)

        container.setOnClickListener {
            showActionSelectionDialog(prefKey, title, summaryView)
        }
        return container
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                setMargins(0, 16, 0, 16)
            }
            setBackgroundColor(Color.parseColor("#1E1F20"))
        }
    }

    private fun getActionDisplayName(action: String): String {
        return when {
            action == "NEXT_IMAGE" -> "次の壁紙に切り替え"
            action == "NEXT_SET" -> "次のイメージセットに切り替え"
            action == "TOGGLE_AI_CHAT" -> "AIキャラチャットの表示/非表示"
            action == "OPEN_APP" -> "このアプリを開く"
            action == "CROP_IMAGE" -> "現在表示している画像のクロップ"
            action == "EDIT_TAGS" -> "現在の壁紙のタグを編集"
            action == "EDIT_ACTIVE_SET" -> "現在のイメージセットを編集"
            action.startsWith("SPECIFIC_SET:") -> {
                val setName = action.substringAfter("SPECIFIC_SET:")
                "「$setName」に切り替え"
            }
            else -> "なにもしない"
        }
    }

    private fun showActionSelectionDialog(prefKey: String, title: String, summaryView: TextView) {
        val options = arrayOf(
            "なにもしない", 
            "次の壁紙に切り替え", 
            "次のイメージセットに切り替え", 
            "特定のイメージセットに切り替え",
            "AIキャラチャットの表示/非表示",
            "このアプリを開く",
            "現在表示している画像のクロップ",
            "現在の壁紙のタグを編集",
            "現在のイメージセットを編集"
        )
        
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper) // デフォルトテーマ適用
            .setTitle(title + "の動作")
            .setItems(options) { _, which ->
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                when (which) {
                    0 -> {
                        prefs.edit().putString(prefKey, "NONE").apply()
                        summaryView.text = getActionDisplayName("NONE")
                    }
                    1 -> {
                        prefs.edit().putString(prefKey, "NEXT_IMAGE").apply()
                        summaryView.text = getActionDisplayName("NEXT_IMAGE")
                    }
                    2 -> {
                        prefs.edit().putString(prefKey, "NEXT_SET").apply()
                        summaryView.text = getActionDisplayName("NEXT_SET")
                    }
                    3 -> {
                        val sets = DataManager.imageSetList.map { it.name }.toTypedArray()
                        if (sets.isEmpty()) {
                            Toast.makeText(this, "イメージセットが存在しません。", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        AlertDialog.Builder(this)
                            .setTitle("どのセットにする？")
                            .setItems(sets) { _, setIndex ->
                                val selectedSet = sets[setIndex]
                                val actionString = "SPECIFIC_SET:$selectedSet"
                                prefs.edit().putString(prefKey, actionString).apply()
                                summaryView.text = getActionDisplayName(actionString)
                            }
                            .show()
                    }
                    4 -> {
                        prefs.edit().putString(prefKey, "TOGGLE_AI_CHAT").apply()
                        summaryView.text = getActionDisplayName("TOGGLE_AI_CHAT")
                    }
                    5 -> {
                        prefs.edit().putString(prefKey, "OPEN_APP").apply()
                        summaryView.text = getActionDisplayName("OPEN_APP")
                    }
                    6 -> {
                        prefs.edit().putString(prefKey, "CROP_IMAGE").apply()
                        summaryView.text = getActionDisplayName("CROP_IMAGE")
                    }
                    7 -> {
                        prefs.edit().putString(prefKey, "EDIT_TAGS").apply()
                        summaryView.text = getActionDisplayName("EDIT_TAGS")
                    }
                    8 -> {
                        prefs.edit().putString(prefKey, "EDIT_ACTIVE_SET").apply()
                        summaryView.text = getActionDisplayName("EDIT_ACTIVE_SET")
                    }
                }
            }
            .show()
    }
}
