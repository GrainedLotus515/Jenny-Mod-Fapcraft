package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.util.KoboldManager
import com.trolmastercard.sexmod.util.KoboldTask
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.neoforged.neoforge.items.ItemStackHandler
import net.neoforged.neoforge.items.wrapper.InvWrapper

/**
 * Inventory handling for [KoboldEntity]: stacking rules for the 27-slot
 * [ItemStackHandler] she carries, overflow to a
 * tribe chest, and the walk-to-chest / open-chest sequence.
 *
 * <b>Pitfalls.</b> `canInsertItemStack` mutates the passed stack while
 * simulating (`stack.setCount(...)`), exactly like the original: do not
 * "fix" that to a copy without re-checking `canAssignTask`, which relies
 * on the simulation not filling the simulated handler. `hasInventoryItems`
 * returns `false` as soon as one slot is non-empty (inverted name kept
 * from the original).
 *
 * 1.21: `TileEntityChest.getSingleChestHandler()` is an [InvWrapper] over the
 * single [ChestBlockEntity]; the 1.12 item+metadata comparison is
 * [ItemStack.isSameItem] (the Flattening made metadata part of the item).
 */
object KoboldInventory {
    @JvmStatic
    fun canStoreInventory(entity: KoboldEntity, tribeId: UUID, checkOpen: Boolean): Boolean {
        if (hasInventoryItems(entity)) {
            return false
        }

        if (isTribeChestOpen(entity, tribeId, checkOpen)) {
            entity.inventoryNotifyCooldown = 0
            return true
        }

        entity.inventoryNotifyCooldown--
        if (entity.inventoryNotifyCooldown < 0 && entity.tribeBlocked) {
            entity.inventoryNotifyCooldown = 300
            val master = entity.level().getPlayerByUUID(UUID.fromString(entity.getEntityData().get(BaseGirlEntity.MASTER)))
            val tribeColor = EyeAndKoboldColor.valueOf(entity.getEntityData().get(KoboldEntity.CURRENT_ACTION))
            if (master != null) {
                master.displayClientMessage(
                    Component.literal(
                        tribeColor.getTextColor().toString() +
                            entity.getDisplayNameText() +
                            "s " +
                            ChatFormatting.WHITE +
                            "inventory is full and there are either no chests to put her items in or said chests are full as well",
                    ),
                    false,
                )
            }

            return false
        } else {
            return false
        }
    }

    @JvmStatic
    fun isTribeChestOpen(entity: KoboldEntity, tribeId: UUID, checkOpen: Boolean): Boolean {
        val chests = KoboldManager.getTribeChests(tribeId) ?: return false

        var chosenChest: BlockPos? = null
        val staleChests = ArrayList<BlockPos>()

        for (chestPos in chests) {
            val tileEntity = entity.level().getBlockEntity(chestPos)
            if (tileEntity !is ChestBlockEntity) {
                staleChests.add(chestPos)
                continue
            }

            val chestHandler = InvWrapper(tileEntity)
            var canStore = false

            for (i in 0 until entity.inventory.slots) {
                val stack = entity.inventory.getStackInSlot(i)
                if (!stack.isEmpty) {
                    for (slot in 0 until chestHandler.slots) {
                        val remaining = chestHandler.insertItem(slot, stack, true)
                        if (remaining.count != stack.count) {
                            canStore = true
                            break
                        }
                    }

                    if (canStore) {
                        break
                    }
                }
            }

            if (canStore) {
                if (chosenChest == null) {
                    chosenChest = chestPos
                } else if (chosenChest.distToLowCornerSqr(entity.x, entity.y, entity.z) > chestPos.distToLowCornerSqr(entity.x, entity.y, entity.z)) {
                    chosenChest = chestPos
                }
            }
        }

        if (staleChests.isNotEmpty()) {
            chests.removeAll(staleChests.toSet())
        }

        if (chosenChest == null) {
            return false
        }

        if (!(Math.sqrt(entity.distanceToSqr(chosenChest.x.toDouble(), chosenChest.y.toDouble(), chosenChest.z.toDouble())) < 2.0)) {
            if (Math.abs(chosenChest.y - entity.blockPosition().y) > 4) {
                if (!checkOpen) {
                    return false
                }

                entity.syncTribeBlocks(chosenChest)
            } else {
                val navigator = entity.getNavigation()
                val standPos = entity.findStandPos(chosenChest)
                navigator.moveTo(standPos.x.toDouble(), standPos.y.toDouble(), standPos.z.toDouble(), 0.35f.toDouble())
                if (navigator.path == null) {
                    if (!checkOpen) {
                        return false
                    }

                    entity.syncTribeBlocks(chosenChest)
                }
            }

            return true
        } else {
            val tileEntity = entity.level().getBlockEntity(chosenChest)
            if (tileEntity !is ChestBlockEntity) {
                return false
            }

            val chestHandler = InvWrapper(tileEntity)

            for (i in 0 until entity.inventory.slots) {
                var stack = entity.inventory.getStackInSlot(i)
                if (!stack.isEmpty) {
                    for (slot in 0 until chestHandler.slots) {
                        val remaining = chestHandler.insertItem(slot, stack, false)
                        if (remaining.count <= 0) {
                            entity.inventory.setStackInSlot(i, ItemStack.EMPTY)
                            break
                        }

                        entity.inventory.setStackInSlot(i, remaining)
                        stack = remaining
                    }
                }
            }

            entity.level().playSound(null, chosenChest, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 1.0f, 1.0f)
            return true
        }
    }

