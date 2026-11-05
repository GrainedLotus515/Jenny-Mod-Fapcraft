package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ScreenEvent

/**
 * CLIENT: clears the player-girl tables when the title or multiplayer screen
 * opens (i.e. after leaving a world). CLIENT-ONLY; 1.21 `GuiOpenEvent` ->
 * [ScreenEvent.Opening].
 */
open class GuiOpenHandler {
    @SubscribeEvent
    open fun onGuiOpen(event: ScreenEvent.Opening) {
        val gui = event.newScreen
        if (gui is TitleScreen || gui is JoinMultiplayerScreen) {
            AbstractPlayerGirlEntity.playerGirlList.clear()
            AbstractPlayerGirlEntity.PLAYER_GIRL_BY_OWNER.clear()
            AbstractPlayerGirlEntity.playerGirlTableDirty = true
        }
    }
}
