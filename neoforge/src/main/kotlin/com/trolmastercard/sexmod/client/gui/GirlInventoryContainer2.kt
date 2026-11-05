package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.entity.LunaEntity
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
 * Luna's equipment container ([ChestContainer] variant with an
 * extra fishing-rod slot): weapon/bow/armor slots 0..5 plus rod slot 6, and
 * the player inventory below. Open instances register in the static
 * [containers] list and unregister in [removed]; the
 * owning screen snapshots its own instance on close.
 *
 * Runs on both sides. COMMON class; menu type `sexmod:luna_equipment`.
 */
open class GirlInventoryContainer2(containerId: Int, luna: LunaEntity?, playerInventory: Inventory, uuid: UUID) :
    AbstractContainerMenu(ModMenus.LUNA_EQUIPMENT.get(), containerId) {
    @JvmField
    var lunaEntity: LunaEntity? = null

    @JvmField
    var equipmentSlots: Array<Slot>? = null

    @JvmField
    var girlUUID: UUID = uuid

    /** Client constructor (`IMenuTypeExtension`): reads `guiId, x, y, z` written by [GuiHandler.openGui]. */
    constructor(containerId: Int, inv: Inventory, buf: RegistryFriendlyByteBuf) :
        this(containerId, GuiHandler.readClientGirl(buf) as? LunaEntity, inv, UUID.randomUUID())

    init {
        containers.add(this)
        val itemHandler = luna?.getCapability(Capabilities.ItemHandler.ENTITY, null)
            ?: GuiHandler.emptyEquipmentHandler()
        this.lunaEntity = luna
        val equipment = arrayOf<Slot>(
            GirlInventorySlot(GirlInventorySlot.SlotType.WEAPON, itemHandler, GirlInventorySlot.SlotType.WEAPON.id, 41, 60),
            GirlInventorySlot(GirlInventorySlot.SlotType.BOW, itemHandler, GirlInventorySlot.SlotType.BOW.id, 59, 60),
            GirlInventorySlot(GirlInventorySlot.SlotType.HELMET, itemHandler, GirlInventorySlot.SlotType.HELMET.id, 81, 60),
            GirlInventorySlot(GirlInventorySlot.SlotType.CHEST_PLATE, itemHandler, GirlInventorySlot.SlotType.CHEST_PLATE.id, 100, 60),
            GirlInventorySlot(GirlInventorySlot.SlotType.PANTS, itemHandler, GirlInventorySlot.SlotType.PANTS.id, 119, 60),
            GirlInventorySlot(GirlInventorySlot.SlotType.SHOES, itemHandler, GirlInventorySlot.SlotType.SHOES.id, 138, 60),
            GirlInventorySlot(GirlInventorySlot.SlotType.ROD, itemHandler, GirlInventorySlot.SlotType.ROD.id, 22, 60),
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
     * Standard shift-click between the 7 girl slots and the player inventory
     * (see [ChestContainer.quickMoveStack] for the merge pattern).
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
        var containers: MutableList<GirlInventoryContainer2> = ArrayList()
    }
}
