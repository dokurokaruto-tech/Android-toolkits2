package com.example.kennys_dokidoki_wallpaper

/** Pure circular neighbor ordering for staged original-image prefetch. */
object CircularPrefetchPlanner {
    fun ring(center: Int, itemCount: Int, distance: Int): List<Int> {
        if (itemCount <= 1 || center !in 0 until itemCount || distance < 1) return emptyList()
        val next = (center + distance) % itemCount
        val previous = ((center - distance) % itemCount + itemCount) % itemCount
        return listOf(next, previous).distinct().filter { it != center }
    }
}
