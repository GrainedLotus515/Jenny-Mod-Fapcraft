package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SpawnEnergyBallParticlesPacket2
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.SuccubusDamageSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.player.Player

/**
 * <b>Role.</b> Galath's damage plumbing, split out of [GalathEntity]:
 * the fire/drown/cactus/fall immunity gate, her own hurt sound throttle, the
 * energy-ball hitbox hits and the per-tick rape damage.
 *
 * <b>Pitfalls.</b> [playHurtSound] is client-only
 * and stays quiet on a killing blow (the entity's own death sound covers it);
 * the 1000 ms throttle on `lastHurtSoundTime` is wall-clock, not ticks.
 */
object GalathCombat {
    @JvmStatic
    fun attackEntityFrom(galath: GalathEntity, source: DamageSource, amount: Float): Boolean {
        if (source.`is`(DamageTypeTags.IS_FIRE)) {
            return false
        }

        if (source.`is`(DamageTypes.DROWN)) {
            return false
        }

        if (source.`is`(DamageTypes.CACTUS)) {
            return false
        }

        if (source.`is`(DamageTypes.FALL)) {
            return false
        }

        if (source.`is`(DamageTypes.FLY_INTO_WALL)) {
            return false
        }

        playHurtSound(galath, amount)
        return galath.attackEntityFromBase(source, amount)
    }

    @JvmStatic
    fun attackEntityFromPart(galath: GalathEntity, part: SexEntityPart, source: DamageSource, amount: Float): Boolean {
        if (galath.level().isClientSide) {
            return false
        }

        if (source.entity !is Player) {
            return false
        }

        if (part === galath.energyBallHitboxRight) {
            galath.entityData.set(GalathEntity.RIGHT_ENERGY_BALL_ALIVE, false)
            PacketHandler.networkWrapper.sendToAllTracking(SpawnEnergyBallParticlesPacket2(galath.energyBallHitboxRight.position(), false), galath)
        }

        if (part === galath.energyBallHitboxLeft) {
            galath.entityData.set(GalathEntity.LEFT_ENERGY_BALL_ALIVE, false)
            PacketHandler.networkWrapper.sendToAllTracking(SpawnEnergyBallParticlesPacket2(galath.energyBallHitboxLeft.position(), false), galath)
        }

        return true
    }

    @JvmStatic
    fun playHurtSound(galath: GalathEntity, damage: Float) {
        if (galath.level().isClientSide) {
            if (!(galath.health - damage <= 0.0f)) {
                val now = System.currentTimeMillis()
                if (now >= galath.lastHurtSoundTime + 1000L) {
                    galath.playRandomSound(SoundHandler.GIRLS_GALATH_UUH)
                    galath.lastHurtSoundTime = now
                }
            }
        }
    }

    /**
     * SERVER: per-tick rape damage — while RAPE_ON_GOING/RAPE_INTRO the bound
     * player takes 1 HP (non-creative, survives at 1), and Galath heals 1.5
     * per pulse when `applyDamage` is set.
     */
    @JvmStatic
    fun handleRapeAction(galath: GalathEntity, applyDamage: Boolean) {
        val action = galath.getCurrentAction()
        if (action == Action.RAPE_ON_GOING || action == Action.RAPE_INTRO) {
            val player = galath.getPlayerEntity()
            if (player != null) {
                if (!(0.0f >= player.health - 1.0f)) {
                    if (!player.abilities.instabuild) {
                        player.hurt(SuccubusDamageSource(galath), 1.0f)
                        if (applyDamage) {
                            galath.heal(1.5f)
                        }
                    }
                }
            }
        }
    }
}
