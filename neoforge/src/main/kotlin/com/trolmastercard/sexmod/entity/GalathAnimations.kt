package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.api.IPositionProvider
import com.trolmastercard.sexmod.api.ITargetProvider
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.EscapeMinigameHud
import com.trolmastercard.sexmod.client.gui.GalathFlightHud
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.command.CommandFuta
import com.trolmastercard.sexmod.networking.GalathRapePouncePacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.TeleportPlayerPacket
import com.trolmastercard.sexmod.networking.UpdateVelocityPacket
import com.trolmastercard.sexmod.util.DynamicTrailRenderer
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.VectorMath
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState
import software.bernie.geckolib.animation.keyframe.event.SoundKeyframeEvent

/**
 * <b>Role.</b> Galath's GeckoLib animation glue, split out of
 * [GalathEntity]: the per-tick predicate for the action/eyes/movement
 * controllers, the scene overrides the predicate cannot express (mounting,
 * bed variants, morning blowjob), the sword-progress curve the renderer reads,
 * and the sound-keyframe listener that drives the scenes.
 *
 * <b>Flow.</b> The controllers are still created in
 * [GalathEntity.registerControllers]; every keyframe goes through
 * [handleSoundKeyframe].
 *
 * <b>Pitfalls.</b> The keyframe switch relies on two deliberate fall-throughs
 * (`creampie` into `creampieGalath`, `blackScreenTamed` into
 * `blackScreen`) and on `corrupt_hard_end` forcing CORRUPT_SLOW, so
 * the cases must not be reordered. Action transitions from the predicate only
 * run where the controller is not locally registered.
 *
 * Port note: COMMON class; the client-type work of the keyframe listener lives
 * in the nested [Client] object (PORTING.md §2.3 Rule C).
 */
object GalathAnimations {
    @JvmStatic
    fun handleActionAnimationOverrides(galath: GalathEntity, action: Action, animName: String, started: Boolean, event: AnimationState<*>): Boolean {
        if (action == Action.MASTERBATE_SITTING && galath.masterbateSittingPending) {
            galath.masterbateSittingPending = false
            galath.createAnimation("animation.galath.masterbating_sitting", true, event, true)
            return true
        }

        if (action == Action.MORNING_BLOWJOB_FAST && galath.morningBlowjobStarted) {
            galath.setCurrentAction(Action.MORNING_BLOWJOB_CUM)
            return true
        }

        if (action == Action.MORNING_BLOWJOB_FAST && galath.bedSoftAnimation) {
            galath.createAnimation("animation.shared.bed_fast", true, event, true)
            galath.bedSoftAnimation = false
            return true
        }

        if (action == Action.MORNING_BLOWJOB_CUM) {
            galath.setCurrentAction(null)
            return true
        }

        if (action == Action.PUSSY_LICKING && galath.pussyLickingForward) {
            galath.pussyLickingForward = false
            galath.createAnimation("animation.galath.pussy_licking", true, event, true)
            return true
        }

        if (action != Action.MORNING_BLOWJOB_SLOW || !galath.morningBlowjobStarted && !HandlePlayerMovement.isJumping) {
            if (action == Action.MORNING_BLOWJOB_SLOW && galath.bedBackAnimation) {
                galath.bedBackAnimation = false
                galath.createAnimation("animation.shared.bed_slow", true, event, true)
                return true
            } else if (action == Action.MORNING_BLOWJOB_FAST && !HandlePlayerMovement.isJumping) {
                galath.setCurrentAction(Action.MORNING_BLOWJOB_SLOW)
                galath.bedBackAnimation = true
                galath.createAnimation("animation.shared.bed_back", true, event, true)
                return true
            } else {
                return false
            }
        } else {
            galath.bedSoftAnimation = true
            galath.setCurrentAction(Action.MORNING_BLOWJOB_FAST)
            galath.createAnimation("animation.shared.bed_soft", true, event, true)
            return true
        }
    }

