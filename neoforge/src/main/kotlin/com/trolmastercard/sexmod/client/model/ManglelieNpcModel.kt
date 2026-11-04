package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.renderer.ManglelieRenderer
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.entity.ManglelieEntity
import com.trolmastercard.sexmod.proxy.ClientProxy
import com.trolmastercard.sexmod.util.EntityLookVectorHelper
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.TrigMath
import com.trolmastercard.sexmod.util.Vector3fSexmodSpecial
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimationProcessor
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.cache.object.GeoBone

/**
 * Geckolib model for Manglelie: the manglelie geo (with a combined Galath pose
 * variant for outfit index 2) whose per-frame pass runs the corruption arm/
 * head animation, the ride-mommy pose blend, the threesome pose (body follows
 * Galath's published rotation/scale) and the skirt/cheek visibility for the
 * look pose.
 *
 * <b>Corruption animation.</b> While corrupting, the arms swing toward/away
 * from Galath with a frame-rate independent blend cycle
 * (`VELOCITY_0`/`wasNoCorruptEntity` on the entity) between the corrupt pose and
 * the ride pose; the head tracks Galath with a speed-limited chase
 * (`TICK_0`/`headPitch`). The entity fields are written by this class —
 * do not move this logic.
 *
 * CLIENT-side only; skipped during preloading.
 *
 * Port notes: `setLivingAnimations` -> [setCustomAnimations]; `getModelLocation`
 * -> [getModelResource]; `Minecraft.getDebugFPS()` -> `mc.getFps()`. Missing
 * bones are null-guarded (a throw would abort the GL4 animation pass).
 */
open class ManglelieNpcModel : GirlModel<BaseGirlEntity>() {
    override fun getModelLocations(): Array<ResourceLocation> = arrayOf(
        Main.rl("geo/manglelie/manglelie.geo.json"),
        Main.rl("geo/manglelie/manglelie.geo.json"),
        Main.rl("geo/galath/galath_con_mang.geo.json"),
    )

    /**
     * The threesome animations (`animation.shared.double_holding_*`) are played
     * by the Manglelie and move the `body2` bone, which only exists in the
     * combined `galath_con_mang` model. The outfit index never selects that
     * model (nothing sets it to 2), so the shared animations used to run against a
     * model without `body2` - the pose looked frozen. Pick the combined model
     * for the duration of the threesome instead.
     */
    override fun getModelResource(entity: BaseGirlEntity): ResourceLocation {
        if (isInThreesome(entity) && this.modelLocations.size > 2) {
            return this.modelLocations[2]
        }

        return super.getModelResource(entity)
    }

    override fun getTextureResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("textures/entity/manglelie/manglelie.png")

    override fun getAnimationResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("animations/manglelie/manglelie.animation.json")

    /**
     * Per-frame pass: shared animation (skirt/cheek/cock-stage bones), then
     * the corruption arm/head poses, threesome body coupling and the
     * corruption leg/arm offset.
     */
    override fun setCustomAnimations(entity: BaseGirlEntity, instanceId: Long, animationState: AnimationState<BaseGirlEntity>) {
        super.setCustomAnimations(entity, instanceId, animationState)
        animateModel(entity, this.animationProcessor, animationState.partialTick)
        this.updatePoseBones(entity)
        this.updateCorruptBones(entity)
        this.updateThreesomePose(entity)
        this.updateCorruptPose(entity)
    }

    /**
     * Static corruption offset on the leg/forearms while Galath runs her
     * corruption actions (not in threesome).
     */
    fun updateCorruptPose(entity: BaseGirlEntity) {
        if (!this.mc.isPaused) {
            if (!isInThreesome(entity)) {
                val galath = ManglelieEntity.getGalathPartnerOf(entity, false)
                if (galath != null) {
                    if (Action.isAny(galath.getCurrentAction(), Action.CORRUPT_CUM, Action.CARRY_FAST, Action.CORRUPT_INTRO, Action.CORRUPT_SLOW)) {
                        val processor = this.animationProcessor
                        val legRBone = processor.getBone("legR") ?: return
                        legRBone.setRotY(legRBone.rotY + CORRUPTION_POSE_OFFSET)
                        val lowerArmRBone = processor.getBone("lowerArmR") ?: return
                        val lowerArmLBone = processor.getBone("lowerArmL") ?: return
                        lowerArmRBone.setRotX(lowerArmRBone.rotX + CORRUPTION_POSE_OFFSET)
                        lowerArmLBone.setRotX(lowerArmLBone.rotX + CORRUPTION_POSE_OFFSET)
                    }
                }
            }
        }
    }

