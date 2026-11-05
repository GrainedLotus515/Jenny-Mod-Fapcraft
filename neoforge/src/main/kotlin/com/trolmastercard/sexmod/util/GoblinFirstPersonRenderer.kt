package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.client.renderer.GirlRenderer
import com.trolmastercard.sexmod.client.renderer.GoblinRenderer
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.api.IGoblin
import java.util.ConcurrentModificationException
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RenderHandEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.event.RenderPlayerEvent

/**
 * **Role.** CLIENT-side first-person rendering for the goblin transformation:
 * while the local player is the owner of a goblin girl, the girl is drawn in
 * first person (level render, after weather), her pick-up/throw animations replace the
 * vanilla hand and player-body render (RenderHandEvent / RenderPlayerEvent.Pre cancellation).
 *
 * **State.** `GoblinRenderer.strafeRotation/forwardRotation` are
 * derived from the player's movement input and smoothed with
 * [RotationHelper.lerp] — the smoothing must keep using prev/cur fields
 * or the goblin's arms jitter.
 *
 * **Pitfall.** All list scans tolerate [ConcurrentModificationException]
 * and the "yaw -420.69" sentinel forces a render pass for the owner's goblin —
 * do not "clean up" either.
 *
 * Port notes: `RenderWorldLastEvent` is [RenderLevelStageEvent] at `AFTER_WEATHER`; the
 * sentinel renders go straight to the girl's renderer with the pose at the 1.12 origin
 * (the lerped local player), drawn into the main buffer source and flushed.
 */
class GoblinFirstPersonRenderer {
    private fun translateToPlayerOrigin(event: RenderLevelStageEvent, player: LocalPlayer, partialTicks: Float) {
        val camera = event.camera.position
        val playerLerp = GirlRenderer.lerpedPosition(player, partialTicks)
        event.poseStack.translate(playerLerp.x - camera.x, playerLerp.y - camera.y, playerLerp.z - camera.z)
    }

    @SubscribeEvent
    fun onRenderWorldLastFirstPerson(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return
        }

        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.options.cameraType.isFirstPerson) {
            val playerUuid = player.uuid
            var goblinGirl: BaseGirlEntity? = null

            try {
                for (girl in BaseGirlEntity.getGirlEntityList()) {
                    if (!girl.isRemoved && girl.level().isClientSide && girl is IGoblin) {
                        if (playerUuid == (girl as IGoblin).getOwnerUUID()) {
                            goblinGirl = girl
                            break
                        }
                    }
                }
            } catch (exception: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }

            if (goblinGirl != null) {
                val partialTicks = event.partialTick.getGameTimeDeltaPartialTick(false)
                val yaw = player.yRot
                GoblinRenderer.strafeRotation = (player.input.leftImpulse * GoblinRenderer.MOVEMENT_DIR_VECTOR.x).toFloat()
                GoblinRenderer.strafeRotation = GoblinRenderer.strafeRotation + -(yaw - GoblinRenderer.lastPlayerYaw) * 3.0f
                GoblinRenderer.strafeRotation = RotationHelper.lerp(GoblinRenderer.prevStrafeRotation, GoblinRenderer.strafeRotation, 0.1f)
                val pitch = -player.xRot
                GoblinRenderer.forwardRotation = (
                    player.input.forwardImpulse * GoblinRenderer.MOVEMENT_DIR_VECTOR.z +
                        player.deltaMovement.y.toFloat() * GoblinRenderer.MOVEMENT_DIR_VECTOR.y
                    ).toFloat()
                GoblinRenderer.forwardRotation = GoblinRenderer.forwardRotation + -(pitch - GoblinRenderer.lastPlayerPitch) * 3.0f
                GoblinRenderer.forwardRotation = RotationHelper.lerp(GoblinRenderer.prevForwardRotation, GoblinRenderer.forwardRotation, 0.1f)
                val poseStack = event.poseStack
                val bufferSource = minecraft.renderBuffers().bufferSource()
                poseStack.pushPose()
                try {
                    this.translateToPlayerOrigin(event, player, partialTicks)
                    val light = minecraft.entityRenderDispatcher.getPackedLightCoords(goblinGirl, partialTicks)
                    GoblinRenderer.renderEntityInFirstPerson(goblinGirl, partialTicks, poseStack, bufferSource, light)
                } finally {
                    poseStack.popPose()
                    bufferSource.endBatch()
                }
                GoblinRenderer.lastPlayerYaw = yaw
                GoblinRenderer.prevStrafeRotation = GoblinRenderer.strafeRotation
                GoblinRenderer.lastPlayerPitch = pitch
                GoblinRenderer.prevForwardRotation = GoblinRenderer.forwardRotation
            }
        }
    }

    @SubscribeEvent
    fun onRenderWorldLastPlayer(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return
        }

        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val playerUuid = player.uuid

        try {
            for (girl in BaseGirlEntity.getGirlEntityList()) {
                if (girl.level().isClientSide && !girl.isRemoved && girl is IGoblin) {
                    if (girl.getCurrentAction() == Action.START_THROWING) {
                        val partialTicks = event.partialTick.getGameTimeDeltaPartialTick(false)
                        val poseStack = event.poseStack
                        val bufferSource = minecraft.renderBuffers().bufferSource()
                        girl.setLocallyRegistered(true)
                        poseStack.pushPose()
                        try {
                            this.translateToPlayerOrigin(event, player, partialTicks)
                            val light = minecraft.entityRenderDispatcher.getPackedLightCoords(girl, partialTicks)
                            minecraft.entityRenderDispatcher.getRenderer(girl).render(
                                girl,
                                if (playerUuid == (girl as IGoblin).getOwnerUUID()) GoblinRenderer.SENTINEL_VALUE else 0.0f,
                                partialTicks,
                                poseStack,
                                bufferSource,
                                light,
                            )
                        } finally {
                            poseStack.popPose()
                            bufferSource.endBatch()
                            girl.setLocallyRegistered(false)
                        }
                        return
                    }
                }
            }
        } catch (exception: ConcurrentModificationException) {
            // ignored: entity list mutated during iteration; retried on the next tick
        }
    }

    @SubscribeEvent
    fun onRenderHand(event: RenderHandEvent) {
        val playerUuid = Minecraft.getInstance().player?.uuid ?: return

        try {
            for (girl in BaseGirlEntity.getGirlEntityList()) {
                if (girl is IGoblin) {
                    val action = girl.getCurrentAction()
                    if (action == Action.PICK_UP || action == Action.START_THROWING) {
                        val ownerUuid = (girl as IGoblin).getOwnerUUID()
                        if (playerUuid == ownerUuid) {
                            event.isCanceled = true
                            break
                        }
                    }
                }
            }
        } catch (exception: ConcurrentModificationException) {
            // ignored: entity list mutated during iteration; retried on the next tick
        }
    }

    @SubscribeEvent
    fun onPre(event: RenderPlayerEvent.Pre) {
        val playerUuid = event.entity.uuid

        try {
            for (girl in BaseGirlEntity.getGirlEntityList()) {
                if (girl is IGoblin) {
                    val action = girl.getCurrentAction()
                    if ((action == Action.PICK_UP || action == Action.START_THROWING) && playerUuid == (girl as IGoblin).getOwnerUUID()) {
                        event.isCanceled = true
                        break
                    }
                }
            }
        } catch (exception: ConcurrentModificationException) {
            // ignored: entity list mutated during iteration; retried on the next tick
        }
    }
}