    @JvmStatic
    fun getSwordAttackProgress(galath: GalathEntity, partialTicks: Float): Float {
        val action = galath.getCurrentAction()
        if (action == Action.PUSSY_LICKING && !galath.pussyLickingForward) {
            return 0.0f
        }

        if (action == Action.MASTERBATE_SITTING && !galath.masterbateSittingPending) {
            return 1.0f
        }

        val scale = Action.getActionTimeScale(galath, partialTicks)
        return if (action == Action.MASTERBATE_SITTING) scale else 1.0f - scale
    }

    @JvmStatic
    fun animationPredicate(galath: GalathEntity, event: AnimationState<BaseGirlEntity>): PlayState {
        if (galath.isLocallyRegistered()) {
            galath.createAnimation("animation.galath.idle", true, event)
            return PlayState.CONTINUE
        }

        val action = galath.getCurrentAction()
        val controller = event.controller
        controller.setAnimationSpeed(1.0)
        if (controller == galath.eyesController) {
            if (action.autoBlink && action != Action.GALATH_DE_SUMMON) {
                galath.createAnimation("animation.galath.blink", true, event)
                return PlayState.CONTINUE
            } else {
                return PlayState.STOP
            }
        } else if (controller == galath.movementController) {
            if (action != Action.NULL) {
                return PlayState.STOP
            } else if (!galath.onGround()) {
                galath.createAnimation("animation.galath.controlled_flight", true, event)
                return PlayState.CONTINUE
            } else {
                val movement = galath.position().subtract(Vec3(galath.xOld, galath.yOld, galath.zOld))
                if (movement == Vec3.ZERO) {
                    galath.createAnimation("animation.galath.idle", true, event)
                    return PlayState.CONTINUE
                } else {
                    galath.setYRot(galath.yHeadRot)
                    galath.createAnimation(
                        "animation.galath." + (if (galath.entityDataManager.get(GalathEntity.SPRINTING)) "run" else "walk"), true, event,
                    )
                    return PlayState.CONTINUE
                }
            }
        } else {
            when (galath.getCurrentAction()) {
                Action.HUG_MANG -> galath.createAnimation("animation.galath.hug_mang", true, event)
                Action.MORNING_BLOWJOB_SLOW -> galath.createAnimation(
                    if (galath.bedBackAnimation) "animation.shared.bed_back" else "animation.shared.bed_slow", true, event,
                )
                Action.MORNING_BLOWJOB_FAST -> if (galath.bedSoftAnimation) {
                    galath.createAnimation("animation.shared.bed_soft", true, event)
                } else {
                    galath.playRandomizedAnimation("animation.shared.bed_fast", 4, 0.75f, event)
                }
                Action.MORNING_BLOWJOB_CUM -> galath.createAnimation("animation.shared.bed_cum", true, event)
                Action.RAPE_INTRO -> galath.createAnimation("animation.galath.rape_intro", true, event)
                Action.RAPE_ON_GOING -> galath.createAnimation("animation.galath.rape" + galath.rapeVariant, true, event)
                Action.RAPE_CUM -> galath.createAnimation("animation.galath.rape_cum", true, event)
                Action.RAPE_CHARGE -> galath.createAnimation("animation.galath.rape_charge", true, event)
                Action.RAPE_CUM_IDLE -> galath.createAnimation("animation.galath.rape_cum_idle", true, event)
                Action.CORRUPT_SLOW -> galath.createAnimation("animation.galath.corrupt_slow", true, event)
                Action.CORRUPT_FAST -> galath.createAnimation(
                    "animation.galath.corrupt_" + (if (galath.corruptHard) "hard" else "soft"), true, event,
                )
                Action.CORRUPT_CUM -> galath.createAnimation("animation.galath.corrupt_cum", true, event)
                Action.MASTERBATE -> galath.createAnimation("animation.galath.masterbate", true, event)
                Action.RAPE_PREPARE -> galath.createAnimation("animation.galath.rape_prepare", true, event)
                // Java: CORRUPT_INTRO falls through into COWGIRLCUM/default, which only break
                Action.CORRUPT_INTRO -> galath.createAnimation("animation.galath.corrupt_intro", true, event)
                Action.NULL -> return PlayState.STOP
                Action.FLY -> galath.createAnimation("animation.galath.idle_flying", true, event)
                Action.SUMMON_SKELETON -> galath.createAnimation(
                    "animation.galath.summon_skeleton" + (if (galath.entityDataManager.get(GalathEntity.MIRROR_SIDE)) "Mirrored" else ""),
                    true, event,
                )
                Action.ATTACK_SWORD -> galath.createAnimation("animation.galath.attack", true, event)
                Action.KNOCK_OUT_FLY -> {
                    controller.setAnimationSpeed(1.5)
                    galath.createAnimation("animation.galath.knockout_air", true, event)
                }
                Action.KNOCK_OUT_GROUND -> galath.createAnimation("animation.galath.knocked_out", true, event)
                Action.KNOCK_OUT_STAND_UP -> galath.createAnimation("animation.galath.knocked_out_stand_up", true, event)
                Action.CONTROLLED_FLIGHT -> galath.createAnimation("animation.galath.controlled_flight", true, event)
                Action.BOOST -> galath.createAnimation("animation.galath.boost", true, event)
                Action.GALATH_SUMMON -> galath.createAnimation("animation.galath.summon", false, event)
                Action.GALATH_DE_SUMMON -> galath.createAnimation(
                    "animation.galath.desummon" + (if (galath.onGround()) "_standing" else ""), true, event,
                )
                Action.GIVE_COIN -> galath.createAnimation("animation.galath.give_coin", true, event)
                Action.RUN -> {
                    controller.setAnimationSpeed(0.7)
                    galath.createAnimation("animation.galath.running", true, event)
                }
                Action.PUSSY_LICKING -> galath.createAnimation(
                    if (galath.pussyLickingForward) "animation.galath.pussy_licking_forward" else "animation.galath.pussy_licking",
                    true, event,
                )
                Action.MASTERBATE_SITTING -> galath.createAnimation(
                    if (galath.masterbateSittingPending) "animation.galath.pussy_licking_back" else "animation.galath.masterbating_sitting",
                    true, event,
                )
                Action.MASTERBATE_SITTING_CUM -> galath.createAnimation("animation.galath.masterbating_sitting_cum", true, event)
                else -> {
                }
            }

            return PlayState.CONTINUE
        }
    }

