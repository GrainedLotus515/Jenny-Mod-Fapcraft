package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.RemoveItemsPacket
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SoundHandler
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * The girl interaction menu: the "action.names.*" button list (follow me / go
 * home / set new home / equipment plus the girl's dynamic action buttons with
 * item costs) shown when right-clicking a girl. Buttons are sent to the server
 * via `girl.doAction(actionName, playerUUID)`, which drives the girl's
 * state machine (actions requiring a boy cam/anchor etc.).
 *
 * **Cost handling.** Dynamic action buttons (id >= 5) may carry an item
 * cost (`actionCosts`); non-creative players must own a matching stack
 * or the action is refused with a chat line and a sad sound. The button click
 * also removes the paid items via [RemoveItemsPacket].
 *
 * CLIENT-side only. The girl's equipment is previewed from her
 * synced data entries (`WEAPON`, `BOW`,
 * `HELMET_SLOT`, ...) with animated slide-in. Closing the screen calls
 * `girl.onInteractionMenuClosed()` to reset the interaction state.
 *
 * **Pitfall (button overflow fix):** the static-button and action-button
 * Y counters must stay `int`. A decompiler artifact typed them
 * `byte`, so the 3rd button (Y = 130) overflowed to a negative Y and
 * the bottom buttons (incl. "equipment", the armour display) vanished from
 * screen. Jar-verified against the original: it uses plain `int`
 * (`iinc` in bytecode, no `i2b`).
 *
 * 1.21 port: buttons are [Button.builder] widgets rebuilt every frame (as the
 * 1.12 button list was); `actionPerformed` / `onButtonClick` take the 1.12
 * button id. The 1.12 metadata comparison is [ItemStack.isSameItem]. The
 * equipment preview reads the `AbstractGirlNpcEntity` keys only for girls of
 * that type (1.21 throws on undefined keys). 1.12 drew no background.
 */
open class GirlInventoryScreen : Screen {
    @JvmField
    val girl: BaseGirlEntity

    @JvmField
    val player: Player

    @JvmField
    val actionNames: Array<String>

    @JvmField
    val actionCosts: Array<ItemStack?>?

    @JvmField
    var dataManager: SynchedEntityData

    @JvmField
    val isReady: Boolean

    @JvmField
    var animProgress: Float = 0.0f

    @JvmField
    var animProgress2: Float = 0.0f

    @JvmField
    var labelIds: Array<String> = arrayOf("action.names.followme", "action.names.stopfollowme", "action.names.gohome", "action.names.setnewhome", "action.names.equipment")

    @JvmField
    var scrollOffsets: IntArray = intArrayOf(0, 0, 0, 0, 0)

    @JvmField
    var buttonWidths: IntArray = intArrayOf(64, 80, 47, 32, 96)

    @JvmField
    var starCounts: IntArray = intArrayOf(4, 4, 5, 5, 4)

    @JvmField
    var maxScrollOffsets: IntArray = intArrayOf(50, 90, 50, 80, 60)

    constructor(girl: BaseGirlEntity, player: Player) : super(Component.empty()) {
        this.girl = girl
        this.player = player
        this.actionNames = arrayOf()
        this.actionCosts = arrayOf()
        this.isReady = true
        this.dataManager = girl.getEntityData()
    }

    constructor(girl: BaseGirlEntity, player: Player, actionNames: Array<String>, actionCosts: Array<ItemStack>?, isReady: Boolean) : super(Component.empty()) {
        this.girl = girl
        this.player = player
        this.actionNames = actionNames
        @Suppress("UNCHECKED_CAST")
        this.actionCosts = actionCosts as Array<ItemStack?>?
        this.isReady = isReady
        this.dataManager = girl.getEntityData()
    }

