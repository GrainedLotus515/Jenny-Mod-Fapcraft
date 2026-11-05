package com.trolmastercard.sexmod.util

import com.mojang.math.Axis
import com.trolmastercard.sexmod.client.renderer.GirlPlayerRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for the player-form Bia (horny potion).
 *
 * Port note: hook parameters use the base names (positional logic unchanged).
 */
open class PlayerBiaRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>) : GirlPlayerRenderer(ctx, geoModel) {
    override fun preRenderCallback() {
        val poseStack = this.renderCtx.poseStack
        poseStack.translate(0.0, -1.0, -0.05)
        poseStack.scale(0.65f, 0.65f, 0.65f)
    }

    override fun applyBowRotation(isOffhand: Boolean) {
        super.applyBowRotation(isOffhand)
        if (isOffhand) {
            this.renderCtx.poseStack.translate(0.15, 0.0, 0.0)
        }
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

    override fun getBlacklistedBones(): HashSet<String> {
        return hashSetOf("boobs", "booty", "vagina", "fuckhole", "leaf7", "leaf8")
    }
}
