package com.trolmastercard.sexmod.util

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.CustomModelData

/**
 * 1.12 item NBT / metadata compatibility layer (§2.4, §2.12).
 *
 * - `stack.getTagCompound()` / `setTagCompound` / `hasTagCompound` map to
 *   [getTag] (a COPY) / [setTag] / [hasTag], stored in
 *   `DataComponents.CUSTOM_DATA`. **Re-set the tag after mutating it.**
 * - `stack.getMetadata()` / `setItemDamage(meta)` used as a variant map to
 *   [getMeta] / [setMeta], stored in `DataComponents.CUSTOM_MODEL_DATA` so item
 *   model overrides can use `custom_model_data`. Meta 0 is the default and is
 *   stored as "no component", which keeps default stacks stackable with fresh
 *   ones (1.12 meta 0 was the default too).
 */
object ItemNbt {
    /** @return a copy of the stack's custom tag, or `null` if it has none (1.12 `getTagCompound()`). */
    @JvmStatic
    fun getTag(stack: ItemStack): CompoundTag? = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()

    /** Replaces the stack's custom tag; `null` removes it (1.12 `setTagCompound`). */
    @JvmStatic
    fun setTag(stack: ItemStack, tag: CompoundTag?) {
        if (tag == null) {
            stack.remove(DataComponents.CUSTOM_DATA)
        } else {
            CustomData.set(DataComponents.CUSTOM_DATA, stack, tag)
        }
    }

    @JvmStatic
    fun hasTag(stack: ItemStack): Boolean = stack.has(DataComponents.CUSTOM_DATA)

    /** 1.12 `getMetadata()` replacement. */
    @JvmStatic
    fun getMeta(stack: ItemStack): Int = stack.get(DataComponents.CUSTOM_MODEL_DATA)?.value() ?: 0

    /** 1.12 `setItemDamage(meta)` (variant use) replacement. */
    @JvmStatic
    fun setMeta(stack: ItemStack, meta: Int) {
        if (meta == 0) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA)
        } else {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(meta))
        }
    }
}
