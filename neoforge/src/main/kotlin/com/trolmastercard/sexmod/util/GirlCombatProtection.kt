package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.player.Player
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent

/**
 * <b>Role.</b> Combat protection for girls: girls in an active scene (interaction
 * partner set) and player-girls cannot be hurt by normal sources; a player
 * standing within 1 block of the girl he owns is likewise invulnerable (except
 * void/succubus damage). Prevents scene-breaking damage and griefing during
 * animations.
 *
 * <b>Invariants.</b> Out-of-world damage is never cancelled (girls
 * must still die to the void). Player-girls are unconditionally protected;
 * NPC girls only while an interaction partner is bound.
 *
 * Port note: 1.12 `LivingAttackEvent` is `LivingIncomingDamageEvent`;
 * `DamageSource.OUT_OF_WORLD` is the `minecraft:out_of_world` damage type.
 */
open class GirlCombatProtection {
    @SubscribeEvent
    open fun onLivingAttack(event: LivingIncomingDamageEvent) {
        if (!event.source.`is`(DamageTypes.FELL_OUT_OF_WORLD)) {
            val girl = event.entity
            if (girl is BaseGirlEntity) {
                if (girl is AbstractPlayerGirlEntity) {
                    event.isCanceled = true
                } else {
                    event.isCanceled = girl.getInteractionPlayerUUID() != null
                }
            }
        }
    }

    @SubscribeEvent
    open fun onPlayerDamage(event: LivingIncomingDamageEvent) {
        val source = event.source
        if (!source.`is`(DamageTypes.FELL_OUT_OF_WORLD) && source !is SuccubusDamageSource) {
            val player = event.entity
            if (player is Player) {
                val girl = BaseGirlEntity.getGirlByUUID(player.uuid)
                if (girl != null) {
                    if (girl.distanceTo(player) < 1.0f) {
                        event.isCanceled = true
                    }
                }
            }
        }
    }
}
