package com.trolmastercard.sexmod.client

import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GoblinInteractions
import com.trolmastercard.sexmod.proxy.ClientProxy
import com.trolmastercard.sexmod.util.SceneDebug
import net.minecraft.client.Minecraft
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.InputEvent
import java.util.ConcurrentModificationException

/**
 * CLIENT keybind handler for the "Leave sex scene" key (R-Shift,
 * `ClientProxy.keyBindings[2]`).
 *
 * **Behavior (reworked):** instead of snapping the player out, the key
 * first tries to PROGRESS the scene to its natural ending:
 * 1. [BaseGirlEntity.triggerCumAction] — jumps to the cum
 *    action if the current action has one; the cum animation plays, the
 *    meter fills, and the normal ending machinery
 *    (`xxx_cumDone` -> `resetCameraAndPhysics` ->
 *    single-arg `ResetGirlPacket`) releases the player cleanly.
 * 2. If the action did not change, walks the chain forward with
 *    [BaseGirlEntity.triggerFastSexAction] (`getNextAction`).
 * 3. If the action still did not change (scene stuck at PAYMENT/NULL with
 *    nowhere to advance), falls back to the full client exit
 *    [BaseGirlEntity.resetCameraAndPhysics] — which clears the
 *    scene camera, unlocks movement, un-hides the player and sends the
 *    full ResetGirlPacket. This guarantees the player is never left stuck
 *    in the scene view.
 *
 * The player-form girl ([AbstractPlayerGirlEntity]) gets the same treatment.
 *
 * **History:** the progression behavior (49e6f87) was originally reverted
 * (9fd5d34) because scenes were permanently stuck at PAYMENT/NULL — that was
 * caused by the dismount-lerp bug (`RotationHelper.lerpVec3dDouble`
 * misuse) which made scenes unable to advance at all. With that fixed, the
 * progression path works and the reset is only the fallback.
 *
 * CLIENT-ONLY. 1.21: `KeyInputEvent` -> [InputEvent.Key]; `isPressed` -> `consumeClick`.
 * The goblin pad hook calls `GoblinInteractions.openGoblinPadForPlayer(uuid)`
 * (S6 dropped the `Minecraft` parameter).
 */
open class SexSceneKeyHandler {
    @SubscribeEvent
    open fun onKeyInput(event: InputEvent.Key) {
        if (ClientProxy.keyBindings[2].consumeClick()) {
            SceneDebug.log(SceneDebug.RSHIFT, "R-SHIFT pressed (Leave sex scene)")
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return

            val playerUuid = player.uuid

            // DUAL KEYBIND: RShift = goblin gesture pad when a goblin is riding our
            // shoulder (same action as G / keyBindings[0]). Otherwise fall through to
            // the scene-exit behavior below.
            if (GoblinInteractions.openGoblinPadForPlayer(playerUuid)) {
                SceneDebug.log(SceneDebug.RSHIFT, "R-SHIFT: opened goblin throw/pickup pad")
                return
            }

            // "Leave sex scene": PROGRESS the scene to its ending when possible —
            // triggerCumAction jumps to the cum/ending animation (fills the meter,
            // plays the ending, then the normal scene-end machinery releases the
            // player through cumDone -> resetCameraAndPhysics). When the current
            // action has no cum, walk the action chain forward with
            // triggerFastSexAction. If neither advances (scene stuck at
            // PAYMENT/NULL), fall back to a full client-side exit
            // (resetCameraAndPhysics -> ResetGirlPacket) so the player is never
            // left stuck.
            try {
                for (girl in BaseGirlEntity.getGirlEntityList()) {
                    val girlUuid = girl.getInteractionPlayerUUID()
                    if (girl.isAlive && girl.level().isClientSide && girlUuid != null) {
                        if (playerUuid == girlUuid) {
                            val before = girl.getCurrentAction()
                            BaseGirlEntity.triggerCumAction(playerUuid)
                            if (girl.getCurrentAction() == before) {
                                for (i in 0 until 32) {
                                    val prev = girl.getCurrentAction()
                                    BaseGirlEntity.triggerFastSexAction(playerUuid)
                                    if (girl.getCurrentAction() == prev) {
                                        break // chain end reached
                                    }
                                }
                            }

                            if (girl.getCurrentAction() == before) {
                                SceneDebug.log(SceneDebug.RSHIFT, "R-SHIFT: %s stuck at %s, full exit", girl.getDisplayNameText(), before)
                                girl.resetCameraAndPhysics()
                            } else {
                                SceneDebug.log(SceneDebug.RSHIFT, "R-SHIFT: %s progressed %s -> %s", girl.getDisplayNameText(), before, girl.getCurrentAction())
                            }
                        }
                    }
                }
            } catch (cme: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }

            val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(playerUuid)
            if (playerGirl != null && playerGirl.getCurrentAction() != Action.NULL) {
                val before = playerGirl.getCurrentAction()
                BaseGirlEntity.triggerCumAction(playerUuid)
                if (playerGirl.getCurrentAction() == before) {
                    for (i in 0 until 32) {
                        val prev = playerGirl.getCurrentAction()
                        BaseGirlEntity.triggerFastSexAction(playerUuid)
                        if (playerGirl.getCurrentAction() == prev) {
                            break
                        }
                    }
                }

                if (playerGirl.getCurrentAction() == before) {
                    playerGirl.resetCameraAndPhysics()
                }
            }
        }
    }
}
