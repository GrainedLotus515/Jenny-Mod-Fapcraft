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
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * GeckoLib glue for [KoboldPlayerEntity]: the animation predicate for the
 * eyes / movement / action controllers and the controller registration with
 * the scene sound listener. The player form toggles `fly`/`fly2` while
 * airborne, has run/walk/backwards-walk movement and a 3-tick movement
 * transition.
 *
 * <b>Pitfalls.</b> `mating_press_startDone` intentionally falls through to
 * `mating_press_hardDone` (reproduced explicitly). The sound handler only
 * runs on the client (Rule B for the HUD statics).
 */
object KoboldPlayerAnimations {
    @JvmStatic
    fun predicate(kobold: KoboldPlayerEntity, event: AnimationState<*>): PlayState {
        if (SexWorldClient.isPreloadWorld(kobold.level())) {
            return PlayState.STOP
        }

        val shrink = 0.25f - kobold.getEntityData().get(KoboldEntity.SIZE_SCALAR)
        MolangVars.set("size", shrink.toDouble())
        when (event.controller.name) {
            "eyes" -> {
                if (kobold.getCurrentAction() == Action.NULL && kobold.getCurrentAction().autoBlink) {
                    kobold.createAnimation("animation.kobold.blink", true, event)
                } else {
                    kobold.createAnimation("animation.kobold.null", true, event)
                }
            }
            "movement" -> {
                if (kobold.getCurrentAction() != Action.NULL) {
                    kobold.createAnimation("animation.kobold.null", true, event)
                } else if (kobold.playerRiding) {
                    kobold.createAnimation("animation.kobold.sit", true, event)
                } else {
                    val movement = kobold.movementController!! // java-npe
                    if (movement.getCurrentAnimationName()?.contains("fly") == true && kobold.playerOnGround) {
                        kobold.flySecondFrame = !kobold.flySecondFrame
                    }

                    if (!kobold.playerOnGround) {
                        kobold.createAnimation("animation.kobold.fly" + (if (kobold.flySecondFrame) "2" else ""), true, event)
                    } else if (Math.abs(kobold.playerMotionOffset.x) + Math.abs(kobold.playerMotionOffset.y) > 0.0f) {
                        if (kobold.playerSprinting) {
                            movement.setAnimationSpeed(1.2f.toDouble())
                            kobold.createAnimation("animation.kobold.run", true, event)
                        } else if (kobold.playerMotionOffset.y >= -0.1f) {
                            movement.setAnimationSpeed(2.0)
                            kobold.createAnimation("animation.kobold.walk", true, event)
                        } else {
                            movement.setAnimationSpeed(1.75)
                            kobold.createAnimation("animation.kobold.backwards_walk", true, event)
                        }
                    } else {
                        kobold.createAnimation("animation.kobold.idle", true, event)
                    }
                }
            }
            "action" -> {
                when (kobold.getCurrentAction()) {
                    Action.NULL -> kobold.createAnimation("animation.kobold.null", true, event)
                    Action.STRIP -> kobold.createAnimation("animation.kobold.strip", false, event)
                    Action.ATTACK -> kobold.createAnimation("animation.kobold.attack" + kobold.nextAttack, false, event)
                    Action.BOW -> kobold.createAnimation("animation.kobold.bowcharge", false, event)
                    Action.SIT -> kobold.createAnimation("animation.kobold.sit", true, event)
                    Action.MINE -> kobold.createAnimation("animation.kobold.fall_tree", true, event)
                    Action.PAYMENT -> kobold.createAnimation("animation.kobold.paymentBackpack", true, event)
                    Action.STARTBLOWJOB -> kobold.createAnimation("animation.kobold.blowjobStart", false, event)
                    Action.SUCKBLOWJOB_BLINK -> {
                        val side = if (kobold.blowjobRightSide) "R" else "L"
                        val animSuffix = if (kobold.switchVariant) "Switch" else ""
                        kobold.createAnimation("animation.kobold.blowjobSlow$side$animSuffix", true, event)
                    }
                    Action.THRUSTBLOWJOB -> kobold.createAnimation("animation.kobold.blowjobFast", true, event)
                    Action.CUMBLOWJOB -> kobold.createAnimation("animation.kobold.blowjobCum", false, event)
                    Action.KOBOLD_ANAL_START -> kobold.createAnimation("animation.kobold.analStart", false, event)
                    Action.KOBOLD_ANAL_SLOW -> kobold.createAnimation("animation.kobold.analSoft", true, event)
                    Action.KOBOLD_ANAL_FAST -> kobold.createAnimation("animation.kobold.analHard", true, event)
                    Action.KOBOLD_ANAL_CUM -> kobold.createAnimation("animation.kobold.analCum", true, event)
                    Action.SLEEP -> kobold.createAnimation("animation.kobold.sleep", true, event)
                    Action.MATING_PRESS_START -> kobold.createAnimation("animation.kobold.mating_press_start", false, event)
                    Action.MATING_PRESS_SOFT -> kobold.createAnimation("animation.kobold.mating_press_soft", true, event)
                    Action.MATING_PRESS_HARD -> kobold.createAnimation("animation.kobold.mating_press_hard", true, event)
                    Action.MATING_PRESS_CUM -> kobold.createAnimation("animation.kobold.mating_press_cum", true, event)
                    else -> {}
                }
            }
        }

        return PlayState.CONTINUE
    }

