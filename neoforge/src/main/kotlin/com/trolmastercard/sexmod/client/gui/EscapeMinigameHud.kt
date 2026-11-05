package com.trolmastercard.sexmod.client.gui

import com.mojang.blaze3d.platform.InputConstants
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.networking.GalathBackOffRapePacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.util.EscapeDirectionKey
import com.trolmastercard.sexmod.util.RotationHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent
import java.util.Random

/**
 * HUD for Galath's escape minigame: the player must press the WASD key
 * matching the blinking direction prompt to fill a progress bar. Correct keys
 * add progress, wrong keys subtract it; the prompt cycles every 35 ticks.
 *
 * **Flow.** [showMinigame] starts it; filling progress to 1.0 (and
 * then pressing an unrelated key) triggers [startMinigame], which
 * sends [GalathBackOffRapePacket] to the server and fails the minigame;
 * failure flashes the bar red for 20 ticks and hides the HUD. Leaving the
 * world resets all state.
 *
 * CLIENT-side only; state is static, so only one minigame can be active at a
 * time. Progress decays by [KEY_DECAY] per tick when idle.
 *
 * 1.21: [RenderGuiEvent.Post], [ClientTickEvent.Pre] (1.12 ran on the START
 * phase), [InputEvent.Key]; `GameSettings.isKeyDown` reads the physical key
 * state through [InputConstants.isKeyDown].
 */
open class EscapeMinigameHud {
    /**
     * Renders the HUD: the prompt card slides in from below the screen, the
     * four WASD icons (blinking prompt highlighted) and the progress bar. On
     * failure the whole card slides back out with an ease-in-out curve.
     */
    @SubscribeEvent
    open fun onRenderGameOverlay(event: RenderGuiEvent.Post) {
        if (showHud) {
            val guiGraphics = event.guiGraphics
            val screenWidth = guiGraphics.guiWidth()
            val screenHeight = guiGraphics.guiHeight()
            val partialTicks = event.partialTick.getGameTimeDeltaPartialTick(true)
            val slide: Double = if (failed) {
                1.0 - RotationHelper.easeInOutQuad(((failTimer + partialTicks) / 20.0f).toDouble())
            } else {
                Math.min(1.0, RotationHelper.smoothStep(((timer + partialTicks) / 20.0f).toDouble()))
            }

            val targetY = screenHeight + 385
            val pose = guiGraphics.pose()
            pose.pushPose()
            try {
                pose.scale(0.33f, 0.33f, 0.33f)
                pose.translate(485.0f, 0.0f, 0.0f)
                val startY = 4 * screenHeight
                guiGraphics.blit(HUD_TEXTURE, screenWidth / 2 - 87, RotationHelper.lerpDouble(startY.toDouble(), targetY.toDouble(), slide).toInt(), 0, 104, 174, 48)
                guiGraphics.blit(HUD_TEXTURE, (screenWidth / 2.0f - 78.0f).toInt(), RotationHelper.lerpDouble(startY.toDouble(), (targetY - 52).toDouble(), slide).toInt(), 52, if (blinkState && currentKey == EscapeDirectionKey.A) 52 else 0, 52, 52)
                guiGraphics.blit(HUD_TEXTURE, (screenWidth / 2.0f - 26.0f).toInt(), RotationHelper.lerpDouble(startY.toDouble(), (targetY - 52).toDouble(), slide).toInt(), 104, if (blinkState && currentKey == EscapeDirectionKey.S) 52 else 0, 52, 52)
                guiGraphics.blit(HUD_TEXTURE, (screenWidth / 2.0f + 26.0f).toInt(), RotationHelper.lerpDouble(startY.toDouble(), (targetY - 52).toDouble(), slide).toInt(), 156, if (blinkState && currentKey == EscapeDirectionKey.D) 52 else 0, 52, 52)
                guiGraphics.blit(HUD_TEXTURE, (screenWidth / 2.0f - 26.0f).toInt(), RotationHelper.lerpDouble(startY.toDouble(), (targetY - 104).toDouble(), slide).toInt(), 0, if (blinkState && currentKey == EscapeDirectionKey.W) 52 else 0, 52, 52)
                guiGraphics.blit(HUD_TEXTURE, screenWidth / 2 - 87 + 8, RotationHelper.lerpDouble((startY - 8).toDouble(), (targetY + 8).toDouble(), slide).toInt(), 8, 152, (158.0f * progress).toInt(), 32)
            } finally {
                pose.popPose()
            }
        }
    }

    @SubscribeEvent
    open fun onClientTick(event: ClientTickEvent.Pre) {
        tickHud()
    }

    /**
     * Evaluates movement-key presses against the current prompt: a matching key
     * adds [KEY_PRESS_SPEED] to progress, a wrong key subtracts
     * [KEY_PRESS_SPEED]/2; pressing any other key with full progress
     * completes the minigame.
     */
    @SubscribeEvent
    open fun onKeyInput(event: InputEvent.Key) {
        val settings = Minecraft.getInstance().options
        if (isKeyDown(settings.keyLeft)) {
            if (currentKey == EscapeDirectionKey.A) {
                progress += 0.08f
            } else {
                progress -= 0.04f
            }
        } else if (isKeyDown(settings.keyRight)) {
            if (currentKey == EscapeDirectionKey.D) {
                progress += 0.08f
            } else {
                progress -= 0.04f
            }
        } else if (isKeyDown(settings.keyUp)) {
            if (currentKey == EscapeDirectionKey.W) {
                progress += 0.08f
            } else {
                progress -= 0.04f
            }
        } else if (isKeyDown(settings.keyDown)) {
            if (currentKey == EscapeDirectionKey.S) {
                progress += 0.08f
            } else {
                progress -= 0.04f
            }
        } else {
            if (progress >= 1.0f) {
                startMinigame()
            }
        }
    }

