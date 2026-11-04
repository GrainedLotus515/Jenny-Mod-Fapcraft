package com.trolmastercard.sexmod.item

import com.trolmastercard.sexmod.registry.ModItems
import com.trolmastercard.sexmod.util.KoboldManager
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult

/**
 * <b>Role.</b> Tribe egg — right-clicking spawns a whole new kobold tribe
 * (4 kobolds, random color) at the targeted block via
 * [KoboldManager.spawnKoboldAt]. Consumed on use unless creative.
 *
 * 1.21: registration and the creative tab moved to `ModItems` /
 * `ModCreativeTabs`.
 */
class TribeEggItem : Item(Properties().stacksTo(1)) {
    override fun use(world: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        val eyes = player.getEyePosition(0.0f)
        val look = player.getViewVector(0.0f)
        val end = eyes.add(look.x * 5.0, look.y * 5.0, look.z * 5.0)
        // 1.12 rayTraceBlocks(eyes, end, stopOnLiquid=false, ignoreNoBox=false, returnLastUncollidable=true)
        val rayTrace = world.clip(ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))

        if (rayTrace.type == HitResult.Type.MISS) {
            return InteractionResultHolder.fail(player.getItemInHand(hand))
        }

        if (!player.abilities.instabuild) {
            stack.shrink(1)
        }

        if (!world.isClientSide) {
            KoboldManager.spawnKoboldAt(world, rayTrace.location)
        }

        return InteractionResultHolder.success(player.getItemInHand(hand))
    }

    companion object {
        @JvmStatic
        val TRIBE_EGG: TribeEggItem
            get() = ModItems.TRIBE_EGG.get()
    }
}
