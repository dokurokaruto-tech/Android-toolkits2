package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAutoScrollPolicyTest {

    @Test
    fun `distance is zero when content fits in the viewport`() {
        val distance = ChatAutoScrollPolicy.distanceFromBottom(
            scrollRange = 800,
            scrollOffset = 0,
            scrollExtent = 800
        )
        assertEquals(0, distance)
        assertTrue(ChatAutoScrollPolicy.isNearBottom(distance, 24))
    }

    @Test
    fun `distance is remaining pixels below the viewport`() {
        val distance = ChatAutoScrollPolicy.distanceFromBottom(
            scrollRange = 2000,
            scrollOffset = 1400,
            scrollExtent = 500
        )
        assertEquals(100, distance)
    }

    @Test
    fun `empty range counts as the bottom`() {
        assertEquals(0, ChatAutoScrollPolicy.distanceFromBottom(0, 0, 0))
    }

    @Test
    fun `leaving the bottom by more than the leave threshold turns stick off`() {
        val stillStuck = ChatAutoScrollPolicy.nextStickState(
            currentlyStuck = true,
            distanceFromBottom = 24,
            leaveThresholdPx = 24,
            rejoinThresholdPx = 48
        )
        assertTrue(stillStuck)

        val left = ChatAutoScrollPolicy.nextStickState(
            currentlyStuck = true,
            distanceFromBottom = 25,
            leaveThresholdPx = 24,
            rejoinThresholdPx = 48
        )
        assertFalse(left)
    }

    @Test
    fun `a small lift off the bottom disables follow during generation`() {
        val stick = ChatStickToBottom(leaveThresholdPx = 24, rejoinThresholdPx = 48)
        assertTrue(stick.shouldFollowGeneration())
        stick.onUserMoved(8)
        assertTrue(stick.shouldFollowGeneration())
        stick.onUserMoved(40)
        assertFalse(stick.shouldFollowGeneration())
        assertFalse(ChatAutoScrollPolicy.shouldFollowGeneration(false))
    }

    @Test
    fun `returning to the bottom re-enables stick`() {
        val stick = ChatStickToBottom(leaveThresholdPx = 24, rejoinThresholdPx = 48)
        stick.onUserMoved(200)
        assertFalse(stick.shouldFollowGeneration())
        stick.onUserMoved(48)
        assertTrue(stick.shouldFollowGeneration())
    }

    @Test
    fun `rejoin uses the wider threshold`() {
        val rejoined = ChatAutoScrollPolicy.nextStickState(
            currentlyStuck = false,
            distanceFromBottom = 30,
            leaveThresholdPx = 24,
            rejoinThresholdPx = 48
        )
        assertTrue(rejoined)

        val stillAway = ChatAutoScrollPolicy.nextStickState(
            currentlyStuck = false,
            distanceFromBottom = 49,
            leaveThresholdPx = 24,
            rejoinThresholdPx = 48
        )
        assertFalse(stillAway)
    }

    @Test
    fun `sending a new message forces stick back on`() {
        val stick = ChatStickToBottom()
        stick.onUserMoved(999)
        assertFalse(stick.shouldFollowGeneration())
        stick.stickForNewContent()
        assertTrue(stick.shouldFollowGeneration())
    }

    @Test
    fun `programmatic generation must not call nextStickState just because content grew`() {
        val stick = ChatStickToBottom(leaveThresholdPx = 24, rejoinThresholdPx = 48)
        stick.onUserMoved(120)
        assertFalse(stick.shouldFollowGeneration())
        // 生成でコンテンツが伸びても、ユーザー操作がなければ stuck は変わらない
        assertFalse(stick.shouldFollowGeneration())
        assertFalse(ChatAutoScrollPolicy.shouldFollowGeneration(stick.stuck))
    }
}
