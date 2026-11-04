package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.trolmastercard.sexmod.entity.LunaEntity
import com.trolmastercard.sexmod.entity.SexEntity
import com.trolmastercard.sexmod.util.RotationHelper
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * Renderer for [SexEntity] — the small "heart/particle" marker entity
 * spawned around Luna during her scenes (e.g. her fishing-rod job). Renders a
 * particle-sprite quad plus, while Luna holds a non-air item, the held item
 * itself; also draws the fishing-line/leash curve from Luna's hand to the
 * particle when her main hand is empty.
 *
 * **Luna coupling.** The particle glides from its spawn position to
 * Luna's hand via a progress flag on Luna (`zFlag`, advanced per real
 * frame) and Luna's position/yaw fields are overwritten with her target
 * position during the render — the yaw is what the fishing line math uses.
 * The whole pass is skipped while Luna is missing or her zFlag is 1 (already
 * arrived).
 *
 * CLIENT-side render thread only. Position interpolation uses
 * [RotationHelper.lerpVec3dDouble] (PROGRESS lerp — correct here).
 *
 * Port notes: [render] dispatches to [doRenderSexEntity] (the 1.12 class kept the body in a
 * separately named method). The 1.12 sprite (`particles.png` cell 1,2: the fishing hook)
 * is the 1.21 `textures/entity/fishing_hook.png`; the billboard uses the camera orientation;
 * the line is a vanilla line strip. Outline mode has no 1.21 counterpart here.
 */
class SexEntityRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<SexEntity>(ctx) {
    override fun render(entity: SexEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        poseStack.pushPose()
        try {
            // 1.12 coordinates are relative to the lerped local player
            GirlRenderer.translateToPlayerOrigin(poseStack, entity, partialTicks)
            val player = Minecraft.getInstance().player
            val entityLerp = GirlRenderer.lerpedPosition(entity, partialTicks)
            val playerLerp = if (player == null) entityLerp else GirlRenderer.lerpedPosition(player, partialTicks)
            this.doRenderSexEntity(
                entity,
                entityLerp.x - playerLerp.x,
                entityLerp.y - playerLerp.y,
                entityLerp.z - playerLerp.z,
                entityYaw,
                partialTicks,
                poseStack,
                bufferSource,
                packedLight,
            )
        } finally {
            poseStack.popPose()
        }
    }

    /**
     * Main render (see class doc): billboarded particle quad (with optional
     * held-item support), then the fishing line from Luna's
     * hand (anchored at her target position) to the particle when her hand is
     * empty. Temporarily rewrites Luna's pos/yaw fields and restores nothing —
     * the entity code tolerates the overwrite, do not "fix" it.
     */
    fun doRenderSexEntity(
        entity: SexEntity,
        x0: Double,
        y0: Double,
        z0: Double,
        entityYaw: Float,
        partialTicks: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
    ) {
        var x = x0
        var y = y0
        var z = z0
        val luna = entity.getOwnerLuna()
        if (luna != null && luna.zFlag != 1.0f) {
            luna.hookEntity = entity
            val heldItem = luna.entityData.get(LunaEntity.CAUGHT_ITEM)
            if (heldItem.item !== Items.AIR) {
                var fps = Minecraft.getInstance().fps.toFloat()
                if (fps == 0.0f) {
                    fps = 0.1f
                }

                luna.zFlag += 60.0f / fps * 0.01666f * 2.0f
                luna.zFlag = Math.min(1.0f, luna.zFlag)
                val localPlayer = Minecraft.getInstance().player!! // java-npe: only rendered in a world
                val localPlayerPos = RotationHelper.lerpVec3dDouble(Vec3(localPlayer.xOld, localPlayer.yOld, localPlayer.zOld), localPlayer.position(), partialTicks.toDouble())
                var entityPos = Vec3(x, y, z)
                var lunaPos = RotationHelper.lerpVec3dDouble(
                    Vec3(luna.xOld, luna.yOld + 0.875, luna.zOld),
                    luna.position().add(0.0, 0.875, 0.0),
                    partialTicks.toDouble(),
                )
                lunaPos = lunaPos.subtract(localPlayerPos)
                entityPos = RotationHelper.lerpVec3dDouble(entityPos, lunaPos, luna.zFlag.toDouble())
                x = entityPos.x
                y = entityPos.y
                z = entityPos.z
            } else {
                luna.zFlag = 0.0f
            }

            poseStack.pushPose()
            try {
                poseStack.translate(x.toFloat(), y.toFloat(), z.toFloat())
                poseStack.scale(0.5f, 0.5f, 0.5f)
                poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation())
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0f))
                if (heldItem.item !== Items.AIR) {
                    poseStack.scale(2.0f, 2.0f, 2.0f)
                    poseStack.translate(0.0f, -0.2f, 0.0f)
                    Minecraft.getInstance().itemRenderer.renderStatic(
                        luna, heldItem, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false,
                        poseStack, bufferSource, luna.level(), packedLight, OverlayTexture.NO_OVERLAY, luna.id,
                    )
                    poseStack.translate(0.0f, 0.2f, 0.0f)
                    poseStack.scale(0.5f, 0.5f, 0.5f)
                }

