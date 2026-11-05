package com.trolmastercard.sexmod.client.model

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.model.Model
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.renderer.RenderType
import org.joml.Quaternionf

/**
 * Simple vanilla "glass crystal" model: three nested transparent cubes with
 * scale/rotation offsets. Used by `DragonStaffRenderer` as the spinning
 * end-crystal on the dragon staff. Renders with the caller-bound texture
 * (e.g. the ender-crystal texture); the rotation parameter spins the crystal.
 *
 * Port notes: 1.12 `ModelBase`/`ModelRenderer` -> [Model]/[ModelPart] baked
 * from a [MeshDefinition] (texture 64×32 like the 1.12 default). The texture
 * is no longer "bound": the caller passes the [VertexConsumer] of its render
 * type. The 1.12 `render(entity, limbSwing, spin, ...)` is
 * [render] (poseStack, buffer, light, overlay, spin); `scale` (0.0625) is
 * implicit in [ModelPart.render].
 */
open class GalathModel : Model(RenderType::entityTranslucent) {
    private val root: ModelPart = createLayer().bakeRoot()
    private val wingModel: ModelPart = root.getChild("cube")
    private val bodyModel: ModelPart = root.getChild("glass")

    open fun render(poseStack: PoseStack, buffer: VertexConsumer, packedLight: Int, packedOverlay: Int, spin: Float) {
        poseStack.pushPose()
        try {
            poseStack.scale(2.0f, 2.0f, 2.0f)
            poseStack.mulPose(Axis.YP.rotationDegrees(spin))
            poseStack.mulPose(DIAGONAL_60)
            this.bodyModel.render(poseStack, buffer, packedLight, packedOverlay)
            poseStack.scale(0.875f, 0.875f, 0.875f)
            poseStack.mulPose(DIAGONAL_60)
            poseStack.mulPose(Axis.YP.rotationDegrees(spin))
            this.bodyModel.render(poseStack, buffer, packedLight, packedOverlay)
            poseStack.scale(0.875f, 0.875f, 0.875f)
            poseStack.mulPose(DIAGONAL_60)
            poseStack.mulPose(Axis.YP.rotationDegrees(spin))
            this.wingModel.render(poseStack, buffer, packedLight, packedOverlay)
        } finally {
            poseStack.popPose()
        }
    }

    override fun renderToBuffer(poseStack: PoseStack, buffer: VertexConsumer, packedLight: Int, packedOverlay: Int, color: Int) {
        this.render(poseStack, buffer, packedLight, packedOverlay, 0.0f)
    }

    companion object {
        /** `GlStateManager.rotate(60, 0.7071, 0, 0.7071)` (GL normalises the axis). */
        private val DIAGONAL_60: Quaternionf
            get() = Quaternionf().rotationAxis(Math.toRadians(60.0).toFloat(), 0.70710677f, 0.0f, 0.70710677f)

        @JvmStatic
        fun createLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            root.addOrReplaceChild("glass", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0f, -4.0f, -4.0f, 8.0f, 8.0f, 8.0f), PartPose.ZERO)
            root.addOrReplaceChild("cube", CubeListBuilder.create().texOffs(32, 0).addBox(-4.0f, -4.0f, -4.0f, 8.0f, 8.0f, 8.0f), PartPose.ZERO)
            return LayerDefinition.create(mesh, 64, 32)
        }
    }
}
