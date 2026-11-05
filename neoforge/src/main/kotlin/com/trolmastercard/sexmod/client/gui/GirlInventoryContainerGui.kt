package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.LunaEntity
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.UploadInventoryToServerPacket
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * GUI screen for Luna's 7-slot equipment chest ([GirlInventoryContainer2]
 * with the rod slot). Draws the mod's `girlinventory.png` texture.
 *
 * **Save flow.** On close, snapshots this screen's own
 * [GirlInventoryContainer2] (player main inventory 0..35, Luna's 7 slots
 * 36..42) and sends it to the server as [UploadInventoryToServerPacket].
 * CLIENT-side only.
 *
 * 1.21: `(menu, inv, title)` constructor (menu type `sexmod:luna_equipment`).
 * 1.12 drew no labels; [renderLabels] is overridden to keep it that way.
 */
open class GirlInventoryContainerGui(menu: GirlInventoryContainer2, inv: Inventory, title: Component) :
    AbstractContainerScreen<GirlInventoryContainer2>(menu, inv, title) {
    @JvmField
    var girlUUID: UUID = menu.girlUUID

    @JvmField
    var lunaEntity: LunaEntity? = menu.lunaEntity

    @JvmField
    var playerUUID: UUID = inv.player.uuid

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
        this.renderTooltip(guiGraphics, mouseX, mouseY)
    }

    override fun renderLabels(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
    }

    /**
     * Serializes Luna's 7 equipment slots + the player's main inventory into a
     * 43-element array and uploads it via [UploadInventoryToServerPacket].
     * Reads this screen's own container only (never the static list), so a close
     * uploads exactly once.
     */
    override fun removed() {
        super.removed()
        val container = this.menu
        val luna = this.lunaEntity ?: return
        val player = Minecraft.getInstance().player ?: return
        val stacks = Array<ItemStack>(43) { ItemStack.EMPTY }
        val main = player.inventory.items
        for (i in main.indices) {
            stacks[i] = main[i]
        }
        stacks[36] = container.getSlot(0).item
        stacks[37] = container.getSlot(1).item
        stacks[38] = container.getSlot(2).item
        stacks[39] = container.getSlot(3).item
        stacks[40] = container.getSlot(4).item
        stacks[41] = container.getSlot(5).item
        stacks[42] = container.getSlot(6).item
        PacketHandler.networkWrapper.sendToServer(UploadInventoryToServerPacket(luna.getGirlId(), this.playerUUID, stacks))
    }

    override fun renderBg(guiGraphics: GuiGraphics, partialTicks: Float, mouseX: Int, mouseY: Int) {
        guiGraphics.blit(GUI_TEXTURE, this.width / 2 - 88, this.height / 2 - 7 - 24, 80, 142, 176, 114)
    }

    companion object {
        @JvmField
        val GUI_TEXTURE: ResourceLocation = Main.rl("textures/gui/girlinventory.png")
    }
}
