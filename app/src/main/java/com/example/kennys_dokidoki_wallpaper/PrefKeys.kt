package com.example.kennys_dokidoki_wallpaper

/**
 * SharedPreferences のファイル名とキーを一箇所に集約する。
 *
 * 旧コードでは "settings" / "active_album_name_chat" などの文字列が
 * MainActivity / ChatOverlayActivity / MyWallpaperService / DataManager に
 * 合計 100 箇所以上コピーされており、typo 1 つで壁紙とチャットの状態が食い違っていた。
 */
object PrefFiles {
    const val SETTINGS = "settings"
    const val WALLPAPER = "wallpaper_prefs"
    const val OPENROUTER = "openrouter_prefs"
}

object PrefKeys {
    // ---- 壁紙 / アルバム ----
    const val ACTIVE_ALBUM = "active_album_name"
    const val ACTIVE_ALBUM_HOME = "active_album_name_homescreen"
    const val ACTIVE_ALBUM_CHAT = "active_album_name_chat"
    const val IS_CHAT_ACTIVE = "is_chat_active"
    const val ACTIVE_IMAGE_INDEX = "active_image_index"
    const val SHUFFLE_IMAGES = "shuffle_images"
    const val DATA_REVISION = "data_revision"
    const val LEGACY_ALL_IMAGES = "all_images"
    const val LEGACY_IMAGE_SETS = "image_sets"

    fun lastIndexForAlbum(setName: String): String = "last_index_for_album_$setName"

    // ---- タップ操作 (TapSettingsActivity と共有) ----
    const val ACTION_HOLD_1S = "action_hold_2s" // 歴史的経緯で 2s という名前だが 1 秒
    fun actionForTaps(count: Int): String = "action_tap_$count"
    fun actionForTapsAndHold(count: Int): String = "action_tap_${count}_hold"

    // ---- チャット ----
    const val CHAT_LLM_ENGINE = "chat_llm_engine"
    const val CHAT_CLOUD_PROVIDER = "chat_cloud_provider"
    const val CHAT_OPENROUTER_MODEL = "chat_openrouter_model"
    const val CHAT_SUGGEST_REPLY = "chat_suggest_reply"
    const val CHAT_SUGGEST_CUSTOM = "chat_suggest_custom_instructions"
    const val CHAT_BUBBLE_OPACITY = "chat_bubble_opacity"
    const val CHAT_BUBBLE_WIDTH = "chat_bubble_width"

    // ---- 秘密情報 (SecurePrefs に保存。平文 "settings" には置かない) ----
    const val SECRET_XAI_API_KEY = "xai_api_key"
    const val SECRET_AGENT_API_KEY = "generation_agent_api_key"
    const val SECRET_OPENROUTER_KEYS = "openrouter_api_keys_v3"

    // ---- PC 生成エージェント ----
    const val AGENT_URL = "remote_server_url"
    const val AGENT_URL_ALTS = "remote_server_url_alts"
    const val AGENT_URL_LAST_GOOD = "remote_server_url_last_good"

    // ---- 外観 ----
    const val DYNAMIC_COLOR = "appearance_dynamic_color"
    const val THEME_MODE = "appearance_theme_mode" // system / light / dark
}

/** 壁紙タップで実行できる動作。旧コードは生文字列で比較していた。 */
object TapActions {
    const val NONE = "NONE"
    const val NEXT_IMAGE = "NEXT_IMAGE"
    const val NEXT_SET = "NEXT_SET"
    const val TOGGLE_AI_CHAT = "TOGGLE_AI_CHAT"
    const val OPEN_APP = "OPEN_APP"
    const val CROP_IMAGE = "CROP_IMAGE"
    const val EDIT_TAGS = "EDIT_TAGS"
    const val EDIT_ACTIVE_SET = "EDIT_ACTIVE_SET"
    const val SPECIFIC_SET_PREFIX = "SPECIFIC_SET:"

    const val MIN_TAP_COUNT = 2
    const val MAX_TAP_COUNT = 6

    /** TapSettingsActivity の既定値と一致させる */
    fun defaultForTaps(count: Int): String = when (count) {
        2 -> NEXT_IMAGE
        3 -> NEXT_SET
        else -> NONE
    }

    fun specificSet(action: String): String? {
        if (!action.startsWith(SPECIFIC_SET_PREFIX)) {
            return null
        }
        return action.substringAfter(SPECIFIC_SET_PREFIX)
    }
}
