package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.trolmastercard.sexmod.client.model.GoblinModel
import com.trolmastercard.sexmod.entity.WildSlimeEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation

/**
 * Renderer for the wild slime entity: a vanilla [MobRenderer] using the
 * mod's [GoblinModel] as the body shape with the vanilla slime texture,
 * squish/scaling driven by the entity's squish factor, plus the
 * [WildSlimeFaceLayer] face.
 *
 * CLIENT-side render thread only.
 */
class WildSlimeRenderer(ctx: EntityRendererProvider.Context) :
    MobRenderer<WildSlimeEntity, GoblinModel<WildSlimeEntity>>(ctx, GoblinModel(), 0.25f) {
    init {
        this.addLayer(WildSlimeFaceLayer(this, ctx.modelSet))
    }

    override fun render(slime: WildSlimeEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        this.shadowRadius = 0.25f * slime.getSquishFactor()
        super.render(slime, entityYaw, partialTicks, poseStack, bufferSource, packedLight)
    }

    /**
     * Scales the model by the slime's squish formula (interpolated squish
     * factor, vanilla slime deformation) with a near-1.0 base scale.
     */
    override fun scale(slime: WildSlimeEntity, poseStack: PoseStack, partialTicks: Float) {
        poseStack.scale(0.999f, 0.999f, 0.999f)
        val squishFactor = slime.getSquishFactor()
        val squishScale = (slime.prevSquishFactor + (slime.squishFactor - slime.prevSquishFactor) * partialTicks) / (squishFactor * 0.5f + 1.0f)
        val inverseScale = 1.0f / (squishScale + 1.0f)
        poseStack.scale(inverseScale * squishFactor, 1.0f / inverseScale * squishFactor, inverseScale * squishFactor)
    }

    override fun getTextureLocation(slime: WildSlimeEntity): ResourceLocation = slimeTexture

    companion object {
        private val slimeTexture: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/entity/slime/slime.png")
    }
}
