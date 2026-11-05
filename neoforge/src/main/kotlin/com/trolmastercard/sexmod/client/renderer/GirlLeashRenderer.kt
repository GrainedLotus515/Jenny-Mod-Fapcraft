package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.RotationHelper
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.entity.decoration.HangingEntity

/**
 * Leash rendering for girl entities (vanilla-style two brown strip passes with
 * a sagging curve, see [GirlRenderer.renderLeash]).
 *
 * **Endpoints.** The holder end follows the leash holder's eye height and
 * look direction (hanging entities pin it straight down); the girl end is
 * offset sideways by her yaw and width so the leash leaves her hip rather than
 * her centre. Positions are partial-tick interpolated.
 *
 * **Render state.** 1.12 disabled texture/lighting/cull for the strips; the 1.21
 * leash render type (untextured, lightmapped triangle strip) is the equivalent.
 * Each strip is its own batch, as each was its own `Tessellator.draw()`.
 *
 * CLIENT-side render thread only.
 */
object GirlLeashRenderer {
    /**
     * Vanilla-style leash render (two brown strip passes with a sagging curve)
     * between the girl and her leash holder. `x, y, z` are relative to `poseStack`.
     */
    @JvmStatic
    fun render(girl: BaseGirlEntity, x: Double, y: Double, z: Double, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        val holder = girl.leashHolder ?: return
        var px = x
        var py = y
        var pz = z
        py -= (1.6 - girl.bbHeight) * 0.5
        val holderYaw = RotationHelper.lerp(holder.yRotO, holder.yRot, partialTicks * 0.5f) * (Math.PI / 180.0).toFloat().toDouble()
        val holderPitch = RotationHelper.lerp(holder.xRotO, holder.xRot, partialTicks * 0.5f) * (Math.PI / 180.0).toFloat().toDouble()
        var cosYaw = Math.cos(holderYaw)
        var sinYaw = Math.sin(holderYaw)
        var sinPitch = Math.sin(holderPitch)
        if (holder is HangingEntity) {
            cosYaw = 0.0
            sinYaw = 0.0
            sinPitch = -1.0
        }

        val cosPitch = Math.cos(holderPitch)
        val holderX = RotationHelper.lerpDouble(holder.xo, holder.x, partialTicks.toDouble()) - cosYaw * 0.7 - sinYaw * 0.5 * cosPitch
        val holderY = RotationHelper.lerpDouble(holder.yo + holder.eyeHeight * 0.7, holder.y + holder.eyeHeight * 0.7, partialTicks.toDouble()) -
            sinPitch * 0.5 -
            0.25
        val holderZ = RotationHelper.lerpDouble(holder.zo, holder.z, partialTicks.toDouble()) - sinYaw * 0.7 + cosYaw * 0.5 * cosPitch
        val girlYaw = RotationHelper.lerp(girl.yBodyRotO, girl.yBodyRot, partialTicks) * (Math.PI / 180.0).toFloat() + (Math.PI / 2)
        cosYaw = Math.cos(girlYaw) * girl.bbWidth * 0.4
        sinYaw = Math.sin(girlYaw) * girl.bbWidth * 0.4
        val girlX = RotationHelper.lerpDouble(girl.xo, girl.x, partialTicks.toDouble()) + cosYaw
        val girlY = RotationHelper.lerpDouble(girl.yo, girl.y, partialTicks.toDouble())
        val girlZ = RotationHelper.lerpDouble(girl.zo, girl.z, partialTicks.toDouble()) + sinYaw
        px += cosYaw
        pz += sinYaw
        val deltaX = (holderX - girlX).toFloat().toDouble()
        val deltaY = (holderY - girlY).toFloat().toDouble()
        val deltaZ = (holderZ - girlZ).toFloat().toDouble()
        val pose = poseStack.last().pose()

        var buffer = bufferSource.getBuffer(RenderType.leash())
        for (segment in 0..24) {
            var r = 0.5f
            var g = 0.4f
            var b = 0.3f
            if (segment % 2 == 0) {
                r = 0.35f
                g = 0.28f
                b = 0.21000001f
            }

            val progress = segment / 24.0f
            buffer.addVertex(
                pose,
                (px + deltaX * progress + 0.0).toFloat(),
                (py + deltaY * (progress * progress + progress) * 0.5 + ((24.0f - segment) / 18.0f + 0.125f)).toFloat(),
                (pz + deltaZ * progress).toFloat(),
            ).setColor(r, g, b, 1.0f).setLight(packedLight)
            buffer.addVertex(
                pose,
                (px + deltaX * progress + 0.025).toFloat(),
                (py + deltaY * (progress * progress + progress) * 0.5 + ((24.0f - segment) / 18.0f + 0.125f) + 0.025).toFloat(),
                (pz + deltaZ * progress).toFloat(),
            ).setColor(r, g, b, 1.0f).setLight(packedLight)
        }

        // separate strip, as the second Tessellator.draw() was
        (bufferSource as? MultiBufferSource.BufferSource)?.endBatch(RenderType.leash())
        buffer = bufferSource.getBuffer(RenderType.leash())

        for (segment2 in 0..24) {
            var r2 = 0.5f
            var g2 = 0.4f
            var b2 = 0.3f
            if (segment2 % 2 == 0) {
                r2 = 0.35f
                g2 = 0.28f
                b2 = 0.21000001f
            }

            val progress2 = segment2 / 24.0f
            buffer.addVertex(
                pose,
                (px + deltaX * progress2 + 0.0).toFloat(),
                (py + deltaY * (progress2 * progress2 + progress2) * 0.5 + ((24.0f - segment2) / 18.0f + 0.125f) + 0.025).toFloat(),
                (pz + deltaZ * progress2).toFloat(),
            ).setColor(r2, g2, b2, 1.0f).setLight(packedLight)
            buffer.addVertex(
                pose,
                (px + deltaX * progress2 + 0.025).toFloat(),
                (py + deltaY * (progress2 * progress2 + progress2) * 0.5 + ((24.0f - segment2) / 18.0f + 0.125f)).toFloat(),
                (pz + deltaZ * progress2 + 0.025).toFloat(),
            ).setColor(r2, g2, b2, 1.0f).setLight(packedLight)
        }

        (bufferSource as? MultiBufferSource.BufferSource)?.endBatch(RenderType.leash())
    }
}
