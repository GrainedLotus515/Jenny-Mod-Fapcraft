package com.trolmastercard.sexmod.util

import com.mojang.math.Axis
import com.trolmastercard.sexmod.client.renderer.GirlPlayerRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for the player-form Jenny (horny potion).
 *
 * Port note: hook parameters use the base names (`isOffhand`, `isBlocking`); the 1.12
 * subclass names were swapped but the logic is positional and unchanged.
 */
open class PlayerJennyRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>) : GirlPlayerRenderer(ctx, geoModel) {
    override fun preRenderCallback() {
        val poseStack = this.renderCtx.poseStack
        poseStack.translate(0.0f, -1.25f, 0.0f)
        poseStack.scale(0.8f, 0.8f, 0.8f)
    }

    override fun applyShieldBlockingTransform(isOffhand: Boolean, isBlocking: Boolean) {
        super.applyShieldBlockingTransform(isOffhand, isBlocking)
        val poseStack = this.renderCtx.poseStack
        if (!isOffhand && !isBlocking) {
            poseStack.translate(0.0, -0.1, 0.05)
            poseStack.mulPose(Axis.XP.rotationDegrees(40.0f))
            poseStack.mulPose(Axis.YP.rotationDegrees(0.0f))
            poseStack.mulPose(Axis.ZP.rotationDegrees(0.0f))
        } else if (isOffhand && !isBlocking) {
            poseStack.translate(-0.025, -0.1, 0.0)
        }
    }

    override fun applyBowRotation(isOffhand: Boolean) {
        super.applyBowRotation(isOffhand)
        if (isOffhand) {
            this.renderCtx.poseStack.translate(0.15, 0.0, 0.0)
        }
    }
}
