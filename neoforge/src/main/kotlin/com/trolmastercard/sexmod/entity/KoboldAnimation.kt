package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.TeleportPlayerPacket
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.MolangVars
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.VectorMath
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * GeckoLib animation glue for [KoboldEntity]: the per-tick animation
 * predicate (eyes / movement / action controllers) and the controller
 * registration with its sound listener.
 *
 * <b>Pitfalls.</b> The sound listener drives scene progression: the
 * `*Done` keys move the action state machine forward, `switch`/
 * `endSwitch` toggle the blowjob side (`WildSlimeFaceLayer` /
 * `switchVariant`) and must clear the animation cache, and `paymentDone`
 * calls `startActionFromHandState()` which re-dispatches the hand state. Do not reorder the
 * switch cases: `mating_press_startDone` intentionally falls through to
 * `mating_press_hardDone` (reproduced explicitly below).
 *
 * Client-only statics (`HornyMeterHud`, `BeeScreen`, `HandlePlayerMovement`)
 * are only reached from the animation predicate / sound handler (Rule B).
 */
object KoboldAnimation {
    @JvmStatic
    fun animationPredicate(entity: KoboldEntity, event: AnimationState<*>): PlayState {
        if (SexWorldClient.isPreloadWorld(entity.level())) {
            return PlayState.STOP
        }

        if (entity.actionController == null) {
            entity.initAnimationControllers()
        }

        val shrink = 0.25f - entity.getEntityData().get(KoboldEntity.SIZE_SCALAR)
        MolangVars.set("size", shrink.toDouble())
        when (event.controller.name) {
            "eyes" -> {
                if (entity.getCurrentAction() != Action.NULL) {
                    entity.createAnimation("animation.kobold.null", true, event)
                } else {
                    entity.createAnimation("animation.kobold.blink", true, event)
                }
            }
            "movement" -> {
                if (entity.getCurrentAction() != Action.NULL) {
                    entity.createAnimation("animation.kobold.null", true, event)
                } else if (entity.isPassenger) {
                    entity.createAnimation("animation.kobold.sit", true, event)
                } else {
                    val moved = Math.abs(entity.xo - entity.x) + Math.abs(entity.zo - entity.z)
                    if (!entity.getEntityData().get(BaseGirlEntity.IS_ANCHORED) && moved > 0.0) {
                        if (entity.onGround() && Math.abs(Math.abs(entity.yo) - Math.abs(entity.y)) < 0.1f) {
                            entity.yRot = entity.yHeadRot
                            val speed = 1.0 + shrink * 2.0f
                            entity.movementController!!.setAnimationSpeed(speed) // java-npe
                            if (entity.isBlockedByCeiling()) {
                                entity.createAnimation("animation.kobold.crouch_walk", true, event)
                            } else if (entity.getEntityData().get(KoboldEntity.ARMED)) {
                                entity.createAnimation("animation.kobold.run_armed", true, event)
                            } else if (moved > 0.2f) {
                                entity.createAnimation("animation.kobold.run", true, event)
                            } else {
                                entity.createAnimation("animation.kobold.walk", true, event)
                            }
                        } else {
                            entity.createAnimation("animation.kobold.fly", true, event)
                        }
                    } else if (entity.isBlockedByCeiling()) {
                        entity.createAnimation("animation.kobold.crouch_idle", true, event)
                    } else {
                        entity.createAnimation(
                            if (entity.getEntityData().get(KoboldEntity.ARMED)) "animation.kobold.idle_armed" else "animation.kobold.idle",
                            true, event,
                        )
                    }
                }
            }
            "action" -> {
                when (entity.getCurrentAction()) {
                    Action.NULL -> entity.createAnimation("animation.kobold.null", true, event)
                    Action.ATTACK -> entity.createAnimation("animation.kobold.attack", false, event)
                    Action.RIDE, Action.SIT -> entity.createAnimation("animation.kobold.sit", true, event)
                    Action.MINE -> entity.createAnimation("animation.kobold.fall_tree", true, event)
                    Action.PAYMENT -> entity.createAnimation("animation.kobold.paymentBackpack", true, event)
                    Action.STARTBLOWJOB -> entity.createAnimation("animation.kobold.blowjobStart", false, event)
                    Action.SUCKBLOWJOB_BLINK -> {
                        val side = if (entity.WildSlimeFaceLayer) "R" else "L"
                        val animSuffix = if (entity.switchVariant) "Switch" else ""
                        entity.createAnimation("animation.kobold.blowjobSlow$side$animSuffix", true, event)
                    }
                    Action.THRUSTBLOWJOB -> entity.createAnimation("animation.kobold.blowjobFast", true, event)
                    Action.CUMBLOWJOB -> entity.createAnimation("animation.kobold.blowjobCum", false, event)
                    Action.KOBOLD_ANAL_START -> entity.createAnimation("animation.kobold.analStart", false, event)
                    Action.KOBOLD_ANAL_SLOW -> entity.createAnimation("animation.kobold.analSoft", true, event)
                    Action.KOBOLD_ANAL_FAST -> entity.createAnimation("animation.kobold.analHard", true, event)
                    Action.KOBOLD_ANAL_CUM -> entity.createAnimation("animation.kobold.analCum", true, event)
                    Action.SLEEP -> entity.createAnimation("animation.kobold.sleep", true, event)
                    Action.MATING_PRESS_START -> entity.createAnimation("animation.kobold.mating_press_start", false, event)
                    Action.MATING_PRESS_SOFT -> entity.createAnimation("animation.kobold.mating_press_soft", true, event)
                    Action.MATING_PRESS_HARD -> entity.createAnimation("animation.kobold.mating_press_hard", true, event)
                    Action.MATING_PRESS_CUM -> entity.createAnimation("animation.kobold.mating_press_cum", true, event)
                    else -> {}
                }
            }
        }

        return PlayState.CONTINUE
    }

