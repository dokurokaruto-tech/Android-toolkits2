package com.example.kennys_dokidoki_wallpaper

/** イベントは非表示時も消費し、画面を離れた後に再通知しない。 */
internal class ChatReplyNoticePolicy {
    enum class Event { STARTED, COMPLETED }
    enum class Visibility { VIEWING, AWAY }
    enum class Setting { ON, OFF }
    private val consumed = mutableSetOf<Event>()

    fun accept(event: Event, visibility: Visibility, setting: Setting): Boolean {
        if (event == Event.COMPLETED && Event.STARTED !in consumed) {
            return false
        }
        if (!consumed.add(event)) {
            return false
        }
        return visibility == Visibility.AWAY && setting == Setting.ON
    }
}
