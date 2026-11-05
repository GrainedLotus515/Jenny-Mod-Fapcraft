package com.trolmastercard.sexmod.entity

import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.Container
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.neoforged.neoforge.items.ItemStackHandler

/**
 * <b>Role.</b> Base class for the bee girls ([BeeEntity] NPC and its
 * variants): a girl that doubles as a 27-slot chest inventory ([Container]
 * delegating to [inventory]) and carries a synced "horny" flag
 * ([HORNY_FLAG], data-manager id 111 — used as the tamed/chest flag).
 *
 * <b>Pitfalls.</b> The id 111 key lives in this class but is re-declared in
 * [BeeEntity] with id 112 — the subclass key wins at runtime; do not
 * "deduplicate" without checking every register/set call site.
 *
 * Port notes: 1.12 `IInventory` is 1.21 [Container]: `getSizeInventory` ->
 * [getContainerSize], `getStackInSlot` -> [getItem], `decrStackSize` ->
 * [removeItem], `removeStackFromSlot` -> [removeItemNoUpdate],
 * `setInventorySlotContents` -> [setItem], `getInventoryStackLimit` ->
 * [getMaxStackSize], `markDirty` -> [setChanged], `isUsableByPlayer` ->
 * [stillValid], `openInventory`/`closeInventory` -> [startOpen]/[stopOpen],
 * `isItemValidForSlot` -> [canPlaceItem], `clear` -> [clearContent]. The
 * field accessors (`getField`/`setField`/`getFieldCount`) have no 1.21
 * counterpart and stay plain methods.
 */
abstract class BeeEntityBase : BaseGirlEntity, Container {
    @JvmField
    var inventory: ItemStackHandler = ItemStackHandler(27)

    protected constructor(type: EntityType<out BeeEntityBase>, level: Level) : super(type, level)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(HORNY_FLAG, false)
    }

    override fun getContainerSize(): Int = 27

    override fun isEmpty(): Boolean = false

    override fun getItem(slot: Int): ItemStack =
        if (slot >= this.inventory.slots) ItemStack.EMPTY else this.inventory.getStackInSlot(slot)

    override fun removeItem(slot: Int, amount: Int): ItemStack = this.inventory.extractItem(slot, amount, false)

    override fun removeItemNoUpdate(slot: Int): ItemStack =
        this.inventory.extractItem(slot, this.inventory.getStackInSlot(slot).count, false)

    override fun setItem(slot: Int, stack: ItemStack) {
        this.inventory.setStackInSlot(slot, stack)
    }

    override fun getMaxStackSize(): Int = 64

    override fun setChanged() {
    }

    override fun stillValid(player: Player): Boolean = true

    override fun startOpen(player: Player) {
    }

    override fun stopOpen(player: Player) {
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = true

    fun getField(id: Int): Int = id

    fun setField(id: Int, value: Int) {
    }

    fun getFieldCount(): Int = 0

    override fun clearContent() {
    }

    companion object {
        /** 1.12 key 111 (declared with `BaseGirlEntity.class`, registered here). */
        @JvmField
        val HORNY_FLAG: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(BeeEntityBase::class.java, EntityDataSerializers.BOOLEAN)

        @JvmStatic
        fun createAttributes() = BaseGirlEntity.createAttributes()
    }
}
