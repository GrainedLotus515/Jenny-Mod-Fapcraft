package com.trolmastercard.sexmod.client.gui

import com.mojang.blaze3d.systems.RenderSystem
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GoblinEntity
import com.trolmastercard.sexmod.proxy.ClientProxy
import com.trolmastercard.sexmod.util.ThreadNames
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * Direction-pad control screen for Galath (and goblin throw/pickup): the
 * player holds the mouse in one of four quadrants around the screen center and
 * closes the screen (release key or Escape) to commit the action.
 *
 * **Action decision.** While open, per-frame mouse position accumulates
 * quadrant indicators (`animLeft/Right/Top/Bottom`, clamped to 0..1).
 * [removed] then picks: left = start throwing
 * (`Action.START_THROWING`), top = throw the goblin at the player,
 * bottom = pick the goblin up. For non-goblin targets only the left action is
 * meaningful.
 *
 * CLIENT-side only. Closing the screen is what commits the action — if a
 * future editor makes closing conditional, the action must still be dispatched
 * exactly once.
 *
 * 1.21: `onGuiClosed` -> [removed]; `handleKeyboardInput` key-up -> [keyReleased];
 * `mc.getTickLength()` -> the frame delta in ticks. 1.12 drew no screen
 * background, so [renderBackground] is empty.
 */
open class GalathScreen(target: BaseGirlEntity) : Screen(Component.empty()) {
    @JvmField
    var animProgress: Float = 0.0f

    @JvmField
    var animLeft: Float = 0.0f

    @JvmField
    var animRight: Float = 0.0f

    @JvmField
    var animTop: Float = 0.0f

    @JvmField
    var animBottom: Float = 0.0f

    @JvmField
    var targetEntity: BaseGirlEntity = target

    @JvmField
    var isGoblinTarget: Boolean = target is GoblinEntity

    /**
     * Commits the chosen action from the accumulated quadrant indicators (see
     * class doc). Must fire exactly once per screen lifetime.
     */
    override fun removed() {
        super.removed()
        if (this.animTop != 0.0f || this.animBottom != 0.0f || this.animLeft != 0.0f) {
            if (this.animLeft > 0.0f) {
                this.startGoblinThrow()
            } else if (this.isGoblinTarget) {
                if (this.animTop > this.animBottom) {
                    this.throwGoblin()
                } else {
                    this.pickupGoblin()
                }
            }
        }
    }

    /**
     * Marks the goblin to be thrown at the local player (entity-side flag set
     * via `setThrowTarget`).
     */
    open fun throwGoblin() {
        if (this.isGoblinTarget) {
            val player = Minecraft.getInstance().player ?: return
            (this.targetEntity as GoblinEntity).setThrowTarget(player.uuid)
        }
    }

    /**
     * Marks the goblin to be picked up by the local player
     * (`setPickupTarget`).
     */
    open fun pickupGoblin() {
        val player = Minecraft.getInstance().player ?: return
        (this.targetEntity as GoblinEntity).setPickupTarget(player.uuid)
    }

    /**
     * Starts the throw sequence (`Action.START_THROWING`) — only valid
     * while the target girl is not already interacting with another player.
     */
    open fun startGoblinThrow() {
        if (this.targetEntity.getInteractionPlayerUUID() == null) {
            this.targetEntity.setCurrentAction(Action.START_THROWING)
        }
    }