    /** Teleports the local (interaction) player relative to the kobold's target position (client only). */
    private fun teleportLocal(entity: KoboldEntity, uuid: String, offset: Vec3, yaw: Float, pitch: Float) {
        val pos = VectorMath.rotateByYaw(offset, entity.getYawRotation() + 180.0f)
        PacketHandler.networkWrapper.sendToServer(TeleportPlayerPacket(uuid, entity.getTargetPosition().add(pos), yaw, pitch))
    }

    @JvmStatic
    fun registerControllers(entity: KoboldEntity, data: AnimatableManager.ControllerRegistrar) {
        if (entity.actionController == null) {
            entity.initAnimationControllers()
        }

        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            val key = sound.keyframeData.sound
            when (key) {
                "attackSound" -> entity.playSound(SoundEvents.PLAYER_ATTACK_STRONG)
                "paymentMSG1" -> {
                    entity.sendChatMessageToPlayer(entity.getInteractionPlayerUUID()!!, "I'd like to use ur services owo") // java-npe
                    entity.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "plob" -> entity.playRandomSound(SoundHandler.MISC_PLOB)
                "blackScreen" -> if (entity.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "paymentDone" -> if (entity.isControlledByLocalPlayer()) {
                    entity.startActionFromHandState()
                }
                "blowjobStartMSG1" -> if (entity.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    teleportLocal(
                        entity, entity.getInteractionPlayerUUID().toString(),
                        Vec3(0.0, 0.625 - player.eyeHeight, -1.0), entity.getYawRotation() + 180.0f, 0.0f,
                    )
                }
                "blowjobStartMSG2" -> if (entity.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    teleportLocal(
                        entity, entity.getInteractionPlayerUUID().toString(),
                        Vec3(0.5, 0.5 - player.eyeHeight, -0.6875), entity.getYawRotation() + 180.0f - 40.0f, 0.0f,
                    )
                }
                "lipsound" -> {
                    if (entity.random.nextBoolean()) {
                        entity.playRandomSoundAtVolume(SoundHandler.GIRLS_ALLIE_LIPSOUND, 1.5f)
                    } else {
                        entity.playRandomSoundAtVolume(SoundHandler.GIRLS_JENNY_LIPSOUND, 1.5f)
                    }

                    HornyMeterHud.addToHornyMeter(0.02f.toDouble())
                }
                "touch" -> entity.playRandomSound(SoundHandler.MISC_TOUCH)
                "blowjobStartDone" -> {
                    entity.setCurrentAction(Action.SUCKBLOWJOB_BLINK)
                    entity.switchVariant = false
                    entity.WildSlimeFaceLayer = true
                    if (entity.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "switch" -> {
                    entity.switchVariant = entity.random.nextBoolean()
                    entity.actionController!!.clearAnimationCache() // java-npe
                }
                "endSwitch" -> {
                    entity.switchVariant = false
                    entity.WildSlimeFaceLayer = !entity.WildSlimeFaceLayer
                    entity.actionController!!.clearAnimationCache() // java-npe
                }
                "blowjobFastDone" -> if (entity.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    entity.setCurrentAction(Action.SUCKBLOWJOB_BLINK)
                }
                "cumLoud" -> entity.playRandomSoundAtVolume(SoundHandler.MISC_SMALLINSERTS, 3.0f)
                "cumQuiet" -> entity.playRandomSoundAtVolume(SoundHandler.MISC_SMALLINSERTS, 3.0f)
                "analCumDone", "blowjobCumDone" -> if (entity.isControlledByLocalPlayer()) {
                    entity.resetCameraAndPhysics()
                    HornyMeterHud.hideHornyMeter()
                }
                "analStartDone" -> {
                    entity.setCurrentAction(Action.KOBOLD_ANAL_SLOW)
                    if (entity.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "analStartCam" -> if (entity.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    teleportLocal(
                        entity, entity.getInteractionPlayerUUID().toString(),
                        Vec3(0.0, 0.5625 - player.eyeHeight, 0.5625), entity.getYawRotation(), 0.0f,
                    )
                }
                "pounding" -> entity.playRandomSound(SoundHandler.MISC_POUNDING)
                "analFastRapid" -> if (entity.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    if (entity.getCurrentAction() == Action.KOBOLD_ANAL_FAST) {
                        entity.actionController!!.setTickOffset(0.0) // java-npe
                    }

                    entity.setCurrentAction(Action.KOBOLD_ANAL_FAST)
                }
                "analDone" -> if (entity.getCurrentAction() == Action.KOBOLD_ANAL_FAST) {
                    entity.setCurrentAction(Action.KOBOLD_ANAL_SLOW)
                }
                "analHard" -> if (entity.isControlledByLocalPlayer()) {
                    HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                }
                "analSoft" -> if (entity.isControlledByLocalPlayer()) {
                    HornyMeterHud.addToHornyMeter(0.02f.toDouble())
                }
                "cum" -> entity.playRandomSoundAtVolume(SoundHandler.MISC_SMALLINSERTS, 3.0f)
                "giggle" -> entity.playRandomSounds(SoundHandler.GIRLS_KOBOLD_GIGGLE)
                "moan" -> entity.playRandomSounds(SoundHandler.GIRLS_KOBOLD_MOAN)
                "moanMating" -> {
                    entity.moanCooldown--
                    if (entity.moanCooldown <= 0) {
                        entity.moanCooldown = 3
                        entity.playRandomSounds(SoundHandler.GIRLS_KOBOLD_MOAN)
                    }
                }
                "analHardMSG1" -> {
                    entity.moanCooldown--
                    if (entity.moanCooldown <= 0) {
                        entity.moanCooldown = 4
                        entity.playRandomSounds(SoundHandler.GIRLS_KOBOLD_MOAN)
                    }
                }
                "orgasm" -> entity.playRandomSounds(SoundHandler.GIRLS_KOBOLD_ORGASM)
                "breath" -> entity.playRandomSound(SoundHandler.GIRLS_KOBOLD_LIGHTBREATHING, 0.5f)
                "haa" -> entity.playRandomSound(SoundHandler.GIRLS_KOBOLD_HAA, 0.7f)
                "interested" -> entity.playRandomSounds(SoundHandler.GIRLS_KOBOLD_INTERESTED)
                "yep" -> entity.playRandomSounds(SoundHandler.GIRLS_KOBOLD_YEP)
                "bjmoan" -> entity.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_KOBOLD_BJMOAN))
                "blowjobStartbreath" -> {
                    val soundId = entity.random.nextInt(3)
                    entity.playSound(SoundHandler.GIRLS_KOBOLD_LIGHTBREATHING[soundId])
                }
                "matingCam" -> if (entity.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    var pos = Vec3(0.0, 0.4375 - player.eyeHeight, -0.6875)
                    pos = VectorMath.rotateByYaw(pos, entity.getYawRotation() + 180.0f)
                    pos = pos.add(entity.getTargetPosition())
                    PacketHandler.networkWrapper.sendToServer(TeleportPlayerPacket(player.uuid.toString(), pos, entity.getYawRotation() + 180.0f, 10.0f))
                }
                "mating_press_startDone", "mating_press_hardDone" -> {
                    // jar-faithful fall-through: startDone shows the meter, then runs hardDone
                    if (key == "mating_press_startDone" && entity.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }

                    if (entity.isControlledByLocalPlayer()) {
                        entity.setCurrentAction(Action.MATING_PRESS_SOFT)
                    }
                }
                "mating_press_softReady" -> {
                    if (entity.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                    }

                    if (entity.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                        entity.setCurrentAction(Action.MATING_PRESS_HARD)
                    }
                }
                "mating_press_hardReady" -> {
                    if (entity.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                    }

                    if (entity.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                        entity.resetAnimationControllerOffset()
                    }
                }
                "mating_cum_cam" -> if (entity.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    var pos = Vec3(0.0, 1.1875 - player.eyeHeight, 0.125)
                    pos = VectorMath.rotateByYaw(pos, entity.getYawRotation() + 180.0f)
                    pos = pos.add(entity.getTargetPosition())
                    PacketHandler.networkWrapper.sendToServer(TeleportPlayerPacket(player.uuid.toString(), pos, entity.getYawRotation() + 180.0f, 70.0f))
                }
                "cumMsg" -> {
                    entity.sendChatMessage("I.. hope I am satisfying you sir")
                    entity.playSound(SoundHandler.GIRLS_KOBOLD_SAD[entity.random.nextInt(1)])
                }
                "renderEgg" -> {
                    entity.isRenderEgg = true
                    entity.playRandomSoundAtVolume(SoundHandler.MISC_PLOB, 0.5f)
                }
                "mating_press_cumDone" -> if (entity.isControlledByLocalPlayer()) {
                    entity.resetCameraAndPhysics()
                }
            }
        }
        entity.movementController!!.setTransitionLengthTicks(10.0) // java-npe
        entity.registerControllers(data, soundListener)
    }
}
