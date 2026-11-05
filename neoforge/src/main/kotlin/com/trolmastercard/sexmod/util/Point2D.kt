package com.trolmastercard.sexmod.util

/**
 * **Role.** Simple int point with distance helper. Used by the
 * `StructureCommandScreen` grid math.
 */
class Point2D(@JvmField var x: Int, @JvmField var y: Int) {
    fun distanceTo(x: Int, y: Int): Float {
        val dx = (x - this.x).toFloat()
        val dy = (y - this.y).toFloat()
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    override fun toString(): String = String.format("(%s, %s)", this.x, this.y)

    companion object {
        @JvmField val ZERO = Point2D(0, 0)
    }
}
