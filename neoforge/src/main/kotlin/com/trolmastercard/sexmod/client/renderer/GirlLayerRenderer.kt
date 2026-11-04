package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.model.ElytraModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import software.bernie.geckolib.renderer.GeoRenderer
import software.bernie.geckolib.renderer.layer.GeoRenderLayer
import software.bernie.geckolib.util.Color

/**
 * Geckolib layer that renders a vanilla [ElytraModel] on girl entities
 * ([AbstractGirlNpcEntity] incl. player-girls) when their chest slot
 * holds an elytra.
 *
 * **Animation source.** The elytra pose is driven by
 * `setupAnim` using the owning player's limb/head angles when
 * the girl is a player-girl with a known owner (`getOwnerUserUUID`),
 * otherwise the girl's own angles — so a girl wearing her owner's elytra
 * flaps in sync with him.
 *
 * CLIENT-side render thread only; drawn by [GirlPlayerRenderer.renderOverlay] at the
 * head bone (and usable as a regular GeckoLib layer).
 *
 * Port note: the mod-level [render] takes the pose/buffer state explicitly (1.12 used the GL
 * matrix and bound textures); 1.12 `ONE, ZERO` blending is the vanilla elytra render type.
 */
class GirlLayerRenderer<T : BaseGirlEntity>(renderer: GeoRenderer<T>) : GeoRenderLayer<T>(renderer) {
    private val elytraModel: ElytraModel<LivingEntity> = ElytraModel(Minecraft.getInstance().entityModels.bakeLayer(ModelLayers.ELYTRA))

    /** GeckoLib layer entry: the elytra with the girl's own limb angles. */
    override fun render(
        poseStack: PoseStack,
        animatable: T,
        bakedModel: BakedGeoModel,
        renderType: RenderType?,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer?,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        this.render(
            animatable,
            animatable.walkAnimation.position(partialTick),
            animatable.walkAnimation.speed(partialTick),
            partialTick,
            0.0f,
            0.0f,
            0.0f,
            Color.WHITE,
            poseStack,
            bufferSource,
            packedLight,
        )
    }

    /**
     * Renders the elytra model over the girl when her chest slot is an elytra:
     * vanilla elytra texture, a 0.125 offset, the layer's rotation angles from
     * the owner player (or the girl itself). No-op for non-girl entities or
     * non-elytra chest items.
     */
    fun render(
        entity: LivingEntity,
        limbSwing: Float,
        limbSwingAmount: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float,
        scale: Float,
        color: Color,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
    ) {
        if (entity is AbstractGirlNpcEntity) {
            val chestStack = entity.entityData.get(AbstractGirlNpcEntity.CHEST_SLOT)
            var owner: Player? = null
            if (entity is AbstractPlayerGirlEntity) {
                val ownerUuid = entity.getOwnerUserUUID()
                if (ownerUuid != null) {
                    owner = entity.level().getPlayerByUUID(ownerUuid)
                }
            }

            if (chestStack.item === Items.ELYTRA) {
                poseStack.pushPose()
                try {
                    poseStack.translate(0.0f, 0.0f, 0.125f)
                    this.renderLayer(poseStack)
                    val poseEntity: LivingEntity = owner ?: entity
                    // 1.12 setRotationAngles(limbSwing, limbSwingAmount, netHeadYaw, headPitch, scale, modelScale, entity)
                    this.elytraModel.setupAnim(poseEntity, limbSwing, limbSwingAmount, netHeadYaw, headPitch, scale)
                    val vertexConsumer = bufferSource.getBuffer(RenderType.armorCutoutNoCull(elytraTexture))
                    this.elytraModel.renderToBuffer(poseStack, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY)
                } finally {
                    poseStack.popPose()
                }
            }
        }
    }

    /**
     * Prepares the vanilla layer transform (mirrored scale, -1.501 translate)
     * and returns the standard 1/16 model scale.
     */
    fun renderLayer(poseStack: PoseStack): Float {
        poseStack.scale(-1.0f, -1.0f, 1.0f)
        poseStack.translate(0.0f, -1.501f, 0.0f)
        return 0.0625f
    }

    fun shouldCombineTextures(): Boolean = false

    companion object {
        private val elytraTexture: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/entity/elytra.png")
    }
}