    /**
     * Threesome coupling: the body yaw follows Galath's published rotation
     * (`bodyRotationY`) and its scale follows `bodyScaleY` — Manglelie mirrors
     * Galath's pose in the threesome scenes.
     */
    fun updateThreesomePose(entity: BaseGirlEntity) {
        if (entity is ManglelieEntity) {
            if (!isInThreesome(entity)) {
                val galath = entity.getGalathPartner(false)
                if (galath != null) {
                    val bodyBone = this.animationProcessor.getBone("body") ?: return
                    bodyBone.setRotY(galath.bodyRotationY + (if (this.mc.isPaused) 0.0f else bodyBone.rotY))
                    bodyBone.setScaleX(galath.bodyScaleY)
                    bodyBone.setScaleY(galath.bodyScaleY)
                    bodyBone.setScaleZ(galath.bodyScaleY)
                }
            }
        }
    }

    fun getLookVector(entity: Entity): Vec3 =
        EntityLookVectorHelper.getEntityLookVector(entity, this.mc.timer.getGameTimeDeltaPartialTick(true)).add(0.0, entity.eyeHeight.toDouble(), 0.0)

    /**
     * Corruption arm animation: blends between the corrupt pose and the ride
     * pose on a frame-rate independent cycle (entity fields `VELOCITY_0`
     * + `wasNoCorruptEntity`) and applies the resulting rotations/scales/elbows to all
     * four arm bones. The look target is the corrupt entity when present, else
     * Galath's look vector.
     */
    fun updateCorruptBones(entity: BaseGirlEntity) {
        if (!ClientProxy.IS_PRELOADING) {
            if (!isInThreesome(entity)) {
                if (!this.mc.isPaused) {
                    val manglelie = entity as ManglelieEntity
                    if (manglelie.isCorrupting()) {
                        val galath = manglelie.getGalathPartner(false)
                        if (galath != null) {
                            val processor = this.animationProcessor
                            val armLBone = processor.getBone("armL") ?: return
                            val armRBone = processor.getBone("armR") ?: return
                            val lowerArmLBone = processor.getBone("lowerArmL") ?: return
                            val lowerArmRBone = processor.getBone("lowerArmR") ?: return
                            val elbowRBone = processor.getBone("elbowR") ?: return
                            val elbowLBone = processor.getBone("elbowL") ?: return
                            val corruptEntity = manglelie.getCorruptEntity()
                            val hasNoCorruptEntity = corruptEntity == null
                            if (corruptEntity != null) {
                                manglelie.ZERO_VECTOR = this.getLookVector(corruptEntity)
                            }

                            var fps = Minecraft.getInstance().fps.toFloat()
                            if (fps == 0.0f) {
                                fps = 1.0f
                            }

                            if (manglelie.wasNoCorruptEntity == hasNoCorruptEntity) {
                                manglelie.VELOCITY_0 = 0.0f
                            } else {
                                manglelie.VELOCITY_0 += 1.5f / fps
                            }

                            if (manglelie.VELOCITY_0 >= 1.0f) {
                                manglelie.VELOCITY_0 = 0.0f
                                manglelie.wasNoCorruptEntity = hasNoCorruptEntity
                            }

                            // the two Java branches differ only in the pose used while not blending
                            val rotationData: RotationData = if (manglelie.VELOCITY_0 == 0.0f) {
                                if (hasNoCorruptEntity) {
                                    this.updateCorruptPose(galath, armRBone, armLBone, lowerArmLBone, lowerArmRBone)
                                } else {
                                    this.updateRidePose(manglelie, galath, lowerArmRBone, lowerArmLBone, processor)
                                }
                            } else {
                                RotationData.lerpRotationData(
                                    this.updateCorruptPose(galath, armRBone, armLBone, lowerArmLBone, lowerArmRBone),
                                    this.updateRidePose(manglelie, galath, lowerArmRBone, lowerArmLBone, processor),
                                    (if (manglelie.wasNoCorruptEntity) RotationHelper.smoothStep(manglelie.VELOCITY_0.toDouble())
                                    else 1.0 - RotationHelper.smoothStep(manglelie.VELOCITY_0.toDouble())).toFloat(),
                                )
                            }

                            armRBone.setRotX(rotationData.armRRotation.x)
                            armRBone.setRotY(rotationData.armRRotation.y)
                            armRBone.setRotZ(rotationData.armRRotation.z)
                            armLBone.setRotX(rotationData.armLRotation.x)
                            armLBone.setRotY(rotationData.armLRotation.y)
                            armLBone.setRotZ(rotationData.armLRotation.z)
                            lowerArmLBone.setRotX(rotationData.lowerArmLRotation.x)
                            lowerArmLBone.setRotY(rotationData.lowerArmLRotation.y)
                            lowerArmLBone.setRotZ(rotationData.lowerArmLRotation.z)
                            lowerArmRBone.setRotX(rotationData.lowerArmRRotation.x)
                            lowerArmRBone.setRotY(rotationData.lowerArmRRotation.y)
                            lowerArmRBone.setRotZ(rotationData.lowerArmRRotation.z)
                            armLBone.setScaleY(rotationData.armLScale)
                            armRBone.setScaleY(rotationData.armRScale)
                            elbowRBone.setRotY(rotationData.elbowRRotationY)
                            elbowLBone.setRotY(rotationData.elbowLRotationY)
                        }
                    }
                }
            }
        }
    }

