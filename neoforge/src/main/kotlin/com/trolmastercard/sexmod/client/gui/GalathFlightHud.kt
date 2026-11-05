package com.trolmastercard.sexmod.client.gui

import com.mojang.blaze3d.systems.RenderSystem
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.Rectangle
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.ThreadNames
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent

/**
 * HUD showing Galath's flight boost charges (up to 3 pips). Rendered while
 * Galath is being ridden/flying; each pip has an animated charge bar that
 * slides up when a charge is spent and blinks while it regenerates.
 *
 * **State.** [canUseCharge] gates charge consumption (3s cooldown
 * between uses); spent charges regenerate one per 5s
 * ([updateChargeRegen], driven from the overlay render). UI fades in
 * on [showHud] and out 500ms after [startFadeOut].
 *
 * CLIENT-side only; static state, single instance. Timing is wall-clock
 * (`System.currentTimeMillis()`), not game ticks. 1.21: [RenderGuiEvent.Post];
 * `GlStateManager.color` is `GuiGraphics.setColor` with blending enabled.
 */
open class GalathFlightHud {
    /**
     * Regenerates one charge every 5 seconds, measured from the later of the
     * last use and the last regeneration. No-op while at max charges.
     */
    open fun updateChargeRegen() {
        if (availableCharges != 3) {
            val now = System.currentTimeMillis()
            if (now - Math.max(lastChargeUsedTime, lastRegenTime) >= 5000L) {
                availableCharges++
                lastRegenTime = now
            }
        }
    }

