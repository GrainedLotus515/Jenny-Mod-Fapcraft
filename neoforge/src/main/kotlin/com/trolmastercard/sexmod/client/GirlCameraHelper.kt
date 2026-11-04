package com.trolmastercard.sexmod.client

import com.mojang.blaze3d.vertex.PoseStack
import com.trolmastercard.sexmod.client.renderer.GirlPlayerRenderer
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.RotationHelper
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.Boat
import net.minecraft.world.item.BowItem
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.util.ObfuscationReflectionHelper
import net.neoforged.neoforge.client.event.RenderFrameEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.event.RenderPlayerEvent
import net.neoforged.neoforge.client.event.ViewportEvent
import org.joml.Vector2f

/**
 * CLIENT scene camera for the horny-potion player-girls
 * ([AbstractPlayerGirlEntity]) — NOT the NPC scenes.
 *
 * While the transformed player-girl is in a scene (non-anchored), the camera
 * is attached to the girl model's `girlCam` bone: the player's
 * position is overridden on the render frame start ([onRenderTickStart],
 * [onRenderTickEnd] save/restore the real position) and the girl's
 * body is rendered first-person via [applyCameraTransform]. When the
 * girl is anchored the camera rolls 180° ([onCameraSetup]).
 *
 * **Pitfall:** the camera only detaches when the girl's scene state clears
 * (action/interaction reset). The NPC-scene R-Shift exit therefore must go
 * through `resetCameraAndPhysics()` on the client (clears
 * `cameraOriginPos`) — sending only the server `ResetGirlPacket`
 * leaves the camera attached and the player model visible in the scene view.
 *
 * 1.21 port: `RenderTickEvent` START/END -> [RenderFrameEvent.Pre]/[RenderFrameEvent.Post];
 * `CameraSetup` -> [ViewportEvent.ComputeCameraAngles]; `RenderWorldLastEvent`
 * -> [RenderLevelStageEvent] AFTER_WEATHER (camera-relative). [applyCameraTransform]
 * gained the pose stack / buffer source / light the 1.21 dispatcher needs.
 * The 1.12 `player.eyeHeight` writes use reflection on the private
 * `Entity.eyeHeight` field (mojmap name); `getDefaultEyeHeight()` is `refreshDimensions()`.
 */
open class GirlCameraHelper {
    @JvmField
    var playerPos: Vec3? = null

    @JvmField
    var playerLastPos: Vec3? = null

    @JvmField
    var currentGirl: AbstractPlayerGirlEntity? = null

    @JvmField
    var isSmoothing: Boolean = false

    @SubscribeEvent
    open fun onPre(event: RenderPlayerEvent.Pre) {
        if (!previewRenderInProgress) {
            AbstractPlayerGirlEntity.rebuildPlayerGirlTableFromWorld()
            val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(event.entity.uuid)
            if (playerGirl != null) {
                event.isCanceled = true
                applyCameraTransform(
                    playerGirl, event.entity, 0.0, 0.0, 0.0, event.partialTick,
                    event.poseStack, event.multiBufferSource, event.packedLight
                )
            }
        }
    }

    @SubscribeEvent
    open fun onRenderTickEnd(event: RenderFrameEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val pos = this.playerPos
        val last = this.playerLastPos
        if (pos != null && last != null) {
            player.setPos(pos.x, pos.y, pos.z)
            player.xOld = last.x
            player.yOld = last.y
            player.zOld = last.z
            this.playerPos = null
            this.playerLastPos = null
        }
    }