    @JvmStatic
    fun hasInventoryItems(entity: KoboldEntity): Boolean {
        for (slot in 0 until entity.inventory.slots) {
            if (!entity.inventory.getStackInSlot(slot).isEmpty) {
                return false
            }
        }

        return true
    }

    @JvmStatic
    fun canAssignTask(entity: KoboldEntity, task: KoboldTask): Boolean {
        val drops = ArrayList<ItemStack>()

        for (target in task.getMiningTargets()) {
            try {
                val state = entity.level().getBlockState(target)
                val drop = state.block.getCloneItemStack(entity.level(), target, state)
                drops.add(drop)
            } catch (ex: IllegalArgumentException) {
                // ignored: blocks without an item form contribute no drop
            }
        }

        return canStoreItems(entity, drops)
    }

    @JvmStatic
    fun canInsertItem(entity: KoboldEntity, stack: ItemStack): Boolean = canInsertItemStack(entity, entity.inventory, stack, true, false)

    @JvmStatic
    fun canStoreItems(entity: KoboldEntity, stacks: List<ItemStack>): Boolean {
        val simulated = ItemStackHandler(entity.inventory.slots)

        for (slot in 0 until simulated.slots) {
            simulated.setStackInSlot(slot, entity.inventory.getStackInSlot(slot))
        }

        for (stack in stacks) {
            if (!canInsertItemStack(entity, simulated, stack, true, false)) {
                return false
            }
        }

        return true
    }

    @JvmStatic
    fun canExtractItem(entity: KoboldEntity, stack: ItemStack): Boolean = canInsertItemStack(entity, entity.inventory, stack, false, true)

    @JvmStatic
    fun canInsertItemStack(entity: KoboldEntity, handler: ItemStackHandler, stack: ItemStack, simulate: Boolean, extract: Boolean): Boolean {
        for (slot in 0 until handler.slots) {
            val existing = handler.getStackInSlot(slot)
            // 1.12: same item and same metadata
            if (!existing.isEmpty && ItemStack.isSameItem(existing, stack)) {
                val maxStack = existing.maxStackSize
                if (maxStack > stack.count + existing.count) {
                    if (!simulate) {
                        existing.count = existing.count + stack.count
                    }

                    return true
                }

                val space = maxStack - existing.count
                existing.count = maxStack
                stack.count = stack.count - space
            }
        }

        for (slot in 0 until handler.slots) {
            val slotStack = handler.getStackInSlot(slot)
            if (slotStack.item === Items.AIR) {
                if (!simulate) {
                    handler.setStackInSlot(slot, stack)
                }

                return true
            }
        }

        if (simulate) {
            return false
        }

        if (!extract) {
            return false
        }

        val itemEntity = ItemEntity(entity.level(), entity.x, entity.y, entity.z, stack)
        entity.level().addFreshEntity(itemEntity)
        return false
    }
}
