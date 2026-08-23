package com.example.kennys_dokidoki_wallpaper

/**
 * AIでタグ文章を置き換える画面の文言。
 * 絵文字や英語ラベルは使わず、短い日本語にする。
 */
object TagAiGenerateCopy {
    const val TITLE = "文章を置き換える"
    const val MODEL_LABEL = "モデル"
    const val CHANGE_MODEL = "選び直す"
    const val PRESET_LABEL = "指示プリセット"
    const val EDIT_PRESET = "指示を編集"
    const val DELETE_PRESET = "プリセットを削除"
    const val EXTRA_HINT = "今回だけの補足"
    const val USE_TAG_NAME = "タグ名を含める"
    const val USE_EXISTING = "今の文を下書きにする"
    const val PICK_IMAGE = "画像を添える"
    const val CANCEL = "閉じる"
    const val GENERATE = "置き換える"
    const val LIST_TITLE = "指示プリセット"
    const val ADD_PRESET = "追加"
    const val CLOSE = "閉じる"
    const val EDITOR_NEW = "プリセットを追加"
    const val EDITOR_EDIT = "プリセットを編集"
    const val NAME_HINT = "プリセット名"
    const val BODY_HINT = "指示"
    const val SAVE = "保存"
    const val BACK = "戻る"
    const val DELETE = "削除"
    const val SAVED = "プリセットを保存した。"
    const val DELETED = "プリセットを消した。"
    const val DELETE_LAST_BLOCKED = "最後の1つは消せない。"
    const val DELETE_CONFIRM_TITLE = "このプリセットを消しますか？"
    const val DELETE_CONFIRM = "消す"

    fun modelLine(provider: String, model: String): String {
        val brand = when (provider.uppercase()) {
            "OPENROUTER" -> "OpenRouter"
            "GROK", "XAI" -> "xAI"
            else -> provider
        }
        return "$brand · $model"
    }

    fun usageLine(used: Int, limit: Int): String = "残り ${ (limit - used).coerceAtLeast(0) } 回"
}
