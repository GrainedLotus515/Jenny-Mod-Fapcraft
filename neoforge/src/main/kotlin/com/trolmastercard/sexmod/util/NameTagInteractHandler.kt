package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent

/**
 * Handles name-tag naming of girls on right-click (sets CUSTOM_NAME).
 *
 * Port note: 1.12 `stack.getDisplayName()` returned the item's name (the
 * renamed hover name for an anvil-named tag, else the plain "Name Tag"); the
 * 1.21 equivalent is `stack.hoverName.string`.
 */
open class NameTagInteractHandler {
    @SubscribeEvent
    open fun onEntityInteractSpecific(event: PlayerInteractEvent.EntityInteractSpecific) {
        val target = event.target
        if (target is BaseGirlEntity) {
            val player = event.entity
            val stack: ItemStack
            if (player.mainHandItem.item === Items.NAME_TAG) {
                stack = player.mainHandItem
            } else {
                if (player.offhandItem.item !== Items.NAME_TAG) {
                    return
                }

                stack = player.offhandItem
            }

            val displayName = stack.hoverName.string
            if ("" != displayName) {
                target.setCustomNameOverride(displayName)
                if (!player.abilities.instabuild) {
                    stack.shrink(1)
                }

                event.isCanceled = true
                // 1.12 also set Event.Result.DENY, which had no effect on a cancelled interact event;
                // the cancellation result stays the default PASS, as in 1.12
            }
        }
    }
}
