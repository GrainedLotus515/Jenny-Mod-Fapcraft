package com.trolmastercard.sexmod.client.renderer

import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.VectorMath
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Visibility gate for girl rendering and the voxel ray-trace it stands on.
 *
 * **Occlusion gate.** [canRender]
 * hides the girl in first person when a solid block occludes every corner of
 * her bounding box; translucent / non-solid blocks count as visible.
 * Player-girls always render.
 *
 * **Ray trace.** [rayTraceBlocks] mirrors vanilla
 * voxel-DDA semantics with NaN guards and only accepts blocks on the
 * solid chunk layer, so glass and foliage do not occlude.
 *
 * CLIENT-side render thread only (reads world state, draws nothing).
 *
 * Port notes: 1.12 `BlockRenderLayer.SOLID` is `ItemBlockRenderTypes.getChunkRenderType(state) == RenderType.solid()`;
 * `getCollisionBoundingBox != NULL_AABB` is a non-empty collision shape; `isTranslucent()` is `!canOcclude()`;
 * `Material.PORTAL` is the nether portal block; `collisionRayTrace` is `VoxelShape.clip`.
 */
object RenderOcclusion {
    private fun isSolidLayer(state: BlockState): Boolean = ItemBlockRenderTypes.getChunkRenderType(state) === RenderType.solid()

    /**
     * First-person occlusion gate: the girl is only rendered when at least one
     * corner of her bounding box has an unobstructed (or translucent/non-solid)
     * ray from the player's eye. Player-girls always render. In third person
     * the camera position is moved behind a solid block when needed.
     *
     * @param thirdPersonView the local player's 1.12 `thirdPersonView` (0 first, 1 back, 2 front)
     * @return `true` if the girl may be drawn
     */
    @JvmStatic
    fun canRender(girl: BaseGirlEntity, player: Player, thirdPersonView: Int): Boolean {
        if (girl is AbstractPlayerGirlEntity) {
            return true
        }

        if (thirdPersonView != 0) {
            return true
        }

        val world = girl.level()
        val girlPos = girl.position()
        val halfWidth = girl.bbWidth * 1.5f
        val height = girl.bbHeight * 1.5f
        var eyePos = player.position().add(0.0, player.eyeHeight.toDouble(), 0.0)
        if (thirdPersonView > 0) {
            val yaw = player.yRot
            var pitch = player.xRot
            if (thirdPersonView == 2) {
                pitch += 180.0f
            }

            val distance = 4.0f
            val cameraPos = eyePos.add(
                (Mth.sin(yaw * (Math.PI / 180.0).toFloat()) * Mth.cos(pitch * (Math.PI / 180.0).toFloat()) * distance).toDouble(),
                (Mth.sin(pitch * (Math.PI / 180.0).toFloat()) * distance).toDouble(),
                (-Mth.cos(yaw * (Math.PI / 180.0).toFloat()) * Mth.cos(pitch * (Math.PI / 180.0).toFloat()) * distance).toDouble(),
            )
            val cameraBlock = BlockPos.containing(cameraPos)
            val isAir = world.isEmptyBlock(cameraBlock)
            if (!isAir) {
                eyePos = cameraPos
            } else if (world.isEmptyBlock(cameraBlock.offset(0, 1, 0))) {
                eyePos = Vec3(cameraPos.x, (cameraBlock.y + 1).toDouble(), cameraPos.z)
            }
        }

        val corners = arrayOf(
            girlPos.add((-halfWidth / 2.0f).toDouble(), 0.0, (-halfWidth / 2.0f).toDouble()),
            girlPos.add((-halfWidth / 2.0f).toDouble(), 0.0, (halfWidth / 2.0f).toDouble()),
            girlPos.add((halfWidth / 2.0f).toDouble(), 0.0, (-halfWidth / 2.0f).toDouble()),
            girlPos.add((halfWidth / 2.0f).toDouble(), 0.0, (halfWidth / 2.0f).toDouble()),
            girlPos.add((-halfWidth / 2.0f).toDouble(), height.toDouble(), (-halfWidth / 2.0f).toDouble()),
            girlPos.add((-halfWidth / 2.0f).toDouble(), height.toDouble(), (halfWidth / 2.0f).toDouble()),
            girlPos.add((halfWidth / 2.0f).toDouble(), height.toDouble(), (-halfWidth / 2.0f).toDouble()),
            girlPos.add((halfWidth / 2.0f).toDouble(), height.toDouble(), (halfWidth / 2.0f).toDouble()),
        )

        for (corner in corners) {
            val hit = rayTraceBlocks(eyePos, corner, world) ?: return true

            val blockState = world.getBlockState(hit.blockPos)
            if (!blockState.canOcclude()) {
                return true
            }

            if (!isSolidLayer(blockState)) {
                return true
            }
        }

        return false
    }

    /**
     * Distance to the first solid block along the view ray, capped at 4 blocks.
     */
    @JvmStatic
    fun rayTraceBoneDistance(world: Level, eyePos: Vec3, yaw: Float, pitch: Float): Float {
        val hit = rayTraceBlocks(eyePos, eyePos.add(VectorMath.rotateByYawPitch(Vec3(0.0, 0.0, -4.0), yaw, pitch)), world)
            ?: return 4.0f

        val hitVec = hit.location
        return eyePos.distanceTo(hitVec).toFloat()
    }

