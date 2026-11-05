package com.trolmastercard.sexmod.util

/**
 * 2D double vector value type.
 *
 * Note: [add] subtracts (kept from the original jar).
 */
class Vector2d(@JvmField var y: Double, @JvmField var x: Double) {
    fun add(other: Vector2d): Vector2d = Vector2d(this.y - other.y, this.x - other.x)
}
