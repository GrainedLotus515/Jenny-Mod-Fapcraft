package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.networking.ClaimTribePacket
import com.trolmastercard.sexmod.networking.PacketHandler
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.UUID

/**
 * Small name-input screen for claiming a kobold tribe: a single text field
 * (max 15 chars) and a "set" button that sends [ClaimTribePacket] with
 * the trimmed name to the server. Does not pause the game.
 *
 * CLIENT-side only. Nothing is sent when the field is empty. 1.21: the field
 * is an [EditBox] widget; the 15-character clamp of `keyTyped` is applied by
 * its responder. 1.12 drew no background.
 */
open class TribeNameScreen(@JvmField var koboldId: UUID) : Screen(Component.literal("Name Tribe")) {
    @JvmField
    var nameField: EditBox? = null

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        super.init()
        val field = EditBox(this.font, this.width / 2 - 50, this.height / 2 - 10, 100, 20, Component.empty())
        field.isFocused = true
        // 1.12 keyTyped: clamps the tribe name to 15 characters while typing
        field.setResponder { text ->
            if (text.length > 15) {
                field.value = text.substring(0, 15)
            }
        }
        this.nameField = field
        this.addRenderableWidget(field)
        this.setInitialFocus(field)
        this.addRenderableWidget(
            Button.builder(Component.literal("set")) { button -> this.actionPerformed(button) }
                .bounds(this.width / 2 - 25, this.height / 2 + 20, 50, 20)
                .build()
        )
    }

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        guiGraphics.renderTooltip(this.font, Component.literal("Name Tribe"), this.width / 2 - 39, this.height / 2 - 10)
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
    }

    /**
     * Sends [ClaimTribePacket] with the entered name (non-empty after
     * trimming) and closes the screen.
     */
    open fun actionPerformed(button: Button) {
        val name = this.nameField?.value?.trim() ?: return
        if (name.isNotEmpty()) {
            val player = Minecraft.getInstance().player ?: return
            PacketHandler.networkWrapper.sendToServer(ClaimTribePacket(this.koboldId, player.uuid, name))
            player.closeContainer()
        }
    }
}
