package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayStepPresetPolicyTest {
    @Test
    fun blankStoredListIsEmptyButNullUsesDefaults() {
        assertEquals(ReplayStepPresetPolicy.DEFAULTS, ReplayStepPresetPolicy.parse(null))
        assertEquals(emptyList<Int>(), ReplayStepPresetPolicy.parse(""))
        assertEquals(listOf(20, 40), ReplayStepPresetPolicy.parse("20, 40, 20"))
    }

    @Test
    fun addAndRemoveKeepUniqueClampedValues() {
        val added = ReplayStepPresetPolicy.add(listOf(20, 30), 48)
        assertEquals(listOf(20, 30, 48), added)
        assertEquals(listOf(20, 30, 48), ReplayStepPresetPolicy.add(added, 48))
        assertEquals(listOf(20, 48), ReplayStepPresetPolicy.remove(added, 30))
        assertEquals(listOf(20, 30, 150), ReplayStepPresetPolicy.add(listOf(20, 30), 999))
    }

    @Test
    fun encodeRoundTrips() {
        val values = listOf(12, 28, 40)
        assertEquals(values, ReplayStepPresetPolicy.parse(ReplayStepPresetPolicy.encode(values)))
    }
}
