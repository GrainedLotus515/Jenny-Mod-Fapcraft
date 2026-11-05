package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.entity.AbstractNpcOnlyEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.KoboldEntity
import com.trolmastercard.sexmod.entity.api.IKobold
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationProcessor
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.constant.DataTickets

/**
 * Geckolib model for the kobold NPC: kobold geo (nude/armored variants) whose
 * per-frame pass applies the owner's model code — horn variants (up/down),
 * boob/eye scales, freckle variants, backpack/tailpack pose, crown/egg
 * visibility — plus action-specific body positioning during the
 * blowjob/anal/mating-press scenes (transition-time interpolation) and a
 * tongue bone shown only for blowjob actions.
 *
 * **Pitfall:** the scene body offsets in [handleSwingAnimation]
 * depend on the action controller being in `TRANSITIONING` state and
 * the `SIZE_SCALAR` data value (0.25 - value); changing either breaks the scene
 * positioning.
 *
 * Port note: bone lookups that would NPE in 1.12 on a model without the bone are
 * null-safe no-ops here (docs/systems/rendering.md "Null-safe bone access").
 */
class KoboldNpcModel : GirlModel<BaseGirlEntity>() {
    override fun getModelLocations(): Array<ResourceLocation> =
        arrayOf(Main.rl("geo/kobold/kobold.geo.json"), Main.rl("geo/kobold/armored.geo.json"))

    override fun getTextureResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("textures/entity/kobold/kobold.png")

    override fun getAnimationResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("animations/kobold/kobold.animation.json")

    /**
     * Per-frame pass: crown/egg visibility (real kobolds only), model-code
     * part application (horns, scales, freckles, packs, colors), tongue
     * visibility by action, then the scene swing animation. Skipped in the
     * [SexWorldClient] preload world.
     */
    override fun setCustomAnimations(entity: BaseGirlEntity, instanceId: Long, animationState: AnimationState<BaseGirlEntity>) {
        super.setCustomAnimations(entity, instanceId, animationState)
        if (!SexWorldClient.isPreloadWorld(entity.level())) {
            val processor = this.animationProcessor
            if (!entity.isLocallyRegistered() && entity is KoboldEntity) {
                ModelBones.setHidden(processor, "crown", !entity.entityData.get(KoboldEntity.TRIBE_MEMBER))
                ModelBones.setHidden(processor, "egg", !entity.isRenderEgg)
            } else {
                ModelBones.setHidden(processor, "crown", true)
                ModelBones.setHidden(processor, "egg", true)
            }

            val modelCodeParts = AbstractNpcOnlyEntity.getModelCodeParts(entity)
            this.getHornsUp(processor, modelCodeParts[0])
            this.getHornsDown(processor, modelCodeParts[1])
            this.setBoneRotationMulti(processor, modelCodeParts[2], 0.75f, 1.35f, "boobL", "boobR", "armorBoobs")
            this.setBoneRotationMulti(processor, modelCodeParts[3], 1.0f, 1.2f, "eyeL", "eyeR")
            this.setBoneRotation(processor, modelCodeParts[3], 1.0f, 1.2f)
            this.getBoneData(processor, modelCodeParts[4])
            this.parseBoneColor(processor, modelCodeParts[5])
            this.updateBonePose(entity, processor, modelCodeParts[6])
            when (entity.getCurrentAction()) {
                Action.STARTBLOWJOB, Action.SUCKBLOWJOB_BLINK, Action.THRUSTBLOWJOB, Action.CUMBLOWJOB ->
                    ModelBones.setHidden(processor, "tounge", false)
                else -> ModelBones.setHidden(processor, "tounge", true)
            }

            this.handleSwingAnimation(entity, processor)
        }
    }

    /**
     * Scene body positioning while the controller transitions into a scene
     * action: blowjob actions drive the body forward (positionZ), anal actions
     * lower+shift it, mating-press actions raise it — all interpolated from
     * the `SIZE_SCALAR` transition value. See class doc pitfall.
     */
    fun handleSwingAnimation(entity: BaseGirlEntity, processor: AnimationProcessor<BaseGirlEntity>) {
        val controller = entity.actionController ?: return
        if (controller.animationState == AnimationController.State.TRANSITIONING) {
            var transitionValue = entity.entityData.get(KoboldEntity.SIZE_SCALAR)
            transitionValue = 0.25f - transitionValue
            when (entity.getCurrentAction()) {
                Action.SUCKBLOWJOB_BLINK, Action.THRUSTBLOWJOB, Action.CUMBLOWJOB -> {
                    val blowjobBodyBone = processor.getBone("body") ?: return
                    blowjobBodyBone.setPosZ(11.43f + transitionValue * -7.0f)
                    return
                }
                Action.KOBOLD_ANAL_SLOW, Action.ANAL_FAST, Action.ANAL_CUM, Action.ANAL_START -> {
                    val analBodyBone = processor.getBone("body") ?: return
                    analBodyBone.setPosX(1.78f + transitionValue * -1.5f)
                    analBodyBone.setPosY(13.07f + transitionValue * -11.0f)
                    analBodyBone.setPosZ(2.05f + transitionValue * -8.0f)
                    return
                }
                Action.MATING_PRESS_CUM, Action.MATING_PRESS_HARD, Action.MATING_PRESS_SOFT, Action.MATING_PRESS_START -> {
                    val matingBodyBone = processor.getBone("body") ?: return
                    matingBodyBone.setPosX(0.0f)
                    matingBodyBone.setPosY(2.85f)
                    matingBodyBone.setPosZ(-7.0f + transitionValue * 4.7f)
                    return
                }
                else -> {
                }
            }
        }
    }