    /** 1.12 `RenderTickEvent` START half: camera snap to the girl's `girlCam` bone. */
    @SubscribeEvent
    open fun onRenderTickStartCamera(event: RenderFrameEvent.Pre) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.options.cameraType == CameraType.FIRST_PERSON) {
            val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player.uuid) ?: return
            if (playerGirl.isSceneActive()) {
                val renderTickTime = event.partialTick.getGameTimeDeltaPartialTick(true)
                val realPos = player.position()
                val realLast = Vec3(player.xOld, player.yOld, player.zOld)
                this.playerPos = realPos
                this.playerLastPos = realLast
                var camPos = playerGirl.getCachedBoneOffset("girlCam")
                camPos = playerGirl.getOwnerAimVector(camPos, renderTickTime)
                camPos = camPos.add(RotationHelper.lerpVec3dDouble(realLast, realPos, renderTickTime.toDouble()))
                player.setPosRaw(camPos.x, camPos.y - player.eyeHeight, camPos.z)
                player.xOld = camPos.x
                player.yOld = camPos.y - player.eyeHeight
                player.zOld = camPos.z
                val action = playerGirl.getCurrentAction()
                var yaw = playerGirl.getYawRotation()
                if (!playerGirl.canPerformAction(action, player)) {
                    if (action.flipGirlYaw) {
                        yaw += 180.0f
                    }

                    if (player.xRot > action.maxGirlPitch) {
                        player.xRot = action.maxGirlPitch
                        player.xRotO = action.maxGirlPitch
                    }

                    if (player.xRot < action.minGirlPitch) {
                        player.xRot = action.minGirlPitch
                        player.xRotO = action.minGirlPitch
                    }

                    if (player.yRot > yaw + 90.0f) {
                        player.yRot = yaw + 90.0f
                        player.yRotO = yaw + 90.0f
                    }

                    if (player.yRot < yaw - 90.0f) {
                        player.yRot = yaw - 90.0f
                        player.yRotO = yaw - 90.0f
                    }
                }
            }
        }
    }

    @SubscribeEvent
    open fun onCameraSetup(event: ViewportEvent.ComputeCameraAngles) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player.uuid) ?: return
        if (playerGirl.usesInvertedCamera()) {
            if (playerGirl.isAnchored()) {
                event.roll = 180.0f
                event.pitch = -event.pitch
                event.yaw = -event.yaw
            }
        }
    }

    @SubscribeEvent
    open fun onRenderWorldLast(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) return
        val mc = Minecraft.getInstance()
        val pos = this.playerPos ?: return
        val last = this.playerLastPos ?: return
        val player = mc.player ?: return
        if (mc.options.cameraType == CameraType.FIRST_PERSON) {
            val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player.uuid) ?: return
            val partialTicks = event.partialTick.getGameTimeDeltaPartialTick(false)
            val lerpedPos = RotationHelper.lerpVec3dDouble(last, pos, partialTicks.toDouble())
            // 1.21 level rendering is camera-relative (1.12 was relative to the moved player)
            val offset = lerpedPos.subtract(event.camera.position)
            val buffers = mc.renderBuffers().bufferSource()
            applyCameraTransform(
                playerGirl, player, offset.x, offset.y, offset.z, partialTicks,
                event.poseStack, buffers, mc.entityRenderDispatcher.getPackedLightCoords(playerGirl, partialTicks)
            )
            buffers.endBatch()
        }
    }

    /** 1.12 `RenderTickEvent` START half: eye-height smoothing and the one-off warm-up render. */
    @SubscribeEvent
    open fun onRenderTickStart(event: RenderFrameEvent.Pre) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player.uuid)
        if (playerGirl == null) {
            if (this.isSmoothing) {
                this.isSmoothing = false
                player.refreshDimensions()
            }
        } else if (playerGirl.isAnchored()) {
            if (this.isSmoothing) {
                this.isSmoothing = false
                player.refreshDimensions()
            }
        } else {
            if (this.currentGirl !== playerGirl) {
                val buffers = mc.renderBuffers().bufferSource()
                applyCameraTransform(
                    playerGirl, player, 0.0, 500.0, 0.0, event.partialTick.getGameTimeDeltaPartialTick(true),
                    PoseStack(), buffers, 0xF000F0
                )
                buffers.endBatch()
                this.currentGirl = playerGirl
            }

            setEyeHeight(player, playerGirl.getCameraBoneHeight())
            this.isSmoothing = true
        }
    }

    companion object {
        /**
         * Partial tick passed to the GUI preview renders. It no longer carries any
         * meaning — [previewRenderInProgress] says "this is not a real
         * frame" — but the models still animate off it, so it keeps the value the
         * old sentinel used to keep preview poses exactly as they were.
         */
        const val PREVIEW_PARTIAL_TICK: Float = 1.2345679f

        /**
         * True while a GUI preview renders an entity, so [onPre] knows the
         * render is a preview and not a real world frame needing the camera
         * transform. Scoped by [beginPreview]/[endPreview].
         */
        private var previewRenderInProgress = false

        @JvmStatic
        fun beginPreview() {
            previewRenderInProgress = true
        }

        @JvmStatic
        fun endPreview() {
            previewRenderInProgress = false
        }

        /** 1.12 `entity.eyeHeight = h` (the field is private in 1.21). */
        @JvmStatic
        fun setEyeHeight(entity: Entity, height: Float) {
            try {
                ObfuscationReflectionHelper.setPrivateValue(Entity::class.java, entity, height, "eyeHeight")
            } catch (t: Throwable) {
                // PORT-GAP fallback: leave the vanilla eye height
            }
        }

        @JvmStatic
        fun applyCameraTransform(
            girl: AbstractPlayerGirlEntity,
            playerIn: Player,
            x: Double,
            y: Double,
            z: Double,
            partialTicks: Float,
            poseStack: PoseStack,
            bufferSource: MultiBufferSource,
            packedLight: Int,
        ) {
            val mc = Minecraft.getInstance()
            val player = girl.resolvePlayerEntity(playerIn) ?: return
            val local = mc.player ?: return
            if (!player.isInvisibleTo(local) || girl.shouldRenderWhenOwnerInvisible()) {
                val renderManager = mc.entityRenderDispatcher
                girl.yRot = player.yRot
                girl.yHeadRotO = player.yHeadRotO
                girl.yHeadRot = player.yHeadRot
                girl.xRotO = player.xRotO
                girl.xRot = player.xRot
                girl.yRotO = player.yRotO
                girl.xo = player.xo
                girl.yo = player.yo
                girl.zo = player.zo
                girl.xOld = player.xOld
                girl.yOld = player.yOld
                girl.zOld = player.zOld
                girl.yBodyRot = player.yBodyRot
                girl.yBodyRotO = player.yBodyRotO
                girl.playerSneaking = player.isShiftKeyDown
                girl.playerSprinting = player.isSprinting
                girl.playerRiding = player.isPassenger
                girl.playerOnGround = player.onGround()
                girl.playerUsingItem = player.useItemRemainingTicks != 0
                val dX = player.xOld - player.x
                val dZ = player.z - player.zOld
                val yawRad = (Math.PI / 180.0) * player.yRot
                girl.playerMotionOffset = Vector2f(
                    (dX * Math.cos(yawRad) + dZ * Math.sin(yawRad)).toFloat(),
                    (dX * Math.sin(yawRad) + dZ * Math.cos(yawRad)).toFloat()
                )
                val cameraOffset = if (girl.isRidingSomething()) getCameraOffset(girl, player) else 0.0f
                GirlPlayerRenderer.isFirstPerson = true
                renderManager.render(girl, x, y + cameraOffset, z, 90.0f, partialTicks, poseStack, bufferSource, packedLight)
            }
        }

        @JvmStatic
        fun getCameraOffset(girl: AbstractPlayerGirlEntity, player: Player): Float {
            if (girl.getEntityData().get(BaseGirlEntity.IS_ANCHORED)) {
                return 0.0f
            }

            if ((player.mainHandItem.item is BowItem || player.offhandItem.item is BowItem) && girl.playerUsingItem) {
                girl.setCurrentAction(Action.BOW)
            }

            if (girl.getCurrentAction() == Action.BOW && !girl.playerUsingItem) {
                girl.setCurrentAction(Action.NULL)
            }

            if (girl.getCurrentAction() == Action.BOW) {
                girl.yRot = girl.yHeadRot
                girl.yBodyRot = girl.yHeadRot
                girl.yBodyRotO = girl.yHeadRotO
            }

            return if (girl.playerRiding) {
                if (player.vehicle is Boat) 0.4f else 0.2f
            } else {
                0.0f
            }
        }
    }
}