    private fun teleportLocal(kobold: KoboldPlayerEntity, uuid: String, offset: Vec3, yaw: Float, pitch: Float) {
        val pos = VectorMath.rotateByYaw(offset, kobold.getYawRotation() + 180.0f)
        PacketHandler.networkWrapper.sendToServer(TeleportPlayerPacket(uuid, kobold.getTargetPosition().add(pos), yaw, pitch))
    }

    @JvmStatic
    fun registerControllers(kobold: KoboldPlayerEntity, data: AnimatableManager.ControllerRegistrar) {
        if (kobold.actionController == null) {
            kobold.initAnimationControllers()
        }

        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            val key = sound.keyframeData.sound
            when (key) {
                "attackDone" -> {
                    kobold.nextAttack++
                    if (kobold.nextAttack == 3) {
                        kobold.nextAttack = 0
                    }
                }
                "paymentMSG1" -> {
                    kobold.sendChatMessageToPlayer(kobold.getInteractionPlayerUUID()!!, "I'd like to use ur services owo") // java-npe
                    kobold.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "plob" -> kobold.playRandomSound(SoundHandler.MISC_PLOB)
                "blackScreen" -> if (kobold.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "paymentDone" -> if (kobold.isControlledByLocalPlayer()) {
                    kobold.startActionFromHandState()
                }
                "blowjobStartMSG1" -> if (kobold.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    teleportLocal(
                        kobold, kobold.getInteractionPlayerUUID().toString(),
                        Vec3(0.0, 0.625 - player.eyeHeight, -1.0), kobold.getYawRotation() + 180.0f, 0.0f,
                    )
                }
                "blowjobStartMSG2" -> if (kobold.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    teleportLocal(
                        kobold, kobold.getInteractionPlayerUUID().toString(),
                        Vec3(0.5, 0.5 - player.eyeHeight, -0.6875), kobold.getYawRotation() + 180.0f - 40.0f, 0.0f,
                    )
                }
                "lipsound" -> {
                    if (kobold.random.nextBoolean()) {
                        kobold.playRandomSoundAtVolume(SoundHandler.GIRLS_ALLIE_LIPSOUND, 1.5f)
                    } else {
                        kobold.playRandomSoundAtVolume(SoundHandler.GIRLS_JENNY_LIPSOUND, 1.5f)
                    }

                    HornyMeterHud.addToHornyMeter(0.02f.toDouble())
                }
                "touch" -> kobold.playRandomSound(SoundHandler.MISC_TOUCH)
                "blowjobStartDone" -> {
                    kobold.setCurrentAction(Action.SUCKBLOWJOB_BLINK)
                    kobold.switchVariant = false
                    kobold.blowjobRightSide = true
                    if (kobold.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "switch" -> {
                    kobold.switchVariant = kobold.random.nextBoolean()
                    kobold.actionController!!.clearAnimationCache() // java-npe
                }
                "endSwitch" -> {
                    kobold.switchVariant = false
                    kobold.blowjobRightSide = !kobold.blowjobRightSide
                    kobold.actionController!!.clearAnimationCache() // java-npe
                }
                "blowjobFastDone" -> if (kobold.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    kobold.setCurrentAction(Action.SUCKBLOWJOB_BLINK)
                }
                "cumLoud" -> kobold.playRandomSoundAtVolume(SoundHandler.MISC_SMALLINSERTS, 3.0f)
                "cumQuiet" -> kobold.playRandomSoundAtVolume(SoundHandler.MISC_SMALLINSERTS, 3.0f)
                "analCumDone", "blowjobCumDone" -> if (kobold.isControlledByLocalPlayer()) {
                    kobold.resetCameraAndPhysics()
                    HornyMeterHud.hideHornyMeter()
                }
                "analStartDone" -> {
                    kobold.setCurrentAction(Action.KOBOLD_ANAL_SLOW)
                    if (kobold.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "analStartCam" -> if (kobold.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    teleportLocal(
                        kobold, kobold.getInteractionPlayerUUID().toString(),
                        Vec3(0.0, 0.5625 - player.eyeHeight, 0.5625), kobold.getYawRotation(), 0.0f,
                    )
                }
                "pounding" -> kobold.playRandomSound(SoundHandler.MISC_POUNDING)
                "analFastRapid" -> if (kobold.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    if (kobold.getCurrentAction() == Action.KOBOLD_ANAL_FAST) {
                        kobold.resetAnimationControllerOffset()
                    } else {
                        kobold.setCurrentAction(Action.KOBOLD_ANAL_FAST)
                    }
                }
                "analDone" -> if (kobold.getCurrentAction() == Action.KOBOLD_ANAL_FAST) {
                    kobold.setCurrentAction(Action.KOBOLD_ANAL_SLOW)
                }
                "analHard" -> if (kobold.isControlledByLocalPlayer()) {
                    HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                }
                "analSoft" -> if (kobold.isControlledByLocalPlayer()) {
                    HornyMeterHud.addToHornyMeter(0.02f.toDouble())
                }
                "cum" -> kobold.playRandomSoundAtVolume(SoundHandler.MISC_SMALLINSERTS, 3.0f)
                "giggle" -> kobold.playRandomSounds(SoundHandler.GIRLS_KOBOLD_GIGGLE)
                "moan" -> kobold.playRandomSounds(SoundHandler.GIRLS_KOBOLD_MOAN)
                "moanMating" -> {
                    kobold.moanCooldown--
                    if (kobold.moanCooldown <= 0) {
                        kobold.moanCooldown = 3
                        kobold.playRandomSounds(SoundHandler.GIRLS_KOBOLD_MOAN)
                    }
                }
                "analHardMSG1" -> {
                    kobold.moanCooldown--
                    if (kobold.moanCooldown <= 0) {
                        kobold.moanCooldown = 4
                        kobold.playRandomSounds(SoundHandler.GIRLS_KOBOLD_MOAN)
                    }
                }
                "orgasm" -> kobold.playRandomSounds(SoundHandler.GIRLS_KOBOLD_ORGASM)
                "breath" -> kobold.playRandomSound(SoundHandler.GIRLS_KOBOLD_LIGHTBREATHING, 0.5f)
                "haa" -> kobold.playRandomSound(SoundHandler.GIRLS_KOBOLD_HAA, 0.7f)
                "interested" -> kobold.playRandomSounds(SoundHandler.GIRLS_KOBOLD_INTERESTED)
                "yep" -> kobold.playRandomSounds(SoundHandler.GIRLS_KOBOLD_YEP)
                "bjmoan" -> kobold.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_KOBOLD_BJMOAN))
                "blowjobStartbreath" -> {
                    val soundId = kobold.random.nextInt(3)
                    kobold.playSound(SoundHandler.GIRLS_KOBOLD_LIGHTBREATHING[soundId])
                }
                "matingCam" -> if (kobold.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    var pos = Vec3(0.0, 0.4375 - player.eyeHeight, -0.6875)
                    pos = VectorMath.rotateByYaw(pos, kobold.getYawRotation() + 180.0f)
                    pos = pos.add(kobold.getTargetPosition())
                    PacketHandler.networkWrapper.sendToServer(TeleportPlayerPacket(player.uuid.toString(), pos, kobold.getYawRotation() + 180.0f, 10.0f))
                }
                "mating_press_startDone", "mating_press_hardDone" -> {
                    // jar-faithful fall-through: startDone shows the meter, then runs hardDone
                    if (key == "mating_press_startDone" && kobold.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }

                    if (kobold.isControlledByLocalPlayer()) {
                        kobold.setCurrentAction(Action.MATING_PRESS_SOFT)
                    }
                }
                "mating_press_softReady" -> {
                    if (kobold.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                    }

                    if (kobold.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                        kobold.setCurrentAction(Action.MATING_PRESS_HARD)
                    }
                }
                "mating_press_hardReady" -> {
                    if (kobold.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                    }

                    if (kobold.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                        kobold.resetAnimationControllerOffset()
                    }
                }
                "mating_cum_cam" -> if (kobold.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    var pos = Vec3(0.0, 1.1875 - player.eyeHeight, 0.125)
                    pos = VectorMath.rotateByYaw(pos, kobold.getYawRotation() + 180.0f)
                    pos = pos.add(kobold.getTargetPosition())
                    PacketHandler.networkWrapper.sendToServer(TeleportPlayerPacket(player.uuid.toString(), pos, kobold.getYawRotation() + 180.0f, 70.0f))
                }
                "cumMsg" -> {
                    kobold.sendChatMessage("I.. hope I am satisfying you sir")
                    kobold.playSound(SoundHandler.GIRLS_KOBOLD_SAD[kobold.random.nextInt(1)])
                }
                "mating_press_cumDone" -> if (kobold.isControlledByLocalPlayer()) {
                    kobold.resetCameraAndPhysics()
                }
            }
        }
        kobold.movementController!!.setTransitionLengthTicks(3.0) // java-npe
        kobold.registerControllers(data, soundListener)
    }
}
