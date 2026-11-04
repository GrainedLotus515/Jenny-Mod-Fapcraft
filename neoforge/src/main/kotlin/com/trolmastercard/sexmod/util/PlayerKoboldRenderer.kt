package com.trolmastercard.sexmod.util

import com.mojang.math.Axis
import com.trolmastercard.sexmod.client.renderer.KoboldRenderer
import com.trolmastercard.sexmod.entity.AbstractNpcOnlyEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.EyeAndKoboldColor
import com.trolmastercard.sexmod.entity.KoboldPlayerEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.core.Vec3i
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import org.joml.Vector4f
import software.bernie.geckolib.model.GeoModel

/**
 * **Role.** Renderer for [KoboldPlayerEntity] (a player transformed
 * into a kobold). Subclasses [AbstractPlayerKoblinGoboldRenderer] and
 * overrides the bone coloring, eye scaling, item poses and shield-blocking
 * transforms for the kobold body.
 *
 * **State.** Colors come from `EyeAndKoboldColor.valueOf(CURRENT_ACTION)`
 * with [KoboldRenderer.hideBones]/[KoboldRenderer.showBones] toggles; the iris color is
 * the `ACTION_TARGET_POS` block position (a debug artifact — the target
 * position doubles as an RGB vector for the eyes). Both keys are the hoisted
 * `SHARED_*` keys (PORTING.md §2.6), so they read correctly on a kobold player.
 *
 * Port note: hook parameters use the base names (positional logic unchanged).
 */
open class PlayerKoboldRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>) :
    AbstractPlayerKoblinGoboldRenderer(ctx, geoModel) {
    override fun resolveBoneColor(boneName: String): Vec3i {
        val dataManager = this.renderEntity.entityData
        val color = EyeAndKoboldColor.valueOf(dataManager.get(AbstractNpcOnlyEntity.CURRENT_ACTION))
        val irisPos = dataManager.get(AbstractNpcOnlyEntity.ACTION_TARGET_POS)
        return if (KoboldRenderer.hideBones.contains(boneName)) {
            color.getMainColor()
        } else if (KoboldRenderer.showBones.contains(boneName)) {
            color.getSecondaryColor()
        } else {
            if ("irisR" != boneName && "irisL" != boneName) tintColor else irisPos
        }
    }

    override fun calculateBoneArmorColor(boneName: String, r: Float, g: Float, b: Float): Vector4f {
        if ("mouth" == boneName) {
            val modelCodeParts = AbstractNpcOnlyEntity.getModelCodeParts(this.renderEntity)
            val eyeIndex = Integer.parseInt(modelCodeParts[7])
            if (eyeIndex == 1) {
                return Vector4f(r, g, b, -0.078125f)
            }
        }

        return super.calculateBoneArmorColor(boneName, r, g, b)
    }

    override fun renderLeftEye() {
        val scale = 0.25f - this.renderEntity.entityData.get(KoboldPlayerEntity.SIZE_SCALAR)
        this.renderCtx.poseStack.scale(1.0f - scale, 1.0f - scale, 1.0f - scale)
    }

    override fun renderRightEye() {
        val scale = 0.25f - this.renderEntity.entityData.get(KoboldPlayerEntity.SIZE_SCALAR)
        val inverseScale = 1.0 / (1.0 - scale)
        this.renderCtx.poseStack.scale(inverseScale.toFloat(), inverseScale.toFloat(), inverseScale.toFloat())
    }

    override fun preRenderCallback() {
        val poseStack = this.renderCtx.poseStack
        poseStack.translate(0.0, (-0.8f).toDouble(), 0.05)
        poseStack.scale(0.5f, 0.5f, 0.5f)
    }

    override fun applyItemPostRotation(isOffhand: Boolean, stack: ItemStack) {
        super.applyItemPostRotation(isOffhand, stack)
        val poseStack = this.renderCtx.poseStack
        if (stack.useAnimation == UseAnim.BOW) {
            if (!isOffhand) {
                poseStack.mulPose(Axis.XP.rotationDegrees(170.0f))
            }

            if (isOffhand) {
                poseStack.translate(0.1f, 0.0f, 0.0f)
            }
        } else {
            poseStack.mulPose(Axis.XP.rotationDegrees(if (isOffhand) 80.0f else 180.0f))
        }
    }

    override fun applyShieldBlockingTransform(isOffhand: Boolean, isBlocking: Boolean) {
        super.applyShieldBlockingTransform(isOffhand, isBlocking)
        val poseStack = this.renderCtx.poseStack
        if (isOffhand) {
            if (isBlocking) {
                poseStack.translate(0.06, 0.0, -0.13)
                poseStack.mulPose(Axis.YP.rotationDegrees(60.0f))
                poseStack.mulPose(Axis.XP.rotationDegrees(38.0f))
                poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f))
            } else {
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0f))
                poseStack.translate(0.0, (-0.3f).toDouble(), -0.13)
            }
        } else if (isBlocking) {
            poseStack.mulPose(Axis.YP.rotationDegrees(150.0f))
            poseStack.translate(0.0, -0.35, 0.0)
        } else {
            poseStack.translate(0.0, -0.1, (-0.083f).toDouble())
        }
    }

    companion object {
        /** Java static inherited from [AbstractPlayerKoblinGoboldRenderer]; companions are not inherited in Kotlin. */
        @JvmStatic
        fun clearRenderCache() {
            AbstractPlayerKoblinGoboldRenderer.clearRenderCache()
        }
    }
}
