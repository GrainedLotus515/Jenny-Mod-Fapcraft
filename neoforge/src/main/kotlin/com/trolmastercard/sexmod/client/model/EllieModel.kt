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
 * Vanilla placeholder model used as the [IVanillaModel] shape for the Ellie
 * NPC's vanilla-model render path: a root at (-5, 1.5708, 0) with one child
 * rotated 90 degrees around Y holding a 2x6x2 box, plus an empty part.
 */
class EllieModel : Model(RenderType::entityCutoutNoCull), IVanillaModel {
    private val rootPart: ModelPart
    private val childPart: ModelPart
    private val emptyPart: ModelPart

    init {
        // textureWidth/Height 16
        val mesh = MeshDefinition()
        val root = mesh.root.addOrReplaceChild("rootPart", CubeListBuilder.create(), PartPose.offset(-5.0f, 1.5708f, 0.0f))
        root.addOrReplaceChild(
            "childPart",
            CubeListBuilder.create().texOffs(0, 0).addBox(-1.0f, -3.0f, -1.0f, 2.0f, 6.0f, 2.0f),
            PartPose.offsetAndRotation(-1.0f, -3.0f, 1.0f, 0.0f, 1.5708f, 0.0f),
        )
        mesh.root.addOrReplaceChild("emptyPart", CubeListBuilder.create(), PartPose.offset(0.0f, 0.0f, 0.0f))
        val baked = LayerDefinition.create(mesh, 16, 16).bakeRoot()
        this.rootPart = baked.getChild("rootPart")
        this.childPart = this.rootPart.getChild("childPart")
        this.emptyPart = baked.getChild("emptyPart")
    }

    override fun renderToBuffer(poseStack: PoseStack, buffer: VertexConsumer, packedLight: Int, packedOverlay: Int, color: Int) {
        this.rootPart.render(poseStack, buffer, packedLight, packedOverlay, color)
        this.emptyPart.render(poseStack, buffer, packedLight, packedOverlay, color)
    }

    fun setRotation(modelRenderer: ModelPart, x: Float, y: Float, z: Float) {
        modelRenderer.xRot = x
        modelRenderer.yRot = y
        modelRenderer.zRot = z
    }

    override fun getModel(): ModelPart = this.rootPart
}
