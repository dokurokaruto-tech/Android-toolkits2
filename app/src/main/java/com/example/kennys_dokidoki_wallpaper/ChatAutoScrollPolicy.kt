package com.example.kennys_dokidoki_wallpaper

/**
 * ストリーム生成中のチャット自動スクロール。
 *
 * 一番下にいるときだけ末尾へ追従する。
 * 画面の一番下から少しでも離したら追従を切り、底へ戻したときだけ再開する。
 * 生成によるレイアウト変化では stick 状態を更新しない（呼び出し側がユーザー操作のときだけ [nextStickState] を使う）。
 */
object ChatAutoScrollPolicy {
    /** これ以上離れたら「底から離れた」。指で少し動かしただけで切れるよう短め（px）。 */
    const val DEFAULT_LEAVE_THRESHOLD_PX = 24

    /** 底へ戻ったとみなす再着地の猶予。leave より広くしてピクセル揺れで点滅しないようにする。 */
    const val DEFAULT_REJOIN_THRESHOLD_PX = 48

    fun distanceFromBottom(scrollRange: Int, scrollOffset: Int, scrollExtent: Int): Int {
        if (scrollRange <= 0) return 0
        return (scrollRange - scrollOffset - scrollExtent).coerceAtLeast(0)
    }

    fun isNearBottom(distanceFromBottom: Int, thresholdPx: Int): Boolean {
        return distanceFromBottom <= thresholdPx
    }

    /**
     * ユーザーがスクロールしたあとの stick 状態。
     * 追従中は [leaveThresholdPx] を超えたら切る。切れているときは [rejoinThresholdPx] 以内に戻したら再開。
     */
    fun nextStickState(
        currentlyStuck: Boolean,
        distanceFromBottom: Int,
        leaveThresholdPx: Int = DEFAULT_LEAVE_THRESHOLD_PX,
        rejoinThresholdPx: Int = DEFAULT_REJOIN_THRESHOLD_PX
    ): Boolean {
        val leave = leaveThresholdPx.coerceAtLeast(0)
        val rejoin = rejoinThresholdPx.coerceAtLeast(leave)
        return if (currentlyStuck) {
            distanceFromBottom <= leave
        } else {
            distanceFromBottom <= rejoin
        }
    }

    fun shouldFollowGeneration(stuckToBottom: Boolean): Boolean = stuckToBottom
}

/**
 * チャット画面が保持する stick-to-bottom 状態。
 */
class ChatStickToBottom(
    var leaveThresholdPx: Int = ChatAutoScrollPolicy.DEFAULT_LEAVE_THRESHOLD_PX,
    var rejoinThresholdPx: Int = ChatAutoScrollPolicy.DEFAULT_REJOIN_THRESHOLD_PX
) {
    var stuck: Boolean = true
        private set

    fun stickForNewContent() {
        stuck = true
    }

    fun onUserMoved(distanceFromBottomPx: Int) {
        stuck = ChatAutoScrollPolicy.nextStickState(
            currentlyStuck = stuck,
            distanceFromBottom = distanceFromBottomPx,
            leaveThresholdPx = leaveThresholdPx,
            rejoinThresholdPx = rejoinThresholdPx
        )
    }

    fun syncFromDistance(distanceFromBottomPx: Int) {
        stuck = ChatAutoScrollPolicy.isNearBottom(distanceFromBottomPx, rejoinThresholdPx)
    }

    fun shouldFollowGeneration(): Boolean = ChatAutoScrollPolicy.shouldFollowGeneration(stuck)
}