    /**
     * CLIENT: the sound-keyframe listener driving the wild fight (charge
     * sounds, sword render toggles, stars) and the tamed scenes (rape
     * switch/UI, corrupt switch, coin cam, morning blowjob, creampie trails).
     * `blackScreenTamed` only black-screens wild girls;
     * `flapControlled` sends the flight input as
     * [UpdateVelocityPacket].
     */
    @JvmStatic
    fun handleSoundKeyframe(galath: GalathEntity, sound: SoundKeyframeEvent<*>) {
        val name = sound.keyframeData.sound
        // explicit fall-through chains of the Java switch
        var fallCreampieGalath = false
        var fallBlackScreen = false
        when (name) {
            "goodTiming" -> {
                galath.playSound(SoundHandler.GIRLS_GALATH_DIALOG[4])
                galath.sendChatMessage("Good timing boy~")
            }
            "huh" -> galath.playRandomSound(SoundHandler.GIRLS_GALATH_HUH)
            "giggle" -> {
                val vec = galath.getVectorTowardPlayer()
                galath.level().playLocalSound(
                    vec.x, vec.y, vec.z,
                    SoundHandler.randomSound(SoundHandler.GIRLS_GALATH_GIGGLE), SoundSource.HOSTILE, 1.0f, 1.0f, false,
                )
            }
            "dialog1" -> galath.playSound(SoundHandler.GIRLS_GALATH_DIALOG[1])
            "moan" -> galath.playRandomSound(SoundHandler.GIRLS_GALATH_MOAN)
            "breath" -> galath.playRandomSound(SoundHandler.GIRLS_GALATH_BREATHING)
            "dialog5" -> galath.playSound(SoundHandler.GIRLS_GALATH_DIALOG[5])
            "switchmoan" -> {
                if (galath.alternateMoan) {
                    galath.playRandomSound(SoundHandler.GIRLS_GALATH_BREATHING)
                } else {
                    galath.playRandomSound(if (galath.random.nextBoolean()) SoundHandler.GIRLS_GALATH_MOAN else SoundHandler.GIRLS_GALATH_AHH)
                }

                galath.alternateMoan = !galath.alternateMoan
            }
            "lightcharge" -> {
                val aimVec = galath.getVectorTowardPlayer()
                galath.level().playLocalSound(
                    aimVec.x, aimVec.y, aimVec.z,
                    SoundHandler.randomSound(SoundHandler.GIRLS_GALATH_LIGHTCHARGE), SoundSource.HOSTILE, 1.0f, 1.0f, false,
                )
            }
            "strongcharge" -> galath.playRandomSound(SoundHandler.GIRLS_GALATH_STRONGCHARGE)
            "hmph" -> galath.playRandomSound(SoundHandler.GIRLS_GALATH_HMPH)
            "cum" -> galath.playRandomSoundAtVolume(SoundHandler.MISC_SMALLINSERTS, 2.0f)
            "giggle0" -> galath.playSound(SoundHandler.GIRLS_GALATH_GIGGLE[0])
            "orgasm" -> galath.playRandomSound(SoundHandler.GIRLS_GALATH_ORGASM)
            "pound" -> galath.playRandomSound(SoundHandler.MISC_POUNDING)
            "flap" -> {
                val flapVec = galath.getVectorTowardPlayer()
                galath.level().playLocalSound(
                    flapVec.x, flapVec.y, flapVec.z,
                    SoundHandler.randomSound(SoundHandler.MISC_FLAP), SoundSource.HOSTILE, 1.0f, 1.0f, false,
                )
            }
            "startRenderSword" -> {
                galath.renderSword = true
                galath.spawnSwordParticles = true
            }
            "stopFadeInParticles" -> galath.spawnSwordParticles = false
            "stopRenderSword" -> {
                galath.renderSword = false
                galath.spawnSwordParticles = false
            }
            "dontDrawStars" -> galath.drawStars = false
            "setNude" -> {
                galath.wingsAnimated = true
                val pos = galath.position()
                val slipR = galath.getCachedBoneOffset("slipR").add(pos)
                val slipL = galath.getCachedBoneOffset("slipL").add(pos)
                val turnable = galath.getCachedBoneOffset("turnable").add(pos)
                galath.level().addParticle(ParticleTypes.DRAGON_BREATH, slipR.x, slipR.y, slipR.z, 0.0, 0.0, 0.0)
                galath.level().addParticle(ParticleTypes.DRAGON_BREATH, slipL.x, slipL.y, slipL.z, 0.0, 0.0, 0.0)
                galath.level().addParticle(ParticleTypes.DRAGON_BREATH, turnable.x, turnable.y, turnable.z, 0.0, 0.0, 0.0)
            }
            "rapeIntroDone" -> if (galath.isControlledByLocalPlayer()) {
                galath.setCurrentAction(Action.RAPE_ON_GOING)
            }
            "rape_switch" -> {
                val random = galath.random
                val oldState = galath.rapeVariant

                do {
                    galath.rapeVariant = random.nextInt(3)
                } while (galath.rapeVariant == oldState)

                if (!galath.hasMaster() && galath.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    if (0.0f >= player.health - 1.0f) {
                        galath.setCurrentAction(Action.RAPE_CUM)
                    }
                }
            }
            "poundRape" -> {
                galath.playRandomSound(SoundHandler.MISC_POUNDING)
                if (galath.isControlledByLocalPlayer()) {
                    if (galath.hasMaster()) {
                        HornyMeterHud.addToHornyMeter(0.03f.toDouble())
                    } else {
                        PacketHandler.networkWrapper.sendToServer(GalathRapePouncePacket(true))
                    }
                }
            }
            "rapeHurt" -> if (!galath.hasMaster() && galath.isControlledByLocalPlayer()) {
                PacketHandler.networkWrapper.sendToServer(GalathRapePouncePacket(false))
            }
            "enableRapeUI" -> if (galath.isControlledByLocalPlayer()) {
                if (galath.hasMaster()) {
                    HornyMeterHud.setHornyMeterVisible(false)
                } else {
                    EscapeMinigameHud.showMinigame(galath)
                }
            }
            "removeUI" -> if (galath.isControlledByLocalPlayer() && !galath.hasMaster()) {
                EscapeMinigameHud.failMinigame()
            }
            "reloadRenderer" -> {
                if (!galath.isControlledByLocalPlayer()) {
                    return
                }

                if (ClientHooks.thirdPersonView() != 0) {
                    Client.reloadRenderers()
                }
            }
            "corruptSwitch" -> if (galath.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                galath.setCurrentAction(Action.CORRUPT_FAST)
            }
            "corrupt_hard" -> if (galath.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                galath.corruptHard = true
                galath.resetAnimationControllerOffset()
            }
            "corrupt_hard_end" -> {
                galath.setCurrentAction(Action.CORRUPT_SLOW)
                galath.corruptHard = false
            }
            "addCum" -> HornyMeterHud.addToHornyMeter(0.03)
            "clearcum" -> Client.spawnCummyTrails(galath)
            "setCamCorrupt" -> {
                if (!galath.isControlledByLocalPlayer()) {
                    return
                }

                galath.corruptIntroActive = true
                val player = ClientHooks.localPlayer()!! // java-npe
                val yaw = galath.getYawRotation() + 220.0f
                val corruptPos = VectorMath.rotateByYaw(Vec3(0.5, (0.5f - player.eyeHeight).toDouble(), 0.4f.toDouble()), galath.getYawRotation())
                    .add(galath.getTargetPosition())
                PacketHandler.networkWrapper.sendToServer(TeleportPlayerPacket(player.uuid.toString(), corruptPos, yaw, 15.0f))
                HornyMeterHud.showHornyMeter()
            }
            "enableBoyCam" -> if (galath.isControlledByLocalPlayer()) {
                galath.corruptIntroActive = false
            }
            "masterbateCumming" -> if (CommandFuta.ENABLED) {
                Client.registerFutaTrail(galath, 90)
            }
            "creampie" -> {
                Client.registerCreampieTrail(galath)
                fallCreampieGalath = true
            }
            "creampieGalath" -> fallCreampieGalath = true
            "blackScreenTamed" -> {
                if (galath.hasMaster()) {
                    return
                }
                fallBlackScreen = true
            }
            "blackScreen" -> fallBlackScreen = true
            "blackScreenMaster" -> if (ClientHooks.localPlayerUUID()!! == galath.getMasterUUID()) { // java-npe
                BeeScreen.enableInteraction()
                HandlePlayerMovement.setMovementLock(false)
            }
            "flapControlled" -> if (galath.isControlledByLocalPlayer()) {
                GalathFlightHud.showHud()
                galath.playRandomSound(SoundHandler.MISC_FLAP)
                Client.sendFlightInput(galath)
            }
            "clap" -> galath.playRandomSound(SoundHandler.MISC_CLAP)
            "energysound" -> galath.playSound(SoundHandler.MISC_BEEW[1])
            "energy2" -> galath.playSound(SoundHandler.MISC_BEEW[2])
            "tpSound" -> galath.playSound(SoundHandler.MISC_WEOWEO[2])
            "lick" -> galath.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_ALLIE_LIPSOUND))
            "setCoinLook" -> if (galath.isControlledByLocalPlayer()) {
                val coinPlayer = ClientHooks.localPlayer()!! // java-npe
                val coinYaw = galath.getYawRotation() + 180.0f
                coinPlayer.setYRot(coinYaw)
                coinPlayer.yRotO = coinYaw
                coinPlayer.setXRot(0.0f)
                coinPlayer.xRotO = 0.0f
            }
            "sexui" -> if (galath.isControlledByLocalPlayer()) {
                HornyMeterHud.showHornyMeter()
            }
            "boostSound" -> {
                val player = ClientHooks.localPlayer()!! // java-npe
                player.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_GALATH_LIGHTCHARGE), 1.0f, 1.0f)
                player.playSound(SoundHandler.randomSound(SoundHandler.MISC_FLAP), 1.0f, 1.0f)
            }
        }

        if (fallCreampieGalath) {
            if (CommandFuta.ENABLED) {
                Client.registerFutaTrail(galath, 130)
            }

            galath.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_SMALLINSERTS), 3.0f)
        }

        if (fallBlackScreen) {
            if (galath.isControlledByLocalPlayer()) {
                BeeScreen.enableInteraction()
            }
        }
    }

    /** Client-only halves of the keyframe listener (PORTING.md §2.3 Rule C). */
    private object Client {
        fun reloadRenderers() {
            // 1.12 mc.renderGlobal.loadRenderers()
            Minecraft.getInstance().levelRenderer.allChanged()
        }

        fun spawnCummyTrails(galath: GalathEntity) {
            CummyEntity.spawnCummyTrails(galath)
        }

        fun registerFutaTrail(galath: GalathEntity, maxSegments: Int) {
            CummyEntity.registerTrail(
                DynamicTrailRenderer(
                    maxSegments,
                    IPositionProvider { girl ->
                        val cockTipPos = girl.getBoneWorldPosition("futaCockTip")
                        val tipDir = girl.getBoneWorldPosition("futaCockTipDirHelp")
                        cockTipPos.subtract(tipDir).normalize()
                    },
                    ITargetProvider { girl -> girl.getCachedBoneOffset("futaCockTip").add(girl.getTargetPosition()) },
                    galath, 0.3f, 0.3f,
                ),
            )
        }

        fun registerCreampieTrail(galath: GalathEntity) {
            CummyEntity.registerTrail(
                DynamicTrailRenderer(
                    100,
                    IPositionProvider { _ -> VectorMath.rotateByYaw(Vec3(0.0, 0.0, 0.6f.toDouble()), galath.getYawRotation()) },
                    ITargetProvider { girl -> girl.getCachedBoneOffset("creampiePos").add(girl.getTargetPosition()) },
                    galath, 0.6f, 0.5f,
                ),
            )
        }

        fun sendFlightInput(galath: GalathEntity) {
            val flapMc = Minecraft.getInstance()
            val flapPlayer = flapMc.player ?: return
            val input = flapPlayer.input
            val moveVec = input.moveVector
            if (moveVec.x != 0.0f || moveVec.y != 0.0f) {
                val partialTicks = flapMc.timer.getGameTimeDeltaPartialTick(true)
                val vel = VectorMath.rotateByYawPitch(
                    Vec3(-moveVec.x.toDouble(), 0.0, moveVec.y.toDouble()),
                    RotationHelper.lerp(flapPlayer.xRotO, flapPlayer.xRot, partialTicks),
                    RotationHelper.lerp(flapPlayer.yHeadRotO, flapPlayer.yHeadRot, partialTicks),
                )
                PacketHandler.networkWrapper.sendToServer(UpdateVelocityPacket(vel, galath.getGirlId()))
            }
        }
    }
}
