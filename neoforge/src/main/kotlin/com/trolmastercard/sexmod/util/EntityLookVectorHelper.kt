package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * **Role.** CLIENT-side look/aim vector helpers for girl renderers and item
 * aiming. Computes entity-to-player and aim direction vectors with partial-tick
 * interpolation; anchored girls aim at their `TARGET_POS` instead of their
 * yaw so scene animations look at the correct spot.
 *
 * COMMON class (no client types): renderers call it, but it only does maths.
 *
 * **Pitfall.** All lerps here use [RotationHelper.lerpVec3dDouble]
 * (PROGRESS lerp by partial tick) — the correct choice for render interpolation.
 */
object EntityLookVectorHelper {
    @JvmStatic
    fun getLookVectorTo(entity: Entity, player: Player, partialTicks: Float): Vec3 {
        val entityPos = RotationHelper.lerpVec3dDouble(
            Vec3(entity.xOld, entity.yOld + player.eyeHeight, entity.zOld),
            entity.position().add(0.0, player.eyeHeight.toDouble(), 0.0),
            partialTicks.toDouble()
        )
        val playerPos = RotationHelper.lerpVec3dDouble(Vec3(player.xOld, player.yOld, player.zOld), player.position(), partialTicks.toDouble())
        return entityPos.subtract(playerPos)
    }

    @JvmStatic
    fun getAimVector(entity: Entity, player: Player?, partialTicks: Float): Vec3 {
        val lookVec = getEntityLookVector(entity, partialTicks)
        if (player == null) {
            return lookVec
        }

        val playerLookVec = getEntityLookVector(player, partialTicks)
        return lookVec.subtract(playerLookVec)
    }

    @JvmStatic
    fun getEntityLookVector(entity: Entity, partialTicks: Float): Vec3 {
        if (entity !is BaseGirlEntity) {
            return getLookVectorYaw(entity, partialTicks)
        }

        val girl = entity
        return if (!girl.isAnchored()) getLookVectorYaw(entity, partialTicks) else girl.getTargetPosition()
    }

    @JvmStatic
    fun getLookVectorYaw(entity: Entity, partialTicks: Float): Vec3 =
        RotationHelper.lerpVec3dDouble(Vec3(entity.xOld, entity.yOld, entity.zOld), entity.position(), partialTicks.toDouble())

    /**
     * PORT-GAP: 1.12 forced the lightmap texture coordinates to 240/240 with
     * `OpenGlHelper.setLightmapTextureCoords`. 1.21 has no global lightmap
     * state; full-bright rendering is requested per vertex with
     * `LightTexture.FULL_BRIGHT` as the packed light. Kept as a no-op.
     */
    @JvmStatic
    fun setFullBrightLightmap() {
    }
}
