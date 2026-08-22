package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class CircularPrefetchPlannerTest {
    @Test
    fun firstOfOneHundredUsesRequestedOutwardPairs() {
        assertEquals(listOf(1, 99), CircularPrefetchPlanner.ring(0, 100, 1))
        assertEquals(listOf(2, 98), CircularPrefetchPlanner.ring(0, 100, 2))
    }

    @Test
    fun tinyAlbumsNeverRepeatCurrentOrDuplicateNeighbors() {
        assertEquals(listOf(1), CircularPrefetchPlanner.ring(0, 2, 1))
        assertEquals(emptyList<Int>(), CircularPrefetchPlanner.ring(0, 2, 2))
    }
}
