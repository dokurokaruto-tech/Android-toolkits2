package com.example.kennys_dokidoki_wallpaper

/**
 * ストリーム生成中のチャット自動スクロール。
 *
 * 一番下にいるときだけ末尾へ追従する。
 * 画面の一番下から少しでも離したら追従を切り、ユーザー自身が底へ戻したときだけ再開する。
 * 生成によるレイアウト変化では stick 状態を更新しない。
 * ドラッグ中に生成が底へ引き戻しても、再着地（rejoin）はさせない。
 */
object ChatAutoScrollPolicy {
    /** これ以上離れたら「底から離れた」。指で少し動かしただけで切れるよう短め（px）。 */
    const val DEFAULT_LEAVE_THRESHOLD_PX = 8

    /** 底へ戻ったとみなす再着地の猶予。leave より広くしてピクセル揺れで点滅しないようにする。 */
    const val DEFAULT_REJOIN_THRESHOLD_PX = 24

    data class ViewportAnchor(val position: Int, val offsetPx: Int)

    /**
     * 生成でアイテムが高くなったあと、同じ行を画面の同じ位置に戻すための dy。
     * RecyclerView.scrollBy(0, dy) に渡す。正で内容が上へ、負で下へ。
     */
    fun scrollByToRestoreChild(currentTop: Int, paddingTop: Int, savedOffsetPx: Int): Int {
        return currentTop - paddingTop - savedOffsetPx
    }

    fun distanceFromBottom(scrollRange: Int, scrollOffset: Int, scrollExtent: Int): Int {
        if (scrollRange <= 0) return 0
        return (scrollRange - scrollOffset - scrollExtent).coerceAtLeast(0)
    }

    fun isNearBottom(distanceFromBottom: Int, thresholdPx: Int): Boolean {
        return distanceFromBottom <= thresholdPx
    }

    /**
     * ユーザーがスクロールしたあとの stick 状態。
     * 追従中は [leaveThresholdPx] を超えたら切る。
     * 切れているときは [allowRejoin] が true のときだけ [rejoinThresholdPx] 以内で再開する。
     * ドラッグ中は [allowRejoin] を false にし、生成の引き戻しで再着地しないようにする。
     */
    fun nextStickState(
        currentlyStuck: Boolean,
        distanceFromBottom: Int,
        leaveThresholdPx: Int = DEFAULT_LEAVE_THRESHOLD_PX,
        rejoinThresholdPx: Int = DEFAULT_REJOIN_THRESHOLD_PX,
        allowRejoin: Boolean = true
    ): Boolean {
        val leave = leaveThresholdPx.coerceAtLeast(0)
        val rejoin = rejoinThresholdPx.coerceAtLeast(leave)
        return if (currentlyStuck) {
            distanceFromBottom <= leave
        } else if (allowRejoin) {
            distanceFromBottom <= rejoin
        } else {
            false
        }
    }

    fun shouldFollowGeneration(stuckToBottom: Boolean, userInteracting: Boolean = false): Boolean {
        return stuckToBottom && !userInteracting
    }

    fun shouldPreserveViewport(stuckToBottom: Boolean, userInteracting: Boolean): Boolean {
        return !shouldFollowGeneration(stuckToBottom, userInteracting)
    }

    /**
     * 改行に合わせて画面を動かすのは、更新前に一番下にいるときだけ。
     * [distanceFromBottomPx] は notify する前の値を渡せ。
     */
    fun shouldFollowStreamingNewLine(
        userInteracting: Boolean,
        distanceFromBottomPx: Int,
        followThresholdPx: Int = DEFAULT_LEAVE_THRESHOLD_PX
    ): Boolean {
        if (userInteracting) return false
        return distanceFromBottomPx <= followThresholdPx.coerceAtLeast(0)
    }

    /**
     * 生成でリストを末尾へ動かしてよいか。途中まで読んでいるなら動かすな。
     */
    fun shouldMoveWithGeneration(
        stuckToBottom: Boolean,
        userInteracting: Boolean,
        distanceFromBottomPx: Int = 0,
        leaveThresholdPx: Int = DEFAULT_LEAVE_THRESHOLD_PX
    ): Boolean {
        if (!stuckToBottom) return false
        return shouldFollowStreamingNewLine(userInteracting, distanceFromBottomPx, leaveThresholdPx)
    }

    /**
     * 生成中の本文は、画面に付いているか見えるなら必ず書け。
     * 画面外の末尾は notify すると stackFromEnd が跳ねるので触るな。
     * スクロールで付いたときに bind し直せ。
     */
    fun shouldBindStreamingText(
        itemIsAttached: Boolean,
        lastVisiblePosition: Int,
        changedIndex: Int
    ): Boolean {
        if (itemIsAttached) return true
        return !shouldSkipOffscreenUpdate(lastVisiblePosition, changedIndex)
    }

    /**
     * 画面外の末尾アイテムはレイアウトしない。notify すると stackFromEnd が
     * リストを先頭へ飛ばすことがある。
     */
    fun shouldSkipOffscreenUpdate(lastVisiblePosition: Int, changedIndex: Int): Boolean {
        return lastVisiblePosition >= 0 && changedIndex > lastVisiblePosition
    }
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

    fun release() {
        stuck = false
    }

    fun onUserMoved(distanceFromBottomPx: Int, allowRejoin: Boolean = true) {
        stuck = ChatAutoScrollPolicy.nextStickState(
            currentlyStuck = stuck,
            distanceFromBottom = distanceFromBottomPx,
            leaveThresholdPx = leaveThresholdPx,
            rejoinThresholdPx = rejoinThresholdPx,
            allowRejoin = allowRejoin
        )
    }

    fun syncFromDistance(distanceFromBottomPx: Int) {
        stuck = ChatAutoScrollPolicy.isNearBottom(distanceFromBottomPx, rejoinThresholdPx)
    }

    fun shouldFollowGeneration(userInteracting: Boolean = false): Boolean {
        return ChatAutoScrollPolicy.shouldFollowGeneration(stuck, userInteracting)
    }
}
