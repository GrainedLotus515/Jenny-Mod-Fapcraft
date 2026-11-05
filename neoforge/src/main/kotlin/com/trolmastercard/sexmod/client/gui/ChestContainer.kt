package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.registry.ModMenus
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.capabilities.Capabilities
import java.util.UUID

/**
 * Container wiring the girl's equipment inventory (weapon, bow, helmet,
 * chestplate, pants, shoes — [GirlInventorySlot]s bound to the girl's
 * item handler capability at slot ids 0..5) together with the
 * player's own inventory. Every open instance registers itself in the static
 * [containers] list and removes itself in [removed]; the owning screen
 * snapshots its own instance on close.
 *
 * Rendered on the CLIENT side; the slot transfer logic in
 * [quickMoveStack] runs on both sides (vanilla container sync).
 *
 * COMMON class. 1.21: menu type `sexmod:girl_equipment`; the client instance is
 * built from the [GuiHandler.openGui] extra data (gui id + girl block position).
 * When the girl cannot be found the slots are still laid out against an empty
 * handler so the server's slot sync cannot index out of bounds.
 */
open class ChestContainer(containerId: Int, girl: BaseGirlEntity?, playerInventory: Inventory, uuid: UUID) :
    AbstractContainerMenu(ModMenus.GIRL_EQUIPMENT.get(), containerId) {
    @JvmField
    var girl: BaseGirlEntity? = null

    @JvmField
    var equipmentSlots: Array<Slot>? = null

    @JvmField
    var girlUUID: UUID = uuid

    /** Client constructor (`IMenuTypeExtension`): reads `guiId, x, y, z` written by [GuiHandler.openGui]. */
    constructor(containerId: Int, inv: Inventory, buf: RegistryFriendlyByteBuf) :
        this(containerId, GuiHandler.readClientGirl(buf), inv, UUID.randomUUID())

    init {
        containers.add(this)
        val itemHandler = girl?.getCapability(Capabilities.ItemHandler.ENTITY, null)
            ?: GuiHandler.emptyEquipmentHandler()
        this.girl = girl
        val equipment = arrayOf<Slot>(
            GirlInventorySlot(GirlInventorySlot.SlotType.WEAPON, itemHandler, GirlInventorySlot.SlotType.WEAPON.id, 31, 60),
            GirlInventorySlot(GirlInventorySlot.SlotType.BOW, itemHandler, GirlInventorySlot.SlotType.BOW.id, 50, 60),
            GirlInventorySlot(GirlInventorySlot.SlotType.HELMET, itemHandler, GirlInventorySlot.SlotType.HELMET.id, 72, 60),
            GirlInventorySlot(GirlInventorySlot.SlotType.CHEST_PLATE, itemHandler, GirlInventorySlot.SlotType.CHEST_PLATE.id, 91, 60),
            GirlInventorySlot(GirlInventorySlot.SlotType.PANTS, itemHandler, GirlInventorySlot.SlotType.PANTS.id, 110, 60),
            GirlInventorySlot(GirlInventorySlot.SlotType.SHOES, itemHandler, GirlInventorySlot.SlotType.SHOES.id, 129, 60),
        )
        this.equipmentSlots = equipment
        val slots = ArrayList<Slot>()

        for (row in 0 until 3) {
            for (col in 0 until 9) {
                slots.add(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18))
            }
        }

        for (i in 0 until 9) {
            slots.add(Slot(playerInventory, i, 8 + i * 18, 142))
        }

        for (slot in equipment) {
            this.addSlot(slot)
        }

        for (slot2 in slots) {
            this.addSlot(slot2)
        }
    }

    /**
     * Standard shift-click transfer between the girl's equipment slots (first
     * `equipmentSlots.size` slots) and the player's inventory. Merges girl->player
     * backwards and player->girl forwards; returns the copied stack or
     * `EMPTY` when nothing moved.
     */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        var copy = ItemStack.EMPTY
        val slot = this.slots[index]
        if (slot != null && slot.hasItem()) {
            val stack = slot.item
            copy = stack.copy()
            val girlSlots = this.slots.size - player.inventory.items.size
            if (index < girlSlots) {
                if (!this.moveItemStackTo(stack, girlSlots, this.slots.size, true)) {
                    return ItemStack.EMPTY
                }
            } else if (!this.moveItemStackTo(stack, 0, girlSlots, false)) {
                return ItemStack.EMPTY
            }

            if (stack.count == 0) {
                slot.setByPlayer(ItemStack.EMPTY)
            } else {
                slot.setChanged()
            }

            slot.onTake(player, stack)
        }

        return copy
    }

    override fun setItem(slotId: Int, stateId: Int, stack: ItemStack) {
        super.setItem(slotId, stateId, stack)
    }

    override fun stillValid(player: Player): Boolean = true

    override fun removed(player: Player) {
        containers.remove(this)
        super.removed(player)
    }

    companion object {
        @JvmField
        var containers: MutableList<ChestContainer> = ArrayList()
    }
}
