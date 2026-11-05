package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.util.WorldUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * Bed-side placement for the beddable girls: the four candidate stand
 * positions around a bed ([SIDE_OFFSETS] with the matching
 * [SIDE_YAWS]) and the scan that picks the free side nearest the girl.
 *
 * Extracted from `JennyEntity.goToSexBed`, `LunaEntity.goToSexBed`
 * and Bia's `getBedVector`/`findNearestBedVector`, which each
 * carried a verbatim copy of both the table and the loop. The callers keep
 * their own "no bed" dialogue, sounds and follow-up state; only the geometry
 * and the selection rule live here.
 */
object GirlBedPlacement {
    /** Yaw the girl faces on each bed side; index-aligned with [SIDE_OFFSETS]. */
    @JvmField
    val SIDE_YAWS: IntArray = intArrayOf(0, 180, -90, 90)

    /**
     * Per bed side: `[0]` = where the girl stands, `[1]` = the
     * direction of the block that must be free (the open side of the bed).
     */
    @JvmField
    val SIDE_OFFSETS: Array<Array<Vec3>> = arrayOf(
        arrayOf(Vec3(0.5, 0.0, -0.5), Vec3(0.0, 0.0, -1.0)),
        arrayOf(Vec3(0.5, 0.0, 1.5), Vec3(0.0, 0.0, 1.0)),
        arrayOf(Vec3(-0.5, 0.0, 0.5), Vec3(-1.0, 0.0, 0.0)),
        arrayOf(Vec3(1.5, 0.0, 0.5), Vec3(1.0, 0.0, 0.0)),
    )

    /**
     * @return index of the nearest bed side whose open block is air, or -1 when
     *         every side is blocked
     */
    @JvmStatic
    fun findFreeSide(girl: BaseGirlEntity, bedVec: Vec3, sides: Array<Array<Vec3>>): Int {
        return findFreeSide(girl, bedVec, sides, false)
    }

    /**
     * As [findFreeSide], additionally requiring that a structure can be
     * placed behind the side (Bia's bed rule).
     */
    @JvmStatic
    fun findFreeSideWithRoom(girl: BaseGirlEntity, bedVec: Vec3, sides: Array<Array<Vec3>>): Int {
        return findFreeSide(girl, bedVec, sides, true)
    }

    private fun findFreeSide(girl: BaseGirlEntity, bedVec: Vec3, sides: Array<Array<Vec3>>, requireRoom: Boolean): Int {
        var bestIndex = -1

        for (i in sides.indices) {
            val openVec = bedVec.add(sides[i][1])
            // 1.12 `getBlock() == Blocks.AIR`; 1.21 also has cave/void air, which isAir covers
            var free = girl.level().getBlockState(BlockPos.containing(openVec.x, openVec.y, openVec.z)).isAir
            if (free && requireRoom) {
                val roomVec = bedVec.subtract(sides[i][1])
                free = WorldUtils.canPlaceStructure(girl.level(), BlockPos.containing(roomVec))
            }

            if (!free) {
                continue
            }

            if (bestIndex == -1) {
                bestIndex = i
            } else {
                val bestVec = bedVec.add(sides[bestIndex][0])
                val sideVec = bedVec.add(sides[i][0])
                val bestDist = girl.blockPosition().distToLowCornerSqr(bestVec.x, bestVec.y, bestVec.z)
                val dist = girl.blockPosition().distToLowCornerSqr(sideVec.x, sideVec.y, sideVec.z)
                if (dist < bestDist) {
                    bestIndex = i
                }
            }
        }

        return bestIndex
    }
}
