package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.command.CommandFuta
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.entity.api.IGalath
import com.trolmastercard.sexmod.proxy.ClientProxy
import com.trolmastercard.sexmod.util.EntityLookVectorHelper
import com.trolmastercard.sexmod.util.MolangVars
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.TrigMath
import com.trolmastercard.sexmod.util.Vector3fSexmodSpecial
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState

/**
 * Geckolib model for Galath: three geo variants (normal, normal, and the
 * combined Manglelie pose `galath_con_mang`). The per-frame pass
 * orchestrates every flight/dash/hurt/scene pose: flight-bone lerping,
 * sword-dash body motion, knock-out-flight body rotation, rape-charge pose,
 * pussy-licking head tracking with lip sounds, wing visibility, futa bones
 * (from `CommandFuta.ENABLED`), and the HUG_MANG body2 placement.
 *
 * <b>Couplings.</b> Publishes `body` rotation/scale into the entity
 * (`bodyRotationY`/`bodyScaleY`) and the head rotation into `headRotationX` — read by the
 * renderer/geometry code; do not reorder the pose chain or drop these writes.
 * While hugging Manglelie, [ManglelieNpcModel.animateModel] takes over.
 *
 * Port notes: `setLivingAnimations` -> [setCustomAnimations]; GL3 `IBone`
 * setters -> GL4 `GeoBone` (`setRotX`, `setPosY`, ...); missing bones are
 * null-checked where 1.12 would have thrown (a throw would abort the GL4
 * animation pass). Molang writes go through [MolangVars].
 */
open class GalathNpcModel : GirlModel<BaseGirlEntity>() {
    @JvmField
    var lastPussyLickingWave: Float = 0.0f

    @JvmField
    var swordDashStartTime: Long = -1L

    @JvmField
    var swordDashEndTime: Long = -1L

    override fun getModelLocations(): Array<ResourceLocation> = arrayOf(
        Main.rl("geo/galath/galath.geo.json"),
        Main.rl("geo/galath/galath.geo.json"),
        Main.rl("geo/galath/galath_con_mang.geo.json"),
    )

    override fun getTextureResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("textures/entity/galath/galath.png")

    override fun getAnimationResource(entity: BaseGirlEntity?): ResourceLocation = Main.rl("animations/galath/galath.animation.json")

    /**
     * Skips Galath's render entirely when she has a target (she flies/attacks
     * invisibly until anchored) unless she has a master.
     */
    override fun shouldRender(girl: BaseGirlEntity): Boolean {
        if (girl !is GalathEntity) {
            return true
        }

        return if (girl.hasMaster()) true else girl.getTargetEntity() == null
    }

    /** The full per-frame pose chain (see class doc for the order and the published values). */
    override fun setCustomAnimations(entity: BaseGirlEntity, instanceId: Long, animationState: AnimationState<BaseGirlEntity>) {
        this.updateIdlePose(entity)
        super.setCustomAnimations(entity, instanceId, animationState)
        this.handleActionPose(entity)
        this.updateHurtPose(entity)
        this.handleFlightPose(entity)
        this.handleWingState(entity)
        this.updateSwordBones(entity)
        this.hideWings(entity)
        this.updatePlayerPose(entity)
        this.hideFutaBone()
        this.updateModelState(entity)
        this.updatePussyPose(entity)
        this.handleDashAnimation(entity)
        if (entity is GalathEntity) {
            val galathHeadBone = this.animationProcessor.getBone("head")
            if (galathHeadBone != null) {
                entity.headRotationX = galathHeadBone.rotX
            }
            if (entity.isHuggingManglelie()) {
                ManglelieNpcModel.animateModel(entity, this.animationProcessor, animationState.partialTick)
            }
        }
    }