    /**
     * Ride-pose arm data: arms reach toward Galath's look point (aim angles
     * from the bone offsets), with scale/elbow corrections and a staged arm
     * swing during the corruption progress phases.
     * (Java passes the forearm bones in swapped order; kept.)
     */
    fun updateRidePose(manglelie: ManglelieEntity, galath: GalathEntity, lowerArmLBone: GeoBone, lowerArmRBone: GeoBone, processor: AnimationProcessor<*>): RotationData {
        val rotationData = RotationData()
        rotationData.lowerArmLRotation = Vector3fSexmodSpecial(UPPER_ARM_BASE_ANGLE, 0.0f, lowerArmLBone.rotZ)
        rotationData.lowerArmRRotation = Vector3fSexmodSpecial(LOWER_ARM_BASE_ANGLE, 0.0f, lowerArmRBone.rotZ)
        val upperBody = ModelBones.get(processor, "upperBody")
        val upperBodyRotation = galath.headRotationX + (upperBody?.rotX ?: 0.0f)
        val partialTicks = this.mc.timer.getGameTimeDeltaPartialTick(true)
        val galathLookVec = ManglelieRenderer.getEntityLookVector(galath, partialTicks)
        val armRPoint = manglelie.getCachedBoneOffset("armR").add(galathLookVec)
        val armLPoint = manglelie.getCachedBoneOffset("armL").add(galathLookVec)
        val armRAngles = ThreadNames.getLookAngles(armRPoint, manglelie.ZERO_VECTOR)
        val armLAngles = ThreadNames.getLookAngles(armLPoint, manglelie.ZERO_VECTOR)
        val aimYaw = GalathEntity.getAimYaw(galath, partialTicks)
        val headYaw = aimYaw ?: RotationHelper.lerpFloat(galath.yHeadRotO, galath.yHeadRot, partialTicks.toDouble())
        val wrappedHeadYaw = TrigMath.wrapDegrees(headYaw)
        val corruptProgress = manglelie.getCorruptProgress(partialTicks)
        val easedProgress = RotationHelper.easeInOutQuad(Math.min(1.0f, corruptProgress).toDouble()).toFloat()
        var swingProgress: Float
        if (easedProgress != 1.0f) {
            swingProgress = 0.0f
        } else {
            swingProgress = (corruptProgress * 28.0f - 28.0f) / 32.0f
            swingProgress = Math.max(0.0f, swingProgress - 0.5f) * 2.0f
        }

        val easedSwingProgress = RotationHelper.easeInOutQuad(swingProgress.toDouble()).toFloat()
        val armLift = TrigMath.wrapDegrees(RotationHelper.lerp(0.0f, 90.0f, easedProgress))
        val isLookingAtGalath = manglelie.isLookingAtGalathPoint(manglelie.ZERO_VECTOR, partialTicks)
        if (isLookingAtGalath) {
            rotationData.armRRotation = Vector3fSexmodSpecial(-upperBodyRotation + armRAngles.y + TrigMath.wrapDegrees(90.0f), armRAngles.x, 0.0f)
            rotationData.armLRotation = Vector3fSexmodSpecial(
                -upperBodyRotation + armLAngles.y + TrigMath.wrapDegrees(90.0f),
                (armLAngles.x + TrigMath.wrapDegrees(-20.0f) * Math.cos((armRAngles.x + wrappedHeadYaw * 1.0f).toDouble()) +
                    RotationHelper.lerp(armLift / 2.0f, 0.0f, easedSwingProgress)).toFloat(),
                0.0f,
            )
            rotationData.armLScale = 1.0f + Math.abs(Math.abs(armRAngles.x) - Math.abs(wrappedHeadYaw)) * 0.1909f
            rotationData.elbowLRotationY = TrigMath.wrapDegrees(90.0f)
            rotationData.lowerArmLRotation.z = RotationHelper.lerp(armLift, 0.0f, easedSwingProgress)
            if (swingProgress > 0.5) {
                rotationData.lowerArmLRotation.x = UPPER_ARM_BASE_ANGLE +
                    RotationHelper.lerpDouble(ARM_SWING_ANGLE.toDouble(), 0.0, RotationHelper.easeInOutQuad(((swingProgress - 0.5f) * 2.0f).toDouble())).toFloat()
            } else if (swingProgress != 0.0f && swingProgress < 0.5) {
                rotationData.lowerArmLRotation.x = UPPER_ARM_BASE_ANGLE +
                    RotationHelper.lerpDouble(0.0, ARM_SWING_ANGLE.toDouble(), RotationHelper.easeInOutQuad((swingProgress * 2.0f).toDouble())).toFloat()
            }
        } else {
            rotationData.armLRotation = Vector3fSexmodSpecial(-upperBodyRotation + armLAngles.y + TrigMath.wrapDegrees(90.0f), armLAngles.x, 0.0f)
            rotationData.armRRotation = Vector3fSexmodSpecial(
                -upperBodyRotation + armRAngles.y + TrigMath.wrapDegrees(90.0f),
                (armRAngles.x + TrigMath.wrapDegrees(20.0f) * Math.cos((armLAngles.x + wrappedHeadYaw * 1.0f).toDouble())).toFloat() -
                    RotationHelper.lerp(armLift / 2.0f, 0.0f, easedSwingProgress),
                0.0f,
            )
            rotationData.armRScale = 1.0f + Math.abs(Math.abs(armLAngles.x) - Math.abs(wrappedHeadYaw)) * 0.1909f
            rotationData.elbowRRotationY = TrigMath.wrapDegrees(90.0f)
            rotationData.lowerArmRRotation.z = -RotationHelper.lerp(armLift, 0.0f, easedSwingProgress)
            if (swingProgress > 0.5) {
                rotationData.lowerArmRRotation.x = LOWER_ARM_BASE_ANGLE +
                    RotationHelper.lerpDouble(ARM_SWING_ANGLE.toDouble(), 0.0, RotationHelper.easeInOutQuad(((swingProgress - 0.5f) * 2.0f).toDouble())).toFloat()
            } else if (swingProgress != 0.0f && swingProgress < 0.5) {
                rotationData.lowerArmRRotation.x = LOWER_ARM_BASE_ANGLE +
                    RotationHelper.lerpDouble(0.0, ARM_SWING_ANGLE.toDouble(), RotationHelper.easeInOutQuad((swingProgress * 2.0f).toDouble())).toFloat()
            }
        }

        rotationData.armRRotation.y += wrappedHeadYaw
        rotationData.armLRotation.y += wrappedHeadYaw
        return rotationData
    }

