package com.trolmastercard.sexmod.util

import com.mojang.math.Axis
import com.trolmastercard.sexmod.client.renderer.GirlPlayerRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for the player-form Bee (horny potion).
 *
 * Port note: hook parameters use the base names (positional logic unchanged).
 */
open class PlayerBeeRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>) : GirlPlayerRenderer(ctx, geoModel) {
    override fun applyItemPostRotation(isOffhand: Boolean, stack: ItemStack) {
        this.renderCtx.poseStack.mulPose(Axis.XP.rotationDegrees(if (isOffhand) 290.0f else 90.0f))
    }

    override fun preRenderCallback() {
        val poseStack = this.renderCtx.poseStack
        poseStack.translate(0.0f, -0.6f, 0.0f)
        poseStack.scale(0.4f, 0.4f, 0.4f)
    }

    override fun applyBowRotation(isOffhand: Boolean) {
        super.applyBowRotation(isOffhand)
        if (isOffhand) {
            this.renderCtx.poseStack.translate(0.1, 0.0, 0.0)
        }
    }

    override fun applyShieldBlockingTransform(isOffhand: Boolean, isBlocking: Boolean) {
        val poseStack = this.renderCtx.poseStack
        if (isOffhand) {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f))
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f))
            poseStack.translate(0.0f, -0.14f, -0.17f)
            if (isBlocking) {
                poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f))
                poseStack.translate(0.067, 0.0, 0.0)
            }
        } else if (isBlocking) {
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f))
            poseStack.mulPose(Axis.ZP.rotationDegrees(-90.0f))
            poseStack.translate(0.0f, 0.165f, 0.0f)
        }
    }
}