    /**
     * Backpack/tailpack pose from the model code (0..3 combinations of
     * visible/hidden), with the backpack forced visible during PAYMENT.
     */
    fun updateBonePose(entity: BaseGirlEntity, processor: AnimationProcessor<BaseGirlEntity>, modelCode: String) {
        val poseValue = Integer.parseInt(modelCode)
        val backpackBone = processor.getBone("backpack")
        val tailpackBone = processor.getBone("tailpack")
        when (poseValue) {
            0 -> {
                backpackBone?.setHidden(false)
                tailpackBone?.setHidden(true)
            }
            1 -> {
                backpackBone?.setHidden(false)
                tailpackBone?.setHidden(false)
            }
            2 -> {
                backpackBone?.setHidden(true)
                tailpackBone?.setHidden(false)
            }
            3 -> {
                backpackBone?.setHidden(true)
                tailpackBone?.setHidden(true)
            }
        }

        if (entity.getCurrentAction() == Action.PAYMENT) {
            backpackBone?.setHidden(false)
        }
    }

    /**
     * Freckle-head variant selection from the model code (1 = variant 1 pair,
     * 2 = variant 2 pair, else hidden).
     */
    fun parseBoneColor(processor: AnimationProcessor<BaseGirlEntity>, modelCode: String) {
        val variant = Integer.parseInt(modelCode)
        val frecklesHR1Bone = processor.getBone("frecklesHR1")
        val frecklesHR2Bone = processor.getBone("frecklesHR2")
        val frecklesHL1Bone = processor.getBone("frecklesHL1")
        val frecklesHL2Bone = processor.getBone("frecklesHL2")
        frecklesHL1Bone?.setHidden(variant != 1)
        frecklesHR1Bone?.setHidden(variant != 1)
        frecklesHL2Bone?.setHidden(variant != 2)
        frecklesHR2Bone?.setHidden(variant != 2)
    }

    /**
     * Freckle-arm variant selection (same 1/2 semantics as the head variant).
     */
    fun getBoneData(processor: AnimationProcessor<BaseGirlEntity>, modelCode: String) {
        val variant = Integer.parseInt(modelCode)
        val frecklesAR1Bone = processor.getBone("frecklesAR1")
        val frecklesAR2Bone = processor.getBone("frecklesAR2")
        val frecklesAL1Bone = processor.getBone("frecklesAL1")
        val frecklesAL2Bone = processor.getBone("frecklesAL2")
        frecklesAL1Bone?.setHidden(variant != 1)
        frecklesAR1Bone?.setHidden(variant != 1)
        frecklesAL2Bone?.setHidden(variant != 2)
        frecklesAR2Bone?.setHidden(variant != 2)
    }

    /**
     * Eye-spacing from the model code: shifts both eyes apart/symmetrically by
     * the normalized value (0..1 lerp between the given bounds minus 1).
     */
    fun setBoneRotation(processor: AnimationProcessor<BaseGirlEntity>, modelCode: String, min: Float, max: Float) {
        if (!Minecraft.getInstance().isPaused) {
            var eyeSpacing = modelCode.toFloat()
            eyeSpacing /= 100.0f
            eyeSpacing = min + (max - min) * eyeSpacing - 1.0f
            val eyeLBone = processor.getBone("eyeL")
            eyeLBone?.setPosX(eyeLBone.posX + eyeSpacing)
            val eyeRBone = processor.getBone("eyeR")
            eyeRBone?.setPosX(eyeRBone.posX - eyeSpacing)
        }
    }

