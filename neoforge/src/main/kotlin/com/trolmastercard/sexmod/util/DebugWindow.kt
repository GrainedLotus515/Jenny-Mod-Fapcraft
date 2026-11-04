package com.trolmastercard.sexmod.util

import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import javax.swing.JFrame

/**
 * Developer debug overlay window (obfuscated-era leftover, largely dormant):
 * on the first client tick it opens the [DebugWindow2] age warning.
 * CLIENT-ONLY; only registered when the JVM is not headless.
 */
open class DebugWindow : JFrame() {
    @JvmField
    var isVisible: Boolean = false

    @SubscribeEvent
    open fun onClientTick(event: ClientTickEvent.Post) {
        if (!this.isVisible) {
            this.isVisible = true
            DebugWindow2.showDebugWindow()
        }
    }
}
