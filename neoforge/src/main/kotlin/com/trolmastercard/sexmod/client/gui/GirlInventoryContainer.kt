package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.registry.ModMenus
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * Vanilla 3x9 girl chest container: 27 girl inventory slots on top, the
 * player's 27+9 inventory below. Used by [ChestContainerGui] for the girls
 * whose entity is itself a [Container] (bees, kobolds). Instances self-register
 * in the static [containers] list (and unregister in [removed]) so nothing pins
 * the entity after the screen closes.
 *
 * Runs on both sides (vanilla container sync); shift-click moves items between
 * the girl's 27 slots and the player inventory.
 *
 * COMMON class; menu type `sexmod:girl_chest`. [girl] is new in 1.21: the
 * screen takes the girl from the menu.
 */
open class GirlInventoryContainer(
    containerId: Int,
    playerInventory: Container,
    girlInventory: Container,
    player: Player,
    uuid: UUID,
) : AbstractContainerMenu(ModMenus.GIRL_CHEST.get(), containerId) {
    private val inventory: Container = girlInventory
    private val slotIndex: Int

    @JvmField
    var girlUUID: UUID = uuid

    /** The girl owning [inventory] (null if the client could not find her). */
    @JvmField
    var girl: BaseGirlEntity? = girlInventory as? BaseGirlEntity

    /** Client constructor (`IMenuTypeExtension`): reads `guiId, x, y, z` written by [GuiHandler.openGui]. */
    constructor(containerId: Int, inv: Inventory, buf: RegistryFriendlyByteBuf) :
        this(containerId, inv, (GuiHandler.readClientGirl(buf) as? Container) ?: SimpleContainer(27), inv.player, UUID.randomUUID())

    init {
        containers.add(this)
        girlInventory.startOpen(player)
        this.slotIndex = 3
        val yOffset: Byte = -18

        for (row in 0 until 3) {
            for (col in 0 until 9) {
                this.addSlot(Slot(girlInventory, col + row * 9, 8 + col * 18, 18 + row * 18))
            }
        }

        for (row2 in 0 until 3) {
            for (col2 in 0 until 9) {
                this.addSlot(Slot(playerInventory, col2 + row2 * 9 + 9, 8 + col2 * 18, 103 + row2 * 18 + yOffset))
            }
        }

        for (i in 0 until 9) {
            this.addSlot(Slot(playerInventory, i, 8 + i * 18, 143))
        }
    }

    override fun stillValid(player: Player): Boolean = this.inventory.stillValid(player)

    /**
     * Standard shift-click between the girl's 27 slots (indices 0..26) and the
     * player's inventory; backwards-merge into the player side, forward-merge
     * into the girl side.
     */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        var copy = ItemStack.EMPTY
        val slot = this.slots[index]
        if (slot != null && slot.hasItem()) {
            val stack = slot.item
            copy = stack.copy()
            if (index < this.slotIndex * 9) {
                if (!this.moveItemStackTo(stack, this.slotIndex * 9, this.slots.size, true)) {
                    return ItemStack.EMPTY
                }
            } else if (!this.moveItemStackTo(stack, 0, this.slotIndex * 9, false)) {
                return ItemStack.EMPTY
            }

            if (stack.isEmpty) {
                slot.setByPlayer(ItemStack.EMPTY)
            } else {
                slot.setChanged()
            }
        }

        return copy
    }

    override fun removed(player: Player) {
        containers.remove(this)
        super.removed(player)
        this.inventory.stopOpen(player)
    }

    open fun getInventoryRef(): Container = this.inventory

    companion object {
        @JvmField
        var containers: MutableList<GirlInventoryContainer> = ArrayList()
    }
}