    /**
     * Block ray-trace (voxel DDA, mirrors vanilla semantics) with NaN guards —
     * used for the occlusion check. Returns the first solid block hit or
     * `null` (NaN inputs or no hit within 200 steps).
     */
    @JvmStatic
    fun rayTraceBlocks(start: Vec3, end: Vec3, world: Level): BlockHitResult? {
        if (java.lang.Double.isNaN(start.x) || java.lang.Double.isNaN(start.y) || java.lang.Double.isNaN(start.z)) {
            return null
        }

        if (!java.lang.Double.isNaN(end.x) && !java.lang.Double.isNaN(end.y) && !java.lang.Double.isNaN(end.z)) {
            val endX = Mth.floor(end.x)
            val endY = Mth.floor(end.y)
            val endZ = Mth.floor(end.z)
            var currentX = Mth.floor(start.x)
            var currentY = Mth.floor(start.y)
            var currentZ = Mth.floor(start.z)
            val currentPos = BlockPos.MutableBlockPos(currentX, currentY, currentZ)
            val blockState = world.getBlockState(currentPos)
            val startShape = blockState.getCollisionShape(world, currentPos)
            if (!startShape.isEmpty && isSolidLayer(blockState)) {
                return startShape.clip(start, end, currentPos.immutable())
            }

            var steps = 200
            // scratch: the ray origin is advanced per step; keep it in doubles and
            // build the Vec3 only on an actual hit, same arithmetic as before
            var startX = start.x
            var startY = start.y
            var startZ = start.z

            while (steps-- >= 0) {
                if (java.lang.Double.isNaN(startX) || java.lang.Double.isNaN(startY) || java.lang.Double.isNaN(startZ)) {
                    return null
                }

                if (currentX == endX && currentY == endY && currentZ == endZ) {
                    return null
                }

                var stepX = true
                var stepY = true
                var stepZ = true
                var nextX = 999.0
                var nextY = 999.0
                var nextZ = 999.0
                if (endX > currentX) {
                    nextX = currentX + 1.0
                } else if (endX < currentX) {
                    nextX = currentX + 0.0
                } else {
                    stepX = false
                }

                if (endY > currentY) {
                    nextY = currentY + 1.0
                } else if (endY < currentY) {
                    nextY = currentY + 0.0
                } else {
                    stepY = false
                }

                if (endZ > currentZ) {
                    nextZ = currentZ + 1.0
                } else if (endZ < currentZ) {
                    nextZ = currentZ + 0.0
                } else {
                    stepZ = false
                }

                var tMaxX = 999.0
                var tMaxY = 999.0
                var tMaxZ = 999.0
                val deltaX = end.x - startX
                val deltaY = end.y - startY
                val deltaZ = end.z - startZ
                if (stepX) {
                    tMaxX = (nextX - startX) / deltaX
                }

                if (stepY) {
                    tMaxY = (nextY - startY) / deltaY
                }

                if (stepZ) {
                    tMaxZ = (nextZ - startZ) / deltaZ
                }

                if (tMaxX == -0.0) {
                    tMaxX = -1.0E-4
                }

                if (tMaxY == -0.0) {
                    tMaxY = -1.0E-4
                }

                if (tMaxZ == -0.0) {
                    tMaxZ = -1.0E-4
                }

                val facing: Direction
                if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                    facing = if (endX > currentX) Direction.WEST else Direction.EAST
                    startX = nextX
                    startY += deltaY * tMaxX
                    startZ += deltaZ * tMaxX
                } else if (tMaxY < tMaxZ) {
                    facing = if (endY > currentY) Direction.DOWN else Direction.UP
                    startX += deltaX * tMaxY
                    startY = nextY
                    startZ += deltaZ * tMaxY
                } else {
                    facing = if (endZ > currentZ) Direction.NORTH else Direction.SOUTH
                    startX += deltaX * tMaxZ
                    startY += deltaY * tMaxZ
                    startZ = nextZ
                }

                currentX = Mth.floor(startX) - (if (facing == Direction.EAST) 1 else 0)
                currentY = Mth.floor(startY) - (if (facing == Direction.UP) 1 else 0)
                currentZ = Mth.floor(startZ) - (if (facing == Direction.SOUTH) 1 else 0)
                currentPos.set(currentX, currentY, currentZ)
                val hitState = world.getBlockState(currentPos)
                val hitShape = hitState.getCollisionShape(world, currentPos)
                if ((hitState.`is`(Blocks.NETHER_PORTAL) || !hitShape.isEmpty) && isSolidLayer(hitState)) {
                    // PORT-GAP: a portal has an empty collision shape, so clip() yields no hit for it (1.12 used the selection box)
                    return hitShape.clip(Vec3(startX, startY, startZ), end, currentPos.immutable())
                }
            }

            return null
        } else {
            return null
        }
    }
}
