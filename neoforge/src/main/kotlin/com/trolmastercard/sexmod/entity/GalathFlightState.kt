package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.MobPredicates
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SpawnEnergyBallParticlesPacket2
import com.trolmastercard.sexmod.util.GalathDamageSource
import com.trolmastercard.sexmod.util.SoundHandler
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> Galath's kinematic state machines, split out of
 * [GalathEntity]: the wild flight machine (target acquisition, next
 * [GalathFlightData] action selection, CHANGE_POSITION clipping) and the
 * knock-out recovery ladder (`KNOCK_OUT_FLY` -> `KNOCK_OUT_GROUND`
 * -> `KNOCK_OUT_STAND_UP`). Every method is stateless bookkeeping over
 * the entity's state (`activeFlight`, `standUpTicks`, `knockoutGroundTicks`,
 * the data keys).
 *
 * <b>Flow.</b> [GalathEntity.tickFlightSystems] runs this in order: target cancel,
 * target acquisition, the flight tick chain, then the knock-out ladder.
 *
 * <b>Pitfalls.</b> [initFlightData] is the only place
 * that picks a random [GalathFlightData] — its retry loop relies on
 * `canInitFlight` always terminating. The flight target box is 7 blocks
 * for a tamed (master) girl and 20 for a wild one.
 */
object GalathFlightState {
    @JvmStatic
    fun tickKnockoutStandUp(galath: GalathEntity) {
        if (galath.getCurrentAction() == Action.KNOCK_OUT_STAND_UP) {
            galath.standUpTicks++
            if (galath.standUpTicks.toDouble() == 39.0) {
                galath.setNoGravity(true)
                galath.setVelocity(0.0, 0.6f.toDouble(), 0.0)
                val pos = galath.position()
                val min = pos.subtract(2.0, 2.0, 2.0)
                val max = pos.add(2.0, 2.0, 2.0)
                val aabb = AABB(min.x, min.y, min.z, max.x, max.y, max.z)

                for (entity in galath.level().getEntitiesOfClass(LivingEntity::class.java, aabb)) {
                    if (entity !is GalathEntity) {
                        val entityPos = entity.position()
                        val dir = entityPos.subtract(pos).normalize()
                        entity.setDeltaMovement(dir.x * 1.0, 1.0, dir.z * 1.0)
                        entity.hurt(GalathDamageSource(galath), 0.5f)
                        if (entity is ServerPlayer) {
                            entity.connection.send(ClientboundSetEntityMotionPacket(entity))
                        }
                    }
                }
            }

            if (!(galath.standUpTicks < 58.0)) {
                galath.setVelocity(Vec3.ZERO)
                galath.entityData.set(GalathEntity.PARALYZED, false)
                galath.standUpTicks = 0
            }
        }
    }

    @JvmStatic
    fun handleKnockout(galath: GalathEntity) {
        if (galath.getCurrentAction() == Action.KNOCK_OUT_GROUND) {
            if (!galath.entityData.get(GalathEntity.HIDE_EFFECTS_FLAG)) {
                if (!(++galath.knockoutGroundTicks < 50.0)) {
                    galath.setCurrentAction(Action.KNOCK_OUT_STAND_UP)
                    galath.standUpTicks = 0
                    galath.knockoutGroundTicks = 0
                }
            }
        }
    }

    @JvmStatic
    fun freezeDuringKnockout(galath: GalathEntity) {
        val action = galath.getCurrentAction()
        if (action == Action.KNOCK_OUT_GROUND || action == Action.KNOCK_OUT_STAND_UP) {
            val motion = galath.deltaMovement
            galath.setDeltaMovement(
                0.0,
                if (galath.entityData.get(GalathEntity.HIDE_EFFECTS_FLAG)) 0.0 else motion.y,
                0.0,
            )
        }
    }

