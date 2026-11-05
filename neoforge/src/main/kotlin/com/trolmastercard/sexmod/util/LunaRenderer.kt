package com.trolmastercard.sexmod.util

import com.mojang.math.Axis
import com.trolmastercard.sexmod.MatrixHelper
import com.trolmastercard.sexmod.client.renderer.GirlRenderer
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.LunaEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for the Luna NPC.
 */
class LunaRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>, scaleFactor: Double) :
    GirlRenderer<BaseGirlEntity>(ctx, geoModel, scaleFactor) {
    @JvmField
    var rotR: Float = 0.0f

    override fun resolveHeldItemStack(stack: ItemStack?): ItemStack? {
        when (this.renderEntity.getCurrentAction()) {
            Action.FISHING_IDLE, Action.FISHING_START -> {
                val lunaStack = (this.renderEntity as LunaEntity).heldRod
                val cachedStack = this.renderEntity.entityData.get(LunaEntity.SYNCED_HELD_ITEM)
                if (cachedStack.isEmpty) {
                    return lunaStack
                }

                EnchantmentHelper.setEnchantments(lunaStack, cachedStack.tagEnchantments)
                this.renderEntity.setItemInHand(InteractionHand.MAIN_HAND, lunaStack)
                return lunaStack
            }
            else -> return stack
        }
    }

    fun isAnchored(): Boolean {
        return this.renderEntity.entityData.get(BaseGirlEntity.IS_ANCHORED)
    }

    override fun onBoneProcessing(boneName: String, bone: GeoBone) {
        if (!Minecraft.getInstance().isPaused) {
            when (boneName) {
                "head" -> this.rotR = bone.rotX
                "backHair" -> {
                    if (!this.isAnchored()) {
                        val t = (this.rotR / TrigMath.wrapDegrees(45.0f)).toDouble()
                        val pos = RotationHelper.lerpDouble(0.0, 0.75, t).toFloat()
                        bone.setPosZ(pos)
                        bone.setPosY(pos)
                        bone.setRotX(-this.rotR)
                    }
                }
                "sideHairR", "sideHairL", "frontHairL", "frontHairR" -> {
                    if (boneName == "sideHairR" || boneName == "sideHairL") {
                        if (this.isAnchored()) {
                            return
                        }

                        val t2 = (this.rotR / TrigMath.wrapDegrees(45.0f)).toDouble()
                        val pos2 = RotationHelper.lerpDouble(0.0, 1.3f.toDouble(), t2).toFloat()
                        bone.setPosZ(-pos2)
                        bone.setPosY(pos2)
                    }

                    // the side-hair case falls through into the front-hair case
                    if (!this.isAnchored()) {
                        bone.setRotX(-this.rotR)
                    }
                }
                "offhand" -> {
                    val luna = this.renderEntity as LunaEntity
                    val itemStack = this.renderEntity.entityData.get(LunaEntity.CAUGHT_ITEM)
                    if (!itemStack.isEmpty && luna.zFlag == 1.0f) {
                        val poseStack = this.renderCtx.poseStack
                        poseStack.pushPose()
                        try {
                            MatrixHelper.applyBoneTransform(poseStack, bone)
                            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f))
                            poseStack.scale(luna.renderScale, luna.renderScale, luna.renderScale)
                            this.renderVanillaItem(itemStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
                        } finally {
                            poseStack.popPose()
                        }
                    }
                }
            }
        }
    }
}
