package com.trolmastercard.sexmod.client.model

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import net.minecraft.client.model.Model
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.renderer.RenderType

/**
 * Vanilla placeholder model (a single 2x6x2 cube at a fixed rotation point)
 * used as the [IVanillaModel] shape for the Allie NPC's vanilla-model
 * render path. Not a geckolib model — no animations.
 */
class AllieModel : Model(RenderType::entityCutoutNoCull), IVanillaModel {
    private val rootPart: ModelPart

    init {
        // textureWidth/Height 16; root at (-5, 2.5, 0) with one 2x6x2 box
        val mesh = MeshDefinition()
        mesh.root.addOrReplaceChild(
            "rootPart",
            CubeListBuilder.create().texOffs(0, 0).addBox(-2.0f, -6.0f, 0.0f, 2.0f, 6.0f, 2.0f),
            PartPose.offset(-5.0f, 2.5f, 0.0f),
        )
        this.rootPart = LayerDefinition.create(mesh, 16, 16).bakeRoot().getChild("rootPart")
    }

    override fun renderToBuffer(poseStack: PoseStack, buffer: VertexConsumer, packedLight: Int, packedOverlay: Int, color: Int) {
        this.rootPart.render(poseStack, buffer, packedLight, packedOverlay, color)
    }

    fun setRotation(modelRenderer: ModelPart, x: Float, y: Float, z: Float) {
        modelRenderer.xRot = x
        modelRenderer.yRot = y
        modelRenderer.zRot = z
    }

    override fun getModel(): ModelPart = this.rootPart
}