    @JvmStatic
    fun tickKnockoutFlight(galath: GalathEntity) {
        if (galath.getCurrentAction() == Action.KNOCK_OUT_FLY) {
            val pos = galath.blockPosition()
            if (galath.level().getBlockState(pos).block !is LiquidBlock) {
                if (galath.onGround()) {
                    galath.setCurrentAction(Action.KNOCK_OUT_GROUND)
                }
            } else {
                var surfacePos = pos

                while (galath.level().getBlockState(surfacePos.above()).block is LiquidBlock) {
                    surfacePos = surfacePos.above()
                }

                for (x in -1 until 2) {
                    for (z in -1 until 2) {
                        galath.level().setBlockAndUpdate(surfacePos.offset(x, 0, z), Blocks.OBSIDIAN.defaultBlockState())
                    }
                }

                surfacePos = surfacePos.above()
                galath.teleportTo(surfacePos.x.toDouble(), surfacePos.y.toDouble(), surfacePos.z.toDouble())
                galath.setTargetPosition(Vec3.atLowerCornerOf(surfacePos))
                PacketHandler.networkWrapper.sendToAllTracking(SpawnEnergyBallParticlesPacket2(Vec3.atLowerCornerOf(surfacePos), true), galath)

                // 1.12 SPacketSoundEffect to every player tracking her
                val level = galath.level()
                if (level is ServerLevel) {
                    level.chunkSource.broadcast(
                        galath,
                        ClientboundSoundPacket(
                            BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.LAVA_EXTINGUISH), SoundSource.AMBIENT,
                            galath.x, galath.y, galath.z, 1.0f, 1.0f, level.random.nextLong(),
                        ),
                    )
                }

                galath.setCurrentAction(Action.KNOCK_OUT_GROUND)
            }
        }
    }

    @JvmStatic
    fun updateChangePositionNoClip(galath: GalathEntity) {
        if (galath.activeFlight == GalathFlightData.CHANGE_POSITION) {
            val progress = galath.getAttackProgress()
            galath.noPhysics = progress == 0
            if (!galath.level().isEmptyBlock(galath.blockPosition())) {
                galath.noPhysics = true
            }
        }
    }

    @JvmStatic
    fun checkFlightFinished(galath: GalathEntity) {
        galath.activeFlight?.checkFinished(galath)
    }

    @JvmStatic
    fun tickFlightStateMachine(galath: GalathEntity) {
        val active = galath.activeFlight
        if (galath.getTargetEntity() == null) {
            clearActiveFlight(galath)
        } else if (active == null) {
            initFlightData(galath)
        } else {
            if (active.executeUpdate(galath)) {
                initFlightData(galath)
            }
        }
    }

    /**
     * SERVER: selects and starts the next flight action — if a scene player is
     * bound the current action stops; otherwise a random executable
     * [GalathFlightData] action is started (with the attack cooldown
     * collapsing into CHANGE_POSITION when it applies).
     */
    @JvmStatic
    fun initFlightData(galath: GalathEntity) {
        if (!galath.entityData.get(GalathEntity.PARALYZED)) {
            val flightData = galath.activeFlight
            if (galath.getInteractionPlayerUUID() != null) {
                flightData?.updateFlight(galath)

                galath.activeFlight = null
            } else if (flightData != null && flightData.applyAttackCoolDown) {
                flightData.updateFlight(galath)
                galath.activeFlight = GalathFlightData.CHANGE_POSITION
                GalathFlightData.CHANGE_POSITION.executeStart(galath)
            } else {
                val values = GalathFlightData.values()

                var chosen: GalathFlightData
                do {
                    chosen = values[galath.random.nextInt(values.size)]
                } while (!canInitFlight(galath, chosen))

                galath.activeFlight = chosen
                flightData?.updateFlight(galath)

                chosen.executeStart(galath)
            }
        }
    }

    @JvmStatic
    fun canInitFlight(galath: GalathEntity, flightData: GalathFlightData): Boolean {
        return if (flightData.onlyDoThisOnPlayers && galath.getTargetEntity() !is Player) false else flightData.canExecute(galath)
    }

    @JvmStatic
    fun clearActiveFlight(galath: GalathEntity) {
        galath.activeFlight = null
    }

    /**
     * SERVER: acquires a flight target — the nearest player (wild) or mob
     * (tamed) within the search box, and switches to CHANGE_POSITION to
     * approach it. See [getPlayerInBox] / [getMobInBox].
     */
    @JvmStatic
    fun acquireFlightTarget(galath: GalathEntity) {
        if (!hasFlightTarget(galath)) {
            if (galath.getInteractionPlayerUUID() == null) {
                val hasMaster = galath.hasMaster()
                val offset = if (hasMaster) 7.0f else 20.0f
                val box = Vec3(offset.toDouble(), offset.toDouble(), offset.toDouble())
                val pos = galath.position()
                val min = pos.subtract(box)
                val max = pos.add(box)
                val aabb = AABB(min.x, min.y, min.z, max.x, max.y, max.z)
                val target: LivingEntity? = if (hasMaster) getMobInBox(galath, aabb) else getPlayerInBox(galath, aabb)
                if (target == null) {
                    clearFlightTarget(galath)
                } else {
                    galath.setTargetEntity(target)
                    BaseGirlEntity.girlPlaySound(galath, SoundHandler.GIRLS_GALATH_DIALOG[1], true)
                    galath.activeFlight?.updateFlight(galath)

                    galath.activeFlight = GalathFlightData.CHANGE_POSITION
                    GalathFlightData.CHANGE_POSITION.executeStart(galath)
                }
            }
        }
    }

    @JvmStatic
    fun getPlayerInBox(galath: GalathEntity, aabb: AABB): Player? {
        val players = galath.level().getEntitiesOfClass(Player::class.java, aabb) { player ->
            !AbstractPlayerGirlEntity.isOwnerPlayer(player) && !player.isCreative && !player.isSpectator
        }
        return if (players.isEmpty()) null else players[0]
    }

    @JvmStatic
    fun getMobInBox(galath: GalathEntity, aabb: AABB): Monster? {
        val mobs = galath.level().getEntitiesOfClass(Monster::class.java, aabb)
        if (mobs.isEmpty()) {
            return null
        }

        val validMobs = ArrayList<Monster>()

        for (mob in mobs) {
            if (MobPredicates.isValidTarget(mob)) {
                validMobs.add(mob)
            }
        }

        val eyePos = galath.position().add(0.0, galath.eyeHeight.toDouble(), 0.0)

        for (mob in validMobs) {
            if (MobPredicates.isDaylight(galath.level(), eyePos, mob)) {
                return mob
            }
        }

        return null
    }

    @JvmStatic
    fun clearFlightTarget(galath: GalathEntity) {
        if (galath.getTargetEntity() != null) {
            galath.setTargetEntity(null)
            galath.activeFlight?.updateFlight(galath)

            galath.activeFlight = null
            if (!galath.entityData.get(GalathEntity.PARALYZED)) {
                galath.setCurrentAction(Action.NULL)
            }
        }
    }

    @JvmStatic
    fun hasFlightTarget(galath: GalathEntity): Boolean {
        val target = galath.getTargetEntity()
        if (target == null) {
            return false
        } else if (!target.isAlive) {
            return false
        } else if (target.level().dimension() != galath.level().dimension()) {
            return false
        } else {
            val dist = galath.distanceTo(target)
            val maxDist = if (galath.hasMaster()) 16.0f else 30.0f
            if (dist > maxDist) {
                return false
            } else if (target !is Player) {
                return true
            } else {
                if (BaseGirlEntity.getActiveSceneInfo(target.uuid) != null) {
                    return false
                } else {
                    return if (target.isCreative) false else !target.isSpectator
                }
            }
        }
    }

    @JvmStatic
    fun cancelFlightOnLostTarget(galath: GalathEntity) {
        if (galath.hasMaster()) {
            if (galath.getTargetEntity() == null) {
                val attackProgress = galath.entityData.get(GalathEntity.TARGET_ENTITY_ID)
                if (attackProgress != -1) {
                    galath.activeFlight?.updateFlight(galath)

                    galath.activeFlight = null
                    galath.setCurrentAction(Action.NULL)
                }
            }
        }
    }
}
