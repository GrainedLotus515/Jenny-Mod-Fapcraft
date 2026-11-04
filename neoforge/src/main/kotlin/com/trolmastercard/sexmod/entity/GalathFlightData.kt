package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.GalathActionListener
import com.trolmastercard.sexmod.api.IGalathFinish
import com.trolmastercard.sexmod.api.IGalathStart
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.ResetControllerPacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.networking.SpawnEnergyBallParticlesPacket2
import com.trolmastercard.sexmod.networking.TargetPoint
import com.trolmastercard.sexmod.util.GalathDamageSource
import com.trolmastercard.sexmod.util.IGalathExecute
import com.trolmastercard.sexmod.util.IGalathUpdate
import com.trolmastercard.sexmod.util.Reference
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.TrigMath
import com.trolmastercard.sexmod.util.Vector2d
import com.trolmastercard.sexmod.util.VectorMath
import com.trolmastercard.sexmod.util.WorldUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> The wild Galath's flight action state machine. Each enum
 * constant bundles a start/update/finish callback set (via
 * [IGalathStart]/[IGalathUpdate]/[IGalathFinish]/
 * [GalathActionListener]/[IGalathExecute]) that drives one
 * behaviour:
 * - [CHANGE_POSITION] — pick a new flight position near the target
 *   (weighted by open air space), glide there, then pick the next action.
 * - [SUMMON_SKELETON] — charge the two energy balls and spawn [DragonEntity]
 *   charges at the target from the hands (mirrored by the `MIRROR_SIDE` data
 *   key); only while fewer than 2 skeleton charges exist.
 * - [ATTACK_SWORD] — lunge at the target, damage at sword-progress
 *   36/40, then back off to flight.
 * - [RAPE] — hover-then-pounce onto a grounded player; on contact
 *   clears the skeleton charges and starts the rape scene
 *   ([Action.RAPE_INTRO], player movement locked, entity look packet).
 *   The pounce path is an eased progress lerp along a parabolic flight path
 *   (uses [RotationHelper.lerpDouble] with ease curves), with the
 *   progress stored in the `RAPE_PROGRESS` data key.
 *
 * <b>Flow.</b> [GalathFlightState.initFlightData] picks a random constant
 * whose `canExecuteAction` predicate passes and calls
 * [executeStart]; every AI tick [checkFinished] runs the per-tick movement
 * lambda (the second constructor argument) and [executeUpdate] asks the
 * "finished?" predicate (the third argument); when it reports finished the
 * next action is chosen. (The Java field names are kept even though they read
 * swapped.)
 *
 * <b>Pitfalls.</b> `applyAttackCoolDown` constants must end with the
 * attack cooldown collapse (initFlightData switches to CHANGE_POSITION after
 * them). `onlyDoThisOnPlayers` actions must never be picked for mob
 * targets. The RAPE pounce MUST use progress lerps (0..1) on the segment
 * halves — this is a path interpolation, not the 40-tick dismount lerp, so
 * `lerpVec3dDouble` is correct here.
 */