                val pose = poseStack.last()
                val buffer = bufferSource.getBuffer(RenderType.entityCutout(this.getTextureLocation(entity)))
                buffer.addVertex(pose, -0.5f, -0.5f, 0.0f).setColor(255, 255, 255, 255).setUv(0.0f, 1.0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, 0.0f, 1.0f, 0.0f)
                buffer.addVertex(pose, 0.5f, -0.5f, 0.0f).setColor(255, 255, 255, 255).setUv(1.0f, 1.0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, 0.0f, 1.0f, 0.0f)
                buffer.addVertex(pose, 0.5f, 0.5f, 0.0f).setColor(255, 255, 255, 255).setUv(1.0f, 0.0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, 0.0f, 1.0f, 0.0f)
                buffer.addVertex(pose, -0.5f, 0.5f, 0.0f).setColor(255, 255, 255, 255).setUv(0.0f, 0.0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(pose, 0.0f, 1.0f, 0.0f)
            } finally {
                poseStack.popPose()
            }

            var handSide = if (luna.mainArm == HumanoidArm.RIGHT) 1 else -1
            val mainhandItem = luna.mainHandItem
            if (mainhandItem.item !is FishingRodItem) {
                handSide = -handSide
            }

            val target = luna.getTargetPosition()
            luna.setYRot(luna.getYawRotation())
            luna.yBodyRot = luna.getYawRotation()
            luna.setPosRaw(target.x, target.y, target.z)
            luna.xo = target.x
            luna.yo = target.y
            luna.zo = target.z
            val yawRadians = (luna.yBodyRotO + (luna.yBodyRot - luna.yBodyRotO) * partialTicks) * (Math.PI / 180.0).toFloat()
            val sinYaw = Mth.sin(yawRadians).toDouble()
            val cosYaw = Mth.cos(yawRadians).toDouble()
            val handOffset = handSide * 0.35
            val handX = luna.xo + (luna.x - luna.xo) * partialTicks - cosYaw * handOffset - sinYaw * 0.8
            val handY = luna.yo + luna.eyeHeight + (luna.y - luna.yo) * partialTicks - 0.45
            val handZ = luna.zo + (luna.z - luna.zo) * partialTicks - sinYaw * handOffset + cosYaw * 0.8
            val sneakOffset = if (luna.isCrouching) -0.1875 else 0.0
            val particleX = entity.xo + (entity.x - entity.xo) * partialTicks -
                Math.sin((luna.getYawRotation() + 90.0f) * (Math.PI / 180.0)) * 0.1896224320030116 -
                Math.sin(luna.getYawRotation() * (Math.PI / 180.0)) * 0.08742380916962415
            val particleY = entity.yo + (entity.y - entity.yo) * partialTicks + 0.25 + -0.5
            val particleZ = entity.zo + (entity.z - entity.zo) * partialTicks +
                Math.cos((luna.getYawRotation() + 90.0f) * (Math.PI / 180.0)) * 0.1896224320030116 +
                Math.cos(luna.getYawRotation() * (Math.PI / 180.0)) * 0.08742380916962415
            val lineDx = (handX - particleX).toFloat().toDouble()
            val lineDy = (handY - particleY).toFloat() + sneakOffset
            val lineDz = (handZ - particleZ).toFloat().toDouble()
            if (heldItem.item === Items.AIR) {
                val pose = poseStack.last()
                val lineBuffer = bufferSource.getBuffer(RenderType.lineStrip())
                for (segment in 0..16) {
                    val progress = segment / 16.0f
                    val next = (segment + 1) / 16.0f
                    val px = (x + lineDx * progress).toFloat()
                    val py = (y + lineDy * (progress * progress + progress) * 0.5 + 0.25).toFloat()
                    val pz = (z + lineDz * progress).toFloat()
                    var nx = (x + lineDx * next).toFloat() - px
                    var ny = (y + lineDy * (next * next + next) * 0.5 + 0.25).toFloat() - py
                    var nz = (z + lineDz * next).toFloat() - pz
                    val len = Mth.sqrt(nx * nx + ny * ny + nz * nz)
                    if (len > 0.0f) {
                        nx /= len
                        ny /= len
                        nz /= len
                    } else {
                        ny = 1.0f
                    }
                    lineBuffer.addVertex(pose, px, py, pz).setColor(0, 0, 0, 255).setNormal(pose, nx, ny, nz)
                }
                (bufferSource as? MultiBufferSource.BufferSource)?.endBatch(RenderType.lineStrip())
            }

            super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight)
        }
    }

    override fun getTextureLocation(entity: SexEntity): ResourceLocation = PARTICLE_TEXTURE

    companion object {
        private val PARTICLE_TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/entity/fishing_hook.png")
    }
}
