package com.trolmastercard.sexmod.util

import com.mojang.math.Axis
import com.trolmastercard.sexmod.client.renderer.GirlPlayerRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import software.bernie.geckolib.model.GeoModel
import org.joml.Vector3f
import software.bernie.geckolib.cache.`object`.GeoBone

/**
 * Renderer for the player-form Slime (horny potion).
 *
 * Port note: hook parameters use the base names (positional logic unchanged).
 */
open class PlayerSlimeRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>) : GirlPlayerRenderer(ctx, geoModel) {
    @JvmField var scaleSnapshot: Vector3f = Vector3f(0.0f, 0.0f, 0.0f)
    @JvmField var positionSnapshot: Vector3f = Vector3f(0.0f, 0.0f, 0.0f)
    @JvmField var rotationSnapshot: Vector3f = Vector3f(0.0f, 0.0f, 0.0f)
    @JvmField var rotationSnapshot3: Vector3f = Vector3f(0.0f, 0.0f, 0.0f)
    @JvmField var currentRotation: Vector3f = Vector3f(0.0f, 0.0f, 0.0f)
    @JvmField var rotationSnapshot2: Vector3f = Vector3f(0.0f, 0.0f, 0.0f)
    @JvmField var rotationSnapshot4: Vector3f = Vector3f(0.0f, 0.0f, 0.0f)

    override fun preRenderCallback() {
        val poseStack = this.renderCtx.poseStack
        poseStack.translate(0.0f, -1.25f, 0.0f)
        poseStack.scale(0.8f, 0.8f, 0.8f)
    }

    override fun onBoneRenderStart(boneName: String, bone: GeoBone) {
        if ("slime" == boneName) {
            this.rotationSnapshot = Vector3f(bone.rotX, bone.rotY, bone.rotZ)
            this.scaleSnapshot = Vector3f(bone.scaleX, bone.scaleY, bone.scaleZ)
            this.positionSnapshot = Vector3f(bone.posX, bone.posY, bone.posZ)
        }

        if ("upperBody" == boneName) {
            this.rotationSnapshot2 = Vector3f(bone.rotX, bone.rotY, bone.rotZ)
        }

        if ("torso" == boneName) {
            this.rotationSnapshot3 = Vector3f(bone.rotX, bone.rotY, bone.rotZ)
        }

        if ("head" == boneName) {
            this.rotationSnapshot4 = Vector3f(bone.rotX, bone.rotY, bone.rotZ)
        }

        if ("boobs" == boneName) {
            this.currentRotation = Vector3f(bone.rotX, bone.rotY, bone.rotZ)
        }

        if ("figure" == boneName) {
            bone.setRotX(this.rotationSnapshot.x)
            bone.setRotY(this.rotationSnapshot.y)
            bone.setRotZ(this.rotationSnapshot.z)
            bone.setScaleX(this.scaleSnapshot.x)
            bone.setScaleY(this.scaleSnapshot.y)
            bone.setScaleZ(this.scaleSnapshot.z)
            bone.setPosX(this.positionSnapshot.x)
            bone.setPosY(this.positionSnapshot.y)
            bone.setPosZ(this.positionSnapshot.z)
        }

        if ("dress" == boneName) {
            bone.setRotX(this.rotationSnapshot2.x)
            bone.setRotY(this.rotationSnapshot2.y)
            bone.setRotZ(this.rotationSnapshot2.z)
        }

        if ("hat" == boneName) {
            bone.setRotX(this.rotationSnapshot4.x)
            bone.setRotY(this.rotationSnapshot4.y)
            bone.setRotZ(this.rotationSnapshot4.z)
        }

        if ("boobsSlime" == boneName) {
            bone.setRotX(this.currentRotation.x)
            bone.setRotY(this.currentRotation.y)
            bone.setRotZ(this.currentRotation.z)
        }
    }

    override fun applyBowRotation(isOffhand: Boolean) {
        super.applyBowRotation(isOffhand)
        val poseStack = this.renderCtx.poseStack
        if (isOffhand) {
            poseStack.translate(0.15f, 0.0f, 0.0f)
        } else {
            poseStack.translate(-0.02, 0.0, 0.0)
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f))
        }
    }

    override fun getBlacklistedBones(): HashSet<String> {
        // 1.12 added to (and so mutated) the shared BodyParts.CUSTOM_PART_BONES set - kept as-is
        val blacklisted = super.getBlacklistedBones()
        blacklisted.add("figure")
        return blacklisted
    }

    override fun applyShieldBlockingTransform(isOffhand: Boolean, isBlocking: Boolean) {
        super.applyShieldBlockingTransform(isOffhand, isBlocking)
        val poseStack = this.renderCtx.poseStack
        if (isOffhand && !isBlocking) {
            poseStack.translate(-0.025, -0.025, 0.0)
        } else if (!isOffhand && isBlocking) {
            poseStack.mulPose(Axis.YP.rotationDegrees(120.0f))
        } else {
            if (!isOffhand && !isBlocking) {
                poseStack.translate(0.0, 0.4, -0.1)
                poseStack.mulPose(Axis.XP.rotationDegrees(-30.0f))
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
                poseStack.mulPose(Axis.XP.rotationDegrees(if (isOffhand) 30.0f else 135.0f))
                poseStack.translate(0.0, 0.05, -0.05)
            }
        }
    }
}