    override fun isPauseScreen(): Boolean = false

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    }

    /**
     * Releases the girl's interaction state (`girl.onInteractionMenuClosed()`) when the menu
     * closes, so the girl returns to idle behavior. Must not be removed —
     * without it the girl stays locked in interaction mode.
     */
    override fun removed() {
        super.removed()
        this.girl.onInteractionMenuClosed()
    }

    /**
     * Button handler. For costed dynamic actions (non-creative): validates the
     * player has a stack matching the cost (item, count, metadata), removes it
     * via [RemoveItemsPacket] and executes; otherwise refuses with a chat
     * line and sad sound. Static buttons (id < 5) execute directly.
     */
    open fun actionPerformed(buttonId: Int) {
        val costs = this.actionCosts
        val cost = if (buttonId >= 5 && costs != null) costs[buttonId - 5] else null
        if (buttonId >= 5 && cost != null && !this.player.abilities.instabuild) {
            for (stack in this.player.inventory.items) {
                if (ItemStack.isSameItem(stack, cost) && stack.count >= cost.count) {
                    PacketHandler.networkWrapper.sendToServer(RemoveItemsPacket(this.player.uuid, cost))
                    this.onButtonClick(buttonId)
                    return
                }
            }

            this.player.sendSystemMessage(Component.literal("<" + this.girl.name.string + "> you cannot afford that..."))
            this.girl.playSound(SoundHandler.GIRLS_JENNY_SADOH[1])
        } else {
            this.onButtonClick(buttonId)
        }
    }

    /**
     * Executes the clicked action on the girl
     * (`girl.doAction(actionName, player.uuid)`) and closes
     * the screen. This is the single funnel for every interaction button — the
     * action name must match the `action.names.*` translation keys the
     * server-side `doAction` switches on.
     */
    open fun onButtonClick(buttonId: Int) {
        val actionName: String = if (buttonId < 5) {
            this.labelIds[buttonId]
        } else {
            this.actionNames[buttonId - 5]
        }

        this.girl.doAction(actionName, this.player.uuid)
        net.minecraft.client.Minecraft.getInstance().setScreen(null) // client-only screen: a container-close packet would close a menu the server just opened
    }

    /**
     * Rebuilds the button list every frame: dynamic action buttons slide in
     * from the right (with item-cost icons + tooltips when applicable), then
     * the static interaction buttons and the girl preview animate in. Button
     * layout depends on two staged animation progress values.
     */
    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
        val focusIndex = WidgetFocus.focusedIndex(this)
        this.clearWidgets()
        val screenWidth = guiGraphics.guiWidth()
        val screenHeight = guiGraphics.guiHeight()
        val tickLength = Minecraft.getInstance().timer.gameTimeDeltaTicks
        this.animProgress = Math.min(1.0f, this.animProgress + tickLength / 5.0f)
        if (this.animProgress == 1.0f) {
            this.animProgress2 = Math.min(1.0f, this.animProgress2 + tickLength / 5.0f)
        }

        val tooltipX = RotationHelper.lerp(115.0f, 161.0f, this.animProgress2).toInt()
        val itemX = RotationHelper.lerp(91.0f, 137.0f, this.animProgress2).toInt()
        val buttonX = RotationHelper.lerp(-30.0f, 120.0f, this.animProgress).toInt()
        var buttonY = 70
        var itemY = 52
        var itemZ = 68

        for (id in 5 until this.actionNames.size + 5) {
            val cost = this.actionCosts?.getOrNull(id - 5)
            if (this.animProgress2 > 0.0f && cost != null && cost.count != 0) {
                // 1.12 zLevel = -300 for the cost tooltip + icon
                val pose = guiGraphics.pose()
                pose.pushPose()
                pose.translate(0.0f, 0.0f, -300.0f)
                this.drawTooltip(guiGraphics, listOf(cost.count.toString() + "x    "), screenWidth - tooltipX, screenHeight - itemY, this.font)
                guiGraphics.renderItem(cost, screenWidth - itemX, screenHeight - itemZ)
                pose.popPose()
            }

            val buttonId = id
            this.addRenderableWidget(
                Button.builder(Component.literal(I18n.get(this.actionNames[id - 5]))) { this.actionPerformed(buttonId) }
                    .bounds(screenWidth - buttonX, screenHeight - buttonY, 100, 20)
                    .build()
            )
            buttonY += 30
            itemY += 30
            itemZ += 30
        }

        if (this.isReady) {
            this.drawGirlPreview(guiGraphics, mouseX, mouseY)
        }
        WidgetFocus.restore(this, focusIndex)
    }

    private fun equipment(key: EntityDataAccessor<ItemStack>): ItemStack =
        if (this.girl is AbstractGirlNpcEntity) this.dataManager.get(key) else ItemStack.EMPTY

    /**
     * Draws the girl's equipped items (weapon/bow/armor from the synced data)
     * and the five static action buttons with animated star icons; the
     * follow/equipment buttons get a per-button scroll offset driven by mouse
     * hover. Matches the original jar exactly: when the girl has a master the
     * "follow me" button is skipped (id 0 → 1), otherwise "stop following" is
     * skipped (id 1 → 2); the button box starts narrow (width 23 + scroll
     * offset) and its label only appears once the hover scroll exceeds 14 —
     * the buttons slide open under the cursor. The Y counter must be an
     * `int`: a decompiler artifact typed it `byte`, which overflowed
     * at the 3rd button (130 > 127 → negative Y) and hid the bottom buttons.
     */
    open fun drawGirlPreview(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val itemX = RotationHelper.lerp(-30.0f, 120.0f, this.animProgress).toInt()
        guiGraphics.renderItem(equipment(AbstractGirlNpcEntity.WEAPON), itemX - 105, 68)
        guiGraphics.renderItem(equipment(AbstractGirlNpcEntity.BOW), itemX - 105, 87)
        guiGraphics.renderItem(equipment(AbstractGirlNpcEntity.HELMET_SLOT), itemX - 105, 109)
        guiGraphics.renderItem(equipment(AbstractGirlNpcEntity.CHEST_SLOT), itemX - 105, 127)
        guiGraphics.renderItem(equipment(AbstractGirlNpcEntity.LEGS_SLOT), itemX - 105, 146)
        guiGraphics.renderItem(equipment(AbstractGirlNpcEntity.BOOTS_SLOT), itemX - 105, 166)
        if (this.animProgress2 != 0.0f) {
            val hasMaster = this.dataManager.get(BaseGirlEntity.MASTER) != ""
            val xStart = 35
            var y = 70

            var id = 0
            while (id < 5) {
                if (id == 0 && hasMaster) {
                    id = 1
                } else if (id == 1 && !hasMaster) {
                    id = 2
                }

                if (mouseX >= xStart && mouseX <= xStart + 23 + this.scrollOffsets[id] && mouseY >= y && mouseY <= y + 20) {
                    this.scrollOffsets[id] = Math.min(this.maxScrollOffsets[id], this.scrollOffsets[id] + 7)
                } else {
                    this.scrollOffsets[id] = Math.max(0, this.scrollOffsets[id] - 7)
                }

                val label = StringBuilder(I18n.get(this.labelIds[id]))

                for (star in 0 until this.starCounts[id]) {
                    label.append(" ")
                }

                guiGraphics.blit(GUI_TEXTURE, this.scrollOffsets[id] + xStart - 18 + RotationHelper.lerp(0.0f, 23.0f, this.animProgress2).toInt(), y + 2, this.buttonWidths[id], 0, 16, 16)
                val buttonText = if (this.scrollOffsets[id] > 14) label.toString() else ""
                val buttonId = id
                this.addRenderableWidget(
                    Button.builder(Component.literal(buttonText)) { this.actionPerformed(buttonId) }
                        .bounds(xStart + 1, y, (RotationHelper.lerp(0.0f, 23.0f, this.animProgress2) + this.scrollOffsets[id].toFloat()).toInt(), 20)
                        .build()
                )
                y += 30
                id++
            }

            guiGraphics.blit(GUI_TEXTURE, itemX - 113, 60, 0, 0, 32, 130)
        }
    }

    /**
     * Hand-rolled tooltip renderer (dark border + gradient fill) drawn outside
     * the vanilla tooltip system so cost hints can appear near the item icon.
     */
    open fun drawTooltip(guiGraphics: GuiGraphics, lines: List<String>, x: Int, y: Int, font: Font) {
        var maxWidth = 0

        for (line in lines) {
            val width = this.font.width(line)
            if (width > maxWidth) {
                maxWidth = width
            }
        }

        var tooltipX = x + 12
        var tooltipY = y - 12
        var tooltipHeight = 8
        if (lines.size > 1) {
            tooltipHeight += 2 + (lines.size - 1) * 10
        }

        if (tooltipX + maxWidth > this.width) {
            tooltipX -= 28 + maxWidth
        }

        if (tooltipY + tooltipHeight + 6 > this.height) {
            tooltipY = this.height - tooltipHeight - 6
        }

        guiGraphics.fillGradient(tooltipX - 3, tooltipY - 4, tooltipX + maxWidth + 3, tooltipY - 3, -267386864, -267386864)
        guiGraphics.fillGradient(tooltipX - 3, tooltipY + tooltipHeight + 3, tooltipX + maxWidth + 3, tooltipY + tooltipHeight + 4, -267386864, -267386864)
        guiGraphics.fillGradient(tooltipX - 3, tooltipY - 3, tooltipX + maxWidth + 3, tooltipY + tooltipHeight + 3, -267386864, -267386864)
        guiGraphics.fillGradient(tooltipX - 4, tooltipY - 3, tooltipX - 3, tooltipY + tooltipHeight + 3, -267386864, -267386864)
        guiGraphics.fillGradient(tooltipX + maxWidth + 3, tooltipY - 3, tooltipX + maxWidth + 4, tooltipY + tooltipHeight + 3, -267386864, -267386864)
        guiGraphics.fillGradient(tooltipX - 3, tooltipY - 3 + 1, tooltipX - 3 + 1, tooltipY + tooltipHeight + 3 - 1, 1347420415, 1344798847)
        guiGraphics.fillGradient(tooltipX + maxWidth + 2, tooltipY - 3 + 1, tooltipX + maxWidth + 3, tooltipY + tooltipHeight + 3 - 1, 1347420415, 1344798847)
        guiGraphics.fillGradient(tooltipX - 3, tooltipY - 3, tooltipX + maxWidth + 3, tooltipY - 3 + 1, 1347420415, 1347420415)
        guiGraphics.fillGradient(tooltipX - 3, tooltipY + tooltipHeight + 2, tooltipX + maxWidth + 3, tooltipY + tooltipHeight + 3, 1344798847, 1344798847)

        for (i in lines.indices) {
            val text = lines[i]
            guiGraphics.drawString(this.font, text, tooltipX, tooltipY, -1, true)
            if (i == 0) {
                tooltipY += 2
            }

            tooltipY += 10
        }
    }

    companion object {
        @JvmField
        val GUI_TEXTURE: ResourceLocation = Main.rl("textures/gui/girlinventory.png")
    }
}
