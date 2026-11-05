package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import net.minecraft.world.phys.Vec3
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Random
import java.util.UUID

/**
 * **Role.** General math/utility helpers despite the name — angle wrapping,
 * look-angle computation, clipboard, weighted random indices, clamps,
 * sign/float helpers and [createDaemonThread] for
 * delayed background work (e.g. particle/cleanup timers) named after the side
 * it runs on (see [ClientServerCheck]).
 *
 * **Pitfall.** [moveToward] implements *step* movement (one step of
 * `step` toward the target), not progress lerp — call sites rely on the
 * stepping semantics.
 */
object ThreadNames {
    @JvmStatic
    fun wrapAngle(angle: Double, target: Double): Float {
        val a = (angle + (Math.PI * 2)) % (Math.PI * 2)
        val t = (target + (Math.PI * 2)) % (Math.PI * 2)
        var diff = t - a

        while (diff < -Math.PI) {
            diff += Math.PI * 2
        }

        while (diff >= Math.PI) {
            diff -= Math.PI * 2
        }

        return diff.toFloat()
    }

    @JvmStatic
    fun getLookAngles(from: Vec3, to: Vec3): Vector2f {
        val delta = to.subtract(from).normalize()
        return Vector2f(
            Math.atan2(delta.x, delta.z).toFloat(),
            Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)).toFloat()
        )
    }

    @JvmStatic
    fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, null)
    }

    @JvmStatic
    fun capitalizeFirst(text: String?): String? =
        if (text != null && text.isNotEmpty()) Character.toUpperCase(text[0]) + text.substring(1).lowercase() else text

    @JvmStatic
    fun isBetween(value: Double, min: Double, max: Double): Boolean =
        if (value < min) false else !(value >= max)

    /**
     * Picks an index in `0..maxIndex` for a list sorted best-first,
     * weighted by rank: index 0 has weight `maxIndex + 1` down to 1 for the
     * last index. The best candidate is therefore the most likely and index 0 is
     * always reachable (callers pass sorted candidates; see
     * `GalathFlightData.CHANGE_POSITION`).
     */
    @JvmStatic
    fun weightedRandomIndex(maxIndex: Int): Int {
        if (maxIndex <= 0) {
            return maxIndex
        }

        val random = Random()
        var totalWeight = 0

        for (i in 0..maxIndex) {
            totalWeight += maxIndex + 1 - i
        }

        val roll = random.nextInt(totalWeight) + 1
        var cumulative = 0

        for (i2 in 0..maxIndex) {
            cumulative += maxIndex + 1 - i2
            if (cumulative >= roll) {
                return i2
            }
        }

        return maxIndex
    }

    @JvmStatic
    fun randomSign(): Int = if (Reference.RANDOM.nextBoolean()) 1 else -1

    @JvmStatic
    fun clampFloat(value: Float, min: Float, max: Float): Float = Math.max(min, Math.min(max, value))

    @JvmStatic
    fun clampDouble(value: Double, min: Double, max: Double): Double = Math.max(min, Math.min(max, value))

    @JvmStatic
    fun randomSignedFloat(magnitude: Float, allowNegative: Boolean): Float {
        val random = Random()
        return random.nextFloat() * magnitude * (if (allowNegative && random.nextBoolean()) -1 else 1)
    }

    @JvmStatic
    fun moveToward(current: Float, target: Float, step: Float): Float {
        return if (Math.abs(current - target) <= step) {
            current
        } else if (Math.abs(current) < Math.abs(target)) {
            if (target > 0.0f) target - step else target + step
        } else {
            if (current > 0.0f) current - step else current + step
        }
    }

    @JvmStatic
    fun roundToInt(value: Double): Int = Math.round(value.toFloat())

    @JvmStatic
    fun createDaemonThread(priority: Int, runnable: Runnable) {
        val threadName = UUID.randomUUID().toString()
        Thread({
            try {
                Thread.sleep(priority.toLong())
            } catch (exception: Exception) {
                Main.LOGGER.warn("interrupted while delaying the sexmod thread start", exception)
            }

            runnable.run()
        }, (if (ClientServerCheck.getInstance()) "server sexmod thread " else "client sexmod thread ") + threadName).start()
    }
}
