package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.client.gui.UnknownScreen
import net.minecraft.core.Vec3i
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/**
 * Math/lerp helpers shared across the mod.
 *
 * **CRITICAL — two different lerp families:**
 * - [lerpVec3d] (Vec3, Vec3, Int) — **STEP lerp**: takes one
 *   step of the remaining distance (`a + (b-a)/t`). The girls'
 *   40-tick dismount/walk lerps call this with `t = 40 - counter`
 *   (int). Do NOT switch those call sites to the double variant — the
 *   deobfuscation once did and every girl got flung 40&times; the
 *   distance on the first tick, their chunk unloaded, and they vanished
 *   (destroy packet -> client setDead, gone on reload).
 * - [lerpVec3dDouble] (Vec3, Vec3, Double) — **PROGRESS lerp**:
 *   interpolates by a factor in 0..1 (`a + (b-a)*t`). Used by
 *   render code with partial ticks — that is correct there.
 *
 * Both variants existed in the original jar with distinct descriptors
 * (`(Vec3d, Vec3d, int)` vs `(Vec3d, Vec3d, double)`); the int
 * arguments auto-widen in Java, which is how the wrong-variant bug slipped in.
 */
object RotationHelper {
    /**
     * STEP lerp: one step of the remaining distance (`a + (b-a)/t`).
     * Returns `b` unchanged when `t == 0`.
     *
     * Used by the girls' 40-tick dismount/walk lerps with `t = 40 - counter`.
     * **Do not reroute those call sites to [lerpVec3dDouble]** — the multiply
     * version flings the girl 40x (see class doc).
     */
    @JvmStatic
    fun lerpVec3d(from: Vec3, to: Vec3, steps: Int): Vec3 {
        if (steps == 0) {
            return to
        }

        val delta = to.subtract(from)
        return from.add(delta.x / steps, delta.y / steps, delta.z / steps)
    }

    @JvmStatic
    fun lerpDouble(from: Double, to: Double, t: Double): Double = from + (to - from) * t

    @JvmStatic
    fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    @JvmStatic
    fun lerpFloat(from: Float, to: Float, t: Double): Float {
        var diff = to - from

        while (diff < -Math.PI) {
            diff = (diff + (Math.PI * 2)).toFloat()
        }

        while (diff >= Math.PI) {
            diff = (diff - (Math.PI * 2)).toFloat()
        }

        return (from + diff * t).toFloat()
    }

    @JvmStatic
    fun lerpAngleDegrees(from: Float, to: Float, t: Double): Float {
        val fromRad = Math.toRadians(from.toDouble())
        val toRad = Math.toRadians(to.toDouble())
        // jar-verified: the deobf once made this self-recursive (StackOverflow);
        // the original calls the wrap-degrees float lerp on the radian values
        return Math.toDegrees(lerpFloat(fromRad.toFloat(), toRad.toFloat(), t).toDouble()).toFloat()
    }

    /**
     * PROGRESS lerp: interpolates by factor `t` in 0..1
     * (`a + (b-a)*t`). Used by render interpolation with partial ticks.
     * Do NOT use for the girls' step lerps (see class doc).
     */
    @JvmStatic
    fun lerpVec3dDouble(from: Vec3, to: Vec3, t: Double): Vec3 {
        val delta = to.subtract(from)
        return from.add(Vec3(delta.x * t, delta.y * t, delta.z * t))
    }

    @JvmStatic
    fun lerpVector3f(from: Vector3fSexmodSpecial, to: Vector3fSexmodSpecial, t: Double): Vector3fSexmodSpecial {
        val sum = to.add(from)
        return from.subtract(sum.scale(t.toFloat()))
    }

    /** 1.12 `new Vec3i(double, double, double)` floored its arguments; done explicitly here. */
    @JvmStatic
    fun lerpVec3i(from: Vec3i, to: Vec3i, t: Double): Vec3i {
        val delta = Vec3(
            (to.x - from.x).toDouble(), (to.y - from.y).toDouble(), (to.z - from.z).toDouble()
        )
        return Vec3i(
            Mth.floor(from.x + delta.x * t), Mth.floor(from.y + delta.y * t), Mth.floor(from.z + delta.z * t)
        )
    }

    @JvmStatic
    fun lerpColor(from: UnknownScreen, to: UnknownScreen, t: Double): UnknownScreen {
        val diff = UnknownScreen(to.red - from.red, to.green - from.green, to.blue - from.blue, to.alpha - from.alpha)
        return UnknownScreen(
            (from.red + diff.red * t).toInt(),
            (from.green + diff.green * t).toInt(),
            (from.blue + diff.blue * t).toInt(),
            (from.alpha + diff.alpha * t).toInt()
        )
    }

    @JvmStatic
    fun easeInOutQuad(t: Double): Double = 1.0 - Math.pow(1.0 - t, 4.0)

    @JvmStatic
    fun easeInOutCubic(t: Double): Double = 1.0 - Math.pow(1.0 - t, 3.0)

    @JvmStatic
    fun easeOutBack(t: Double): Double {
        val c1 = 1.70158
        val c3 = 2.70158
        return 1.0 + c3 * Math.pow(t - 1.0, 3.0) + c1 * Math.pow(t - 1.0, 2.0)
    }

    @JvmStatic
    fun easeInBack(t: Double): Double {
        val c1 = 1.70158
        val c3 = 2.70158
        return c3 * t * t * t - c1 * t * t
    }

    @JvmStatic
    fun smoothSine(t: Double): Double = Math.sin(t * Math.PI / 2.0)

    @JvmStatic
    fun easeInCubic(t: Double): Double = t * t * t

    @JvmStatic
    fun smoothStep(t: Double): Double = -(Math.cos(Math.PI * t) - 1.0) / 2.0

    @JvmStatic
    fun easeInQuad(t: Double): Double = 1.0 - Math.cos(Math.PI * t / 2.0)

    @JvmStatic
    fun lerpAngle(from: Double, to: Double, t: Double): Double {
        val smooth = (1.0 - Math.cos(t * Math.PI)) / 2.0
        return from * (1.0 - smooth) + to * smooth
    }
}
