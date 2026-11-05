package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.particle.DragonBreathParticle
import com.trolmastercard.sexmod.item.GalathCoinItem
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SuccubusDamageSource
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> Galath's per-tick scene dispatcher, split out of
 * [GalathEntity]: the scene enders (RAPE_CUM / CORRUPT_CUM teardown, the
 * wild rape kill), the wild-system tick chain, and the client-side visual
 * helpers that ride along with it (coin scene, corrupt-intro camera, sword
 * particles, wing flag). Every method is stateless bookkeeping over the
 * entity's state and data keys.
 *
 * <b>Flow.</b> [GalathEntity.tick] calls the wild chain
 * ([tickWildSystems]) or the tamed chain ([handleRapeCum], [endTamedCorruptScene]);
 * [tickGiveCoinScene] runs on the client every tick.
 *
 * <b>Pitfalls.</b> The enders only act on the server and only once the action's
 * `ticksPlaying` passes the threshold — they also restore player
 * movement, so they must not be called from the client. `HIDE_EFFECTS_FLAG`
 * pins the wings animated regardless of the current action.
 */
object GalathSceneTicks {
    /**
     * CLIENT: the GIVE_COIN scene — at tick 95 summons the coin for the local
     * player, and during ticks 25-38 spawns the dragon-breath particles
     * streaming between the two hands (progress lerp between the weapon and
     * offhand bones).
     */
    @JvmStatic
    fun tickGiveCoinScene(galath: GalathEntity) {
        if (galath.getCurrentAction() == Action.GIVE_COIN) {
            val coinTick = Action.GIVE_COIN.ticksPlaying[1]
            if (coinTick == 95) {
                GalathCoinItem.summonForPlayer(ClientHooks.localPlayer()!!, galath) // java-npe
            }

            if (coinTick > 25 && coinTick < 38) {
                val pos = galath.position()
                val weaponPos = galath.getCachedBoneOffset("weapon").add(pos)
                val offhandPos = galath.getCachedBoneOffset("offhand").add(pos)
                Client.spawnHandBreath(weaponPos, offhandPos)
            }
        }
    }

