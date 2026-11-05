package com.trolmastercard.sexmod

import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.RotationHelper
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RenderFrameEvent
import net.neoforged.neoforge.client.event.RenderHandEvent
import net.neoforged.neoforge.client.event.RenderPlayerEvent
import java.util.ConcurrentModificationException

/**
 * CLIENT-side scene camera manager. Drives the first-person "boyCam" camera
 * while the local player is involved in a girl's action: it cancels the vanilla
 * player render (the girl scene is drawn instead), cancels the first-person hand
 * model, and on each render frame repositions the client player at the girl's
 * `boyCam` bone so the scene is viewed from the girl's perspective.
 *
 * **State flow.** On a render frame with first-person view and an
 * active action with `useBoyCam`, the player's position and last-tick
 * position are captured (fields `position`/`rotation`) and the
 * player is moved to the bone offset. On the matching frame end the captured
 * position is written back, restoring the player to where he
 * was before the camera snap — so world physics/packets keep seeing a stable
 * position. The 1.21 camera interpolates `xo/yo/zo` (1.12 used `lastTickPos`),
 * so those are pinned and restored as well; otherwise the view slides from the
 * real position to the bone within every tick.
 *
 * **Pitfalls.** When the girl is anchored, the bone offset is taken from
 * `getCachedBoneOffset("boyCam")` relative to her target position;
 * otherwise it is interpolated from last-tick to current position with
 * [RotationHelper.lerpVec3dDouble] (PROGRESS lerp — correct here, render
 * interpolation must NOT use the INT step variant). Both [onPre] and
 * [onRenderHand] iterate [BaseGirlEntity.getGirlEntityList] and
 * swallow [ConcurrentModificationException] — do not remove that guard.
 *
 * CLIENT-ONLY. 1.21: `RenderTickEvent` START/END -> [RenderFrameEvent.Pre]/[RenderFrameEvent.Post].
 */
open class PositionData {
    @JvmField
    var position: Vec3? = null

    @JvmField
    var rotation: Vec3? = null

    /** The player's `xo/yo/zo` captured with [position]; the 1.21 camera interpolates from these. */
    @JvmField
    var previousPosition: Vec3? = null

    /**
     * Cancels the vanilla player-body render when the rendered player is the
     * interaction partner of an active girl action that has a player, so the
     * girl scene renderer is the only thing drawn for that player.
     */
    @SubscribeEvent
    open fun onPre(event: RenderPlayerEvent.Pre) {
        try {
            for (girl in BaseGirlEntity.getGirlEntityList()) {
                val partner = girl.getInteractionPlayerUUID()
                if (girl.isAlive && partner != null && girl.getCurrentAction() != Action.NULL) {
                    val player = event.entity
                    if (girl.getCurrentAction().hasPlayer && partner == player.uuid) {
                        event.isCanceled = true
                        return
                    }
                }
            }
        } catch (cme: ConcurrentModificationException) {
            // ignored: entity list mutated during iteration; retried on the next tick
        }
    }

    /**
     * Hides the first-person hand/held item while the local player is the
     * interaction partner of an active girl action, or while the local player
     * itself is an anchored [AbstractPlayerGirlEntity] (potion-transformed
     * girl). Without this the player's own arm would clip through the scene.
     */
    @SubscribeEvent
    open fun onRenderHand(event: RenderHandEvent) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player)
        if (playerGirl != null && playerGirl.isAnchored()) {
            event.isCanceled = true
        } else {
            try {
                for (girl in BaseGirlEntity.getGirlEntityList()) {
                    val partnerUuid = girl.getInteractionPlayerUUID()
                    val action: Action? = girl.getCurrentAction()
                    if (girl.isAlive && partnerUuid != null && action != null && action.hasPlayer && partnerUuid == player.uuid) {
                        event.isCanceled = true
                        return
                    }
                }
            } catch (cme: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }
        }
    }

    /**
     * Frame end: write the captured position back (position and
     * lastTickPos) and clear the capture. END restores what the frame start saved.
     */
    @SubscribeEvent
    open fun onRenderTickEnd(event: RenderFrameEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val pos = this.position
        val rot = this.rotation
        if (pos != null && rot != null) {
            player.setPos(pos.x, pos.y, pos.z)
            player.xOld = rot.x
            player.yOld = rot.y
            player.zOld = rot.z
            val prev = this.previousPosition
            if (prev != null) {
                player.xo = prev.x
                player.yo = prev.y
                player.zo = prev.z
            }

            this.position = null
            this.rotation = null
            this.previousPosition = null
        }
    }

    /**
     * Frame start: if the local player is the boy of an active
     * `useBoyCam` action with a non-custom girl, capture the player's
     * position + last-tick position, then snap the player to the girl's
     * `boyCam` bone (anchored: relative to `getTargetPosition()`;
     * otherwise lerped with partial ticks via `lerpVec3dDouble`).
     */
    @SubscribeEvent
    open fun onRenderTick(event: RenderFrameEvent.Pre) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.options.cameraType == CameraType.FIRST_PERSON) {
            val girl = BaseGirlEntity.getGirlByUUID(player.uuid, false) ?: return
            if (girl.getCurrentAction().useBoyCam) {
                if (!girl.isCustomType()) {
                    val renderTickTime = event.partialTick.getGameTimeDeltaPartialTick(true)
                    this.position = player.position()
                    this.rotation = Vec3(player.xOld, player.yOld, player.zOld)
                    this.previousPosition = Vec3(player.xo, player.yo, player.zo)
                    val bonePos = if (girl.isAnchored())
                        girl.getCachedBoneOffset("boyCam").add(girl.getTargetPosition())
                    else
                        girl.getCachedBoneOffset("boyCam")
                            .add(RotationHelper.lerpVec3dDouble(Vec3(girl.xOld, girl.yOld, girl.zOld), girl.position(), renderTickTime.toDouble()))
                    player.setPosRaw(bonePos.x, bonePos.y - player.eyeHeight, bonePos.z)
                    player.xOld = bonePos.x
                    player.yOld = bonePos.y - player.eyeHeight
                    player.zOld = bonePos.z
                    // 1.12 rendered the view from lastTickPos; the 1.21 camera lerps xo -> x instead
                    player.xo = bonePos.x
                    player.yo = bonePos.y - player.eyeHeight
                    player.zo = bonePos.z
                }
            }
        }
    }
}
