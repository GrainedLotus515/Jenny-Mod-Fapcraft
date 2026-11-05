package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.player.Input
import net.minecraft.network.chat.Component
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent
import org.lwjgl.glfw.GLFW

/**
 * CLIENT-side player input interceptor for scenes (horny-potion player-girls
 * and NPC scenes alike).
 *
 * **Movement lock.** [setMovementLock] toggles the mod's
 * input lock (static `isSneaking` flag, confusingly named). While
 * unlocked (in a scene), [onInputUpdate] zeroes all movement and maps
 * the keys to scene controls:
 * - **sneak (shift)** — `triggerFastSexAction`: advance the
 *   action chain one step (`getNextAction`); also exposes
 *   [isJumping] (yes, it mirrors sneak).
 * - **jump (space)** — `resetPlayerGirlCamera` (player-girls), or
 *   `triggerCumAction` when the cum meter
 *   ([HornyMeterHud.meterValue]) is full — the standard scene
 *   ending input. Exposed as [isInAction].
 *
 * While locked, mouse clicks are swallowed ([onMouse]) so the player cannot
 * click away from the scene.
 *
 * **Pitfall:** the lock must be released (true) at scene end — the natural
 * end does this via `resetCameraAndPhysics` ->
 * `resetLocalPlayerClientState`; the R-Shift keybind
 * (`SexSceneKeyHandler`) does it through the same client exit path when
 * the scene cannot progress.
 *
 * CLIENT-ONLY class; [setMovementLock] / [isJumping] / [isInAction] use common
 * types so common code may use them on client paths (PORTING.md §2.3 rule B).
 * 1.21: `InputUpdateEvent` -> [MovementInputUpdateEvent]; `MouseEvent` button
 * cancel -> [InputEvent.MouseButton.Pre] (in-game only, like 1.12).
 */
open class HandlePlayerMovement {
    /**
     * CLIENT input hook: captures movement while a scene is active and maps
     * sneak/jump to the scene controls (see class doc).
     */
    @SubscribeEvent
    open fun onInputUpdate(event: MovementInputUpdateEvent) {
        val input = event.input
        HandlePlayerMovement.input = input
        isJumping = input.shiftKeyDown
        isInAction = input.jumping
        if (!isSneaking) {
            val player = Minecraft.getInstance().player ?: return
            if (input.jumping) {
                AbstractPlayerGirlEntity.resetPlayerGirlCamera()
            }

            if (input.shiftKeyDown) {
                BaseGirlEntity.triggerFastSexAction(player.uuid)
            }

            if (input.jumping && HornyMeterHud.meterValue >= 1.0) {
                BaseGirlEntity.triggerCumAction(player.uuid)
            }

            input.down = false
            input.up = false
            input.left = false
            input.right = false
            input.shiftKeyDown = false
            input.jumping = false
            input.forwardImpulse = 0.0f
            input.leftImpulse = 0.0f
            player.setDeltaMovement(0.0, 0.0, 0.0)
        }
    }

    @SubscribeEvent
    open fun onMouse(event: InputEvent.MouseButton.Pre) {
        if (!isSneaking && event.action == GLFW.GLFW_PRESS && Minecraft.getInstance().screen == null) {
            event.isCanceled = true
        }
    }

    companion object {
        /** True while the mod's input lock is engaged (idle); false during scenes. */
        private var isSneaking = true

        /** Mirrors the sneak key while in a scene (used by action "Done" checks). */
        @JvmField
        var isJumping: Boolean = false

        /** Mirrors the jump key while in a scene (cum-meter expansion check). */
        @JvmField
        var isInAction: Boolean = false

        /** The latest movement input from [onInputUpdate]. */
        @JvmField
        var input: Input? = null

        @JvmStatic
        fun isSneakingState(): Boolean = isSneaking

        @JvmStatic
        fun setMovementLock(locked: Boolean) {
            SceneDebug.log(SceneDebug.MOVEMENT, "setMovementLock(%s) (was %s)", locked, isSneaking)
            isSneaking = locked
            if (!locked) {
                handlePlayerMovementTick()
            }
        }

        @JvmStatic
        fun handlePlayerMovementTick() {
            val player = Minecraft.getInstance().player
            if (AbstractPlayerGirlEntity.isOwnerPlayer(player)) {
                player?.displayClientMessage(Component.literal("Jump to get out of the animation"), true)
            }
        }
    }
}
