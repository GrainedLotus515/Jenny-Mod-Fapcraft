package com.trolmastercard.sexmod.util

/**
 * 2D float vector value type.
 */
class Vector2f(@JvmField var x: Float, @JvmField var y: Float) {
    companion object {
        @JvmField val ZERO = Vector2f(0.0f, 0.0f)
    }
}
