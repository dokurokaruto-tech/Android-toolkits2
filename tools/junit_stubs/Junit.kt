// Standalone harness only; replaces org.junit for local checks without gradle.
package org.junit

annotation class Test

object Assert {
    fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) {
            throw AssertionError("expected:<$expected> but was:<$actual>")
        }
    }

    fun assertEquals(expected: Double, actual: Double, delta: Double) {
        if (Math.abs(expected - actual) > delta) {
            throw AssertionError("expected:<$expected> but was:<$actual> delta:$delta>")
        }
    }

    fun assertEquals(expected: Float, actual: Float, delta: Float) {
        if (Math.abs(expected - actual) > delta) {
            throw AssertionError("expected:<$expected> but was:<$actual> delta:$delta>")
        }
    }

    fun assertNotEquals(unexpected: Any?, actual: Any?) {
        if (unexpected == actual) {
            throw AssertionError("expected not:<$unexpected>")
        }
    }

    fun assertTrue(condition: Boolean) {
        if (!condition) {
            throw AssertionError("expected true")
        }
    }

    fun assertFalse(condition: Boolean) {
        if (condition) {
            throw AssertionError("expected false")
        }
    }

    fun assertNull(value: Any?) {
        if (value != null) {
            throw AssertionError("expected null but was <$value>")
        }
    }

    fun assertNotNull(value: Any?) {
        if (value == null) {
            throw AssertionError("expected null")
        }
    }

    fun assertSame(expected: Any?, actual: Any?) {
        if (expected !== actual) {
            throw AssertionError("expected the same instance")
        }
    }
}
