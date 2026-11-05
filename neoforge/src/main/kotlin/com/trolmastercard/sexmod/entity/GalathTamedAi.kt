package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.GirlSavedData
import com.trolmastercard.sexmod.util.PathUtils
import com.trolmastercard.sexmod.util.TrigMath
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> Galath's tamed-side AI wiring, split out of
 * [GalathEntity]: the owner-nearby gate, the Manglelie partnership hunt
 * (walk up to an unpartnered Manglelie and hug her), the on-foot owner follow
 * (path/speed/sprint bookkeeping) and the flight reset that hands control back
 * to the flight state machine.
 *
 * <b>Flow.</b> [GalathEntity.updateAITasks] calls [tickTamedAi] when she has
 * a master; [tickPartneredFollow] falls back to the flight AI
 * ([GalathEntity.tickFlightSystems]) when the owner cannot be
 * followed on foot.
 *
 * <b>Pitfalls.</b> [followOwnerOnFoot] mutates
 * `currentPath` and the navigator speed; it must always leave
 * `activeFlight` null before the flight machine picks again.
 */
object GalathTamedAi {
    @JvmStatic
    fun tickTamedAi(galath: GalathEntity) {
        galath.bossBar.isVisible = false
        if (!GirlSavedData.isOwnerNearby(galath)) {
            GirlSavedData.updateMangleliePartner(galath)
        } else if (galath.getRidingPlayer() != null) {
            clearFlightData(galath)
        } else {
            handleManglelieOwned(galath)
            if (galath.getMangleliePartnerUUID() == null) {
                tickUnpartneredAi(galath)
            } else {
                tickPartneredFollow(galath)
            }
        }
    }

    @JvmStatic
    fun handleManglelieOwned(galath: GalathEntity) {
        if (GirlSavedData.isManglelieOwned(GirlSavedData.getManglelieOwnerOf(galath))) {
            val canLick = galath.canStartPussyLicking()
            if (canLick) {
                Main.LOGGER.warn("mommy thinks she got no daughter but she actually does have one. Failsafe called. Hopefully its fixed")
            }
        }
    }

    @JvmStatic
    fun tickPartneredFollow(galath: GalathEntity) {
        if (!followOwnerOnFoot(galath)) {
            galath.entityData.set(GalathEntity.SPRINTING, false)
            galath.tickFlightSystems()
        }
    }

    @JvmStatic
    fun followOwnerOnFoot(galath: GalathEntity): Boolean {
        val ownerUuid = GirlSavedData.getManglelieOwnerOf(galath) ?: return false

        val owner = galath.level().getPlayerByUUID(ownerUuid) ?: return false

        val ownerPos = owner.blockPosition()
        if (!isFlightBlocked(galath, ownerPos)) {
            return false
        }

        if (galath.activeFlight != null) {
            galath.activeFlight?.updateFlight(galath)
            galath.activeFlight = null
        }

        val dist = galath.distanceTo(owner)
        val navigator = galath.getNavigation()
        if (dist < 4.0f) {
            navigator.stop()
            return false
        }

        if (dist > 16.0f) {
            navigator.stop()
            galath.handlePlayerRide(owner)
            return true
        }

        if (PathUtils.getPathEnd(galath.currentPath).distSqr(ownerPos) > 16.0) {
            if (!galath.onGround()) {
                return true
            }

            galath.currentPath = getPathToPlayer(galath, owner, ownerPos)
            val path = galath.currentPath
            if (path == null) {
                galath.handlePlayerRide(owner)
            } else {
                navigator.moveTo(path, 1.0)
            }
        }

        val path = galath.currentPath
        if (path != null && !path.isDone) {
            val sprinting = owner.isSprinting || galath.distanceTo(owner) > 7.0f
            var speed = if (sprinting) 0.55f.toDouble() else 0.5
            val extra = Math.floor((dist / 5.0f).toDouble()) * 0.2
            speed += extra
            if (galath.isInWater) {
                speed *= 60.0
            }

            navigator.setSpeedModifier(speed)
            galath.entityData.set(GalathEntity.SPRINTING, sprinting)
            galath.setCurrentAction(null)
            return true
        } else {
            return false
        }
    }

    @JvmStatic
    fun isFlightBlocked(galath: GalathEntity, pos: BlockPos): Boolean {
        if (galath.activeFlight == null) {
            return true
        }

        val selfPos = galath.blockPosition()
        // (sic) the Java adds the X distance twice
        val dist = Math.abs(pos.x - selfPos.x) + Math.abs(pos.x - selfPos.x)
        return dist > 16
    }

    @JvmStatic
    fun tickSeekMangleliePartner(galath: GalathEntity) {
        if (galath.onGround()) {
            if (galath.getMangleliePartnerUUID() == null) {
                if (galath.getCurrentAction() != Action.HUG_MANG) {
                    if (!GirlSavedData.isManglelieOwned(GirlSavedData.getManglelieOwnerId(galath.getGirlId()))) {
                        val center = galath.blockPosition()
                        val min = center.offset(-15, -15, -15)
                        val max = center.offset(15, 15, 15)
                        val aabb = AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(max))
                        val manglelies = galath.level().getEntitiesOfClass(ManglelieEntity::class.java, aabb)
                        var chosen: ManglelieEntity? = null

                        for (manglelie in manglelies) {
                            if (manglelie.isAlive && manglelie.getGalathPartner(true) == null) {
                                chosen = manglelie
                                break
                            }
                        }

                        if (chosen == null) {
                            if (galath.getCurrentAction() == Action.RUN) {
                                galath.setCurrentAction(null)
                                galath.getNavigation().stop()
                            }
                        } else {
                            galath.pathNavigator = galath.getNavigation()
                            if (chosen.distanceTo(galath) <= 3.65f) {
                                galath.pathNavigator.stop()
                                galath.setCurrentAction(Action.HUG_MANG)
                                galath.setDeltaMovement(0.0, 0.0, 0.0)
                                galath.setTargetPosition(galath.position())
                                galath.setAnchored(true)
                                galath.setMangleliePartnerUUID(chosen.getGirlId())
                                chosen.setGalathPartnerUUID(galath.getGirlId())
                                chosen.setCurrentAction(Action.RIDE_MOMMY_HEAD)
                                GirlSavedData.markAsManglelieOwned(galath.getGirlId())
                            } else {
                                val selfPos = galath.position()
                                val partnerPos = chosen.position()
                                val delta = partnerPos.subtract(selfPos)
                                val yaw = TrigMath.sinDegrees(Math.atan2(delta.z, delta.x)).toFloat() - 90.0f
                                galath.setYawRotation(yaw)
                                galath.pathNavigator.stop()
                                galath.pathNavigator.moveTo(chosen, 0.65f.toDouble())
                                galath.setCurrentAction(Action.RUN)
                            }
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun tickUnpartneredAi(galath: GalathEntity) {
        tickSeekMangleliePartner(galath)
        galath.wanderRandomly()
    }

    @JvmStatic
    fun clearFlightData(galath: GalathEntity) {
        galath.wanderTarget = null
        galath.wanderTicks = 0
        if (galath.activeFlight != null) {
            galath.activeFlight?.updateFlight(galath)
            galath.activeFlight = null
        }
    }

    @JvmStatic
    fun getPathToPlayer(galath: GalathEntity, player: Player, pos: BlockPos): Path? {
        val navigator = galath.getNavigation()
        return navigator.createPath(player, 0)
    }
}
