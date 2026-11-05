package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SpawnEnergyBallParticlesPacket2
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.VectorMath
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.monster.WitherSkeleton
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> Bookkeeping for Galath's SUMMON_SKELETON attack, split out of
 * [GalathEntity]: the two energy-ball hitbox parts, the summon progress
 * counter, and the lifecycle of the skeleton charges she keeps alive (dragon
 * breath particle sync, despawning charges that fell behind, clearing them
 * when she is paralyzed or dead).
 *
 * <b>Flow.</b> [GalathEntity.updateFlightUI] ticks the progress,
 * [GalathSceneTicks.tickWildSystems] places the hitboxes
 * every tick, and [GalathEntity.tickFlightSystems] prunes/despawns the skeleton list.
 *
 * <b>Pitfalls.</b> The hitboxes must be active exactly while
 * `summonSkeletonTicks` is in [9, 30] — the entity's
 * `getParts()` hit detection depends on it.
 */
object GalathEnergyBalls {
    /**
     * SERVER: places the two energy-ball hitbox parts at the summoning hands
     * while the summon action is charging (`summonSkeletonTicks` in [9, 30]); both parts
     * are inactive outside that window.
     */
    @JvmStatic
    fun resetEnergyBalls(galath: GalathEntity) {
        galath.energyBallHitboxLeft.isActive = false
        galath.energyBallHitboxRight.isActive = false
        if (!(galath.summonSkeletonTicks < 9.0f)) {
            if (!(galath.summonSkeletonTicks > 30.0f)) {
                galath.energyBallHitboxLeft.isActive = true
                galath.energyBallHitboxRight.isActive = true
                val reset = galath.entityData.get(GalathEntity.MIRROR_SIDE)
                val pos = galath.position()
                var tempPos: Vec3
                var tempOffset: Vec3
                if (reset) {
                    tempPos = pos
                    tempOffset = VectorMath.MirrorXZ(GalathEntity.LEFT_ENERGY_BALL_OFFSET)
                } else {
                    tempPos = pos
                    tempOffset = GalathEntity.LEFT_ENERGY_BALL_OFFSET
                }

                val leftPos = tempPos.add(VectorMath.rotateByYaw(tempOffset, 180.0f + galath.yBodyRot))
                val rightPos = galath.position()
                if (reset) {
                    tempPos = rightPos
                    tempOffset = VectorMath.MirrorXZ(GalathEntity.RIGHT_ENERGY_BALL_OFFSET)
                } else {
                    tempPos = rightPos
                    tempOffset = GalathEntity.RIGHT_ENERGY_BALL_OFFSET
                }

                val mirrorRightPos = tempPos.add(VectorMath.rotateByYaw(tempOffset, 180.0f + galath.yBodyRot))
                galath.energyBallHitboxLeft.moveTo(leftPos.x, leftPos.y, leftPos.z, galath.yBodyRot, 0.0f)
                galath.energyBallHitboxRight.moveTo(mirrorRightPos.x, mirrorRightPos.y, mirrorRightPos.z, galath.yBodyRot, 0.0f)
                galath.energyBallHitboxLeft.tick()
                galath.energyBallHitboxRight.tick()
            }
        }
    }

    @JvmStatic
    fun tickSummonSkeletonProgress(galath: GalathEntity) {
        if (galath.getCurrentAction() != Action.SUMMON_SKELETON) {
            galath.summonSkeletonTicks = 0
        } else {
            val previous = galath.summonSkeletonTicks
            galath.summonSkeletonTicks++
            if (previous > 45) {
                galath.summonSkeletonTicks = 0
            }
        }
    }

    @JvmStatic
    fun despawnFarSummonedSkeletons(galath: GalathEntity) {
        val target = galath.getTargetEntity()
        if (target != null) {
            for (skeleton in galath.summonedSkeletons) {
                if (!skeleton.isRemoved && !(target.distanceTo(skeleton) < 15.0f)) {
                    PacketHandler.networkWrapper.sendToAllTracking(SpawnEnergyBallParticlesPacket2(skeleton.position(), true), galath)
                    // 1.12 setDead() + world.removeEntity(skeleton)
                    skeleton.discard()
                }
            }
        }
    }

    @JvmStatic
    fun despawnSummonedSkeletons(galath: GalathEntity) {
        if (galath.entityData.get(GalathEntity.PARALYZED)) {
            for (skeleton in galath.summonedSkeletons) {
                if (!skeleton.isRemoved) {
                    PacketHandler.networkWrapper.sendToAllTracking(SpawnEnergyBallParticlesPacket2(skeleton.position(), true), galath)
                    skeleton.discard()
                }
            }

            galath.summonedSkeletons.clear()
        }
    }

    @JvmStatic
    fun spawnSkeletonBreathParticles(galath: GalathEntity) {
        val level = galath.level() as? ServerLevel ?: return
        for (skeleton in galath.summonedSkeletons) {
            if (!skeleton.isRemoved && skeleton.tickCount % 10 == 0) {
                // 1.12 SPacketParticles to every player tracking the skeleton
                level.chunkSource.broadcast(
                    skeleton,
                    ClientboundLevelParticlesPacket(
                        ParticleTypes.DRAGON_BREATH,
                        false,
                        skeleton.x.toFloat().toDouble(),
                        skeleton.y.toFloat().toDouble(),
                        skeleton.z.toFloat().toDouble(),
                        0.2f * ThreadNames.randomSign(),
                        skeleton.eyeHeight / 2.0f,
                        0.2f * ThreadNames.randomSign(),
                        0.0f,
                        5,
                    ),
                )
            }
        }
    }

    @JvmStatic
    fun pruneDeadSkeletons(galath: GalathEntity) {
        val deadSkeletons = ArrayList<WitherSkeleton>()

        for (skeleton in galath.summonedSkeletons) {
            if (skeleton.isRemoved) {
                deadSkeletons.add(skeleton)
            }
        }

        for (skeleton in deadSkeletons) {
            galath.summonedSkeletons.remove(skeleton)
        }
    }
}
