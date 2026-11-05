package com.trolmastercard.sexmod.util

import com.mojang.math.Axis
import com.trolmastercard.sexmod.client.renderer.GirlPlayerRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import software.bernie.geckolib.model.GeoModel
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.AlliePlayerEntity
import net.minecraft.world.phys.Vec2
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import software.bernie.geckolib.cache.`object`.GeoBone

/**
 * Renderer for the player-form Allie (horny potion).
 *
 * Port note: hook parameters use the base names (positional logic unchanged).
 */
open class PlayerAllieRenderer(ctx: EntityRendererProvider.Context, geoModel: GeoModel<out BaseGirlEntity>) : GirlPlayerRenderer(ctx, geoModel) {
    @JvmField var currentPosX: Double = 0.0
    @JvmField var currentPosZ: Double = 0.0
    @JvmField var prevPosX: Double = 0.0
    @JvmField var prevPosZ: Double = 0.0
    @JvmField var prevRotX: Float = 0.0f
    @JvmField var prevRotZ: Float = 0.0f
    @JvmField var rotG: Float = 0.0f
    @JvmField var rotI: Float = 0.0f
    @JvmField var smoothedBob: Double = 0.0
    @JvmField var moveMagnitude: Double = 0.0

    init {
        renderers.add(this)
    }

    override fun preRenderCallback() {
        val poseStack = this.renderCtx.poseStack
        poseStack.translate(0.0f, -1.1f, 0.0f)
        poseStack.scale(0.7f, 0.7f, 0.7f)
    }

    override fun applyItemPostRotation(isOffhand: Boolean, stack: ItemStack) {
        super.applyItemPostRotation(isOffhand, stack)
        when (stack.useAnimation) {
            UseAnim.BLOCK, UseAnim.BOW -> {
            }
            else -> {
                val poseStack = this.renderCtx.poseStack
                if (!isOffhand) {
                    poseStack.mulPose(Axis.XP.rotationDegrees(20.0f))
                }

                poseStack.translate(0.0, 0.05, 0.0)
            }
        }
    }

    override fun applyBowRotation(isOffhand: Boolean) {
        super.applyBowRotation(isOffhand)
        if (isOffhand) {
            this.renderCtx.poseStack.translate(0.15, 0.0, 0.0)
        } else {
            this.renderCtx.poseStack.translate(-0.05, 0.0, 0.0)
        }
    }

    override fun applyShieldBlockingTransform(isOffhand: Boolean, isBlocking: Boolean) {
        super.applyShieldBlockingTransform(isOffhand, isBlocking)
        val poseStack = this.renderCtx.poseStack
        if (isOffhand && !isBlocking) {
            poseStack.translate(-0.025, -0.1, -0.1)
            poseStack.mulPose(Axis.XP.rotationDegrees(10.0f))
        } else if (!isOffhand && !isBlocking) {
            poseStack.translate(-0.05, -0.125, 0.125)
            poseStack.mulPose(Axis.XP.rotationDegrees(50.0f))
        }
    }

    override fun onBoneRenderStart(boneName: String, bone: GeoBone) {
        if (!this.playerGirl.entityData.get(BaseGirlEntity.IS_ANCHORED)) {
            if ("tail" == boneName) {
                this.applyBoneRotation(bone, 0.0f, 0.0f, 1.0f)
            }

            if ("body" == boneName) {
                this.updateBoneBob(bone)
            }

            if (this.playerGirl.getCurrentAction() != Action.BOW) {
                if ("armL" == boneName) {
                    this.applyBoneRotation(bone, 0.0f, (-Math.PI / 9).toFloat(), 0.15f)
                }

                if (this.playerGirl.getCurrentAction() != Action.ATTACK) {
                    if ("armR" == boneName) {
                        this.applyBoneRotation(bone, 0.0f, (Math.PI / 9).toFloat(), 0.15f)
                    }
                }
            }
        }
    }

    fun applyBoneRotation(bone: GeoBone, rotX: Float, rotZ: Float, intensity: Float) {
        val dx = this.currentPosX - this.prevPosX
        val dz = this.currentPosZ - this.prevPosZ
        val yawRad = (Math.PI / 180.0) * this.playerGirl.yRot
        val movement = Vec2((dx * Math.cos(yawRad) + dz * Math.sin(yawRad)).toFloat(), (-dx * Math.sin(yawRad) + dz * Math.cos(yawRad)).toFloat())
        this.rotG = movement.y * -8.0f
        this.rotI = movement.x * 8.0f
        this.rotG = ThreadNames.clampFloat(this.rotG, -1.68f, 1.68f)
        this.rotI = ThreadNames.clampFloat(this.rotI, -1.68f, 1.68f)
        this.rotG = RotationHelper.lerp(this.prevRotX, this.rotG, this.partialTicks)
        this.rotI = RotationHelper.lerp(this.prevRotZ, this.rotI, this.partialTicks)
        bone.setRotX(rotX + this.rotG * intensity)
        bone.setRotZ(rotZ + this.rotI * intensity)
    }

    fun updateBoneBob(bone: GeoBone) {
        val dx = this.currentPosX - this.prevPosX
        val dz = this.currentPosZ - this.prevPosZ
        this.moveMagnitude = (Math.abs(dx) + Math.abs(dz)) * 5.0
        this.moveMagnitude = ThreadNames.clampFloat(this.moveMagnitude.toFloat(), 0.0f, 1.0f).toDouble()
        bone.setPosY(RotationHelper.lerpAngle(5.0, 0.0, RotationHelper.lerpDouble(this.smoothedBob, this.moveMagnitude, this.partialTicks.toDouble())).toFloat())
        val girl = this.playerGirl
        if (girl is AlliePlayerEntity) {
            girl.scaleOffset = RotationHelper.lerpAngle(0.3f.toDouble(), 0.0, RotationHelper.lerpDouble(this.smoothedBob, this.moveMagnitude, this.partialTicks.toDouble())).toFloat()
        }
    }

    fun updateCameraRotations() {
        if (this.hasPlayerGirl()) {
            this.prevRotX = this.rotG
            this.prevRotZ = this.rotI
            this.smoothedBob = this.moveMagnitude
            val ownerUuid = this.playerGirl.getOwnerUserUUID()
            if (ownerUuid != null) {
                val owner = this.playerGirl.level().getPlayerByUUID(ownerUuid)
                if (owner != null) {
                    this.prevPosX = this.currentPosX
                    this.prevPosZ = this.currentPosZ
                    this.currentPosX = owner.x
                    this.currentPosZ = owner.z
                }
            }
        }
    }

    /**
     * 1.12 subscribed the phase-less `ClientTickEvent`, which fired at both START and END of
     * every tick; both 1.21 phases are subscribed to keep the double update.
     */
    class ClientTickHandler {
        @SubscribeEvent
        fun onClientTickPre(event: ClientTickEvent.Pre) {
            for (renderer in renderers) {
                renderer.updateCameraRotations()
            }
        }

        @SubscribeEvent
        fun onClientTickPost(event: ClientTickEvent.Post) {
            for (renderer in renderers) {
                renderer.updateCameraRotations()
            }
        }
    }

    companion object {
        const val BOB_SCALE_8: Float = 8.0f
        const val BOB_SCALE_1_68: Float = 1.68f
        const val BOB_SCALE_5: Float = 5.0f

        @JvmField
        var renderers: MutableCollection<PlayerAllieRenderer> = ArrayList()
    }
}
