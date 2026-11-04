package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.UploadInventoryToServerPacket
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * Screen for the girl's equipment chest ([GirlInventoryContainer], the
 * 27-slot girl inventory + vanilla player inventory), sized by the girl's row
 * count. Draws the generic-54 chest texture with a dynamic height and shows the
 * girl's name as title.
 *
 * **Save flow.** On close it snapshots this screen's own
 * [GirlInventoryContainer] (player main inventory 0..35, girl slots
 * 36..62) and sends [UploadInventoryToServerPacket] — the girl's
 * inventory is thus synced only when the GUI closes. CLIENT-side only.
 *
 * 1.21: `(menu, inv, title)` constructor (menu type `sexmod:girl_chest`); the
 * girl comes from the menu.
 */
open class ChestContainerGui(menu: GirlInventoryContainer, inv: Inventory, title: Component) :
    AbstractContainerScreen<GirlInventoryContainer>(menu, inv, title) {
    private val playerInventory: Inventory = inv
    private val chestInventory: Container = menu.getInventoryRef()
    private val rowCount: Int

    @JvmField
    var containerUUID: UUID = menu.girlUUID

    @JvmField
    var girl: BaseGirlEntity? = menu.girl

    @JvmField
    var girlUUID: UUID = inv.player.uuid

    init {
        this.rowCount = this.chestInventory.containerSize / 9
        this.imageHeight = 114 + this.rowCount * 18
        this.inventoryLabelY = this.imageHeight - 94
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
        this.renderTooltip(guiGraphics, mouseX, mouseY)
    }

    override fun renderLabels(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        guiGraphics.drawString(this.font, this.girl?.getDisplayNameText() ?: this.title.string, 8, 6, 4210752, false)
        guiGraphics.drawString(this.font, this.playerInventory.displayName.string, 8, this.imageHeight - 96 + 2, 4210752, false)
    }

    override fun renderBg(guiGraphics: GuiGraphics, partialTicks: Float, mouseX: Int, mouseY: Int) {
        val left = (this.width - this.imageWidth) / 2
        val top = (this.height - this.imageHeight) / 2
        guiGraphics.blit(CONTAINER_TEXTURE, left, top, 0, 0, this.imageWidth, this.rowCount * 18 + 17)
        guiGraphics.blit(CONTAINER_TEXTURE, left, top + this.rowCount * 18 + 17, 0, 126, this.imageWidth, 96)
    }

    /**
     * Serializes the current container contents (player inventory + girl's 27
     * equipment slots, index 36+) into a 63-element stack array and sends it to
     * the server as [UploadInventoryToServerPacket]. Reads this screen's
     * own container only (never the static list), so a close uploads exactly
     * once and the girl's gear is persisted.
     */
    override fun removed() {
        super.removed()
        val container = this.menu
        val girl = this.girl ?: return
        val player = Minecraft.getInstance().player ?: return
        val stacks = Array<ItemStack>(63) { ItemStack.EMPTY }
        val main = player.inventory.items
        for (i in main.indices) {
            stacks[i] = main[i]
        }

        for (i in 0 until 27) {
            stacks[i + 36] = container.getSlot(i).item
        }

        PacketHandler.networkWrapper.sendToServer(UploadInventoryToServerPacket(girl.getGirlId(), this.girlUUID, stacks))
    }

    companion object {
        private val CONTAINER_TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png")
    }
}