    /**
     * Uniform scale for the named bones from the model code (0..1 lerp between
     * the given bounds) — used for boobs and eyes.
     */
    fun setBoneRotationMulti(processor: AnimationProcessor<BaseGirlEntity>, modelCode: String, min: Float, max: Float, vararg boneNames: String) {
        var scale = modelCode.toFloat()
        scale /= 100.0f
        scale = min + (max - min) * scale

        for (boneName in boneNames) {
            val bone = processor.getBone(boneName)
            if (bone != null) {
                bone.setScaleX(scale)
                bone.setScaleY(scale)
                bone.setScaleZ(scale)
            }
        }
    }

    /**
     * Horn part selection: hides every down-horn variant bone and shows the
     * one chosen by the model code.
     */
    fun getHornsDown(processor: AnimationProcessor<BaseGirlEntity>, modelCode: String) {
        val downLeftBones = this.getHornBones(processor, "hornDL")
        val downRightBones = this.getHornBones(processor, "hornDR")
        this.hideAllBones(downLeftBones)
        this.hideAllBones(downRightBones)
        val variant = Integer.parseInt(modelCode)
        processor.getBone("hornDL$variant")?.setHidden(false)
        processor.getBone("hornDR$variant")?.setHidden(false)
    }

    /**
     * Up-horn variant selection (mirror of [getHornsDown]).
     */
    fun getHornsUp(processor: AnimationProcessor<BaseGirlEntity>, modelCode: String) {
        val upLeftBones = this.getHornBones(processor, "hornUL")
        val upRightBones = this.getHornBones(processor, "hornUR")
        this.hideAllBones(upLeftBones)
        this.hideAllBones(upRightBones)
        val variant = Integer.parseInt(modelCode)
        processor.getBone("hornUL$variant")?.setHidden(false)
        processor.getBone("hornUR$variant")?.setHidden(false)
    }

    /**
     * Collects all bones sharing the given prefix (numbered suffixes until a
     * null bone) — used for the horn variants.
     */
    fun getHornBones(processor: AnimationProcessor<BaseGirlEntity>, prefix: String): List<GeoBone> {
        val bones = ArrayList<GeoBone>()
        var i = 0

        while (true) {
            val bone = processor.getBone(prefix + i) ?: return bones

            bones.add(bone)
            i++
        }
    }

    fun hideAllBones(bones: List<GeoBone>) {
        for (bone in bones) {
            bone.setHidden(true)
        }
    }

    /**
     * Head-look for the idle action, skipped while the kobold is blocked by a
     * ceiling or on an unstable surface (the scene poses take over).
     */
    override fun handleAnimationEvent(girl: BaseGirlEntity, processor: AnimationProcessor<BaseGirlEntity>, event: AnimationState<BaseGirlEntity>) {
        if (!SexWorldClient.isPreloadWorld(girl.level())) {
            when (girl.getCurrentAction()) {
                Action.NULL -> {
                    if (Math.abs(girl.xo - girl.x) + Math.abs(girl.zo - girl.z) < 0.0 ||
                        girl.onGround() && Math.abs(Math.abs(girl.yo) - Math.abs(girl.y)) > 0.1f ||
                        !(girl as IKobold).isBlockedByCeiling()
                    ) {
                        val modelData = event.getData(DataTickets.ENTITY_MODEL_DATA) ?: return
                        val headBone = processor.getBone("head")
                        headBone?.setRotY(modelData.netHeadYaw() * (Math.PI / 180.0).toFloat())
                        headBone?.setRotX(modelData.headPitch() * (Math.PI / 180.0).toFloat())
                        val bodyBone = if (processor.getBone("body") == null) processor.getBone("dd") else processor.getBone("body")
                        bodyBone?.setRotY(0.0f)
                        return
                    }
                }
                else -> {
                }
            }
        }
    }

    override fun HeadArmor(): Array<String> = arrayOf("armorHelmet")

    override fun TopArmor(): Array<String> = arrayOf("armorShoulderR", "armorShoulderL", "armorChest", "armorBoobs")

    override fun Top(): Array<String> = arrayOf("boobsFlesh", "upperBodyL", "upperBodyR")

    override fun BottomArmor(): Array<String> = arrayOf(
        "armorBootyR",
        "armorBootyL",
        "armorPantsLowL",
        "armorPantsLowR",
        "armorPantsLowR",
        "armorPantsUpR",
        "armorPantsUpL",
        "armorHip",
        "armorKneeR",
        "armorKneeL",
    )

    override fun Bottom(): Array<String> = arrayOf("fleshL", "fleshR", "vagina", "fuckhole", "curvesL", "curvesR", "kneeL", "kneeR")

    override fun ShoesArmor(): Array<String> = arrayOf("armorShoesL", "armorShoesR")

    override fun Shoes(): Array<String> = arrayOf("toesR", "toesL")

    companion object {
        const val swingProgress: Float = 1.2f
        const val legSwing: Float = 1.0f
    }
}
