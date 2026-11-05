package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
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
 * GUI for a generic girl's 6-slot equipment chest ([ChestContainer]:
 * weapon, bow, helmet, chestplate, pants, shoes), used by the non-Luna girls.
 *
 * **Save flow.** On close, snapshots this screen's own [ChestContainer]
 * (player main inventory 0..35, the 6 girl slots 36..41) and sends it to the
 * server via [UploadInventoryToServerPacket]. CLIENT-side only.
 *
 * 1.21: `(menu, inv, title)` constructor (menu type `sexmod:girl_equipment`).
 * 1.12 drew no labels; [renderLabels] is overridden to keep it that way.
 */
open class GirlInventoryContainerGui2(menu: ChestContainer, inv: Inventory, title: Component) :
    AbstractContainerScreen<ChestContainer>(menu, inv, title) {
    @JvmField
    var playerUUID: UUID = menu.girlUUID

    @JvmField
    var girlEntity: BaseGirlEntity? = menu.girl

    @JvmField
    var girlUUID: UUID = inv.player.uuid

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
        this.renderTooltip(guiGraphics, mouseX, mouseY)
    }

    override fun renderLabels(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
    }

    /**
     * Serializes the 6 girl equipment slots + the player's main inventory into
     * a 42-element array and uploads it via [UploadInventoryToServerPacket].
     * Reads this screen's own container only (never the static list), so a close
     * uploads exactly once.
     */
    override fun removed() {
        super.removed()
        val container = this.menu
        val girl = this.girlEntity ?: return
        val player = Minecraft.getInstance().player ?: return
        val stacks = Array<ItemStack>(42) { ItemStack.EMPTY }
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
        PacketHandler.networkWrapper.sendToServer(UploadInventoryToServerPacket(girl.getGirlId(), this.girlUUID, stacks))
    }

    override fun renderBg(guiGraphics: GuiGraphics, partialTicks: Float, mouseX: Int, mouseY: Int) {
        guiGraphics.blit(GUI_TEXTURE, this.width / 2 - 88, this.height / 2 - 7 - 24, 33, 16, 176, 114)
    }

    companion object {
        @JvmField
        val GUI_TEXTURE: ResourceLocation = Main.rl("textures/gui/girlinventory.png")
    }
}
