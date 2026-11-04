package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.RotationHelper
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent

/**
 * CLIENT cum meter HUD ("the balls"). Visible during scenes; shows a slide-in
 * meter that fills as the player performs actions
 * ([addToHornyMeter]), and expands when full
 * (`meterValue >= 1.0`) to indicate the ending input is available —
 * `HandlePlayerMovement` maps jump + full meter to
 * `triggerCumAction`.
 *
 * Key API: [showHornyMeter] (scene start), [resetHornyMeter]
 * (scene end / between actions), [addToHornyMeter] (per action
 * sound keyframe, e.g. 0.02 slow / 0.04 fast), [hideHornyMeter].
 * The meter state is static/global — one meter per client, not per girl.
 *
 * **Pitfall:** [resetHornyMeter] must also reset
 * [isExpanded] or the meter stays in its expanded "ending ready" state
 * after the scene ends.
 *
 * 1.21 port: draws in [RenderGuiEvent.Post] (1.12 `RenderGameOverlayEvent`
 * TEXT element) with [GuiGraphics]; `mc.getTickLength()` is the frame delta
 * in ticks (`DeltaTracker.getGameTimeDeltaTicks()`). The static API stays
 * `@JvmStatic` with common types so common code may call it on client paths.
 */
open class HornyMeterHud {
    @SubscribeEvent
    open fun onRenderGameOverlay(event: RenderGuiEvent.Post) {
        if (isVisible) {
            val guiGraphics = event.guiGraphics
            val tickLength = event.partialTick.gameTimeDeltaTicks
            if (slideInProgress < 1.0f) {
                slideInProgress = slideInProgress + tickLength / 25.0f
            } else {
                slideInProgress = 1.0f
            }

            val pose = guiGraphics.pose()
            pose.pushPose()
            try {
                pose.scale(0.35f, 0.35f, 0.35f)
                if (meterValue >= 1.0) {
                    if (HandlePlayerMovement.isInAction) {
                        isExpanded = true
                    }

                    val yOffset = if (isExpanded) 54 else 0
                    guiGraphics.blit(BUTTON_TEXTURE, 240, 160, 0, 108 + yOffset, 256, 52)
                }

                if (displayState && !isExpanded) {
                    val jumpOffset = if (HandlePlayerMovement.isJumping) 54 else 0
                    guiGraphics.blit(BUTTON_TEXTURE, RotationHelper.lerp(-200.0f, 98.0f, slideInProgress).toInt(), 405, 0, jumpOffset, 158, 54)
                }

                pose.scale(2.857143f, 2.857143f, 2.857143f)
                pose.scale(0.75f, 0.75f, 0.75f)
                guiGraphics.blit(METER_TEXTURE, 10, RotationHelper.lerp(-200.0f, 10.0f, slideInProgress).toInt(), 0, 0, 146, 175)
                smoothedMeter = RotationHelper.lerpDouble(smoothedMeter, meterValue, tickLength.toDouble())
                val meterHeight = RotationHelper.lerpDouble(0.0, 160.0, smoothedMeter).toInt()
                val meterY = RotationHelper.lerpDouble(167.0, 8.0, smoothedMeter).toInt()
                val lerpedY = RotationHelper.lerpDouble(178.0, 18.0, smoothedMeter)
                if (!isExpanded) {
                    guiGraphics.blit(METER_TEXTURE, 67, RotationHelper.lerpDouble(-45.0, lerpedY, slideInProgress.toDouble()).toInt(), 159, meterY, 32, meterHeight)
                    guiGraphics.blit(
                        METER_TEXTURE,
                        120,
                        RotationHelper.lerpDouble(-58.0, RotationHelper.lerpDouble(178.0, 149.0, 1.0 - smoothedMeter), slideInProgress.toDouble()).toInt(),
                        212,
                        RotationHelper.lerpDouble(169.0, 141.0, 1.0 - smoothedMeter).toInt(),
                        28,
                        RotationHelper.lerpDouble(1.0, 29.0, 1.0 - smoothedMeter).toInt()
                    )
                    guiGraphics.blit(
                        METER_TEXTURE,
                        18,
                        RotationHelper.lerpDouble(-58.0, RotationHelper.lerpDouble(178.0, 149.0, 1.0 - smoothedMeter), slideInProgress.toDouble()).toInt(),
                        212,
                        RotationHelper.lerpDouble(169.0, 141.0, 1.0 - smoothedMeter).toInt(),
                        28,
                        RotationHelper.lerpDouble(1.0, 29.0, 1.0 - smoothedMeter).toInt()
                    )
                } else {
                    slideOutProgress = slideOutProgress + tickLength / 15.0f
                    guiGraphics.blit(METER_TEXTURE, 67, RotationHelper.lerp(18.0f, -300.0f, slideOutProgress).toInt(), 159, 8, 32, 160)
                }
            } finally {
                pose.popPose()
            }
        }
    }

    companion object {
        @JvmField
        var BUTTON_TEXTURE: ResourceLocation = Main.rl("textures/gui/buttons.png")

        @JvmField
        var METER_TEXTURE: ResourceLocation = Main.rl("textures/gui/hornymeter.png")

        @JvmField
        var isVisible: Boolean = false

        @JvmField
        var meterValue: Double = 0.0

        @JvmField
        var smoothedMeter: Double = meterValue

        @JvmField
        var slideInProgress: Float = 0.0f

        @JvmField
        var slideOutProgress: Float = 0.0f

        @JvmField
        var isExpanded: Boolean = false

        @JvmField
        var displayState: Boolean = true

        @JvmStatic
        fun showHornyMeter() {
            if (!isVisible) {
                resetHornyMeter()
                isVisible = true
                displayState = true
            }
        }

        @JvmStatic
        fun setHornyMeterVisible(visible: Boolean) {
            if (!isVisible) {
                resetHornyMeter()
                isVisible = true
                displayState = visible
            }
        }

        @JvmStatic
        fun hideHornyMeter() {
            resetHornyMeter()
            isVisible = false
            displayState = true
        }

        @JvmStatic
        fun isHornyMeterVisible(): Boolean = isVisible

        @JvmStatic
        fun addToHornyMeter(amount: Double) {
            meterValue += amount
            meterValue = if (meterValue > 1.0) 1.0 else meterValue
        }

        @JvmStatic
        fun resetHornyMeter() {
            meterValue = 0.0
            isExpanded = false
            // also reset the animation state so a re-shown meter slides in again
            slideInProgress = 0.0f
            slideOutProgress = 0.0f
            smoothedMeter = 0.0
        }
    }
}
