package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.trolmastercard.sexmod.entity.EyeAndKoboldColor
import com.trolmastercard.sexmod.entity.KoboldEggEntity
import java.awt.Color
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.util.FastColor
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.model.GeoModel
import software.bernie.geckolib.renderer.GeoEntityRenderer

/**
 * Entity renderer for the thrown/spawned kobold egg: geckolib egg model with
 * the `shell` bone in the static egg color and the `colorSpots`
 * bone in the egg's stored color (data-manager `EGG_COLOR`), so each egg
 * entity shows its own wool-color variant.
 *
 * CLIENT-side render thread only.
 */
class KoboldEggRenderer(ctx: EntityRendererProvider.Context, model: GeoModel<KoboldEggEntity>) :
    GeoEntityRenderer<KoboldEggEntity>(ctx, model) {
    @JvmField
    var eggEntity: KoboldEggEntity? = null

    override fun render(entity: KoboldEggEntity, entityYaw: Float, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        this.eggEntity = entity
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    /**
     * Per-bone tint pass: `shell` -> static egg color, `colorSpots`
     * -> the entity's `EGG_COLOR` data-manager value (safe lookup).
     */
    override fun renderRecursively(
        poseStack: PoseStack,
        animatable: KoboldEggEntity,
        bone: GeoBone,
        renderType: RenderType?,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        colour: Int,
    ) {
        var tint = colour
        val boneName = bone.name
        if ("shell" == boneName) {
            tint = FastColor.ARGB32.color(FastColor.ARGB32.alpha(colour), eggColor.red, eggColor.green, eggColor.blue)
        }

        if ("colorSpots" == boneName) {
            val egg = this.eggEntity ?: animatable
            val eggColor = EyeAndKoboldColor.safeValueOf(egg.entityData.get(KoboldEggEntity.EGG_COLOR)).getMainColor()
            tint = FastColor.ARGB32.color(FastColor.ARGB32.alpha(colour), eggColor.x, eggColor.y, eggColor.z)
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, tint)
    }

    companion object {
        @JvmField
        val eggColor: Color = Color(223, 206, 155)
    }
}
