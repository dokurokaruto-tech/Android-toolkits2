package com.example.kennys_dokidoki_wallpaper

/** EOFだけでは正常終了とみなさない。 */
internal class ChatReplyStreamState {
    private enum class Terminal { NONE, DONE, FAILED }
    private var terminal = Terminal.NONE

    fun finishReason(reason: String?) {
        if (reason.isNullOrBlank()) {
            return
        }
        if (reason == "stop" || reason == "length") {
            done()
        } else {
            fail()
        }
    }

    fun done() {
        if (terminal != Terminal.FAILED) {
            terminal = Terminal.DONE
        }
    }

    fun fail() {
        terminal = Terminal.FAILED
    }

    fun completed(text: String): Boolean = terminal == Terminal.DONE && text.isNotBlank()
}
