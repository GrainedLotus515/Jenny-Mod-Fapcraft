package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.entity.AbstractNpcOnlyEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.EyeAndKoboldColor
import com.trolmastercard.sexmod.entity.KoboldEntity
import com.trolmastercard.sexmod.entity.KoboldPlayerEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.core.Vec3i
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.model.GeoModel

/**
 * Renderer for the kobold NPC (and kobold-player): a [GirlRendererBase]
 * whose bone colors encode the kobold's color scheme and whose held items
 * follow the current action.
 *
 * **Coloring.** [getBoneColor] maps bones from the static
 * [hideBones] (main color) / [showBones] (secondary color) sets
 * to the kobold's [EyeAndKoboldColor] palette; the iris bones use the
 * action target position as an RGB color. The color cache is invalidated on
 * action changes ([doRenderKobold] watches the action string).
 *
 * **Held items.** [resolveHeldItemStack] switches the held item by
 * action: iron axe/pickaxe while mining, sword while attacking or when the
 * guard flag is set. Payment items (pickaxe / 3 gold) come from the hand
 * states during the blowjob/anal trade actions.
 *
 * CLIENT-side render thread only. In the [SexWorldClient] preload world
 * the custom-bone pass is skipped.
 *
 * Port note: [doRenderEntity] routes through [doRenderKobold] (the 1.12 class defined it
 * but nothing called it, so the colour cache was never invalidated on colour changes).
 */
