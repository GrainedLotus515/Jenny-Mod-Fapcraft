package com.trolmastercard.sexmod.util

import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

/**
 * **Role.** Vector math helpers for the renderers and scene code: scaling,
 * dot/cross products, yaw/pitch and Euler rotations, mirroring and linear-factor
 * interpolation.
 *
 * **Pitfall.** [rotateByYawPitch] uses the Minecraft convention
 * (yaw + 90 shift, x/z plane) — feeding it raw degrees from a different
 * convention rotates meshes sideways. [getLinearFactor] divides by
 * `(b-a)`; a zero denominator yields NaN — callers must ensure distinct
 * points.
 */
object VectorMath {
    @JvmStatic
    fun scale(vector: Vec3, factor: Double): Vec3 = Vec3(vector.x * factor, vector.y * factor, vector.z * factor)

    @JvmStatic
    fun dotProduct(a: Vector3f, b: Vec3): Double = a.x * b.x + a.y * b.y + a.z * b.z

    @JvmStatic
    fun dotProduct(a: Vec3, b: Vec3): Double = a.x * b.x + a.y * b.y + a.z * b.z

    @JvmStatic
    fun crossProduct(a: Vec3, b: Vec3): Vec3 = Vec3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x
    )

    @JvmStatic
    fun rotateByYaw(x: Double, y: Double, z: Double, yaw: Float): Vec3 = rotateByYaw(Vec3(x, y, z), yaw)

    @JvmStatic
    fun rotateByYaw(vector: Vec3, yaw: Float): Vec3 = rotateByYawPitch(vector, 0.0f, yaw)

    @JvmStatic
    fun rotateByYawPitch(vector: Vec3, yaw: Float, pitch: Float): Vec3 {
        val rotated = Vec3(
            vector.x,
            vector.y * Math.cos(yaw * (Math.PI / 180.0)) - vector.z * Math.sin(yaw * (Math.PI / 180.0)),
            vector.y * Math.sin(yaw * (Math.PI / 180.0)) + vector.z * Math.cos(yaw * (Math.PI / 180.0))
        )
        return Vec3(
            -Math.sin((pitch + 90.0f) * (Math.PI / 180.0)) * rotated.x - Math.sin(pitch * (Math.PI / 180.0)) * rotated.z,
            rotated.y,
            Math.cos((pitch + 90.0f) * (Math.PI / 180.0)) * rotated.x + Math.cos(pitch * (Math.PI / 180.0)) * rotated.z
        )
    }

    @JvmStatic
    fun rotate(x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Vec3 = rotateByYawPitch(Vec3(x, y, z), yaw, pitch)

    @JvmStatic
    fun rotateByEuler(vector: Vec3, yaw: Float, pitch: Float, roll: Float): Vec3 {
        var v = vector
        val yawR = TrigMath.wrapDegrees(yaw)
        val pitchR = TrigMath.wrapDegrees(pitch)
        val rollR = TrigMath.wrapDegrees(roll)
        val sinYaw = Math.sin(yawR.toDouble()).toFloat().toDouble()
        val cosYaw = Math.cos(yawR.toDouble()).toFloat().toDouble()
        val sinPitch = Math.sin(pitchR.toDouble()).toFloat().toDouble()
        val cosPitch = Math.cos(pitchR.toDouble()).toFloat().toDouble()
        val sinRoll = Math.sin(rollR.toDouble()).toFloat().toDouble()
        val cosRoll = Math.cos(rollR.toDouble()).toFloat().toDouble()
        var newY = v.y * cosYaw - v.z * sinYaw
        var newZ = v.y * sinYaw + v.z * cosYaw
        v = Vec3(v.x, newY, newZ)
        var newX = v.x * cosPitch + v.z * sinPitch
        newZ = -v.x * sinPitch + v.z * cosPitch
        v = Vec3(newX, v.y, newZ)
        newX = v.x * cosRoll - v.y * sinRoll
        newY = v.x * sinRoll + v.y * cosRoll
        return Vec3(newX, newY, v.z)
    }

    @JvmStatic
    fun MirrorXZ(vector: Vec3): Vec3 = Vec3(-vector.x, vector.y, -vector.z)

    @JvmStatic
    fun MirrorXY(vector: Vec3): Vec3 = Vec3(-vector.x, -vector.y, vector.z)

    @JvmStatic
    fun MirrorYZ(vector: Vec3): Vec3 = Vec3(vector.x, -vector.y, -vector.z)

    @JvmStatic
    fun getLinearFactor(start: Double, end: Double, value: Double): Double = (value - start) / (end - start)

    @JvmStatic
    fun getLinearFactor(start: Vec3, end: Vec3, value: Vec3): Double = getLinearFactor(start.x, end.x, value.x)
}
