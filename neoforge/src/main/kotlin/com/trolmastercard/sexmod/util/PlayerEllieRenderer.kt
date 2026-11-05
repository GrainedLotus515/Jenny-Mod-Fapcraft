package com.trolmastercard.sexmod.util

import com.mojang.math.Axis
import com.trolmastercard.sexmod.client.renderer.GirlPlayerRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for the player-form Ellie (horny potion).
 *
 * Port note: hook parameters use the base names (positional logic unchanged).
 */
open class PlayerEllieRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>) : GirlPlayerRenderer(ctx, geoModel) {
    override fun preRenderCallback() {
        this.renderCtx.poseStack.translate(0.0f, -1.5f, 0.0f)
    }

    override fun applyItemPostRotation(isOffhand: Boolean, stack: ItemStack) {
        super.applyItemPostRotation(isOffhand, stack)
        val poseStack = this.renderCtx.poseStack
        when (stack.useAnimation) {
            UseAnim.BLOCK, UseAnim.BOW -> {
            }
            else -> {
                poseStack.mulPose(Axis.XP.rotationDegrees(if (isOffhand) 90.0f else 180.0f))
                if (isOffhand) {
                    poseStack.translate(0.0, 0.23900000452995301, (-0.1f).toDouble())
                } else {
                    poseStack.translate(0.0, 0.1, -0.07)
                }
            }
        }
    }

    override fun applyBowRotation(isOffhand: Boolean) {
        val poseStack = this.renderCtx.poseStack
        poseStack.mulPose(Axis.XP.rotationDegrees(if (isOffhand) 90.0f else 180.0f))
        if (isOffhand) {
            poseStack.translate(0.2, -0.2, 0.0)
        }
    }

    override fun applyShieldBlockingTransform(isOffhand: Boolean, isBlocking: Boolean) {
        val poseStack = this.renderCtx.poseStack
        if (isOffhand) {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f))
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f))
            if (isBlocking) {
                poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f))
                poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f))
                poseStack.mulPose(Axis.XP.rotationDegrees(-20.0f))
                poseStack.translate(0.4f, 0.0f, 0.228f)
            }
        } else {
            poseStack.translate(0.0f, 0.282f, 0.141f)
            if (isBlocking) {
                poseStack.translate(0.165, (-0.45f).toDouble(), 0.0)
                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f))
                poseStack.mulPose(Axis.ZP.rotationDegrees(-90.0f))
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0f))
                poseStack.mulPose(Axis.YP.rotationDegrees(-27.0f))
            } else {
                poseStack.translate(0.0, 0.0, -0.05)
            }
        }
    }
}
