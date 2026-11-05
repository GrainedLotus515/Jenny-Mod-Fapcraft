package com.trolmastercard.sexmod.util

import com.mojang.math.Axis
import com.trolmastercard.sexmod.client.renderer.GirlPlayerRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import software.bernie.geckolib.model.GeoModel
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.LunaEntity
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import software.bernie.geckolib.cache.`object`.GeoBone

/**
 * Renderer for the player-form Luna (horny potion).
 *
 * Port note: hook parameters use the base names (positional logic unchanged).
 */
open class PlayerLunaRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>) : GirlPlayerRenderer(ctx, geoModel) {
    @JvmField
    var rotationZ: Float = 0.0f

    override fun preRenderCallback() {
        val poseStack = this.renderCtx.poseStack
        poseStack.translate(0.0f, -1.0f, 0.0f)
        poseStack.scale(0.65f, 0.65f, 0.65f)
    }

    override fun resolveHeldItemStack(stack: ItemStack?): ItemStack? {
        return when (this.renderEntity.getCurrentAction()) {
            Action.FISHING_IDLE, Action.FISHING_START -> {
                val lunaStack = (this.renderEntity as LunaEntity).heldRod
                this.renderEntity.setItemInHand(InteractionHand.MAIN_HAND, lunaStack)
                lunaStack
            }
            else -> stack
        }
    }

    fun isLunaAnchored(): Boolean = this.renderEntity.entityData.get(BaseGirlEntity.IS_ANCHORED)

    override fun onBoneRenderStart(boneName: String, bone: GeoBone) {
        if (!Minecraft.getInstance().isPaused) {
            when (boneName) {
                "head" -> this.rotationZ = bone.rotX
                "backHair" -> {
                    if (!this.isLunaAnchored() && this.rotationZ > 0.0f) {
                        val t = (this.rotationZ / TrigMath.wrapDegrees(45.0f)).toDouble()
                        val pos = RotationHelper.lerpDouble(0.0, 0.75, t).toFloat()
                        bone.setPosZ(pos)
                        bone.setPosY(pos)
                        bone.setRotX(-this.rotationZ)
                    }
                }
                "frontHairL", "frontHairR" -> {
                    if (!this.isLunaAnchored()) {
                        bone.setRotX(-this.rotationZ)
                    }
                }
            }
        }
    }

    override fun applyItemPostRotation(isOffhand: Boolean, stack: ItemStack) {
        super.applyItemPostRotation(isOffhand, stack)
        when (stack.useAnimation) {
            UseAnim.BLOCK, UseAnim.BOW -> {
            }
            else -> {
                val poseStack = this.renderCtx.poseStack
                poseStack.mulPose(Axis.XP.rotationDegrees(if (isOffhand) 60.0f else 150.0f))
                poseStack.translate(0.0, 0.08, -0.05)
            }
        }
    }

    override fun applyBowRotation(isOffhand: Boolean) {
        val poseStack = this.renderCtx.poseStack
        poseStack.mulPose(Axis.XP.rotationDegrees(if (isOffhand) 60.0f else 150.0f))
        if (isOffhand) {
            poseStack.translate(0.12, 0.0, 0.0)
        }
    }

    override fun applyShieldBlockingTransform(isOffhand: Boolean, isBlocking: Boolean) {
        super.applyShieldBlockingTransform(isOffhand, isBlocking)
        val poseStack = this.renderCtx.poseStack
        if (!isOffhand && isBlocking) {
            poseStack.mulPose(Axis.YP.rotationDegrees(120.0f))
        } else if (!isOffhand && !isBlocking) {
            poseStack.translate(0.0, 0.3, -0.15)
            poseStack.mulPose(Axis.XP.rotationDegrees(-45.0f))
        } else if (isOffhand && !isBlocking) {
            poseStack.translate(-0.025, -0.05, 0.0)
        }
    }
}