    /**
     * Pussy-licking head tracking: the head follows the sword-swing offset
     * (lerped by the sword attack progress) and the lip sound fires on each
     * lick wave (sine zero-crossing).
     */
    fun updatePussyPose(entity: BaseGirlEntity) {
        if (Action.isAnyAction(entity, Action.PUSSY_LICKING)) {
            if (entity is GalathEntity) {
                if (!this.mc.isPaused) {
                    val headBone = this.animationProcessor.getBone("head") ?: return
                    val waveTime = this.mc.timer.getGameTimeDeltaPartialTick(true) + (this.mc.player?.tickCount ?: 0)
                    val swordOffset = this.getSwordPos(entity, waveTime)
                    headBone.setRotX(headBone.rotX + swordOffset.x)
                    headBone.setRotY(headBone.rotY + swordOffset.y)
                    headBone.setRotZ(headBone.rotZ + swordOffset.z)
                    if (entity.getCurrentAction() == Action.PUSSY_LICKING && !entity.pussyLickingForward) {
                        val wave = (Math.sin((waveTime * 0.3f).toDouble()) * 10.0).toFloat()
                        if (wave > 0.0f && this.lastPussyLickingWave < 0.0f || wave < 0.0f && this.lastPussyLickingWave > 0.0f) {
                            entity.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_LIPSOUND))
                        }

                        this.lastPussyLickingWave = wave
                    }
                }
            }
        }
    }

    fun getSwordPos(galath: GalathEntity, partialTicks: Float): Vector3fSexmodSpecial =
        RotationHelper.lerpVector3f(
            this.getSwordSwingOffset(partialTicks), Vector3fSexmodSpecial.ZERO,
            galath.getSwordAttackProgress(this.mc.timer.getGameTimeDeltaPartialTick(true)).toDouble(),
        )

    fun getSwordSwingOffset(time: Float): Vector3fSexmodSpecial = Vector3fSexmodSpecial(
        Math.sin((time * 0.3f).toDouble()).toFloat() * TrigMath.wrapDegrees(10.0f),
        Math.sin((time * 0.15f).toDouble()).toFloat() * TrigMath.wrapDegrees(7.0f),
        Math.sin(time * -0.15).toFloat() * TrigMath.wrapDegrees(7.0f),
    )

    /**
     * Publishes the body bone's rotation/scale to the entity fields
     * (`bodyRotationY` = rotationY, `bodyScaleY` = scaleY) — consumed by the wing/
     * geometry renderers.
     */
    fun updateModelState(entity: BaseGirlEntity) {
        if (entity is GalathEntity) {
            val bodyBone = this.animationProcessor.getBone("body") ?: return
            entity.bodyRotationY = bodyBone.rotY
            entity.bodyScaleY = bodyBone.scaleY
        }
    }

    /**
     * HUG_MANG transition: positions the `body2` bone (Manglelie's
     * attachment) while the controller transitions into the hug.
     */
    fun handleDashAnimation(entity: BaseGirlEntity) {
        if (entity.actionController?.animationState == AnimationController.State.TRANSITIONING) {
            val action = entity.getCurrentAction()
            if (action == Action.HUG_MANG) {
                val body2Bone = this.animationProcessor.getBone("body2") ?: return

                body2Bone.setPosX(0.0f)
                body2Bone.setPosY(-0.53f)
                body2Bone.setPosZ(-40.05f)
            }
        }
    }

    /**
     * Masturbate pose: drives the molang variables (pitch, armpitch, armyaw,
     * yaw) from the look vector to the master player so the animation reacts
     * to his position.
     */
    fun updateIdlePose(entity: BaseGirlEntity) {
        if (!ClientProxy.IS_PRELOADING) {
            if (entity.getCurrentAction() == Action.MASTERBATE) {
                val masterPlayer: Player = entity.getMasterPlayer() ?: this.mc.player ?: return
                val lookVec = EntityLookVectorHelper.getLookVectorTo(entity, masterPlayer, this.mc.timer.getGameTimeDeltaPartialTick(true))
                    .add(entity.getCachedBoneOffset("head"))
                val yaw = TrigMath.sinDegrees(Math.atan2(lookVec.z, lookVec.x)).toFloat() - entity.getYawRotation()
                val pitch = TrigMath.sinDegrees(Math.atan2(lookVec.y, Math.sqrt(lookVec.x * lookVec.x + lookVec.z * lookVec.z))).toFloat()
                val magnitude = Math.abs(lookVec.x) + Math.abs(lookVec.y) + Math.abs(lookVec.z)
                val pitchMolang = magnitude * 7.0 + -20.0
                val armpitchMolang = magnitude * 5.0 + -20.0
                MolangVars.set("pitch", pitchMolang + pitch - 80.0)
                MolangVars.set("armpitch", armpitchMolang + pitch + -110.0)
                MolangVars.set("armyaw", (yaw + 80.0f).toDouble())
                MolangVars.set("yaw", (yaw + 90.0f).toDouble())
            }
        }
    }

    /** Hides the futa bones unless the futa command is enabled. */
    fun hideFutaBone() {
        if (!ClientProxy.IS_PRELOADING) {
            ModelBones.setHidden(this.animationProcessor, "futaCock", !CommandFuta.ENABLED)
            ModelBones.setHidden(this.animationProcessor, "futaBallLL", !CommandFuta.ENABLED)
            ModelBones.setHidden(this.animationProcessor, "futaBallLR", !CommandFuta.ENABLED)
        }
    }

    /** Hides the coin bone for player-girls (only NPC Galath gives coins). */
    fun updatePlayerPose(entity: BaseGirlEntity) {
        if (entity is AbstractPlayerGirlEntity) {
            ModelBones.setHidden(this.animationProcessor, "coin", true)
        }
    }

    fun hideWings(entity: BaseGirlEntity) {
        ModelBones.setHidden(this.animationProcessor, "wings", !(entity as IGalath).areWingsAnimated())
    }

    /**
     * Nipple/bra/slip visibility by wing state: while the wings are hidden
     * (idle/corrupted) the nipples hide and the bra/slip show; wing-animated
     * states expose the nipples.
     */
    fun updateSwordBones(entity: BaseGirlEntity) {
        val processor = this.animationProcessor
        val nippleRBone = processor.getBone("nippleR")
        val nippleLBone = processor.getBone("nippleL")
        val braBoobLBone = processor.getBone("braBoobL")
        val braBoobRBone = processor.getBone("braBoobR")
        val slipBone = processor.getBone("slip")
        val wingsAnimated = (entity as IGalath).isWingsAnimated()
        if (wingsAnimated) {
            if (nippleRBone != null) {
                if (braBoobLBone != null) {
                    nippleRBone.setHidden(false)
                    nippleLBone?.setHidden(false)
                    braBoobLBone.setHidden(wingsAnimated)
                    braBoobRBone?.setHidden(wingsAnimated)
                    slipBone?.setHidden(true)
                }
            }
        } else {
            val isPussyAction = Action.isAnyAction(entity, Action.PUSSY_LICKING, Action.MASTERBATE_SITTING, Action.MASTERBATE_SITTING_CUM)
            if (nippleRBone != null) {
                if (braBoobLBone != null) {
                    nippleRBone.setHidden(true)
                    nippleLBone?.setHidden(true)
                    braBoobLBone.setHidden(wingsAnimated)
                    braBoobRBone?.setHidden(wingsAnimated)
                    slipBone?.setHidden(isPussyAction)
                }
            }
        }
    }

    /**
     * Knock-out-flight pose: while flying with the KNOCK_OUT_FLY action the
     * body pitches -90 (hovering) or follows the interpolated flight vector.
     */
    fun handleWingState(entity: BaseGirlEntity) {
        if (entity is GalathEntity) {
            if (entity.entityData.get(GalathEntity.PARALYZED)) {
                if (entity.getCurrentAction() == Action.KNOCK_OUT_FLY) {
                    val bodyBone = this.animationProcessor.getBone("body") ?: return
                    val lastPos = Vec3(entity.xOld, entity.yOld, entity.zOld)
                    val motion = entity.position().subtract(lastPos)
                    val isHovering = Math.abs(motion.x) + Math.abs(motion.z) < 0.01f
                    if (isHovering) {
                        bodyBone.setRotX(TrigMath.wrapDegrees(-90.0f))
                        bodyBone.setPosY(0.0f)
                        bodyBone.setPosZ(0.0f)
                    } else {
                        val interpolated = getInterpolatedPosition(entity)
                        bodyBone.setRotX(-interpolated.x.toFloat())
                        bodyBone.setPosY(interpolated.y.toFloat())
                        bodyBone.setPosZ(interpolated.z.toFloat())
                    }
                }
            }
        }
    }

    /**
     * Rape-charge pose: the rotation tool/body follow the interpolated charge
     * vector, with the body yaw from the charge progress value (`RAPE_PROGRESS`).
     */
    fun updateHurtPose(entity: BaseGirlEntity) {
        if (entity is GalathEntity) {
            if (entity.getCurrentAction() == Action.RAPE_CHARGE) {
                val interpolated = getInterpolatedPosition(entity)
                val bodyBone = this.animationProcessor.getBone("body") ?: return
                val rotationToolBone = this.animationProcessor.getBone("rotationTool") ?: return
                rotationToolBone.setRotX(interpolated.x.toFloat())
                bodyBone.setPosY(interpolated.y.toFloat())
                bodyBone.setPosZ(interpolated.z.toFloat())
                val chargeProgress = entity.entityData.get(GalathEntity.RAPE_PROGRESS)
                bodyBone.setRotY(TrigMath.wrapDegrees(chargeProgress * 180.0f))
            }
        }
    }

    /**
     * Sword-dash body motion: while the dash progress is in the 24..32 window
     * the body lerps from the dash-start offset back to zero (8-tick dash).
     */
    fun handleFlightPose(entity: BaseGirlEntity) {
        if (entity is GalathEntity) {
            if (entity.getCurrentAction() != Action.ATTACK_SWORD) {
                this.swordDashStartTime = -1L
                this.swordDashEndTime = -1L
            } else {
                val worldTime = this.mc.level?.gameTime ?: 0L
                val dashTick = entity.getSwordAttackProgress()
                if (dashTick == 24 && this.swordDashStartTime == -1L) {
                    this.swordDashStartTime = worldTime
                    this.swordDashEndTime = this.swordDashStartTime + 8L
                }

                if (ThreadNames.isBetween(dashTick.toDouble(), 24.0, 32.0)) {
                    val bodyBone = this.animationProcessor.getBone("body") ?: return
                    var dashOffset = getBoneOffsetWorld(entity, entity.getFlightTargetPos())
                    val dashProgress = (worldTime.toFloat() + this.mc.timer.getGameTimeDeltaPartialTick(true) - this.swordDashStartTime.toFloat()) /
                        (this.swordDashEndTime - this.swordDashStartTime).toFloat()
                    dashOffset = RotationHelper.lerpVec3dDouble(dashOffset, Vec3.ZERO, dashProgress.toDouble())
                    bodyBone.setRotX(dashOffset.x.toFloat())
                    bodyBone.setPosY(dashOffset.y.toFloat())
                    bodyBone.setPosZ(dashOffset.z.toFloat())
                }
            }
        }
    }

    /**
     * Flight/boost pose: lerps the rotation tool between the flight data
     * vectors (with an extra 45-degree pitch during the boost window).
     */
    fun handleActionPose(entity: BaseGirlEntity) {
        var boostPitch = 0.0f
        val action = entity.getCurrentAction()
        if (action == Action.BOOST) {
            if (Action.BOOST.ticksPlaying[1] > 13 && Action.BOOST.ticksPlaying[1] < 40) {
                boostPitch = 45.0f
            }
        }

        // Java: BOOST falls through into FLY / CONTROLLED_FLIGHT
        if (action == Action.BOOST || action == Action.FLY || action == Action.CONTROLLED_FLIGHT) {
            val partialTicks = this.mc.timer.getGameTimeDeltaPartialTick(true)
            val rotationToolBone = this.animationProcessor.getBone("rotationTool") ?: return
            val flightData = (entity as IGalath).getFlightData()
            rotationToolBone.setRotX(RotationHelper.lerpDouble(flightData.z + boostPitch, flightData.x + boostPitch, partialTicks.toDouble()).toFloat())
            rotationToolBone.setRotZ(RotationHelper.lerpDouble(flightData.w, flightData.y, partialTicks.toDouble()).toFloat())
        }
    }

    override fun HeadArmor(): Array<String> = arrayOf("armorHelmet")

    companion object {
        @JvmField
        var GALATH_TEXTURE: ResourceLocation = Main.rl("textures/entity/galath/galath.png")
    }
}
