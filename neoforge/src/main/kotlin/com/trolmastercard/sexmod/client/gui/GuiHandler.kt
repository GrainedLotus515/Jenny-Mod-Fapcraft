package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.LunaEntity
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.Level
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.ItemStackHandler
import java.util.ConcurrentModificationException
import java.util.UUID

/**
 * Routes girl GUI ids to their menus/screens (1.12 Forge `IGuiHandler`).
 *
 * **GUI ids.** 0 = girl equipment (server:
 * [GirlInventoryContainer2] for Luna / [ChestContainer] otherwise;
 * client: [GirlInventoryContainerGui] / [GirlInventoryContainerGui2])
 * and 1 = girl 27-slot chest (server: [GirlInventoryContainer]; client:
 * [ChestContainerGui]). The entity is located by matching its block
 * position against the packet's x/y/z; UUIDs are random per open — the GUI
 * closes by matching the container instance in the static container lists.
 *
 * Server and client sides must return container/screen pairs with the same
 * slot layout or vanilla sync desyncs. Both lookups swallow
 * [ConcurrentModificationException] while scanning the shared girl list.
 *
 * 1.21 port (PORTING.md §2.11): an `object` (COMMON). [openGui] replaces
 * `player.openGui(Main.instance, id, ...)`: it builds the server menu like the
 * 1.12 `getServerGuiElement` and sends `guiId, x, y, z` as the menu's extra
 * data. The client menu constructors (`(containerId, Inventory, buf)`) read that
 * data through [readClientGirl] (the 1.12 `getClientGuiElement` lookup); the
 * screens are bound to the menu types in `ClientProxy` (`RegisterMenuScreensEvent`).
 */
object GuiHandler {

    /**
     * 1.12 `player.openGui(Main.instance, id, world, x, y, z)`. Does nothing if
     * no matching girl is found (1.12 returned a null container).
     */
    @JvmStatic
    fun openGui(player: ServerPlayer, id: Int, world: Level, x: Int, y: Int, z: Int) {
        val girl = findGirl(id, false, x, y, z) ?: return
        val provider = SimpleMenuProvider(
            { containerId, inventory, menuPlayer -> getServerGuiElement(containerId, id, menuPlayer, inventory, girl) },
            Component.literal(girl.getDisplayNameText())
        )
        player.openMenu(provider) { buf ->
            buf.writeVarInt(id)
            buf.writeInt(x)
            buf.writeInt(y)
            buf.writeInt(z)
        }
    }

    /**
     * SERVER-side: builds the server container for the given GUI id for the
     * girl found at the packet position.
     *
     * @return the container (the menu provider always has a girl here)
     */
    @JvmStatic
    fun getServerGuiElement(containerId: Int, id: Int, player: Player, inventory: Inventory, girl: BaseGirlEntity): AbstractContainerMenu? {
        if (id == 0) {
            if (girl is LunaEntity) {
                return GirlInventoryContainer2(containerId, girl, inventory, UUID.randomUUID())
            }

            return ChestContainer(containerId, girl, inventory, UUID.randomUUID())
        }

        if (id == 1 && girl is Container) {
            return GirlInventoryContainer(containerId, inventory, girl, player, UUID.randomUUID())
        }

        return null
    }

    /**
     * Scans the shared girl list for the girl at the given block position on the
     * requested side (1.12 `getServerGuiElement` / `getClientGuiElement` lookup).
     * Id 1 additionally requires the girl to be a [Container].
     */
    @JvmStatic
    fun findGirl(id: Int, clientSide: Boolean, x: Int, y: Int, z: Int): BaseGirlEntity? {
        if (id == 0) {
            try {
                for (girl in BaseGirlEntity.getGirlEntityList()) {
                    val pos = girl.blockPosition()
                    if (girl.level().isClientSide == clientSide && pos.x == x && pos.y == y && pos.z == z) {
                        return girl
                    }
                }
            } catch (cme: ConcurrentModificationException) {
                // ignored: girl list mutated mid-iteration; the container just is not found this tick
            }
        }

        if (id == 1) {
            try {
                for (girl2 in BaseGirlEntity.getGirlEntityList()) {
                    val pos = girl2.blockPosition()
                    if (girl2.level().isClientSide == clientSide && girl2 is Container && pos.x == x && pos.y == y && pos.z == z) {
                        return girl2
                    }
                }
            } catch (cme2: ConcurrentModificationException) {
                // ignored: girl list mutated mid-iteration; the container just is not found this tick
            }
        }

        return null
    }

    /**
     * CLIENT-side half: reads the extra data written by [openGui] and finds the
     * client copy of the girl (1.12 `getClientGuiElement`).
     */
    @JvmStatic
    fun readClientGirl(buf: RegistryFriendlyByteBuf): BaseGirlEntity? {
        val id = buf.readVarInt()
        val x = buf.readInt()
        val y = buf.readInt()
        val z = buf.readInt()
        return findGirl(id, true, x, y, z)
    }

    /** Placeholder handler used when the client cannot find the girl, so slot indices still match the server. */
    @JvmStatic
    fun emptyEquipmentHandler(): IItemHandler = ItemStackHandler(7)
}