    /**
     * Closes the screen when the pad toggle key is released — either G
     * (keyBindings[0], the original) or RShift (keyBindings[2], the dual
     * keybind). Committing the chosen action happens in [removed],
     * so without this the pad stayed open until Escape.
     */
    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val padToggle = ClientProxy.keyBindings[0].matches(keyCode, scanCode) || ClientProxy.keyBindings[2].matches(keyCode, scanCode)
        if (padToggle) {
            net.minecraft.client.Minecraft.getInstance().setScreen(null) // client-only screen: a container-close packet would close a menu the server just opened
            return true
        }
        return super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    }

    /**
     * Draws the animated control pad: four directional buttons scale/translate
     * toward the mouse position with an ease-out-back entrance, plus a
     * goblin-specific throw/pickup layout. Mouse-side accumulation
     * (animLeft/Right/Top/Bottom) happens here, which [removed]
     * reads when the screen closes.
     */
    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()

        val tickLength = Minecraft.getInstance().timer.gameTimeDeltaTicks
        this.animProgress = Math.min(1.0f, this.animProgress + tickLength / 5.0f)

        val scale = this.easeOutBack(this.animProgress.toDouble()).toFloat()
        val offset = (1.0f - scale) * 100.0f
        this.animLeft = this.animLeft + (if (mouseX < this.width / 2) 1 else -1) * tickLength
        this.animRight = this.animRight + (if (mouseX > this.width / 2) 1 else -1) * tickLength
        this.animTop = this.animTop + (if (mouseY < this.height / 2 - 1) 1 else -1) * tickLength
        this.animBottom = this.animBottom + (if (mouseY > this.height / 2) 1 else -1) * tickLength
        this.animLeft = ThreadNames.clampFloat(this.animLeft, 0.0f, 1.0f)
        this.animRight = ThreadNames.clampFloat(this.animRight, 0.0f, 1.0f)
        this.animTop = ThreadNames.clampFloat(this.animTop, 0.0f, 1.0f)
        this.animBottom = ThreadNames.clampFloat(this.animBottom, 0.0f, 1.0f)
        val pose = guiGraphics.pose()
        pose.pushPose()
        try {
            pose.translate(this.width / 2.0f, this.height / 2.0f, 0.0f)
            pose.scale(scale, scale, scale)
            pose.pushPose()
            pose.scale(1.0f + this.animLeft * 0.5f, 1.0f + this.animLeft * 0.5f, 1.0f)
            blitAt(guiGraphics, -62.0f + offset - this.animLeft * 15.0f, offset - 32.0f, 0, 0, 64, 64)
            blitAt(guiGraphics, -62.0f + offset - this.animLeft * 15.0f, offset - 32.0f, 64, 128, 64, 64)
            pose.popPose()
            if (this.isGoblinTarget) {
                pose.pushPose()
                pose.scale(1.0f - this.animRight, 1.0f - this.animRight, 1.0f)
                blitAt(guiGraphics, -2.0f - offset + this.animRight * 32.0f, -offset - 32.0f, 0, 0, 64, 64)
                blitAt(guiGraphics, -2.0f - offset + this.animRight * 32.0f, -offset - 32.0f, 0, 128, 64, 64)
                pose.popPose()
                if (this.animRight > 0.0f) {
                    pose.pushPose()
                    pose.scale(-1.0f + this.animRight + 1.0f + this.animTop * 0.5f, -1.0f + this.animRight + 1.0f + this.animTop * 0.5f, 1.0f)
                    blitAt(guiGraphics, -2.0f - offset + this.animTop * 5.0f, -offset - 64.0f - this.animTop * 5.0f / 2.0f, 0, 0, 64, 64)
                    blitAt(guiGraphics, -2.0f - offset + this.animTop * 5.0f, -offset - 64.0f - this.animTop * 5.0f / 2.0f, 128, 128, 64, 64)
                    pose.popPose()
                    pose.pushPose()
                    pose.scale(-1.0f + this.animRight + 1.0f + this.animBottom * 0.5f, -1.0f + this.animRight + 1.0f + this.animBottom * 0.5f, 1.0f)
                    blitAt(guiGraphics, -2.0f - offset + this.animBottom * 5.0f, -offset + this.animBottom * 5.0f / 2.0f, 0, 0, 64, 64)
                    blitAt(guiGraphics, -2.0f - offset + this.animBottom * 5.0f, -offset + this.animBottom * 5.0f / 2.0f, 192, 128, 64, 64)
                    pose.popPose()
                }
            }
        } finally {
            pose.popPose()
            RenderSystem.disableBlend()
        }
    }

    open fun easeOutBack(t: Double): Double {
        val c1 = 1.70158
        val c3 = 2.70158
        return 1.0 + c3 * Math.pow(t - 1.0, 3.0) + c1 * Math.pow(t - 1.0, 2.0)
    }

    override fun isPauseScreen(): Boolean = false

    /** 1.12 `drawTexturedModalRect(float x, float y, ...)`. */
    private fun blitAt(guiGraphics: GuiGraphics, x: Float, y: Float, u: Int, v: Int, w: Int, h: Int) {
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(x, y, 0.0f)
        guiGraphics.blit(GUI_TEXTURE, 0, 0, u, v, w, h)
        pose.popPose()
    }

    companion object {
        @JvmField
        val GUI_TEXTURE: ResourceLocation = Main.rl("textures/gui/command.png")
    }
}
