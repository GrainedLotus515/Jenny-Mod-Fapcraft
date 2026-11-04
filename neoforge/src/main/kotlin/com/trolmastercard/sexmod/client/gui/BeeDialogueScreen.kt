package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BeeEntityBase
import com.trolmastercard.sexmod.networking.BeeOpenChestPacket
import com.trolmastercard.sexmod.networking.ChangeDataParameterPacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendCompanionHomePacket
import com.trolmastercard.sexmod.networking.SetNewHomePacket
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * Dialogue GUI for interacting with a bee companion ([BeeEntityBase]):
 * "follow me / stop following", "go home", "set home here" and — when the bee
 * is horny (HORNY_FLAG data entry) — opening the bee's chest inventory.
 *
 * **Flow.** Opened by right-clicking the bee. All actions are sent to the
 * server as packets (`ChangeDataParameterPacket` toggling the
 * `master` data parameter, `SendCompanionHomePacket`,
 * `SetNewHomePacket` at the bee's current position, or
 * [BeeOpenChestPacket] on the horny-icon click). The follow-mode buttons
 * flip a local flag and print localized feedback chat lines.
 *
 * CLIENT-side only. Does not pause the game; the buttons slide in with
 * `animProgress` on draw (the button list is rebuilt every frame, as in 1.12).
 * 1.12 drew no screen background.
 */
open class BeeDialogueScreen(bee: BeeEntityBase, player: Player) : Screen(Component.empty()) {
    @JvmField
    var beeEntity: BeeEntityBase = bee

    @JvmField
    var player: Player = player

    @JvmField
    var isFollowMode: Boolean = "" != bee.getEntityData().get(BaseGirlEntity.MASTER)

    @JvmField
    var animProgress: Double = 0.0

    override fun isPauseScreen(): Boolean = false

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    }

    /**
     * Builds the three dialogue buttons (follow toggle, go home, set home) and
     * the horny-bee icon; button positions and sizes animate in from the left
     * based on `animProgress`. Button list is rebuilt every frame.
     */
    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
        val focusIndex = WidgetFocus.focusedIndex(this)
        this.clearWidgets()
        val screenWidth = guiGraphics.guiWidth()
        this.animProgress = Math.min(1.0, this.animProgress + Minecraft.getInstance().timer.gameTimeDeltaTicks / 5.0f)
        this.addRenderableWidget(
            Button.builder(
                Component.literal(if (this.isFollowMode) I18n.get("action.names.stopfollowme") else I18n.get("action.names.followme"))
            ) { this.actionPerformed(0) }
                .bounds(screenWidth / 2 - 119 + (100.0 - 100.0 * this.animProgress).toInt(), 30, (this.animProgress * 100.0).toInt(), 20)
                .build()
        )
        this.addRenderableWidget(
            Button.builder(Component.literal(I18n.get("action.names.gohome"))) { this.actionPerformed(1) }
                .bounds(screenWidth / 2 + 19, 30, (this.animProgress * 100.0).toInt(), 20)
                .build()
        )
        guiGraphics.blit(GUI_TEXTURE, screenWidth / 2 - 7, 61 - (15.0 - this.animProgress * 15.0).toInt(), 32, 0, 15, 15)
        this.addRenderableWidget(
            Button.builder(Component.literal("")) { this.actionPerformed(2) }
                .bounds(screenWidth / 2 - 10, 59 - (15.0 - this.animProgress * 15.0).toInt(), 20, 20)
                .build()
        )
        guiGraphics.blit(GUI_TEXTURE, screenWidth / 2 - 20, 20, if (this.beeEntity.getEntityData().get(BeeEntityBase.HORNY_FLAG)) 0 else 40, 130, 40, 40)
        WidgetFocus.restore(this, focusIndex)
    }

    /**
     * Handles a click on the horny-bee chest icon (center of the screen, only
     * when `HORNY_FLAG` is set): sends [BeeOpenChestPacket] to the
     * server and runs the close hook, letting the server open the girl-inventory
     * chest container instead.
     */
    override fun mouseClicked(mouseX: Double, mouseY: Double, mouseButton: Int): Boolean {
        val screenWidth = this.width
        if (this.beeEntity.getEntityData().get(BeeEntityBase.HORNY_FLAG) && mouseX >= screenWidth / 2 - 20 && mouseX <= screenWidth / 2 + 20 && mouseY >= 20 && mouseY <= 60) {
            PacketHandler.networkWrapper.sendToServer(BeeOpenChestPacket(this.beeEntity.getGirlId(), this.player.uuid))
            this.removed()
        }

        return super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    /**
     * Dispatches the dialogue buttons to their server packets and closes the
     * screen. Button 0 toggles follow mode (writes the player UUID to the bee's
     * `master` data parameter, or clears it); button 1 sends the bee home;
     * button 2 pins the bee's current position as its new home.
     */
    open fun actionPerformed(buttonId: Int) {
        if (buttonId == 0) {
            if (this.isFollowMode) {
                PacketHandler.networkWrapper.sendToServer(ChangeDataParameterPacket(this.beeEntity.getGirlId(), "master", ""))
                this.player.sendSystemMessage(Component.literal(I18n.get("bee.dialogue.sad")))
            } else {
                PacketHandler.networkWrapper.sendToServer(ChangeDataParameterPacket(this.beeEntity.getGirlId(), "master", this.player.uuid.toString()))
                this.player.sendSystemMessage(Component.literal(I18n.get("bee.dialogue.exited")))
            }

            this.isFollowMode = !this.isFollowMode
            net.minecraft.client.Minecraft.getInstance().setScreen(null) // client-only screen: a container-close packet would close a menu the server just opened
        }

        if (buttonId == 1) {
            PacketHandler.networkWrapper.sendToServer(SendCompanionHomePacket(this.beeEntity.getGirlId()))
            net.minecraft.client.Minecraft.getInstance().setScreen(null) // client-only screen: a container-close packet would close a menu the server just opened
        }

        if (buttonId == 2) {
            PacketHandler.networkWrapper.sendToServer(SetNewHomePacket(this.beeEntity.getGirlId(), Vec3(this.beeEntity.x, this.beeEntity.y, this.beeEntity.z)))
            net.minecraft.client.Minecraft.getInstance().setScreen(null) // client-only screen: a container-close packet would close a menu the server just opened
            this.player.sendSystemMessage(Component.literal(I18n.get("bee.dialogue.home")))
        }
    }

    companion object {
        @JvmField
        val GUI_TEXTURE: ResourceLocation = Main.rl("textures/gui/girlinventory.png")
    }
}