    /**
     * Corrupt-pose arm data from Galath's head rotation: positive head pitch
     * bends both arms up with mirrored yaw/z offsets; negative pitch drops the
     * forearms (scaled factors) and spreads the arms slightly.
     */
    fun updateCorruptPose(galath: GalathEntity, armRBone: GeoBone, armLBone: GeoBone, lowerArmLBone: GeoBone, lowerArmRBone: GeoBone): RotationData {
        val headRotation = galath.headRotationX
        val rotationData = RotationData()
        if (headRotation > 0.0f) {
            rotationData.armRRotation = Vector3fSexmodSpecial(armRBone.rotX - headRotation, armRBone.rotY - headRotation * -25.0f / 45.0f, armRBone.rotZ + headRotation * 12.5f / 45.0f)
            rotationData.armLRotation = Vector3fSexmodSpecial(armLBone.rotX - headRotation, armLBone.rotY + headRotation * 15.0f / 45.0f, armLBone.rotZ)
            rotationData.lowerArmLRotation = Vector3fSexmodSpecial(lowerArmLBone.rotX, lowerArmLBone.rotY, lowerArmLBone.rotZ)
            rotationData.lowerArmRRotation = Vector3fSexmodSpecial(lowerArmRBone.rotX, lowerArmRBone.rotY, lowerArmRBone.rotZ)
            return rotationData
        } else {
            rotationData.lowerArmRRotation = Vector3fSexmodSpecial(lowerArmRBone.rotX + 2.0f * headRotation, lowerArmRBone.rotY, lowerArmRBone.rotZ)
            rotationData.lowerArmLRotation = Vector3fSexmodSpecial(lowerArmLBone.rotX + 2.2222223f * headRotation, lowerArmLBone.rotY, lowerArmLBone.rotZ)
            rotationData.armRRotation = Vector3fSexmodSpecial(armRBone.rotX - headRotation, armRBone.rotY, armRBone.rotZ + headRotation * 5.0f / 45.0f)
            rotationData.armLRotation = Vector3fSexmodSpecial(armLBone.rotX - headRotation, armLBone.rotY, armLBone.rotZ - headRotation * 5.0f / 45.0f)
            return rotationData
        }
    }

