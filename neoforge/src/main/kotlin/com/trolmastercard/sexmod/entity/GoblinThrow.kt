package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.entity.api.IGoblin
import com.trolmastercard.sexmod.util.WorldUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * Carry / throw mechanics shared by [GoblinEntity] and (through [IGoblin])
 * `GoblinPlayerEntity`: owner head-look, tamed guard wandering, shoulder
 * glue, the 15/39-tick throw flight, pick-up glue and the yaw/pitch vector
 * rotation used by the renderers.
 *
 * 1.21: the non-player `setPositionAndUpdate` is `moveTo` (it runs on both
 * sides; `Entity.teleportTo` is a no-op on the client).
 */
object GoblinThrow {
    @JvmStatic
    fun handleGoblinOwner(goblin: GoblinEntity, player: Player) {
        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player.uuid)
        val headPos = Vec3(player.x, player.y + (playerGirl?.eyeHeight ?: player.eyeHeight), player.z)
        val eyePos = Vec3(goblin.x, goblin.y + goblin.eyeHeight, goblin.z)
        val dist = eyePos.distanceTo(headPos)
        val heightDiff = headPos.y - eyePos.y
        goblin.xRot = (-(Math.sin(heightDiff / dist) * (180.0 / Math.PI))).toFloat()
    }

    @JvmStatic
    fun handleHeldState(goblin: GoblinEntity) {
        if (goblin.getEntityData().get(GoblinEntity.TAMED)) {
            if (goblin.getInteractionPlayerUUID() == null) {
                if (goblin.getCurrentAction() == Action.NULL) {
                    val player = goblin.level().getNearestPlayer(goblin, 15.0)
                    if (player != null && player.distanceTo(goblin) < 2.0f) {
                        handleGoblinOwner(goblin, player)
                        goblin.getNavigation().stop()
                    } else {
                        var post = goblin.guardPost
                        if (post == null ||
                            Math.sqrt(goblin.distanceToSqr(post.x.toDouble(), post.y.toDouble(), post.z.toDouble())) > goblin.getThrowRange() ||
                            goblin.guardPostTicks > 100
                        ) {
                            val xOffset = (if (goblin.random.nextBoolean()) 1 else -1) * goblin.random.nextInt(5)
                            val zOffset = (if (goblin.random.nextBoolean()) 1 else -1) * goblin.random.nextInt(5)
                            val height = WorldUtils.getHeightAt(goblin.level(), goblin.blockPosition().x + xOffset, goblin.blockPosition().z + zOffset)
                            post = BlockPos(goblin.blockPosition().x + xOffset, height, goblin.blockPosition().z + zOffset)
                            goblin.guardPost = post
                            goblin.guardPostTicks = 0
                        }

                        if (Math.sqrt(post.distSqr(goblin.blockPosition())) > 2.0) {
                            goblin.getNavigation().moveTo(post.x.toDouble(), post.y.toDouble(), post.z.toDouble(), 0.3f.toDouble())
                            goblin.tickPathVelocity()
                        } else {
                            goblin.guardPostTicks++
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun handleShoulderIdle(goblin: GoblinEntity) {
        if (goblin.getCurrentAction() == Action.SHOULDER_IDLE) {
            val uuid = goblin.getOwnerUUID()
            if (uuid != null) {
                val player = goblin.level().getPlayerByUUID(uuid)
                if (player != null) {
                    goblin.setPos(player.x, player.y, player.z)
                    goblin.noPhysics = true
                    goblin.isNoGravity = true
                }
            }
        }
    }

    @JvmStatic
    fun updateThrowProgress(goblin: GoblinEntity) {
        var progress = goblin.getThrowProgress()
        if (progress != -1) {
            progress++
            goblin.setThrowProgress(progress)
            if (progress == 15) {
                val pos = getGoblinThrowPos(goblin)
                val pitch = getGoblinThrowHeight(goblin)
                val yaw = getGoblinThrowDistance(goblin)
                goblin.moveTo(pos.x, pos.y, pos.z, goblin.yRot, goblin.xRot)
                val vec = rotateVectorPitchYaw(Vec3(0.0, 0.0, 1.5), pitch, yaw)
                goblin.setDeltaMovement(vec.x, vec.y, vec.z)
                if (!goblin.level().isClientSide) {
                    goblin.setYawRotation(yaw)
                }
            }

            goblin.noPhysics = false
            goblin.isNoGravity = false
            if (progress == 39) {
                goblin.setThrowProgress(-1)
                goblin.setCurrentAction(Action.THROWN)
                goblin.setInteractionPlayerUUID(null)
                goblin.setOwnerUUID(null)
            }
        }
    }

    @JvmStatic
    fun handleGoblinThrowAction(girl: BaseGirlEntity) {
        val action = girl.getCurrentAction()
        val goblin = girl as IGoblin
        if (goblin.getPreviousAction() != Action.START_THROWING && action == Action.START_THROWING) {
            goblin.setThrowProgress(0)
        }

        goblin.setPreviousAction(action)
    }

    @JvmStatic
    fun getGoblinThrowPos(girl: BaseGirlEntity): Vec3 {
        val goblin = girl as IGoblin
        val uuid = goblin.getOwnerUUID() ?: return girl.position()

        val player = girl.level().getPlayerByUUID(uuid)
        return if (player == null) {
            girl.position()
        } else {
            player.position()
                .add(0.0, player.eyeHeight.toDouble(), 0.0)
                .add(rotateVectorPitchYaw(Vec3(0.4f.toDouble(), 0.0, 0.0), getGoblinThrowHeight(girl), getGoblinThrowDistance(girl)))
        }
    }

    @JvmStatic
    fun getGoblinThrowDistance(girl: BaseGirlEntity): Float {
        val goblin = girl as IGoblin
        val uuid = goblin.getOwnerUUID() ?: return 0.0f

        val player = girl.level().getPlayerByUUID(uuid)
        return player?.yHeadRot ?: 0.0f
    }

    @JvmStatic
    fun getGoblinThrowHeight(girl: BaseGirlEntity): Float {
        val goblin = girl as IGoblin
        val uuid = goblin.getOwnerUUID() ?: return 0.0f

        val player = girl.level().getPlayerByUUID(uuid)
        return player?.xRot ?: 0.0f
    }

    @JvmStatic
    fun rotateVectorYaw(vec: Vec3, yaw: Float): Vec3 = rotateVectorPitchYaw(vec, 0.0f, yaw)

    @JvmStatic
    fun rotateVectorPitchYaw(vec: Vec3, pitch: Float, yaw: Float): Vec3 {
        val rotated = Vec3(
            vec.x,
            vec.y * Math.cos(pitch * (Math.PI / 180.0)) - vec.z * Math.sin(pitch * (Math.PI / 180.0)),
            vec.y * Math.sin(pitch * (Math.PI / 180.0)) + vec.z * Math.cos(pitch * (Math.PI / 180.0)),
        )
        return Vec3(
            -Math.sin((yaw + 90.0f) * (Math.PI / 180.0)) * rotated.x - Math.sin(yaw * (Math.PI / 180.0)) * rotated.z,
            rotated.y,
            Math.cos((yaw + 90.0f) * (Math.PI / 180.0)) * rotated.x + Math.cos(yaw * (Math.PI / 180.0)) * rotated.z,
        )
    }

    @JvmStatic
    fun handlePickUpState(girl: BaseGirlEntity) {
        if (girl.getCurrentAction() == Action.PICK_UP) {
            val goblin = girl as IGoblin
            val uuid = goblin.getOwnerUUID()
            if (uuid == null) {
                goblin.setHeldPlayerDistance(-1)
                girl.setCurrentAction(Action.NULL)
                goblin.setOwnerUUID(null)
            } else {
                val player = girl.level().getPlayerByUUID(uuid)
                if (player == null) {
                    goblin.setHeldPlayerDistance(-1)
                    girl.setCurrentAction(Action.NULL)
                    goblin.setOwnerUUID(null)
                } else {
                    girl.setPos(player.x, player.y, player.z)
                    if (girl.position().distanceTo(player.position()) > 10.0) {
                        goblin.setHeldPlayerDistance(-1)
                        girl.setCurrentAction(Action.NULL)
                        goblin.setOwnerUUID(null)
                    } else {
                        val distance = goblin.getHeldPlayerDistance() - 1
                        goblin.setHeldPlayerDistance(distance)
                        if (distance == 0) {
                            girl.setCurrentAction(Action.SHOULDER_IDLE)
                            girl.noPhysics = true
                        }
                    }
                }
            }
        }
    }
}
