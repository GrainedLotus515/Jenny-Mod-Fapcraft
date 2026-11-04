package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.trolmastercard.sexmod.client.model.SummonItemModel
import com.trolmastercard.sexmod.entity.BodyParts
import com.trolmastercard.sexmod.item.WinchesterItem
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.util.FastColor
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import software.bernie.geckolib.cache.object.GeoCube
import software.bernie.geckolib.renderer.GeoItemRenderer
import software.bernie.geckolib.util.RenderUtil

/**
 * Item renderer for the Winchester summoning staff ([WinchesterItem]):
 * geckolib model with a custom cube pass that (in non-debug mode) offsets each
 * cube's tint by its normal direction via [BodyParts.offsetBonePosition],
 * creating a fake "depth shading" on the model, and disables lighting so the
 * shading reads as the only lighting.
 *
 * CLIENT-side render thread only.
 *
 * Port notes: 1.12 `DebugMode.DEV_FLOATS[0]` (S3 debug tweak) is not ported
 * anywhere visible to S5; the non-debug branch (`DEV_FLOATS[0] == 0`, the
 * default) is hard-wired. "Lighting disabled" becomes full-bright packed light.
 * Never attached to the item in 1.12 either (see `WinchesterItem`).
 */
open class SummonItemRenderer : GeoItemRenderer<WinchesterItem>(SummonItemModel()) {
    override fun renderByItem(stack: ItemStack, transformType: ItemDisplayContext, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        this.renderSummonItem(stack, transformType, poseStack, bufferSource, packedLight, packedOverlay)
    }

    fun renderSummonItem(stack: ItemStack, transformType: ItemDisplayContext, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        // 1.12 disableLighting when DEV_FLOATS[0] == 0
        super.renderByItem(stack, transformType, poseStack, bufferSource, LightTexture.FULL_BRIGHT, packedOverlay)
    }

    /**
     * Cube pass: transforms normals (mirroring them for zero-size cube faces)
     * and emits tinted vertices. Tint = the render color offset by the normal
     * (fake lighting).
     */
    override fun renderCube(poseStack: PoseStack, cube: GeoCube, buffer: VertexConsumer, packedLight: Int, packedOverlay: Int, colour: Int) {
        RenderUtil.translateToPivotPoint(poseStack, cube)
        RenderUtil.rotateMatrixAroundCube(poseStack, cube)
        RenderUtil.translateAwayFromPivotPoint(poseStack, cube)
        val normalMatrix = poseStack.last().normal()
        val poseMatrix = poseStack.last().pose()
        val r = FastColor.ARGB32.red(colour) / 255.0
        val g = FastColor.ARGB32.green(colour) / 255.0
        val b = FastColor.ARGB32.blue(colour) / 255.0
        val alpha = FastColor.ARGB32.alpha(colour) / 255.0f

        for (quad in cube.quads()) {
            if (quad != null) {
                val normal = normalMatrix.transform(Vector3f(quad.normal()))
                RenderUtil.fixInvertedFlatCube(cube, normal)
                val tint = BodyParts.offsetBonePosition(Vec3(r, g, b), normal, offsetVec)
                val tintColour = FastColor.ARGB32.colorFromFloat(alpha, tint.x.toFloat(), tint.y.toFloat(), tint.z.toFloat())
                this.createVerticesOfQuad(quad, poseMatrix, normal, buffer, packedLight, packedOverlay, tintColour)
            }
        }
    }

    companion object {
        @JvmField
        val offsetVec: Vec3 = Vec3(0.0, 1.0, 0.0)
    }
}
