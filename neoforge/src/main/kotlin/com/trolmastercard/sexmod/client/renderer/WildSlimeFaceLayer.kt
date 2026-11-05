package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.trolmastercard.sexmod.client.model.GoblinModel
import com.trolmastercard.sexmod.entity.WildSlimeEntity
import net.minecraft.client.model.SlimeModel
import net.minecraft.client.model.geom.EntityModelSet
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.layers.RenderLayer

/**
 * Vanilla face layer for the wild slime: renders the classic slime
 * face model (1.12 `ModelSlime(0)`, the inner slime layer) over the slime body model,
 * inheriting the main model's pose attributes. Hidden while the slime is invisible.
 *
 * CLIENT-side render thread only.
 *
 * Port note: the constructor also takes the [EntityModelSet] to bake the vanilla model.
 */
class WildSlimeFaceLayer(
    private val slimeRenderer: WildSlimeRenderer,
    modelSet: EntityModelSet,
) : RenderLayer<WildSlimeEntity, GoblinModel<WildSlimeEntity>>(slimeRenderer) {
    private val modelBase: SlimeModel<WildSlimeEntity> = SlimeModel(modelSet.bakeLayer(ModelLayers.SLIME))

    /**
     * Renders the slime face with alpha blending over the body, using the
     * renderer's main model attributes for pose consistency. No-op for
     * invisible slimes.
     */
    override fun render(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        slime: WildSlimeEntity,
        limbSwing: Float,
        limbSwingAmount: Float,
        partialTick: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float,
    ) {
        if (!slime.isInvisible) {
            this.parentModel.copyPropertiesTo(this.modelBase)
            this.modelBase.prepareMobModel(slime, limbSwing, limbSwingAmount, partialTick)
            this.modelBase.setupAnim(slime, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch)
            val buffer = bufferSource.getBuffer(RenderType.entityTranslucent(this.getTextureLocation(slime)))
            this.modelBase.renderToBuffer(poseStack, buffer, packedLight, LivingEntityRenderer.getOverlayCoords(slime, 0.0f))
        }
    }

    fun shouldCombineTextures(): Boolean = true
}
