package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.StartStandingSexAnimationPacket
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.Component
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientChatEvent
import java.util.UUID

/**
 * Client-side consent prompt for the gender-swap (standing sex) action: when a
 * player requests a swap action on another player, that player receives a chat
 * prompt with accept/decline lines and answers by typing the corresponding
 * word into chat.
 *
 * **Flow.** [onButtonClicked] installs the pending
 * [SwapButton] and prints the purple prompt (with a 1200-tick countdown
 * shown by [tick]). [onClientChat] intercepts
 * the reply: "accept" sends [StartStandingSexAnimationPacket] to the
 * server, "decline" just confirms; both cancel the chat event and clear the
 * pending button.
 *
 * CLIENT-side only, singleton ([instance]); only one prompt can be
 * pending at a time. 1.21: the prompt lines go to the local chat (1.12
 * `EntityPlayer.sendMessage` on a client player entity printed locally).
 */
open class GenderSwapScreen {
    private var activeButton: SwapButton? = null

    /**
     * Decrements the pending prompt's countdown and clears it with a timeout
     * message when it reaches zero. Call once per CLIENT tick.
     */
    open fun tick() {
        val inst = instance ?: return
        val button = inst.activeButton
        if (button != null) {
            button.countdown -= 1.0f
            if (button.countdown <= 0.0f) {
                Minecraft.getInstance().player
                    ?.sendSystemMessage(Component.literal(ChatFormatting.DARK_PURPLE.toString() + I18n.get("genderswap.sexpromt.timeout")))
                this.clearActiveButton()
            }
        }
    }

    open fun getActiveButton(): SwapButton? = instance?.activeButton

    open fun clearActiveButton() {
        instance?.activeButton = null
    }

    /**
     * Installs `button` as the pending swap button and prints the prompt
     * (who asked whom for which action, accept/decline line, auto-deletion
     * notice) to the asked player. No-op unless both involved players are
     * present in the world.
     */
    open fun onButtonClicked(button: SwapButton) {
        val world = Minecraft.getInstance().player?.level() ?: return
        val player = world.getPlayerByUUID(button.playerUUID)
        val girl = world.getPlayerByUUID(button.girlUUID)
        if (girl != null && player != null) {
            val requestLine = Component.literal(
                ChatFormatting.LIGHT_PURPLE.toString() +
                    (if (button.isMale) girl.gameProfile.name else player.gameProfile.name) +
                    " " +
                    ChatFormatting.DARK_PURPLE +
                    I18n.get("genderswap.sexpromt.playerxaskedfory") +
                    " " +
                    ChatFormatting.LIGHT_PURPLE +
                    I18n.get(button.label)
            )
            val deleteLine = Component.literal(ChatFormatting.DARK_PURPLE.toString() + I18n.get("genderswap.sexpromt.autodeletion"))
            val choiceLine = Component.literal(
                ChatFormatting.DARK_PURPLE.toString() +
                    "[ " +
                    ChatFormatting.LIGHT_PURPLE +
                    I18n.get("genderswap.sexpromt.accept") +
                    ChatFormatting.DARK_PURPLE +
                    " | " +
                    ChatFormatting.LIGHT_PURPLE +
                    I18n.get("genderswap.sexpromt.decline") +
                    ChatFormatting.DARK_PURPLE +
                    " ]"
            )
            val chat = Minecraft.getInstance().gui.chat
            chat.addMessage(requestLine)
            chat.addMessage(deleteLine)
            chat.addMessage(choiceLine)
            this.activeButton = button
        }
    }

    /**
     * Chat interception for the prompt reply: "accept" sends the swap request
     * packet, "decline" confirms in chat; both clear the pending button and
     * swallow the message. Matching is case-insensitive against the localized
     * accept/decline strings.
     */
    @SubscribeEvent
    open fun onClientChat(event: ClientChatEvent) {
        val inst = instance ?: return
        if (inst.getActiveButton() != null) {
            val message = event.message.lowercase()
            if (message == I18n.get("genderswap.sexpromt.accept").lowercase()) {
                val button = inst.getActiveButton()!!
                this.sendSwapRequest(button.label, button.playerUUID, button.girlUUID)
                this.clearActiveButton()
                event.isCanceled = true
            }

            if (message == I18n.get("genderswap.sexpromt.decline").lowercase()) {
                Minecraft.getInstance().player
                    ?.sendSystemMessage(Component.literal(ChatFormatting.DARK_PURPLE.toString() + I18n.get("genderswap.sexpromt.declineconformation")))
                this.clearActiveButton()
                event.isCanceled = true
            }
        }
    }

    /**
     * Sends the standing-sex animation request to the server.
     *
     * @param label the localized action label, e.g. `genderswap.sexpromt.missionary`
     * @param playerUuid the asked player's UUID (the girl side)
     * @param girlUuid the requesting player's UUID (the boy side)
     */
    open fun sendSwapRequest(label: String, playerUuid: UUID, girlUuid: UUID) {
        PacketHandler.networkWrapper.sendToServer(StartStandingSexAnimationPacket(playerUuid, girlUuid, label))
    }

    /**
     * A pending consent prompt: the requested action label, the two involved
     * UUIDs, whether the requester is male, and the remaining validity time
     * (starts at 1200 ticks, drained by [GenderSwapScreen.tick]).
     */
    open class SwapButton(label: String, girlUuid: UUID, playerUuid: UUID, isMale: Boolean) {
        @JvmField
        var label: String = label

        @JvmField
        var girlUUID: UUID = girlUuid

        @JvmField
        var playerUUID: UUID = playerUuid

        @JvmField
        var countdown: Float = 1200.0f

        @JvmField
        var isMale: Boolean = isMale
    }

    companion object {
        @JvmField
        var instance: GenderSwapScreen? = null
    }
}
