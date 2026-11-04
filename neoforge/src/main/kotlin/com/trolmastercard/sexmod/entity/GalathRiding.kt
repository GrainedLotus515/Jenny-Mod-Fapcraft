package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.util.ClientServerCheck
import com.trolmastercard.sexmod.util.Reference
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> Galath's riding and camera helpers, split out of
 * [GalathEntity]: gravity while somebody sits on her, the render yaw she
 * copies from her rider, the boost impulse, the master-facing pitch and the
 * teleport that puts her under a player.
 *
 * <b>Flow.</b> [GalathEntity.tick] calls [updateRidingGravity],
 * `tickTamedSystems` the rest; the tamed AI calls [handlePlayerRide]
 * when the owner is too far away to walk to.
 */
object GalathRiding {
    @JvmStatic
    fun updateRidingGravity(galath: GalathEntity) {
        galath.setNoGravity(galath.getRidingPlayer() != null)
    }

    @JvmStatic
    fun syncRenderYawToRider(galath: GalathEntity) {
        val rider = galath.getRidingPlayer()
        if (rider != null) {
            galath.yBodyRotO = rider.yHeadRotO
            galath.yBodyRot = rider.yHeadRot
        }
    }

    @JvmStatic
    fun tickBoostFlight(galath: GalathEntity) {
        val player = galath.getRidingPlayer()
        val action = galath.getCurrentAction()
        if (player != null) {
            if (action == Action.BOOST) {
                val sideIndex = if (ClientServerCheck.getInstance()) 0 else 1
                if (action.ticksPlaying[sideIndex] >= 13) {
                    if (action.ticksPlaying[sideIndex] == 13) {
                        galath.boostSpeed = 6.0f
                    }

                    val look = player.getViewVector(0.0f).normalize()
                    galath.setDeltaMovement(look.x * galath.boostSpeed, look.y * galath.boostSpeed, look.z * galath.boostSpeed)
                    galath.boostSpeed *= 0.94f
                }
            }
        }
    }

    @JvmStatic
    fun updateMasterLookPitch(galath: GalathEntity) {
        if (galath.level().isClientSide) {
            if (galath.getRidingPlayer() == null) {
                val master = galath.getMasterPlayer()
                if (master != null) {
                    handleGalathPlayer(galath, master)
                }
            }
        }
    }

    @JvmStatic
    fun handleGalathPlayer(galath: GalathEntity, player: Player) {
        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player.uuid)
        val headPos = Vec3(player.x, player.y + (playerGirl?.eyeHeight ?: player.eyeHeight), player.z)
        val eyePos = Vec3(galath.x, galath.y + galath.eyeHeight, galath.z)
        val dist = eyePos.distanceTo(headPos)
        val heightDiff = headPos.y - eyePos.y
        galath.setXRot((-(Math.sin(heightDiff / dist) * (180.0 / Math.PI))).toFloat())
    }

    @JvmStatic
    fun handlePlayerRide(galath: GalathEntity, player: Player) {
        var attempts = 0

        var teleportPos: BlockPos
        do {
            teleportPos = player.blockPosition().offset(Reference.RANDOM.nextInt(4), 0, Reference.RANDOM.nextInt(4))
            attempts++
        } while (attempts < 20 && !galath.randomTeleport(teleportPos.x.toDouble(), teleportPos.y.toDouble(), teleportPos.z.toDouble(), false))

        if (attempts >= 20) {
            galath.setPos(player.x, player.y, player.z)
        }

        galath.setDeltaMovement(0.0, 0.0, 0.0)
    }
}
