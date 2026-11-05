package com.trolmastercard.sexmod.util

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> One particle segment of a [DynamicTrailRenderer] trail:
 * integrates velocity/position with gravity + air resistance
 * ([onUpdate]) and resolves block collisions by sliding along the
 * blocking axis (Bresenham line check), then parks the segment until reset.
 *
 * Naming (kept from the deobfuscated jar): [velocity] is the current world
 * position, [offset] the previous one and [position] the velocity.
 *
 * <b>Pitfalls.</b> [onUpdate] does NOTHING while [position] is
 * zero (parked state) — that is the collision stop, not a bug. The 0.95 decay /
 * 0.4905 gravity constants are tuned together; changing one without the other
 * alters trail length and bounce behavior. [bresenhamLine] must include
 * both endpoints for the collision slide to be exact.
 */
open class TrailSegment(world: Level, velocity: Vec3, position: Vec3) {
    @JvmField
    var world: Level = world

    @JvmField
    var offset: Vec3 = velocity

    @JvmField
    var velocity: Vec3 = velocity

    @JvmField
    var position: Vec3 = position

    open fun onUpdate() {
        if (Vec3.ZERO == this.position) {
            this.offset = this.velocity
        } else {
            this.position = Vec3(
                this.position.x * 0.95f,
                (this.position.y - 0.49050003f) * 0.95f,
                this.position.z * 0.95f,
            )
            this.offset = this.velocity
            this.velocity = Vec3(
                this.velocity.x + this.position.x * 0.05f,
                this.velocity.y + this.position.y * 0.05f,
                this.velocity.z + this.position.z * 0.05f,
            )
            var hitPos = BlockPos.containing(this.offset)
            var lastHitPos: BlockPos? = null

            for (blockPos in bresenhamLine(BlockPos.containing(this.offset), BlockPos.containing(this.velocity))) {
                if (!this.world.getBlockState(blockPos).`is`(Blocks.AIR)) {
                    lastHitPos = blockPos
                    break
                }

                hitPos = blockPos
            }

            if (lastHitPos != null) {
                val hitX = lastHitPos.x
                val lastX = hitPos.x
                if (hitX - lastX != 0) {
                    val maxX = Math.max(hitX, lastX).toDouble()
                    val slopeXY = (this.offset.y - this.velocity.y) / (this.offset.x - this.velocity.x)
                    val interceptY = this.velocity.y - slopeXY * this.velocity.x
                    val y = slopeXY * maxX + interceptY
                    val slopeZY = (this.offset.z - this.velocity.z) / (this.offset.x - this.velocity.x)
                    val interceptZ = this.velocity.z - slopeZY * this.velocity.x
                    val z = slopeZY * maxX + interceptZ
                    this.velocity = Vec3(maxX + 0.03f * (if (hitX > lastX) -1 else 1), y, z)
                    this.position = Vec3(0.0, 0.0, 0.0)
                } else {
                    val hitY = lastHitPos.y
                    val lastY = hitPos.y
                    if (hitY - lastY != 0) {
                        val maxY = Math.max(hitY, lastY).toDouble()
                        val slopeXY2 = (this.offset.x - this.velocity.x) / (this.offset.y - this.velocity.y)
                        val x = this.velocity.x - slopeXY2 * this.velocity.y
                        val x2 = slopeXY2 * maxY + x
                        val slopeZY2 = (this.offset.z - this.velocity.z) / (this.offset.y - this.velocity.y)
                        val z2 = this.velocity.z - slopeZY2 * this.velocity.y
                        val z3 = slopeZY2 * maxY + z2
                        this.velocity = Vec3(x2, maxY + 0.03f * (if (hitY > lastY) -1 else 1), z3)
                        this.position = Vec3(0.0, 0.0, 0.0)
                    } else {
                        val hitZ = lastHitPos.z
                        val lastZ = hitPos.z
                        if (hitZ - lastZ != 0) {
                            val maxZ = Math.max(hitZ, lastZ).toDouble()
                            val slopeYZ = (this.offset.y - this.velocity.y) / (this.offset.z - this.velocity.z)
                            val y2 = this.velocity.y - slopeYZ * this.velocity.z
                            val y3 = slopeYZ * maxZ + y2
                            val slopeXZ = (this.offset.x - this.velocity.x) / (this.offset.z - this.velocity.z)
                            val x3 = this.velocity.x - slopeXZ * this.velocity.z
                            val x4 = slopeXZ * maxZ + x3
                            this.velocity = Vec3(x4, y3, maxZ + 0.03f * (if (hitZ > lastZ) -1 else 1))
                            this.position = Vec3(0.0, 0.0, 0.0)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val GRAVITY: Float = 9.81f
        const val TIME_DELTA: Float = 0.05f
        const val AIR_RESISTANCE: Float = 0.05f
        const val COLLISION_OFFSET: Float = 0.03f

        @JvmStatic
        fun bresenhamLine(start: BlockPos, end: BlockPos): List<BlockPos> {
            val points = ArrayList<BlockPos>()
            points.add(start)
            val x0 = start.x
            val y0 = start.y
            val z0 = start.z
            val x1 = end.x
            val y1 = end.y
            val z1 = end.z
            val dx = Math.abs(x1 - x0)
            val dy = Math.abs(y1 - y0)
            val dz = Math.abs(z1 - z0)
            val sx = if (x0 < x1) 1 else -1
            val sy = if (y0 < y1) 1 else -1
            val sz = if (z0 < z1) 1 else -1
            val maxStep = Math.max(dx, Math.max(dy, dz))
            var x = x0
            var y = y0
            var z = z0
            var errX = maxStep / 2
            var errY = maxStep / 2
            var errZ = maxStep / 2

            for (i in 0 until maxStep) {
                points.add(BlockPos(x, y, z))
                errX -= dx
                errY -= dy
                errZ -= dz
                if (errX < 0) {
                    x += sx
                    errX += maxStep
                } else if (errY < 0) {
                    y += sy
                    errY += maxStep
                } else if (errZ < 0) {
                    z += sz
                    errZ += maxStep
                }
            }

            points.add(end)
            return points
        }
    }
}
