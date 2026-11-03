package com.trolmastercard.sexmod.util

import net.minecraft.world.phys.Vec3

/**
 * Trigonometry helpers (wrapDegrees etc.).
 *
 * Note: several names do not match their maths (e.g. [wrapDegrees] converts
 * degrees to radians, [sinDegrees] converts radians to degrees). Callers rely
 * on the actual behaviour; keep it.
 */
object TrigMath {
    @JvmStatic
    fun calculatePitchAngle(from: Vec3, to: Vec3): Double {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        return Math.atan2(dz, Math.sqrt(dx * dx + dy * dy))
    }

    @JvmStatic
    fun NormalizeAngle(angle: Float): Float {
        var a = angle % 360.0f
        if (a < 0.0f) {
            a += 360.0f
        }
        return a
    }

    @JvmStatic
    fun normalizedAngle360(angle: Float): Float {
        val result = angle % 360.0f
        return if (result >= 0.0f) result else result + 360.0f
    }

    @JvmStatic
    fun normalizedAngle360(angle: Double): Double {
        val result = angle % 360.0
        return if (result >= 0.0) result else result + 360.0
    }

    @JvmStatic
    fun wrapDegrees(angle: Float): Float = ((Math.PI * 2) / (360.0 / angle)).toFloat()

    @JvmStatic
    fun toRadians(degrees: Double): Float = ((Math.PI * 2) / (360.0 / degrees)).toFloat()

    @JvmStatic
    fun toDegrees(radians: Float): Float = ((180.0 / Math.PI) * radians).toFloat()

    @JvmStatic
    fun sinDegrees(degrees: Double): Double = (180.0 / Math.PI) * degrees
}