    /**
     * Draws the flight HUD (bottom-center): background bar, three pip shadows,
     * and the three charge bars. The whole UI fades in/out with a 500ms window
     * and auto-hides once the fade-out completes. Charge bar animations use
     * `easeInOutQuad` over 150ms per pip.
     */
    @SubscribeEvent
    open fun onRenderGameOverlay(event: RenderGuiEvent.Post) {
        this.updateChargeRegen()
        if (isUIVisible) {
            val guiGraphics = event.guiGraphics
            val screenWidth = guiGraphics.guiWidth()
            val screenHeight = guiGraphics.guiHeight()
            val centerX = screenWidth / 2
            val now = System.currentTimeMillis()
            if (now - uiFadeOutStartTime > 500L) {
                hideHud()
            } else {
                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
                try {
                    var alpha: Float = if (now < uiFadeInStartTime + 500L) {
                        (now - uiFadeInStartTime).toFloat() / 500.0f
                    } else if (now < uiFadeOutStartTime + 500L) {
                        1.0f + (uiFadeOutStartTime - now).toFloat() / 500.0f
                    } else {
                        1.0f
                    }

                    alpha = ThreadNames.clampFloat(alpha, 0.0f, 1.0f)
                    guiGraphics.setColor(1.0f, 1.0f, 1.0f, alpha)
                    this.drawChargeText(guiGraphics, BACKGROUND_BOUNDS, centerX - BACKGROUND_BOUNDS.width / 2, screenHeight - 70)
                    this.drawChargeText(guiGraphics, ICON_SHADOWS_BOUNDS, (centerX - 1.5f * CHARGE_ACTIVE_BOUNDS.width + 1.0f).toInt(), screenHeight - 70 + 3)
                    this.drawChargeText(guiGraphics, ICON_SHADOWS_BOUNDS, centerX - CHARGE_ACTIVE_BOUNDS.width / 2 + 1, screenHeight - 70 + 3)
                    this.drawChargeText(guiGraphics, ICON_SHADOWS_BOUNDS, centerX + CHARGE_ACTIVE_BOUNDS.width / 2 + 1, screenHeight - 70 + 3)
                    val chargeProgress = RotationHelper.easeInOutQuad(Math.min(1.0f, (now - lastChargeUsedTime).toFloat() / 150.0f).toDouble()).toFloat()
                    val regenProgress = if (chargeProgress == 1.0f) ThreadNames.clampFloat(1.0f - (now - lastRegenTime).toFloat() / 500.0f, 0.0f, 1.0f) else 0.0f
                    this.drawChargeBar(guiGraphics, 1, -1.5f * CHARGE_ACTIVE_BOUNDS.width, regenProgress, chargeProgress, centerX, screenHeight, alpha)
                    this.drawChargeBar(guiGraphics, 2, -CHARGE_ACTIVE_BOUNDS.width / 2.0f, regenProgress, chargeProgress, centerX, screenHeight, alpha)
                    this.drawChargeBar(guiGraphics, 3, CHARGE_ACTIVE_BOUNDS.width / 2.0f, regenProgress, chargeProgress, centerX, screenHeight, alpha)
                } finally {
                    guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f)
                    RenderSystem.disableBlend()
                }
            }
        }
    }

    /**
     * Renders one charge pip: active icon for an available charge, a
     * scale/translate animated "refill" pip while regenerating, and a
     * sine-blinking empty pip otherwise. Positions are computed per pip index
     * so the animation offsets never collide.
     */
    open fun drawChargeBar(guiGraphics: GuiGraphics, pipIndex: Int, xOffset: Float, regenProgress: Float, chargeProgress: Float, centerX: Int, screenHeight: Int, alpha: Float) {
        val spentScale: Float = if (availableCharges >= pipIndex) {
            0.0f
        } else if (availableCharges < pipIndex - 1) {
            1.0f
        } else {
            chargeProgress
        }

        val regenScale: Float = if (availableCharges == pipIndex) regenProgress else 0.0f

        val pipScale = 1.0f + spentScale * 0.075f + regenScale * -0.15f
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.scale(pipScale, pipScale, pipScale)
        pose.translate(
            spentScale * SPENT_PIP_X_OFFSETS[pipIndex - 1] + regenScale * REGEN_PIP_X_OFFSETS[pipIndex - 1],
            spentScale * -11.25f + regenScale * 37.5f,
            0.0f
        )
        guiGraphics.setColor(1.0f, 1.0f, 1.0f, alpha - spentScale - regenScale)
        this.drawChargeText(guiGraphics, CHARGE_ACTIVE_BOUNDS, (centerX + xOffset).toInt(), screenHeight - 70)
        guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f)
        guiGraphics.setColor(1.0f, 1.0f, 1.0f, Math.sin(Math.PI * spentScale).toFloat() * 0.5f)
        this.drawChargeText(guiGraphics, CHARGE_BLINK_BOUNDS, (centerX + xOffset).toInt(), screenHeight - 70)
        pose.popPose()
        guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    open fun drawChargeText(guiGraphics: GuiGraphics, bounds: Rectangle, x: Int, y: Int) {
        guiGraphics.blit(UI_TEXTURE, x, y, bounds.x, bounds.y, bounds.width, bounds.height)
    }

    companion object {
        @JvmField
        val UI_TEXTURE: ResourceLocation = Main.rl("textures/gui/galath_flight_ui.png")

        @JvmField
        val BACKGROUND_BOUNDS: Rectangle = Rectangle(0, 77, 128, 41)

        @JvmField
        val CHARGE_ACTIVE_BOUNDS: Rectangle = Rectangle(0, 0, 23, 36)

        @JvmField
        val CHARGE_BLINK_BOUNDS: Rectangle = Rectangle(0, 36, 23, 36)

        @JvmField
        val ICON_SHADOWS_BOUNDS: Rectangle = Rectangle(23, 2, 20, 31)
        const val FADE_DURATION: Long = 500L
        const val ANIMATION_SPEED: Float = 150.0f

        @JvmField
        val SPENT_PIP_X_OFFSETS: FloatArray = floatArrayOf(-14.25f, -15.5f, -16.875f)

        @JvmField
        val REGEN_PIP_X_OFFSETS: FloatArray = floatArrayOf(37.5f, 43.0f, 45.0f)

        @JvmField
        var isUIVisible: Boolean = false

        @JvmStatic
        val mc: Minecraft
            get() = Minecraft.getInstance()

        @JvmField
        var availableCharges: Int = 3

        @JvmField
        var lastChargeUsedTime: Long = 0L

        @JvmField
        var lastRegenTime: Long = 0L

        @JvmField
        var uiFadeInStartTime: Long = 0L

        @JvmField
        var uiFadeOutStartTime: Long = 9223372036854775307L

        @JvmStatic
        fun canUseCharge(): Boolean =
            if (availableCharges <= 0) false else System.currentTimeMillis() - lastChargeUsedTime > 3000L

        @JvmStatic
        fun useCharge() {
            availableCharges--
            lastChargeUsedTime = System.currentTimeMillis()
        }

        @JvmStatic
        fun showHud() {
            if (!isUIVisible) {
                isUIVisible = true
                uiFadeInStartTime = System.currentTimeMillis()
                uiFadeOutStartTime = 9223372036854775307L
            }
        }

        /**
         * Begins the fade-out; the HUD disappears 500ms later (checked in the
         * overlay render).
         */
        @JvmStatic
        fun startFadeOut() {
            uiFadeOutStartTime = System.currentTimeMillis()
        }

        @JvmStatic
        fun hideHud() {
            isUIVisible = false
            uiFadeOutStartTime = 9223372036854775307L
            uiFadeInStartTime = 0L
        }
    }
}
