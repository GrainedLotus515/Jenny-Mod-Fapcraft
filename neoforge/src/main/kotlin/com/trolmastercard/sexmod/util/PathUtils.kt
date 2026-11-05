package com.trolmastercard.sexmod.util

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path

/**
 * Pathfinding helpers for girl movement goals.
 */
object PathUtils {
    @JvmStatic
    fun getPathEnd(path: Path?): BlockPos {
        if (path == null) {
            return BlockPos.ZERO
        }

        val endPoint = path.endNode
        return if (endPoint == null) BlockPos.ZERO else BlockPos(endPoint.x, endPoint.y, endPoint.z)
    }

    @JvmStatic
    fun getNavigatorTarget(entity: Mob): BlockPos {
        val navigator = entity.navigation
        val path = navigator.path
        return getPathEnd(path)
    }

    @JvmStatic
    fun isPathValid(path: Path, targets: Array<BlockPos>): Boolean {
        val length = path.nodeCount
        val points = ArrayList<Node>()

        for (i in 0 until length) {
            points.add(path.getNode(i))
        }

        for (point in points) {
            for (target in targets) {
                if (point.x == target.x && point.y == target.y && point.z == target.z) {
                    return true
                }
            }
        }

        return false
    }
}
