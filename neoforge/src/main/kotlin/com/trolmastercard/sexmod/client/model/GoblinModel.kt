package com.trolmastercard.sexmod.client.model

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.world.entity.Entity

/**
 * Vanilla body model for the goblin: a blocky body with ears, nose and a
 * 4-segment tail (used by `WildSlimeRenderer` as the slime's body
 * shape). Static pose — no animation hooks.
 *
 * Port note: an [EntityModel] (1.12 `ModelBase`) so it can back a `MobRenderer`; the
 * type parameter is new.
 */
class GoblinModel<T : Entity> : EntityModel<T>() {
    private val body: ModelPart
    private val rightEar: ModelPart
    private val leftEar: ModelPart
    private val nose: ModelPart
    private val tail: ModelPart

    init {
        // textureWidth 64, textureHeight 32
        val mesh = MeshDefinition()
        val root = mesh.root
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 16).mirror(true).addBox(-3.0f, 17.0f, -3.0f, 6.0f, 6.0f, 6.0f), PartPose.offset(0.0f, 0.0f, 0.0f))
        root.addOrReplaceChild("rightEar", CubeListBuilder.create().texOffs(32, 0).mirror(true).addBox(1.3f, 18.0f, -3.5f, 2.0f, 2.0f, 2.0f), PartPose.offset(0.0f, 0.0f, 0.0f))
        root.addOrReplaceChild("leftEar", CubeListBuilder.create().texOffs(32, 4).mirror(true).addBox(-3.3f, 18.0f, -3.5f, 2.0f, 2.0f, 2.0f), PartPose.offset(0.0f, 0.0f, 0.0f))
        root.addOrReplaceChild("nose", CubeListBuilder.create().texOffs(32, 8).mirror(true).addBox(-1.0f, 21.0f, -3.5f, 1.0f, 1.0f, 1.0f), PartPose.offset(0.0f, 0.0f, 0.0f))
        val tail = root.addOrReplaceChild("tail", CubeListBuilder.create(), PartPose.offset(-0.5f, 0.0f, 0.1f))
        tail.addOrReplaceChild(
            "tailSegment1",
            CubeListBuilder.create().texOffs(10, 11).addBox(-2.5f, 0.0f, 0.0f, 2.0f, 2.0f, 1.0f),
            PartPose.offsetAndRotation(2.0f, 20.7406f, 4.0504f, 1.0908f, 0.0f, 0.0f),
        )
        tail.addOrReplaceChild(
            "tailSegment2",
            CubeListBuilder.create().texOffs(10, 11).addBox(-3.0f, 0.0f, 0.0f, 3.0f, 1.0f, 1.0f),
            PartPose.offsetAndRotation(2.0f, 19.9214f, 3.4768f, 0.6109f, 0.0f, 0.0f),
        )
        tail.addOrReplaceChild(
            "tailSegment3",
            CubeListBuilder.create().texOffs(10, 11).addBox(-4.0f, 0.0f, 0.075f, 5.0f, 1.0f, 1.0f),
            PartPose.offsetAndRotation(2.0f, 19.0074f, 3.0643f, 0.3491f, 0.0f, 0.0f),
        )
        tail.addOrReplaceChild(
            "tailSegment4",
            CubeListBuilder.create().texOffs(10, 11).addBox(-3.0f, -1.0f, -0.5f, 7.0f, 2.0f, 1.0f),
            PartPose.offsetAndRotation(0.0f, 17.925f, 3.5f, 0.1309f, 0.0f, 0.0f),
        )
        val baked = LayerDefinition.create(mesh, 64, 32).bakeRoot()
        this.body = baked.getChild("body")
        this.rightEar = baked.getChild("rightEar")
        this.leftEar = baked.getChild("leftEar")
        this.nose = baked.getChild("nose")
        this.tail = baked.getChild("tail")
    }

    override fun renderToBuffer(poseStack: PoseStack, buffer: VertexConsumer, packedLight: Int, packedOverlay: Int, color: Int) {
        this.body.render(poseStack, buffer, packedLight, packedOverlay, color)
        this.rightEar.render(poseStack, buffer, packedLight, packedOverlay, color)
        this.leftEar.render(poseStack, buffer, packedLight, packedOverlay, color)
        this.nose.render(poseStack, buffer, packedLight, packedOverlay, color)
        this.tail.render(poseStack, buffer, packedLight, packedOverlay, color)
    }

    override fun setupAnim(entity: T, limbSwing: Float, limbSwingAmount: Float, ageInTicks: Float, netHeadYaw: Float, headPitch: Float) {
    }

    fun setRotation(modelRenderer: ModelPart, x: Float, y: Float, z: Float) {
        modelRenderer.xRot = x
        modelRenderer.yRot = y
        modelRenderer.zRot = z
    }
}
