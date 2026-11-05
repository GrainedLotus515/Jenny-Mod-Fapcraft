package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.networking.ResetGirlPacket
import net.minecraft.world.entity.player.Player
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent

/**
 * Death handler: cleans dead girls out of the global girl list
 * (`BaseGirlEntity.getGirlEntityList` removals) and, when a player dies,
 * fully resets the player-girl he owns.
 */
class LivingDeathHandler {
    @SubscribeEvent(priority = EventPriority.LOW)
    fun onLivingDeath(event: LivingDeathEvent) {
        val entity = event.entity
        if (entity is BaseGirlEntity) {
            // 1.12: getGirlEntityList().remove(girl). The list is a fresh copy built from the loaded
            // worlds on every call, so the removal never had an effect; a dead girl leaves it when
            // she is removed from her level.
        } else if (entity is Player) {
            val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByOwner(entity.uuid)
            if (playerGirl != null) {
                ResetGirlPacket.Handler.resetGirl(playerGirl)
            }
        }
    }
}
