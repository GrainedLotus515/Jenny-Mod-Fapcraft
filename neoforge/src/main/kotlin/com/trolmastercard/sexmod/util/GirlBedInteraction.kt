package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.network.chat.Component
import net.minecraft.tags.BlockTags
import net.minecraft.world.phys.AABB
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.level.BlockEvent

/**
 * Bed hooks used by bed scenes (girls walking a player to bed / waking up):
 * a bed that an anchored girl is using cannot be broken.
 *
 * Port note: 1.12 `Blocks.BED` is any block in `#minecraft:beds`.
 */
open class GirlBedInteraction {
    @SubscribeEvent
    open fun onBlockBreak(event: BlockEvent.BreakEvent) {
        if (event.state.`is`(BlockTags.BEDS)) {
            val pos = event.pos
            val aabb = AABB(
                (pos.x - 3).toDouble(),
                (pos.y - 3).toDouble(),
                (pos.z - 3).toDouble(),
                (pos.x + 3).toDouble(),
                (pos.y + 3).toDouble(),
                (pos.z + 3).toDouble(),
            )
            val girls = event.level.getEntitiesOfClass(BaseGirlEntity::class.java, aabb)
            var found = false

            for (girl in girls) {
                if (!girl.isRemoved && girl.getEntityData().get(BaseGirlEntity.IS_ANCHORED)) {
                    found = true
                    break
                }
            }

            if (found) {
                event.player.displayClientMessage(
                    Component.literal("this bed is currently used by a girl.. pls don't disturb okay? ... you are kinda mean rn"),
                    true,
                )
                event.isCanceled = true
            }
        }
    }

    // PORT-GAP: 1.12 onPushOutOfBlocks(PlayerSPPushOutOfBlocksEvent) cancelled the client push-out while
    // the local player was in an active scene (BaseGirlEntity.getActiveSceneInfo(player) != null).
    // NeoForge 1.21 has no such event; the integration pass may add a mixin on LocalPlayer.moveTowardsClosestSpace.
}