    /**
     * SERVER: ends the rape scene — 28 ticks into RAPE_CUM she un-anchors,
     * resets to NULL and releases the player (positioned on solid ground with
     * movement restored).
     */
    @JvmStatic
    fun handleRapeCum(galath: GalathEntity) {
        if (!galath.level().isClientSide) {
            if (galath.getCurrentAction() == Action.RAPE_CUM) {
                if (Action.RAPE_CUM.ticksPlaying[0] >= 28) {
                    galath.setAnchored(false)
                    galath.setCurrentAction(Action.NULL)
                    val player = galath.getPlayerEntity()
                    galath.setInteractionPlayerUUID(null)
                    if (player != null) {
                        player.teleportTo(player.x, Math.ceil(player.y) + 1.0, player.z)
                        PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(true), player as ServerPlayer)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun endTamedCorruptScene(galath: GalathEntity) {
        if (!galath.level().isClientSide) {
            if (galath.getCurrentAction() == Action.CORRUPT_CUM) {
                if (Action.CORRUPT_CUM.ticksPlaying[0] >= 30) {
                    galath.setAnchored(false)
                    galath.setCurrentAction(Action.NULL)
                    val player = galath.getPlayerEntity()
                    galath.setInteractionPlayerUUID(null)
                    if (player != null) {
                        player.teleportTo(player.x, Math.ceil(player.y) + 1.0, player.z)
                        PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(true), player as ServerPlayer)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun tickWildSystems(galath: GalathEntity) {
        GalathEntity.getAimYaw(galath, 0.0f)
        GalathEnergyBalls.resetEnergyBalls(galath)
        galath.updateFlightRenderAngles()
        spawnSwordAttackParticles(galath)
        clearSwordRenderFlags(galath)
        restoreStarsVisible(galath)
        updateWingsAnimated(galath)
        handleCorruptCum(galath)
        if (galath.level().isClientSide) {
            tickCorruptIntroCamera(galath)
        }
    }

    /**
     * SERVER: the corrupt-scene ender — 30 ticks into CORRUPT_CUM advances to
     * the coin-give scene ([Action.GIVE_COIN]).
     */
    @JvmStatic
    fun handleCorruptCum(galath: GalathEntity) {
        if (!galath.level().isClientSide) {
            if (galath.getCurrentAction() == Action.CORRUPT_CUM) {
                if (Action.CORRUPT_CUM.ticksPlaying[0] >= 30) {
                    galath.setCurrentAction(Action.GIVE_COIN)
                }
            }
        }
    }

    @JvmStatic
    fun updateWingsAnimated(galath: GalathEntity) {
        if (galath.entityData.get(GalathEntity.HIDE_EFFECTS_FLAG)) {
            galath.wingsAnimated = true
        } else {
            when (galath.getCurrentAction()) {
                Action.RAPE_INTRO, Action.RAPE_ON_GOING, Action.RAPE_CUM, Action.RAPE_CHARGE, Action.RAPE_CUM_IDLE,
                Action.CORRUPT_SLOW, Action.CORRUPT_FAST, Action.CORRUPT_CUM, Action.MASTERBATE,
                -> {
                    // Java falls through into the RAPE_PREPARE return
                    galath.wingsAnimated = true
                    return
                }
                Action.RAPE_PREPARE -> return
                else -> galath.wingsAnimated = false
            }
        }
    }

    /**
     * CLIENT: re-enables the knock-out star overlay every tick except during
     * [Action.KNOCK_OUT_STAND_UP] — the `dontDrawStars` animation
     * key clears [GalathEntity.drawStars], this puts it back.
     */
    @JvmStatic
    fun restoreStarsVisible(galath: GalathEntity) {
        if (galath.level().isClientSide) {
            if (galath.getCurrentAction() != Action.KNOCK_OUT_STAND_UP) {
                galath.drawStars = true
            }
        }
    }

    @JvmStatic
    fun updateHealthBar(galath: GalathEntity) {
        galath.bossBar.setProgress(galath.health / galath.maxHealth)
    }

    @JvmStatic
    fun updateGravity(galath: GalathEntity) {
        if (!galath.entityData.get(GalathEntity.PARALYZED)) {
            galath.setNoGravity(galath.getTargetEntity() != null)
        }
    }

    @JvmStatic
    fun clearSwordRenderFlags(galath: GalathEntity) {
        if (galath.getCurrentAction() != Action.ATTACK_SWORD) {
            galath.renderSword = false
            galath.spawnSwordParticles = false
        }
    }

    /**
     * CLIENT: tracks the energy-ball attack — spawns dragon-breath particles
     * along the weapon bone while the sword is drawn (`spawnSwordParticles`).
     */
    @JvmStatic
    fun spawnSwordAttackParticles(galath: GalathEntity) {
        if (galath.level().isClientSide) {
            if (galath.spawnSwordParticles) {
                val pos = galath.position()
                val startPos = galath.getCachedBoneOffset("weaponStart").add(pos)
                val endPos = galath.getCachedBoneOffset("weaponEnd").add(pos)
                val step = 0.1f
                val random = galath.random

                var t = 0.0f
                while (t < 1.0f) {
                    val lerped = RotationHelper.lerpVec3dDouble(startPos, endPos, t.toDouble())

                    for (i in 0 until 3) {
                        galath.level().addParticle(
                            ParticleTypes.DRAGON_BREATH,
                            lerped.x + random.nextDouble() * 0.25 * (if (random.nextBoolean()) 1 else -1),
                            lerped.y + random.nextDouble() * 0.25 * (if (random.nextBoolean()) 1 else -1),
                            lerped.z + random.nextDouble() * 0.25 * (if (random.nextBoolean()) 1 else -1),
                            0.0, 0.0, 0.0,
                        )
                    }
                    t += step
                }

                for (i in 0 until 3) {
                    galath.level().addParticle(
                        ParticleTypes.DRAGON_BREATH,
                        endPos.x + random.nextDouble() * 0.25 * (if (random.nextBoolean()) 1 else -1) * (if (random.nextBoolean()) 1 else -1),
                        endPos.y + random.nextDouble() * 0.25 * (if (random.nextBoolean()) 1 else -1),
                        endPos.z + random.nextDouble() * 0.25 * (if (random.nextBoolean()) 1 else -1),
                        0.0, 0.0, 0.0,
                    )
                }
            }
        }
    }

    /** CLIENT. */
    @JvmStatic
    fun tickCorruptIntroCamera(galath: GalathEntity) {
        val action = galath.getCurrentAction()
        if (galath.previousAction != Action.CORRUPT_INTRO && action == Action.CORRUPT_INTRO) {
            val player = ClientHooks.localPlayer()!! // java-npe
            if (player.uuid != galath.getInteractionPlayerUUID()) {
                galath.previousAction = action
            } else {
                val yaw = if (galath.hasMaster()) 0.0f else galath.getYawRotation() + 180.0f
                player.setYRot(yaw)
                player.yRotO = yaw
                player.setXRot(80.0f)
                player.xRotO = 80.0f
                galath.previousAction = action
            }
        } else {
            galath.previousAction = action
        }
    }

    @JvmStatic
    fun tickWildRapeCum(galath: GalathEntity) {
        if (!galath.hasMaster()) {
            val action = galath.getCurrentAction()
            if (action != Action.RAPE_CUM) {
                galath.rapeCumTicks = 0
            } else {
                val player = galath.getPlayerEntity()
                if (player == null) {
                    galath.rapeCumTicks = 0
                } else if (++galath.rapeCumTicks == 15) {
                    player.hurt(SuccubusDamageSource(galath), 2.1474836E9f)
                }
            }
        }
    }

    /** Client-only halves (PORTING.md §2.3 Rule C). */
    private object Client {
        fun spawnHandBreath(weaponPos: Vec3, offhandPos: Vec3) {
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return
            DragonBreathParticle.BREATH_SCALE = 0.5f

            var t = 0.0f
            while (t < 1.0f) {
                val lerped = RotationHelper.lerpVec3dDouble(weaponPos, offhandPos, t.toDouble())
                mc.particleEngine.add(DragonBreathParticle(level, lerped.x, lerped.y, lerped.z))
                t += 0.2f
            }
        }
    }
}
