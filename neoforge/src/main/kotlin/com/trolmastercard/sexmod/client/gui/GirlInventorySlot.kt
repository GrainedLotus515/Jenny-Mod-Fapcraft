package com.trolmastercard.sexmod.client.gui

import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.DiggerItem
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.item.HoeItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SwordItem
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.SlotItemHandler

/**
 * A girl-equipment slot bound to the girl's item handler, enforcing
 * per-slot item types: swords/tools, bows, armor by body part, or the fishing
 * rod (Luna only).
 *
 * Runs on both sides; [mayPlace] (1.12 `isItemValid`) is what keeps e.g. food
 * out of the weapon slot. The enum [SlotType] maps slot ids 0..6 to their types.
 *
 * COMMON class (PORTING.md §2.3).
 */
open class GirlInventorySlot(slotType: SlotType, itemHandler: IItemHandler, index: Int, xPosition: Int, yPosition: Int) :
    SlotItemHandler(itemHandler, index, xPosition, yPosition) {
    @JvmField
    var slotType: SlotType = slotType

    override fun mayPlace(stack: ItemStack): Boolean = isSlotTypeCompatible(stack, this.slotType)

    enum class SlotType(@JvmField var id: Int) {
        WEAPON(0),
        BOW(1),
        HELMET(2),
        CHEST_PLATE(3),
        PANTS(4),
        SHOES(5),
        ROD(6);

        companion object {
            @JvmStatic
            fun getSlotType(slotId: Int): SlotType {
                return when (slotId) {
                    0 -> WEAPON
                    1 -> BOW
                    2 -> HELMET
                    3 -> CHEST_PLATE
                    4 -> PANTS
                    5 -> SHOES
                    6 -> ROD
                    else -> throw NullPointerException("Girls don't have a slot nr. $slotId")
                }
            }
        }
    }

    companion object {
        /**
         * Whether the stack fits the slot type for the given slot id.
         *
         * @param stack the stack to test
         * @param slotId the girl slot id (0=weapon, 1=bow, 2=helmet, 3=chestplate,
         *              4=pants, 5=shoes, 6=rod)
         */
        @JvmStatic
        fun isSlotCompatible(stack: ItemStack, slotId: Int): Boolean =
            isSlotTypeCompatible(stack, SlotType.getSlotType(slotId))

        /**
         * The canonical slot-type test: the item class must match the slot type
         * (weapon = sword/tool, bow, armor matching the body part, rod).
         *
         * Port note: 1.12 `ItemTool` (axe/pickaxe/shovel) is 1.21 [DiggerItem]
         * minus [HoeItem] (1.12 hoes were not tools).
         */
        @JvmStatic
        fun isSlotTypeCompatible(stack: ItemStack, slotType: SlotType): Boolean {
            val item = stack.item
            return when (slotType) {
                SlotType.WEAPON -> item is SwordItem || (item is DiggerItem && item !is HoeItem)
                SlotType.BOW -> item is BowItem
                SlotType.HELMET -> item is ArmorItem && item.equipmentSlot == EquipmentSlot.HEAD
                SlotType.CHEST_PLATE -> item is ArmorItem && item.equipmentSlot == EquipmentSlot.CHEST
                SlotType.PANTS -> item is ArmorItem && item.equipmentSlot == EquipmentSlot.LEGS
                SlotType.SHOES -> item is ArmorItem && item.equipmentSlot == EquipmentSlot.FEET
                SlotType.ROD -> item is FishingRodItem
            }
        }
    }
}