class KoboldRenderer(ctx: EntityRendererProvider.Context, model: GeoModel<out AbstractNpcOnlyEntity>, shadowSize: Double) :
    GirlRendererBase<KoboldEntity>(ctx, model, shadowSize) {
    /**
     * Bone tint by name: hide-list bones use the kobold color's main color,
     * show-list bones its secondary color, iris bones encode the action-target
     * position as RGB; everything else stays white.
     */
    override fun getBoneColor(boneName: String): Vec3i {
        val dataManager = this.renderEntity.entityData
        val koboldColor = EyeAndKoboldColor.valueOf(dataManager.get(AbstractNpcOnlyEntity.CURRENT_ACTION))
        val targetPos = dataManager.get(AbstractNpcOnlyEntity.ACTION_TARGET_POS)
        return if (hideBones.contains(boneName)) {
            koboldColor.getMainColor()
        } else if (showBones.contains(boneName)) {
            koboldColor.getSecondaryColor()
        } else {
            if ("irisR" != boneName && "irisL" != boneName) defaultColor else targetPos
        }
    }

    /**
     * Held item by action: axe or pickaxe while mining (flag chooses), sword
     * while attacking or when the guard flag is set; otherwise the default
     * stack.
     */
    override fun resolveHeldItemStack(stack: ItemStack?): ItemStack? {
        when (this.renderEntity.getCurrentAction()) {
            Action.MINE -> {
                if (this.renderEntity.entityData.get(KoboldEntity.MINING_WITH_AXE)) {
                    return ItemStack(Items.IRON_AXE)
                }

                return ItemStack(Items.IRON_PICKAXE)
            }
            Action.NULL -> {
                if (this.renderEntity.entityData.get(KoboldEntity.ARMED)) {
                    return ItemStack(Items.IRON_SWORD)
                }
                return stack
            }
            Action.ATTACK -> return ItemStack(Items.IRON_SWORD)
            else -> return stack
        }
    }

    /**
     * Custom-bone pass tweaks: the `blowOpening` bone disables the
     * texture offset; the `mouth` bone shifts it by -1/128 * 10 when the
     * model code selects the open-mouth variant. Otherwise delegates to
     * [GirlRendererBase.renderCustomBones].
     */
    override fun renderCustomBones(bone: GeoBone, r: Float, g: Float, b: Float, a: Float, overlayAlpha: Double) {
        if (!SexWorldClient.isPreloadWorld(this.renderEntity.level())) {
            var alpha = overlayAlpha
            val boneName = bone.name
            if ("blowOpening" == boneName) {
                alpha = 0.0
            }

            if ("mouth" == boneName) {
                val modelCodeParts = AbstractNpcOnlyEntity.getModelCodeParts(this.renderEntity)
                val mouthVariant = Integer.parseInt(modelCodeParts[7])
                if (mouthVariant == 1) {
                    alpha = -0.078125
                }
            }

            super.renderCustomBones(bone, r, g, b, a, alpha)
        }
    }

    /**
     * Eye-squint pair: the left eye shrinks by the squint value (from the
     * player-kobold's data manager) — the right eye compensates with the
     * inverse scale so the face stays closed/open correctly.
     */
    override fun renderLeftEye() {
        val squint = 0.25f - this.renderEntity.entityData.get(KoboldPlayerEntity.SIZE_SCALAR)
        this.renderCtx.poseStack.scale(1.0f - squint, 1.0f - squint, 1.0f - squint)
    }

    override fun renderRightEye() {
        val squint = 0.25f - this.renderEntity.entityData.get(KoboldPlayerEntity.SIZE_SCALAR)
        val inverseScale = 1.0 / (1.0 - squint)
        this.renderCtx.poseStack.scale(inverseScale.toFloat(), inverseScale.toFloat(), inverseScale.toFloat())
    }

    override fun getPaymentItemStack(): ItemStack? {
        val handState = this.renderEntity.entityData.get(BaseGirlEntity.GIRL_HAND_STATES)
        return if ("STARTBLOWJOB" == handState) {
            ItemStack(Items.IRON_PICKAXE)
        } else {
            if ("ANAL_START" == handState) ItemStack(Items.GOLD_INGOT, 3) else null
        }
    }

    override fun doRenderEntity(entity: KoboldEntity, entityYaw: Float, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        this.doRenderKobold(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    /**
     * Main render: detects action changes and clears the cached bone colors
     * (colors depend on the action), then delegates to the normal pipeline.
     */
    fun doRenderKobold(kobold: KoboldEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        val actionString = kobold.entityData.get(AbstractNpcOnlyEntity.CURRENT_ACTION)
        if (kobold.lastRenderedAction == null) {
            kobold.lastRenderedAction = actionString
        }

        if (kobold.lastRenderedAction != actionString) {
            clearBoneColors()
            kobold.lastRenderedAction = actionString
        }

        super.doRenderEntity(kobold, entityYaw, partialTicks, poseStack, bufferSource, packedLight)
    }

    /**
     * Name tag: tribed kobolds (custom name tag, not "null") show the tribe
     * name in the kobold color's text color below their display name.
     */
    override fun renderNameTag(x: Double, y: Double, z: Double) {
        val dataManager = this.renderEntity.entityData
        var tribeName = dataManager.get(KoboldEntity.TRIBE_NAME)
        if ("null" == tribeName) {
            super.renderNameTag(x, y, z)
        } else {
            val koboldColor = EyeAndKoboldColor.valueOf(dataManager.get(AbstractNpcOnlyEntity.CURRENT_ACTION))
            tribeName = koboldColor.getTextColor().toString() + " -" + tribeName + "-"
            this.renderLivingLabel(this.renderEntity, this.renderEntity.getEffectiveDisplayName() + tribeName, x, y + this.renderEntity.getScaleFactor(), z, 300)
        }
    }

    companion object {
        @JvmField
        val hideBones: HashSet<String> = hashSetOf(
            "colorSpots", "neck", "head", "snout", "midSectionR", "midSectionL", "innerCheekLR", "innerCheekRR", "gayL", "gayR",
            "legR", "legL", "shinL", "toesL", "kneeL", "curvesL", "shinR", "toesR", "kneeR", "curvesR", "sideL", "sideR", "hip",
            "torsoL", "torsoR", "armR", "lowerArmR", "ellbowR", "armL", "lowerArmL", "ellbowL", "hornUL", "hornUR", "tail", "tail2",
            "tail3", "tail4", "tail5", "hornDL2", "hornDR2", "hornDR3M", "hornDL3M", "frecklesAL1", "frecklesAL2", "frecklesAR1",
            "frecklesAR2", "frecklesHL1", "frecklesHL2", "frecklesHR1", "frecklesHR2",
        )

        @JvmField
        val showBones: HashSet<String> = hashSetOf(
            "boobR", "boobL", "frontNeck", "Rside", "Lside", "frontAndInside", "innerCheekLL", "innerCheekRL", "layer", "layer2",
            "down", "down2", "down3", "down4", "down5", "fuckhole", "hornDR3S", "hornDL3S", "assholeCoverUp", "assholeCoverUp2",
        )
    }
}