enum class GalathFlightData(
    startAction: IGalathStart,
    finishAction: IGalathFinish,
    updateAction: IGalathUpdate,
    stopAction: GalathActionListener,
    @JvmField val applyAttackCoolDown: Boolean,
    canExecuteAction: IGalathExecute,
    @JvmField val onlyDoThisOnPlayers: Boolean,
) {
    CHANGE_POSITION(
        IGalathStart { galath ->
            val world = galath.level()
            val pos = galath.blockPosition()
            val targetPos = galath.getTargetEntity()!!.blockPosition() // java-npe
            val candidates = ArrayList<BlockPos>()
            val weighted = HashMap<BlockPos, Int>()
            var bestWeight = 0
            val onGround = !world.isEmptyBlock(pos.below())

            for (dx in -10 until 10) {
                for (dy in -10 until 10) {
                    for (dz in -10 until 10) {
                        if (dx != 0 || dy != 0 || dz != 0) {
                            val candidatePos = targetPos.offset(BlockPos(dx, dy, dz))
                            if ((!onGround || pos.y < candidatePos.y) &&
                                world.isEmptyBlock(candidatePos) &&
                                world.isEmptyBlock(candidatePos.above()) &&
                                world.isEmptyBlock(candidatePos.above().above())
                            ) {
                                // 1.12 rayTraceBlocks(from, to, stopOnLiquid=true, ignoreNoBox=true, lastUncollidable=true) == null
                                val rayTrace = world.clip(
                                    ClipContext(
                                        Vec3.atLowerCornerOf(pos), Vec3.atLowerCornerOf(candidatePos),
                                        ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, galath,
                                    ),
                                )
                                if (rayTrace.type == HitResult.Type.MISS) {
                                    var groundY = candidatePos.y

                                    do {
                                        groundY--
                                    } while (groundY >= world.minBuildHeight &&
                                        world.getBlockState(BlockPos(candidatePos.x, groundY, candidatePos.z)).isAir
                                    )

                                    if (world.getBlockState(BlockPos(candidatePos.x, groundY, candidatePos.z)).block !is LiquidBlock) {
                                        candidates.add(candidatePos)
                                        if (world.isEmptyBlock(candidatePos.below()) &&
                                            world.isEmptyBlock(candidatePos.below().below()) &&
                                            !(Math.sqrt(targetPos.distToLowCornerSqr(candidatePos.x.toDouble(), candidatePos.y.toDouble(), candidatePos.z.toDouble())) < 5.0) &&
                                            !(Math.sqrt(pos.distToLowCornerSqr(candidatePos.x.toDouble(), candidatePos.y.toDouble(), candidatePos.z.toDouble())) < 3.0)
                                        ) {
                                            var count = 0

                                            for (ox in -1 until 2) {
                                                for (oz in -1 until 2) {
                                                    for (oy in -1 until 4) {
                                                        if (world.isEmptyBlock(candidatePos.offset(ox, oy, oz))) {
                                                            count++
                                                        }
                                                    }
                                                }
                                            }

                                            if (count >= 25) {
                                                weighted[candidatePos] = count
                                                if (count > bestWeight) {
                                                    bestWeight = count
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (weighted.isNotEmpty()) {
                val entries = ArrayList<Map.Entry<BlockPos, Int>>(weighted.entries)
                // jar-verified: descending sort by weight; the deobf once replaced the
                // lambda params with the entity var (ClassCastException + no sort)
                entries.sortWith { entryA, entryB -> entryB.value.compareTo(entryA.value) }
                galath.flightTargetPosition = Vec3.atLowerCornerOf(entries[ThreadNames.weightedRandomIndex(entries.size - 1)].key)
            } else if (candidates.isEmpty()) {
                galath.flightTargetPosition = Vec3.atLowerCornerOf(
                    BlockPos.containing(
                        targetPos.x + ThreadNames.randomSignedFloat(10.0f, true).toDouble(),
                        targetPos.y + ThreadNames.randomSignedFloat(10.0f, false).toDouble(),
                        targetPos.z + ThreadNames.randomSignedFloat(10.0f, true).toDouble(),
                    ),
                )
            } else {
                galath.flightTargetPosition = Vec3.atLowerCornerOf(candidates[Reference.RANDOM.nextInt(candidates.size)])
            }

            galath.lastFlightPosition = null
            galath.setAttackProgress(0)
            galath.setCurrentAction(Action.FLY)
            PacketHandler.networkWrapper.sendToAllTracking(ResetControllerPacket(galath.getGirlId()), galath)
        },
        IGalathFinish { galath ->
            val pos = galath.position()
            val targetPos = galath.flightTargetPosition
            if (targetPos != null) {
                galath.lastFlightPosition = pos
                val attackProgress = galath.getAttackProgress()
                galath.setAttackProgress(attackProgress + 1)
                if (attackProgress == 0) {
                    val delta = targetPos.subtract(pos)
                    val dir = delta.normalize()
                    galath.setDeltaMovement(
                        dir.x * 0.6f,
                        ThreadNames.clampDouble(delta.y * 0.6f, -0.6f.toDouble(), 0.6f.toDouble()).toFloat().toDouble(),
                        dir.z * 0.6f,
                    )
                }
            }
        },
        IGalathUpdate { galath -> galath.getAttackProgress() > 23 },
        GalathActionListener { galath ->
            galath.setVelocity(Vec3.ZERO)
            galath.setAttackProgress(0)
            galath.lastFlightPosition = null
        },
        false,
        IGalathExecute { _ -> true },
        false,
    ),
    SUMMON_SKELETON(
        IGalathStart { galath ->
            galath.setCurrentAction(Action.SUMMON_SKELETON)
            galath.summonSkeletonTicks = 0
            val dataManager = galath.entityData
            dataManager.set(GalathEntity.LEFT_ENERGY_BALL_ALIVE, true)
            dataManager.set(GalathEntity.RIGHT_ENERGY_BALL_ALIVE, true)
            dataManager.set(GalathEntity.MIRROR_SIDE, galath.random.nextBoolean())
            BaseGirlEntity.playRandomSound(galath, SoundHandler.GIRLS_GALATH_STRONGCHARGE, true)
        },
        IGalathFinish { galath ->
            galath.setVelocity(Vec3.ZERO)
            if (galath.summonSkeletonTicks.toFloat() == 30.0f) {
                GalathEntity.getAimYaw(galath, 0.0f)
                val pos = galath.position()
                val targetPos = galath.getTargetEntity()!!.position() // java-npe
                if (galath.entityData.get(GalathEntity.MIRROR_SIDE)) {
                    if (galath.entityData.get(GalathEntity.LEFT_ENERGY_BALL_ALIVE)) {
                        spawnCharge(galath, pos, targetPos, VectorMath.MirrorXZ(GalathEntity.LEFT_ENERGY_BALL_OFFSET))
                    }

                    if (galath.entityData.get(GalathEntity.RIGHT_ENERGY_BALL_ALIVE)) {
                        spawnCharge(galath, pos, targetPos, VectorMath.MirrorXZ(GalathEntity.RIGHT_ENERGY_BALL_OFFSET))
                    }
                } else {
                    if (galath.entityData.get(GalathEntity.LEFT_ENERGY_BALL_ALIVE)) {
                        spawnCharge(galath, pos, targetPos, GalathEntity.LEFT_ENERGY_BALL_OFFSET)
                    }

                    if (galath.entityData.get(GalathEntity.RIGHT_ENERGY_BALL_ALIVE)) {
                        spawnCharge(galath, pos, targetPos, GalathEntity.RIGHT_ENERGY_BALL_OFFSET)
                    }
                }
            }
        },
        IGalathUpdate { galath -> galath.summonSkeletonTicks >= 45 },
        GalathActionListener { galath -> galath.summonSkeletonTicks = 0 },
        true,
        IGalathExecute { galath -> galath.summonedSkeletons.size < 2 },
        true,
    ),
    ATTACK_SWORD(
        IGalathStart { galath ->
            galath.setSwordAttackProgress(0)
            galath.setCurrentAction(Action.ATTACK_SWORD)
            galath.setVelocity(Vec3.ZERO)
            val pos = galath.position()
            galath.setFlightTargetPos(pos)
            val targetPos = galath.getTargetEntity()!!.position() // java-npe
            val delta = Vector2d(targetPos.x - pos.x, targetPos.z - pos.z)
            val yaw = TrigMath.sinDegrees(Math.atan2(delta.x, delta.y)) - 90.0
            galath.setAnchored(true)
            galath.setTargetPosition(pos)
            galath.setYawRotation(yaw.toFloat())
            BaseGirlEntity.playRandomSound(galath, SoundHandler.GIRLS_GALATH_STRONGCHARGE, true)
        },
        IGalathFinish { galath ->
            val target = galath.getTargetEntity()!! // java-npe
            val attackProgress = galath.getSwordAttackProgress() + 1
            galath.setSwordAttackProgress(attackProgress)
            if (ThreadNames.isBetween(attackProgress.toDouble(), 24.0, 32.0)) {
                val eyePos = target.position().add(0.0, target.eyeHeight.toDouble(), 0.0)
                val delta2 = Vector2d(eyePos.x - galath.x, eyePos.z - galath.z)
                val yaw = TrigMath.sinDegrees(Math.atan2(delta2.x, delta2.y)) - 90.0
                galath.setYawRotation(yaw.toFloat())
                val forward = VectorMath.rotateByYaw(Vec3(0.0, 0.0, 3.0), (yaw + 180.0).toFloat())
                val from = galath.getFlightTargetPos()
                val to = eyePos.add(forward)
                val progress = (attackProgress - 24) / 8.0f
                val lerped = RotationHelper.lerpVec3dDouble(from, to, progress.toDouble())
                galath.setTargetPosition(lerped)
            } else if (ThreadNames.isBetween(attackProgress.toDouble(), 32.0, 54.0)) {
                val behind = VectorMath.rotateByYaw(Vec3(0.0, 0.0, 1.5), galath.getYawRotation() + 180.0f)
                val targetPos2 = target.position().add(behind)
                galath.setTargetPosition(targetPos2)
                val damageSource = GalathDamageSource(galath)
                target.hurtTime = 0
                target.invulnerableTime = 0
                if (attackProgress == 36) {
                    target.hurt(damageSource, 5.0f)
                }

                if (attackProgress == 40) {
                    target.hurt(damageSource, 5.0f)
                }
            } else if (attackProgress == 54) {
                galath.setAnchored(false)
                galath.setCurrentAction(Action.FLY)
                val dir = galath.getFlightTargetPos().subtract(galath.position()).normalize()
                galath.setDeltaMovement(dir.x * 0.6f, dir.y * 0.6f, dir.z * 0.6f)
                galath.setSwordAttackProgress(1)
            } else {
                galath.setAttackProgress(galath.getAttackProgress() + 1)
            }
        },
        IGalathUpdate { galath -> galath.getAttackProgress() > 23 },
        GalathActionListener { galath ->
            galath.setAttackProgress(0)
            galath.setVelocity(Vec3.ZERO)
            galath.setSwordAttackProgress(-1)
            galath.setAnchored(false)
        },
        true,
        IGalathExecute { _ -> true },
        false,
    ),
    RAPE(
        IGalathStart { galath ->
            galath.setCurrentAction(Action.RAPE_PREPARE)
            galath.rapeChargeTicks = 0
            galath.chargeStartPosition = null
            galath.flightTargetPosition = null
            galath.entityData.set(GalathEntity.RAPE_PROGRESS, 0.0f)
        },
        IGalathFinish { galath -> tickRapeCharge(galath) },
        IGalathUpdate { galath ->
            if (galath.getCurrentAction() == Action.RAPE_INTRO) {
                return@IGalathUpdate true
            }

            val from = galath.chargeStartPosition ?: return@IGalathUpdate false
            val to = galath.flightTargetPosition!! // java-npe
            val delta = to.subtract(from)
            var newPos = to.add(delta)
            newPos = Vec3(newPos.x, from.y, newPos.z)
            Math.sqrt(galath.distanceToSqr(newPos.x, newPos.y, newPos.z)) < 0.1f
        },
        GalathActionListener { galath ->
            galath.flightTargetPosition = null
            galath.chargeStartPosition = null
            galath.rapeChargeTicks = 0
            galath.entityData.set(GalathEntity.RAPE_PROGRESS, 0.0f)
        },
        true,
        IGalathExecute { _ -> true },
        true,
    );

    @JvmField
    val updateAction: IGalathUpdate = updateAction

    @JvmField
    val startAction: IGalathStart = startAction

    @JvmField
    val finishAction: IGalathFinish = finishAction

    @JvmField
    val stopAction: GalathActionListener = stopAction

    @JvmField
    val canExecuteAction: IGalathExecute = canExecuteAction

    fun executeStart(galath: GalathEntity) {
        this.startAction.start(galath)
    }

    fun executeUpdate(galath: GalathEntity): Boolean = this.updateAction.update(galath)

    fun checkFinished(galath: GalathEntity) {
        this.finishAction.finish(galath)
    }

    fun updateFlight(galath: GalathEntity) {
        this.stopAction.stop(galath)
    }

    fun canExecute(galath: GalathEntity): Boolean = this.canExecuteAction.canExecute(galath)

    private companion object {
        /** The four identical charge blocks of the Java SUMMON_SKELETON lambda (anchor offset differs only). */
        fun spawnCharge(galath: GalathEntity, pos: Vec3, targetPos: Vec3, offset: Vec3) {
            val random = galath.random
            val anchor = pos.add(VectorMath.rotateByYaw(offset, 180.0f + galath.yBodyRot))
            var aim = targetPos.subtract(anchor).normalize()
            aim = Vec3(
                aim.x + random.nextDouble() * 0.3f,
                aim.y + random.nextDouble() * 0.3f,
                aim.z + random.nextDouble() * 0.3f,
            )
            aim = aim.normalize()
            val vel = Vec3(aim.x * 0.4f, aim.y * 0.4f, aim.z * 0.4f)
            val dragon = DragonEntity(galath.level(), galath, vel)
            dragon.moveTo(anchor.x, anchor.y, anchor.z)
            galath.level().addFreshEntity(dragon)
        }

        /** The RAPE per-tick lambda: 48 hover ticks, then the eased parabolic pounce. */
        fun tickRapeCharge(galath: GalathEntity) {
            if (++galath.rapeChargeTicks >= 48) {
                galath.setCurrentAction(Action.RAPE_CHARGE)
                val target = galath.getTargetEntity()!! // java-npe
                if (galath.chargeStartPosition == null) {
                    galath.flightTargetPosition = target.position().add(0.0, (target.eyeHeight / 2.0f).toDouble(), 0.0)
                    galath.chargeStartPosition = galath.position()
                    val dir = target.position().subtract(galath.position()).normalize()
                    galath.setYawRotation((TrigMath.sinDegrees(Math.atan2(dir.z, dir.x)) - 90.0).toFloat())
                }

                var pos = galath.position()
                val min = pos.subtract(0.65f.toDouble(), 0.65f.toDouble(), 0.65f.toDouble())
                val max = pos.add(0.65f.toDouble(), 0.65f.toDouble(), 0.65f.toDouble())
                val aabb = AABB(min.x, min.y, min.z, max.x, max.y, max.z)

                for (player in galath.level().getEntitiesOfClass(Player::class.java, aabb)) {
                    if (player.isAlive && player.onGround() && BaseGirlEntity.getGirlByUUID(player.uuid, true) == null) {
                        val playerPos = player.position()
                        val toPlayer = pos.subtract(playerPos)
                        val rotated = VectorMath.rotateByYaw(toPlayer, galath.getYawRotation())
                        val dist = Math.abs(rotated.x)
                        if (!(dist > 0.65f)) {
                            for (skeleton in galath.summonedSkeletons) {
                                val skeletonPos = skeleton.position()
                                skeleton.discard()
                                PacketHandler.networkWrapper.sendToAllTracking(
                                    SpawnEnergyBallParticlesPacket2(skeletonPos, true),
                                    TargetPoint(WorldUtils.getDimensionId(skeleton.level()), skeletonPos.x, skeletonPos.y, skeletonPos.z, 50.0),
                                )
                            }

                            galath.summonedSkeletons.clear()
                            val playerMP = player as ServerPlayer
                            galath.setTargetPosition(player.position())
                            galath.setInteractionPlayerUUID(player.uuid)
                            galath.setAnchored(true)
                            galath.setCurrentAction(Action.RAPE_INTRO)
                            val yawByte = Mth.floor((galath.getYawRotation() + 180.0f) * 256.0f / 360.0f).toByte()
                            PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), playerMP)
                            playerMP.connection.send(ClientboundSetEntityMotionPacket(playerMP.id, Vec3.ZERO))
                            playerMP.connection.send(ClientboundMoveEntityPacket.Rot(playerMP.id, yawByte, (-14).toByte(), true))
                            return
                        }
                    }
                }

                val from = galath.chargeStartPosition!!
                val to = galath.flightTargetPosition!!
                val delta = to.subtract(from)
                var newPos = to.add(delta)
                newPos = Vec3(newPos.x, from.y, newPos.z)
                val isPast = pos.distanceTo(Vec3(from.x, pos.y, from.z)) > pos.distanceTo(Vec3(newPos.x, pos.y, newPos.z))
                var distAlong: Double
                val totalDist: Double
                if (isPast) {
                    distAlong = VectorMath.getLinearFactor(to, newPos, pos)
                    totalDist = to.distanceTo(newPos)
                } else {
                    distAlong = VectorMath.getLinearFactor(from, to, pos)
                    totalDist = from.distanceTo(to)
                }

                val steps = totalDist / 0.05f
                val speedFactor = 1.0 / steps * 20.0
                distAlong += speedFactor
                if (!isPast && distAlong < 0.9f) {
                    galath.flightTargetPosition = target.position().add(0.0, (target.eyeHeight / 2.0f).toDouble(), 0.0)
                }

                if (isPast) {
                    pos = Vec3(
                        RotationHelper.lerpDouble(to.x, newPos.x, Math.min(1.0, distAlong)),
                        RotationHelper.lerpDouble(to.y, newPos.y, Math.min(1.0, RotationHelper.easeInCubic(distAlong))),
                        RotationHelper.lerpDouble(to.z, newPos.z, Math.min(1.0, distAlong)),
                    )
                } else {
                    pos = Vec3(
                        RotationHelper.lerpDouble(from.x, to.x, distAlong),
                        RotationHelper.lerpDouble(from.y, to.y, RotationHelper.easeInOutQuad(distAlong)),
                        RotationHelper.lerpDouble(from.z, to.z, distAlong),
                    )
                }

                galath.setPos(pos.x, pos.y, pos.z)
                if (isPast) {
                    galath.entityData.set(GalathEntity.RAPE_PROGRESS, distAlong.toFloat())
                }
            }
        }
    }
}
