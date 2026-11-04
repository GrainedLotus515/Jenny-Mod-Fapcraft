package com.trolmastercard.sexmod.item

import com.trolmastercard.sexmod.entity.LunaEntity
import com.trolmastercard.sexmod.entity.SexEntity
import com.trolmastercard.sexmod.registry.ModItems
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> The Luna rod — a fishing rod for the cat-girl Luna with a
 * `cast` model override driven by her data manager. [castFishingRod]
 * is invoked by `CatActivateFishingPacket` server-side: it retrieves the
 * existing `SexEntity` bobber or spawns a new one aimed at her fishing
 * target, applying fishing-speed/luck enchantments from the rod.
 *
 * Port notes:
 * - The 1.12 `cast` property override (`FISH_ON_LINE` on a [LunaEntity]) is
 *   registered for this item in `ClientProxy`; the model is
 *   `models/item/luna_rod.json` (was the vanilla `fishing_rod` model).
 * - Registration (`register`, `registerItems`, `onModelRegistry`) lives in
 *   `ModItems`; [Events] therefore has no handlers left (see its doc).
 * - 1.12 `getFishingSpeedBonus` (the Lure level) is recovered from 1.21's
 *   `getFishingTimeReduction` (5 seconds per Lure level).
 */
class LunaRodItem : FishingRodItem(Properties().durability(64)) {
    fun castFishingRod(world: Level, luna: LunaEntity, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = luna.getItemInHand(hand)
        val hook = luna.hookEntity
        if (hook != null) {
            val catchResult = hook.getCatchResult()
            stack.hurtAndBreak(catchResult, luna, LivingEntity.getSlotForHand(hand))
            luna.swing(hand)
            world.playSound(
                null,
                luna.x,
                luna.y,
                luna.z,
                SoundEvents.FISHING_BOBBER_RETRIEVE,
                SoundSource.NEUTRAL,
                1.0f,
                0.4f / (world.random.nextFloat() * 0.4f + 0.8f),
            )
        } else {
            world.playSound(
                null,
                luna.x,
                luna.y,
                luna.z,
                SoundEvents.FISHING_BOBBER_THROW,
                SoundSource.NEUTRAL,
                0.5f,
                0.4f / (world.random.nextFloat() * 0.4f + 0.8f),
            )
            if (!world.isClientSide) {
                SexEntity.ownerLuna = luna
                val spot = luna.fishingSpot!! // java-npe: only cast with a fishing spot
                val distance = luna.position().distanceTo(Vec3(spot.x.toDouble(), spot.y.toDouble(), spot.z.toDouble()))
                val sexEntity = SexEntity(world, luna, distance * 0.01)
                val serverLevel = world as ServerLevel
                val speedBonus = (EnchantmentHelper.getFishingTimeReduction(serverLevel, stack, luna) / 5.0f).toInt()
                if (speedBonus > 0) {
                    sexEntity.setFishingLevel(speedBonus)
                }

                val luckBonus = EnchantmentHelper.getFishingLuckBonus(serverLevel, stack, luna)
                if (luckBonus > 0) {
                    sexEntity.setPhase(luckBonus)
                }

                world.addFreshEntity(sexEntity)
            }

            luna.swing(hand)
        }

        return InteractionResultHolder.success(stack)
    }

    /**
     * Event holder named by the contract (`ForgeEventHandler` registers it). The
     * 1.12 class only subscribed registry/model events, which are replaced by
     * `ModItems` and the `models/item/luna_rod.json` model, so no handler is
     * left. NOTE for integration: `EventBus.register` throws on an object without
     * `@SubscribeEvent` methods, so `ForgeEventHandler` must not register this.
     */
    object Events

    companion object {
        @JvmStatic
        val LUNA_ROD: LunaRodItem
            get() = ModItems.LUNA_ROD.get()
    }
}
