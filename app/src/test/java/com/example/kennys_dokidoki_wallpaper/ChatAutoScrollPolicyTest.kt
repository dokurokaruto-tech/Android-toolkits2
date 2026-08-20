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
        assertTrue(ChatAutoScrollPolicy.isNearBottom(distance, 8))
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
            distanceFromBottom = 8,
            leaveThresholdPx = 8,
            rejoinThresholdPx = 24
        )
        assertTrue(stillStuck)

        val left = ChatAutoScrollPolicy.nextStickState(
            currentlyStuck = true,
            distanceFromBottom = 9,
            leaveThresholdPx = 8,
            rejoinThresholdPx = 24
        )
        assertFalse(left)
    }

    @Test
    fun `a small lift off the bottom disables follow during generation`() {
        val stick = ChatStickToBottom(leaveThresholdPx = 8, rejoinThresholdPx = 24)
        assertTrue(stick.shouldFollowGeneration())
        stick.onUserMoved(4)
        assertTrue(stick.shouldFollowGeneration())
        stick.onUserMoved(12)
        assertFalse(stick.shouldFollowGeneration())
        assertFalse(ChatAutoScrollPolicy.shouldFollowGeneration(false))
    }

    @Test
    fun `returning to the bottom re-enables stick`() {
        val stick = ChatStickToBottom(leaveThresholdPx = 8, rejoinThresholdPx = 24)
        stick.onUserMoved(200)
        assertFalse(stick.shouldFollowGeneration())
        stick.onUserMoved(24)
        assertTrue(stick.shouldFollowGeneration())
    }

    @Test
    fun `rejoin uses the wider threshold`() {
        val rejoined = ChatAutoScrollPolicy.nextStickState(
            currentlyStuck = false,
            distanceFromBottom = 20,
            leaveThresholdPx = 8,
            rejoinThresholdPx = 24
        )
        assertTrue(rejoined)

        val stillAway = ChatAutoScrollPolicy.nextStickState(
            currentlyStuck = false,
            distanceFromBottom = 25,
            leaveThresholdPx = 8,
            rejoinThresholdPx = 24
        )
        assertFalse(stillAway)
    }

    @Test
    fun `dragging does not rejoin even if generation yanks back to the bottom`() {
        val stick = ChatStickToBottom(leaveThresholdPx = 8, rejoinThresholdPx = 24)
        stick.onUserMoved(80, allowRejoin = false)
        assertFalse(stick.shouldFollowGeneration())
        stick.onUserMoved(0, allowRejoin = false)
        assertFalse(stick.shouldFollowGeneration())
        stick.onUserMoved(0, allowRejoin = true)
        assertTrue(stick.shouldFollowGeneration())
    }

    @Test
    fun `user interaction blocks follow even while stuck`() {
        val stick = ChatStickToBottom()
        assertTrue(stick.shouldFollowGeneration())
        assertFalse(stick.shouldFollowGeneration(userInteracting = true))
        assertTrue(ChatAutoScrollPolicy.shouldPreserveViewport(true, userInteracting = true))
        assertFalse(ChatAutoScrollPolicy.shouldPreserveViewport(true, userInteracting = false))
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
    fun `release turns follow off until the user returns`() {
        val stick = ChatStickToBottom()
        stick.release()
        assertFalse(stick.shouldFollowGeneration())
        stick.onUserMoved(0)
        assertTrue(stick.shouldFollowGeneration())
    }

    @Test
    fun `programmatic generation must not call nextStickState just because content grew`() {
        val stick = ChatStickToBottom(leaveThresholdPx = 8, rejoinThresholdPx = 24)
        stick.onUserMoved(120)
        assertFalse(stick.shouldFollowGeneration())
        assertFalse(stick.shouldFollowGeneration())
        assertFalse(ChatAutoScrollPolicy.shouldFollowGeneration(stick.stuck))
    }

    @Test
    fun `offscreen last item should not be laid out`() {
        assertTrue(ChatAutoScrollPolicy.shouldSkipOffscreenUpdate(lastVisiblePosition = 4, changedIndex = 9))
        assertFalse(ChatAutoScrollPolicy.shouldSkipOffscreenUpdate(lastVisiblePosition = 9, changedIndex = 9))
        assertFalse(ChatAutoScrollPolicy.shouldSkipOffscreenUpdate(lastVisiblePosition = -1, changedIndex = 3))
    }

    @Test
    fun `generation must not move the screen unless pinned to the bottom`() {
        assertTrue(
            ChatAutoScrollPolicy.shouldMoveWithGeneration(
                stuckToBottom = true,
                userInteracting = false,
                distanceFromBottomPx = 0
            )
        )
        assertFalse(
            ChatAutoScrollPolicy.shouldMoveWithGeneration(
                stuckToBottom = false,
                userInteracting = false,
                distanceFromBottomPx = 0
            )
        )
        assertFalse(
            ChatAutoScrollPolicy.shouldMoveWithGeneration(
                stuckToBottom = true,
                userInteracting = true,
                distanceFromBottomPx = 0
            )
        )
    }

    @Test
    fun `growing the last bubble must not freeze text while still stuck`() {
        assertTrue(
            ChatAutoScrollPolicy.shouldMoveWithGeneration(
                stuckToBottom = true,
                userInteracting = false,
                distanceFromBottomPx = 48,
                leaveThresholdPx = 8
            )
        )
        assertTrue(
            ChatAutoScrollPolicy.shouldBindStreamingText(
                itemIsAttached = true,
                lastVisiblePosition = 4,
                changedIndex = 9
            )
        )
        assertFalse(
            ChatAutoScrollPolicy.shouldBindStreamingText(
                itemIsAttached = false,
                lastVisiblePosition = 4,
                changedIndex = 9
            )
        )
        assertTrue(
            ChatAutoScrollPolicy.shouldBindStreamingText(
                itemIsAttached = false,
                lastVisiblePosition = 9,
                changedIndex = 9
            )
        )
    }
}
