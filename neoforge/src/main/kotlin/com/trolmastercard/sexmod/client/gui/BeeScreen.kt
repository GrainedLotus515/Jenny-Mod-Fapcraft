package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.ThreadNames
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent

/**
 * Shared transition overlay / black-screen effect used by the girls' scenes
 * (not only bees): when visible it covers the whole screen with an animated
 * scrolling texture for about a second, then hides itself. While animating it
 * also hides the [HornyMeterHud].
 *
 * **Usage contract.** Scene code calls [runOnMainThread]
 * (which makes the overlay visible and schedules the given action) or
 * [enableInteraction]; the overlay auto-resets after `animTimer`
 * exceeds 69 ticks of animation. `isVisible` is a static flag — exactly
 * one transition can play at a time.
 *
 * CLIENT-side only, rendered via the GUI overlay event (it never takes input
 * focus; [isPauseScreen] returns `false`). 1.21: [RenderGuiEvent.Post];
 * `mc.getTickLength()` is the frame delta in ticks.
 */
open class BeeScreen : Screen(Component.empty()) {
    override fun isPauseScreen(): Boolean = false

    /**
     * Renders the animated transition: three tiled strips of the trade texture
     * slide horizontally (speed depends on gui scale) followed by the mirrored
     * strip and a solid black band, all translated by a cosine-lerped offset.
     * After 30 animation ticks the horny meter is hidden; after 69 the overlay
     * resets and un-visibilizes itself.
     */
    @SubscribeEvent
    open fun onRenderGameOverlay(event: RenderGuiEvent.Post) {
        if (isVisible) {
            val mc = Minecraft.getInstance()
            val guiGraphics = event.guiGraphics
            animTimer = animTimer + event.partialTick.gameTimeDeltaTicks * 0.75f
            val guiScale = mc.options.guiScale().get()
            val offsetX: Float = if (guiScale == 1) {
                RotationHelper.lerpDouble(-1800.0, 1000.0, 0.5 * Math.cos(animTimer / 25.0) + 0.5).toFloat()
            } else if (guiScale == 2) {
                RotationHelper.lerpDouble(-900.0, 750.0, 0.5 * Math.cos(animTimer / 25.0) + 0.5).toFloat()
            } else {
                RotationHelper.lerpDouble(-900.0, 600.0, 0.5 * Math.cos(animTimer / 25.0) + 0.5).toFloat()
            }

            val pose = guiGraphics.pose()
            pose.pushPose()
            try {
                if (guiScale == 1) {
                    pose.scale(2.0f, 2.0f, 2.0f)
                }

                if (guiScale == 2) {
                    pose.scale(1.5f, 1.5f, 1.5f)
                }

                val v = (animTimer * 1.5).toInt()
                blitAt(guiGraphics, TRADE_TEXTURE, offsetX, 0.0f, 0, v, 256, 256)
                blitAt(guiGraphics, TRADE_TEXTURE, offsetX, 256.0f, 0, v, 256, 256)
                blitAt(guiGraphics, TRADE_TEXTURE, offsetX, 512.0f, 0, v, 256, 256)
                blitAt(guiGraphics, MIRROR_TEXTURE, offsetX + 600.0f, 0.0f, 0, v, 256, 256)
                blitAt(guiGraphics, MIRROR_TEXTURE, offsetX + 600.0f, 256.0f, 0, v, 256, 256)
                blitAt(guiGraphics, MIRROR_TEXTURE, offsetX + 600.0f, 512.0f, 0, v, 256, 256)
                blitAt(guiGraphics, BLANK_TEXTURE, offsetX + 200.0f, 0.0f, 0, 0, 400, 256)
                blitAt(guiGraphics, BLANK_TEXTURE, offsetX + 200.0f, 256.0f, 0, 0, 400, 256)
                blitAt(guiGraphics, BLANK_TEXTURE, offsetX + 200.0f, 512.0f, 0, 0, 400, 256)
                if (animTimer > 30.0) {
                    HornyMeterHud.hideHornyMeter()
                }

                if (animTimer > 69.0) {
                    animTimer = 0.0
                    isVisible = false
                }
            } finally {
                pose.popPose()
            }
        }
    }

    companion object {
        const val TRADE_TIMEOUT: Int = 1200
        private var isVisible = false
        private var animTimer = 0.0

        @JvmField
        var TRADE_TEXTURE: ResourceLocation = Main.rl("textures/gui/transitionscreen.png")

        @JvmField
        var MIRROR_TEXTURE: ResourceLocation = Main.rl("textures/gui/mirroredtransitionscreen.png")

        @JvmField
        var BLANK_TEXTURE: ResourceLocation = Main.rl("textures/gui/blackscreen.png")

        @JvmStatic
        fun isBeeScreenVisible(): Boolean = isVisible

        @JvmStatic
        fun enableInteraction() {
            isVisible = true
        }

        /**
         * Shows the overlay and runs `action` on a daemon thread with a
         * [TRADE_TIMEOUT] ms delay (see `ThreadNames.createDaemonThread`),
         * so the screen covers a scene transition while the action executes. Do not
         * call from the render thread expecting immediate execution.
         */
        @JvmStatic
        fun runOnMainThread(action: Runnable) {
            isVisible = true
            ThreadNames.createDaemonThread(1200, action)
        }

        /** 1.12 `drawTexturedModalRect(float x, float y, ...)`: blit at a sub-pixel position. */
        private fun blitAt(guiGraphics: GuiGraphics, texture: ResourceLocation, x: Float, y: Float, u: Int, v: Int, w: Int, h: Int) {
            val pose = guiGraphics.pose()
            pose.pushPose()
            pose.translate(x, y, 0.0f)
            guiGraphics.blit(texture, 0, 0, u, v, w, h)
            pose.popPose()
        }
    }
}
