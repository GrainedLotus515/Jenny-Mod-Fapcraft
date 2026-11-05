package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.util.WorldUtils
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.server.ServerLifecycleHooks

/**
 * Replacement for 1.12 `NetworkRegistry.TargetPoint`: a point plus a range in a
 * dimension identified by the 1.12 integer id (`WorldUtils.getDimensionId`).
 * Used by [PacketHandler.NetworkWrapper.sendToAllAround] / `sendToAllTracking`.
 */
class TargetPoint(
    @JvmField val dimension: Int,
    @JvmField val x: Double,
    @JvmField val y: Double,
    @JvmField val z: Double,
    @JvmField val range: Double,
) {
    /** The server level of [dimension], or `null` if no server is running or the id is unknown. */
    fun getLevel(): ServerLevel? {
        val server = ServerLifecycleHooks.getCurrentServer() ?: return null
        return WorldUtils.getLevelById(server, dimension)
    }
}