    companion object {
        @JvmField
        val HUD_TEXTURE: ResourceLocation = Main.rl("textures/gui/escape_minigame_ui.png")
        const val KEY_SIZE: Int = 52
        const val TEXTURE_SCALE: Float = 20.0f
        const val KEY_SPACING: Int = 35
        const val KEY_PRESS_SPEED: Float = 0.08f
        const val KEY_DECAY: Float = 0.006f
        const val TIMER_MODULUS: Int = 2
        const val PROGRESS_THRESHOLD: Float = 0.33f

        @JvmField
        var showHud: Boolean = false

        @JvmField
        var currentKey: EscapeDirectionKey? = null

        @JvmField
        var progress: Float = 0.0f

        @JvmField
        var timer: Float = 0.0f

        @JvmField
        var blinkState: Boolean = true

        @JvmField
        var failTimer: Float = 0.0f

        @JvmField
        var failed: Boolean = false

        @JvmStatic
        val mc: Minecraft
            get() = Minecraft.getInstance()

        @JvmField
        var hasStarted: Boolean = false

        /** The galath whose scene owns this HUD; hides the card when that scene ends. */
        @JvmField
        var hudOwner: GalathEntity? = null

        /** 1.12 `GameSettings.isKeyDown`: physical key state of the binding. */
        private fun isKeyDown(key: KeyMapping): Boolean =
            InputConstants.isKeyDown(Minecraft.getInstance().window.window, key.key.value)

        /**
         * Per-tick state machine (CLIENT-side, called from the client tick event):
         * resets everything when the world is gone; on failure counts up to 20 ticks
         * then hides the HUD; otherwise advances the blink timer, decays progress
         * and picks the next key prompt every 35 ticks (or immediately when none is
         * set).
         */
        @JvmStatic
        fun tickHud() {
            if (showHud) {
                val owner = hudOwner
                if (owner == null || !owner.isAlive || !isRapeScene(owner.getCurrentAction())) {
                    hideHud()
                    return
                }

                if (mc.level == null) {
                    showHud = false
                    hasStarted = false
                    timer = 0.0f
                    progress = 0.0f
                    failTimer = 0.0f
                    failed = false
                }

                if (failed) {
                    blinkState = false
                    failTimer++
                    if (failTimer >= 20.0f) {
                        showHud = false
                    }
                } else {
                    timer++
                    if (timer % Math.max(1, 2) == 0.0f) {
                        blinkState = !blinkState
                    }

                    progress = Math.max(0.0f, progress - 0.006f)
                    if (!(timer < 20.0f)) {
                        if (timer % 35.0f == 0.0f || currentKey == null) {
                            pickNextKey()
                        }
                    }
                }
            }
        }

        /**
         * Picks a random direction different from the currently shown one, so the
         * prompt never repeats twice in a row.
         */
        @JvmStatic
        fun pickNextKey() {
            val previous = currentKey
            val random = Random()

            do {
                currentKey = EscapeDirectionKey.entries[random.nextInt(EscapeDirectionKey.entries.size)]
            } while (previous == currentKey)
        }

        /**
         * Called once per minigame when the player completes the bar: sends
         * [GalathBackOffRapePacket] to the server (Galath backs off) and
         * immediately ends the minigame as a "failure" for display purposes.
         * Guarded by `hasStarted` so it fires exactly once.
         */
        @JvmStatic
        fun startMinigame() {
            if (showHud) {
                if (!hasStarted) {
                    hasStarted = true
                    PacketHandler.networkWrapper.sendToServer(GalathBackOffRapePacket())
                    failMinigame()
                }
            }
        }

        /**
         * Resets all static state and shows the HUD. Must be called from the CLIENT
         * tick thread (also resets any prior failure state).
         */
        @JvmStatic
        fun showMinigame(owner: GalathEntity?) {
            hudOwner = owner
            // A looping scene re-fires the enableRapeUI keyframe; restarting the slide
            // every loop made the card bounce up and down the screen.
            if (showHud && !failed) {
                return
            }

            showHud = true
            hasStarted = false
            timer = 0.0f
            progress = 0.0f
            failTimer = 0.0f
            failed = false
        }

        /** The HUD belongs to the wild-galath rape scene only. */
        @JvmStatic
        fun isRapeScene(action: Action?): Boolean {
            return action == Action.RAPE_PREPARE ||
                action == Action.RAPE_CHARGE ||
                action == Action.RAPE_INTRO ||
                action == Action.RAPE_ON_GOING ||
                action == Action.RAPE_CUM ||
                action == Action.RAPE_CUM_IDLE
        }

        @JvmStatic
        fun hideHud() {
            showHud = false
            hasStarted = false
            timer = 0.0f
            progress = 0.0f
            failTimer = 0.0f
            failed = false
            hudOwner = null
        }

        @JvmStatic
        fun failMinigame() {
            failed = true
            failTimer = 0.0f
        }
    }
}