    /**
     * Corruption head/body tracking: the rotation tool follows Galath's head
     * rotation, the upper body/head/boobs bend by it, and the head chases
     * Galath with a speed-limited (7 deg/frame at 60fps) wrap-around lerp —
     * state persists in the entity's `TICK_0`/`headPitch` fields.
     */
    fun updatePoseBones(entity: BaseGirlEntity) {
        if (!ClientProxy.IS_PRELOADING) {
            if (!this.mc.isPaused) {
                val manglelie = entity as ManglelieEntity
                if (ManglelieRenderer.isCorrupting(manglelie)) {
                    val galath = manglelie.getGalathPartner(false)
                    if (galath != null) {
                        val processor = this.animationProcessor
                        val headRotation = galath.headRotationX
                        ModelBones.get(processor, "rotationTool")?.setRotX(headRotation)
                        val headBone = processor.getBone("head") ?: return
                        val upperBodyBone = processor.getBone("upperBody") ?: return
                        val boobsBone = processor.getBone("boobs")
                        if (headRotation > 0.0f) {
                            upperBodyBone.setRotX(-1.1111112f * headRotation)
                            headBone.setRotX(0.1333f * headRotation)
                            boobsBone?.setRotX(headRotation * 22.5f / 45.0f)
                        } else {
                            upperBodyBone.setRotX(-1.6666666f * headRotation)
                            headBone.setRotX(headRotation * 0.666f)
                        }

                        val yawError = ThreadNames.wrapAngle(manglelie.TICK_0.toDouble(), manglelie.yawOffset.toDouble())
                        val pitchError = ThreadNames.wrapAngle(manglelie.headPitch.toDouble(), manglelie.rotationLerp.toDouble())
                        var fps = Minecraft.getInstance().fps.toFloat()
                        if (fps == 0.0f) {
                            fps = 1.0f
                        }

                        val yawStep = 7.0f * (if (Math.abs(yawError) < 7.0f) yawError else (if (yawError > 0.0f) 7.0f else -7.0f)) * (1.0f / fps)
                        val pitchStep = 7.0f * (if (Math.abs(pitchError) < 7.0f) pitchError else (if (pitchError > 0.0f) 7.0f else -7.0f)) * (1.0f / fps)
                        val newYaw = manglelie.TICK_0 + yawStep
                        val newPitch = manglelie.headPitch + pitchStep
                        headBone.setRotY(headBone.rotY + newYaw)
                        headBone.setRotX(headBone.rotX + newPitch)
                        manglelie.TICK_0 = newYaw
                        manglelie.headPitch = newPitch
                    }
                }
            }
        }
    }

    /**
     * Per-frame arm pose bundle (rotations, scales, elbow yaws) lerped between
     * the corrupt and ride poses during the corruption cycle.
     */
    class RotationData {
        @JvmField var armRRotation: Vector3fSexmodSpecial = Vector3fSexmodSpecial(0.0f, 0.0f, 0.0f)
        @JvmField var armLRotation: Vector3fSexmodSpecial = Vector3fSexmodSpecial(0.0f, 0.0f, 0.0f)
        @JvmField var lowerArmRRotation: Vector3fSexmodSpecial = Vector3fSexmodSpecial(0.0f, 0.0f, 0.0f)
        @JvmField var lowerArmLRotation: Vector3fSexmodSpecial = Vector3fSexmodSpecial(0.0f, 0.0f, 0.0f)
        @JvmField var armRScale: Float = 1.0f
        @JvmField var armLScale: Float = 1.0f
        @JvmField var elbowLRotationY: Float = 0.0f
        @JvmField var elbowRRotationY: Float = 0.0f

