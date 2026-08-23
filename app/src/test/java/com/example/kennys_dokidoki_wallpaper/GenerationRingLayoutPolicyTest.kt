package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationRingLayoutPolicyTest {
    @Test
    fun hugsVisiblePillWithUniformGap() {
        val spec = GenerationRingLayoutPolicy.layout(
            buttonWidth = 200,
            buttonHeight = 80,
            insetLeft = 4,
            insetTop = 10,
            insetRight = 4,
            insetBottom = 10,
            buttonCornerRadius = 40f,
            strokeWidth = 8f,
            gap = 2f
        )
        assertEquals(4 - 6, spec.left)
        assertEquals(10 - 6, spec.top)
        assertEquals(192 + 12, spec.width)
        assertEquals(60 + 12, spec.height)
        assertEquals(32f, spec.cornerRadius)
    }

    @Test
    fun treatsMissingCornerAsVisiblePill() {
        val spec = GenerationRingLayoutPolicy.layout(
            buttonWidth = 160,
            buttonHeight = 56,
            insetLeft = 0,
            insetTop = 6,
            insetRight = 0,
            insetBottom = 6,
            buttonCornerRadius = 0f,
            strokeWidth = 4f,
            gap = 2f
        )
        assertEquals(-4, spec.left)
        assertEquals(2, spec.top)
        assertEquals(168, spec.width)
        assertEquals(52, spec.height)
        assertEquals(24f, spec.cornerRadius)
    }

    @Test
    fun keepsSmallerCornerWhenButtonIsNotAPill() {
        val spec = GenerationRingLayoutPolicy.layout(
            buttonWidth = 120,
            buttonHeight = 48,
            insetLeft = 0,
            insetTop = 0,
            insetRight = 0,
            insetBottom = 0,
            buttonCornerRadius = 8f,
            strokeWidth = 4f,
            gap = 2f
        )
        assertEquals(-4, spec.left)
        assertEquals(-4, spec.top)
        assertEquals(128, spec.width)
        assertEquals(56, spec.height)
        assertEquals(10f, spec.cornerRadius)
    }

    @Test
    fun outerRingSitsImmediatelyOutsideInner() {
        val nested = GenerationRingLayoutPolicy.nested(
            buttonWidth = 200,
            buttonHeight = 80,
            insetLeft = 0,
            insetTop = 10,
            insetRight = 0,
            insetBottom = 10,
            buttonCornerRadius = 40f,
            strokeWidth = 6f,
            gap = 2f
        )
        val inner = nested.inner
        val outer = nested.outer
        assertEquals(inner.left - 6, outer.left)
        assertEquals(inner.top - 6, outer.top)
        assertEquals(inner.width + 12, outer.width)
        assertEquals(inner.height + 12, outer.height)
        assertEquals(inner.cornerRadius + 6f, outer.cornerRadius)
    }
}
