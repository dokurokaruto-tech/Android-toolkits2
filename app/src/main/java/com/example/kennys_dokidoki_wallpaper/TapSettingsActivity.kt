package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.divider.MaterialDivider

/**
 * タップ操作設定。MD3 の Top app bar + two-line list item。
 *
 * 旧実装の問題:
 *   - 行を Kotlin で生成し setPadding(16, 24, 16, 24) (px) / Color.WHITE / "#1A2235" (旧ネイビーテーマの残骸) を直書き
 *   - AlertDialog.Builder に "アプリ全体のテーマ" を渡していた
 *   - 動作名の表示文字列がここと MyWallpaperService とで別々に保持されていた
 * Pref キーは PrefKeys / TapActions 経由で壁紙エンジンと共有する。
 */
class TapSettingsActivity : AppCompatActivity() {

    private data class Row(val prefKey: String, val title: String, val defaultAction: String)

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tap_settings)
        prefs = getSharedPreferences(PrefFiles.SETTINGS, Context.MODE_PRIVATE)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.settings_container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom)
            insets
        }

        val rows = buildList {
            for (count in TapActions.MIN_TAP_COUNT..TapActions.MAX_TAP_COUNT) {
                add(Row(PrefKeys.actionForTaps(count), getString(R.string.tap_n_times, count), TapActions.defaultForTaps(count)))
            }
            add(Row(PrefKeys.actionForTapsAndHold(2), getString(R.string.tap_double_hold), TapActions.NONE))
            add(Row(PrefKeys.actionForTapsAndHold(3), getString(R.string.tap_triple_hold), TapActions.NONE))
            add(Row(PrefKeys.ACTION_HOLD_1S, getString(R.string.tap_hold_1s), TapActions.NONE))
        }

        val inflater = LayoutInflater.from(this)
        rows.forEachIndexed { index, row ->
            container.addView(createRow(inflater, container, row))
            if (index < rows.lastIndex) {
                container.addView(MaterialDivider(this).apply {
                    val margin = resources.getDimensionPixelSize(R.dimen.space_md)
                    setDividerInsetStart(margin)
                    setDividerInsetEnd(margin)
                })
            }
        }
    }

    private fun createRow(inflater: LayoutInflater, parent: LinearLayout, row: Row): View {
        val view = inflater.inflate(R.layout.item_tap_setting, parent, false)
        val summary = view.findViewById<TextView>(R.id.tv_summary)
        view.findViewById<TextView>(R.id.tv_title).text = row.title
        summary.text = displayName(currentAction(row))
        view.setOnClickListener { showActionPicker(row, summary) }
        return view
    }

    private fun currentAction(row: Row): String = prefs.getString(row.prefKey, row.defaultAction) ?: row.defaultAction

    private fun save(row: Row, action: String, summary: TextView) {
        prefs.edit().putString(row.prefKey, action).apply()
        summary.text = displayName(action)
    }

    // 選択肢の並びは旧版と同じ
    private val choices = listOf(
        R.string.tap_action_none to TapActions.NONE,
        R.string.tap_action_next_image to TapActions.NEXT_IMAGE,
        R.string.tap_action_next_set to TapActions.NEXT_SET,
        R.string.tap_action_specific_set to TapActions.SPECIFIC_SET_PREFIX,
        R.string.tap_action_toggle_chat to TapActions.TOGGLE_AI_CHAT,
        R.string.tap_action_open_app to TapActions.OPEN_APP,
        R.string.tap_action_crop to TapActions.CROP_IMAGE,
        R.string.tap_action_edit_tags to TapActions.EDIT_TAGS,
        R.string.tap_action_edit_set to TapActions.EDIT_ACTIVE_SET
    )

    private fun showActionPicker(row: Row, summary: TextView) {
        val labels = choices.map { getString(it.first) }
        Md3Dialogs.pickOne(this, getString(R.string.tap_action_dialog_title, row.title), labels) { index ->
            val action = choices[index].second
            if (action != TapActions.SPECIFIC_SET_PREFIX) {
                save(row, action, summary)
                return@pickOne
            }
            pickSpecificSet(row, summary)
        }
    }

    private fun pickSpecificSet(row: Row, summary: TextView) {
        val sets = DataManager.imageSetList.map { it.name }
        if (sets.isEmpty()) {
            Md3Dialogs.snackbar(findViewById(R.id.root), getString(R.string.tap_no_sets))
            return
        }
        Md3Dialogs.pickOne(this, getString(R.string.tap_pick_set), sets) { index ->
            save(row, TapActions.SPECIFIC_SET_PREFIX + sets[index], summary)
        }
    }

    private fun displayName(action: String): String {
        TapActions.specificSet(action)?.let { return getString(R.string.tap_action_specific_set_named, it) }
        val res = choices.firstOrNull { it.second == action }?.first ?: R.string.tap_action_none
        return getString(res)
    }
}