        companion object {
            /** Component-wise lerp of two pose bundles. */
            @JvmStatic
            fun lerpRotationData(from: RotationData, to: RotationData, progress: Float): RotationData {
                val result = RotationData()
                result.armRRotation = RotationHelper.lerpVector3f(from.armRRotation, to.armRRotation, progress.toDouble())
                result.armLRotation = RotationHelper.lerpVector3f(from.armLRotation, to.armLRotation, progress.toDouble())
                result.lowerArmRRotation = RotationHelper.lerpVector3f(from.lowerArmRRotation, to.lowerArmRRotation, progress.toDouble())
                result.lowerArmLRotation = RotationHelper.lerpVector3f(from.lowerArmLRotation, to.lowerArmLRotation, progress.toDouble())
                result.armRScale = RotationHelper.lerp(from.armRScale, to.armRScale, progress)
                result.armLScale = RotationHelper.lerp(from.armLScale, to.armLScale, progress)
                result.elbowLRotationY = RotationHelper.lerp(from.elbowLRotationY, to.elbowLRotationY, progress)
                result.elbowRRotationY = RotationHelper.lerp(from.elbowRRotationY, to.elbowRRotationY, progress)
                return result
            }
        }
    }

    companion object {
        const val HEAD_ROTATION_SPEED: Float = 7.0f
        const val headRotSpeed: Float = 0.75f
        @JvmField val LOWER_ARM_BASE_ANGLE: Float = TrigMath.wrapDegrees(140.0f)
        @JvmField val UPPER_ARM_BASE_ANGLE: Float = TrigMath.wrapDegrees(35.0f)
        const val armSwing: Float = 90.0f
        @JvmField val ARM_SWING_ANGLE: Float = TrigMath.wrapDegrees(45.0f)
        @JvmField val CORRUPTION_POSE_OFFSET: Float = TrigMath.wrapDegrees(-45.0f)
        @JvmField val MANGLELIE_TEXTURE: ResourceLocation = Main.rl("textures/entity/manglelie/manglelie.png")

        /**
         * Whether the girl is in a threesome action (slow/fast/cum) — disables the
         * corruption and look poses.
         */
        @JvmStatic
        fun isInThreesome(girl: BaseGirlEntity): Boolean =
            Action.isAnyAction(girl, Action.THREESOME_SLOW, Action.THREESOME_FAST, Action.THREESOME_CUM)

        /**
         * Shared pose pass (also called from `GalathNpcModel` while Galath
         * hugs Manglelie): toggles the skirt/cheek bones by the look state and
         * stages the cock bones by the corruption stage.
         */
        @JvmStatic
        fun animateModel(girl: BaseGirlEntity, processor: AnimationProcessor<*>, partialTicks: Float) {
            if (!ClientProxy.IS_PRELOADING) {
                val isGalathLooking = ManglelieRenderer.isGalathLooking(girl)
                setCheekHidden(processor, isGalathLooking)
                setSkirtHidden(processor, isGalathLooking)
                animatePose(girl, processor, partialTicks)
            }
        }

        /** Cock-stage bone visibility from the corruption stage counter. */
        @JvmStatic
        fun animatePose(girl: BaseGirlEntity, processor: AnimationProcessor<*>, partialTicks: Float) {
            if (girl is ManglelieEntity) {
                for (i in 0 until 3) {
                    processor.getBone("cockStage$i")?.setHidden(i > girl.corruptionStage)
                }
            }
        }

        /** Hides the skirt bone while the look pose is active (the custom ribbon mesh replaces it). */
        @JvmStatic
        fun setSkirtHidden(processor: AnimationProcessor<*>, hidden: Boolean) {
            ModelBones.setHidden(processor, "skirt", !hidden)
        }

        /** Swaps the below-skirt cheek/side bones against their skirted variants depending on the look pose. */
        @JvmStatic
        fun setCheekHidden(processor: AnimationProcessor<*>, hidden: Boolean) {
            ModelBones.setHidden(processor, "cheekRBelowSkirt", hidden)
            ModelBones.setHidden(processor, "cheekLBelowSkirt", hidden)
            ModelBones.setHidden(processor, "sideRNoSkirt", hidden)
            processor.getBone("sideRSkirt")?.setHidden(!hidden)
            ModelBones.setHidden(processor, "sideLNoSkirt", hidden)
            processor.getBone("sideLSkirt")?.setHidden(!hidden)
        }
    }
}
