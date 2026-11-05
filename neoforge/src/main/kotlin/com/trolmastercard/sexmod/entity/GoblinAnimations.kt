package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.SoundHandler
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * GeckoLib glue for [GoblinEntity]: the animation predicate (eyes /
 * movement / action, with the `1person`/`3person` camera-mode suffix for the
 * owner's own view) and the scene sound listener (catch dialogue, paizuri,
 * breeding, nelson).
 *
 * <b>Pitfalls.</b> `breedingCam2` intentionally falls through into
 * `breedingIntroDone` (reproduced explicitly). The local-player camera writes
 * go through [ClientHooks] (Rule D); the handler only runs on the client.
 */
object GoblinAnimations {
    @JvmStatic
    fun predicate(goblin: GoblinEntity, event: AnimationState<*>): PlayState {
        if (SexWorldClient.isPreloadWorld(goblin.level())) {
            return PlayState.STOP
        }

        if (goblin.actionController == null) {
            goblin.initAnimationControllers()
        }

        when (event.controller.name) {
            "eyes" -> {
                if (goblin.getCurrentAction() != Action.NULL) {
                    goblin.createAnimation("animation.goblin.null", true, event)
                } else {
                    goblin.createAnimation("animation.goblin.blink", true, event)
                }
            }
            "movement" -> {
                if (goblin.getCurrentAction() != Action.NULL) {
                    goblin.createAnimation("animation.goblin.null", true, event)
                } else {
                    val moved = Math.abs(goblin.xo - goblin.x) + Math.abs(goblin.zo - goblin.z)
                    if (!goblin.getEntityData().get(BaseGirlEntity.IS_ANCHORED) && moved > 0.0) {
                        if (goblin.onGround() && Math.abs(Math.abs(goblin.yo) - Math.abs(goblin.y)) < 0.1f) {
                            if (moved > 0.2f) {
                                goblin.createAnimation("animation.goblin.walk", true, event)
                            } else {
                                goblin.createAnimation("animation.goblin.walk", true, event)
                            }

                            goblin.yRot = goblin.yHeadRot
                        } else {
                            goblin.createAnimation("animation.goblin.fly", true, event)
                        }
                    } else {
                        goblin.createAnimation("animation.goblin.idle", true, event)
                    }
                }
            }
            "action" -> {
                val camMode = if (ClientHooks.localPlayerUUID() == goblin.getOwnerUUID() && ClientHooks.thirdPersonView() == 0) "1" else "3"
                when (goblin.getCurrentAction()) {
                    Action.PAIZURI_IDLE -> goblin.createAnimation("animation.goblin.paizuri_idle", true, event)
                    Action.PAIZURI_SLOW -> goblin.createAnimation("animation.goblin.paizuri_slow" + goblin.paizuriPoseSuffix, true, event)
                    Action.BREEDING_SLOW_0 ->
                        goblin.createAnimation("animation.goblin.breeding_slow_1" + (if (goblin.breedingSideLeft) "l" else "r"), true, event)
                    Action.BREEDING_SLOW_2 -> goblin.createAnimation("animation.goblin.breeding_slow_3", true, event)
                    Action.NELSON_SLOW ->
                        goblin.createAnimation("animation.goblin.nelson_slow" + (if (goblin.nelsonSlowBasePose) "" else "2"), true, event)
                    Action.PAIZURI_FAST -> goblin.createAnimation("animation.goblin.paizuri_fast", true, event)
                    Action.PAIZURI_FAST_CONTINUES -> goblin.createAnimation("animation.goblin.paizuri_fast_countinues", true, event)
                    Action.BREEDING_1 -> goblin.createAnimation("animation.goblin.breeding_2", true, event)
                    Action.BREEDING_FAST_2 -> goblin.createAnimation("animation.goblin.breeding_fast_3", true, event)
                    Action.NELSON_FAST ->
                        goblin.createAnimation("animation.goblin.nelson_fast" + (if (goblin.nelsonAltPose) "c" else "s"), true, event)
                    Action.BREEDING_FAST_0 ->
                        goblin.createAnimation("animation.goblin.breeding_fast_1" + (if (goblin.breedingFastReadyPose) "c" else "s"), true, event)
                    Action.NULL -> goblin.createAnimation("animation.goblin.null", true, event)
                    Action.SHOULDER_IDLE -> goblin.createAnimation("animation.goblin.shoulder_idle", true, event)
                    Action.PICK_UP -> goblin.createAnimation(String.format("animation.goblin.pick_up_%sperson", camMode), true, event)
                    Action.SIT -> goblin.createAnimation("animation.goblin.sit", true, event)
                    Action.RUN -> {
                        if (goblin.onGround()) {
                            goblin.createAnimation("animation.goblin.running", true, event)
                        } else {
                            goblin.createAnimation("animation.goblin.fly", true, event)
                        }
                    }
                    Action.CATCH -> goblin.createAnimation(String.format("animation.goblin.catch_%sperson", camMode), true, event)
                    Action.CATCH_BJ -> goblin.createAnimation(String.format("animation.goblin.catch_%spersonBj", camMode), true, event)
                    Action.CATCH_BJ_IDLE -> goblin.createAnimation(String.format("animation.goblin.catch_%spersonBj_idle", camMode), true, event)
                    Action.START_THROWING -> goblin.createAnimation(String.format("animation.goblin.throw_%sperson", camMode), true, event)
                    Action.THROWN -> goblin.createAnimation("animation.goblin.thrown", true, event)
                    Action.PAIZURI_START -> goblin.createAnimation("animation.goblin.paizuri_start", true, event)
                    Action.PAIZURI_CUM -> goblin.createAnimation("animation.goblin.paizuri_cum", true, event)
                    Action.JUMP_0 -> goblin.createAnimation("animation.goblin.jump_1", true, event)
                    Action.JUMP_1 -> goblin.createAnimation("animation.goblin.jump_2", true, event)
                    Action.JUMP_2 -> goblin.createAnimation("animation.goblin.jump_3", true, event)
                    Action.BREEDING_INTRO_0 -> goblin.createAnimation("animation.goblin.breeding_intro_1", true, event)
                    Action.BREEDING_INTRO_1 -> goblin.createAnimation("animation.goblin.breeding_intro_2", true, event)
                    Action.BREEDING_INTRO_2 -> goblin.createAnimation("animation.goblin.breeding_intro_3", true, event)
                    Action.BREEDING_CUM_0 -> goblin.createAnimation("animation.goblin.breeding_cum_1", true, event)
                    Action.BREEDING_CUM_1 -> goblin.createAnimation("animation.goblin.breeding_cum_2", true, event)
                    Action.BREEDING_CUM_2 -> goblin.createAnimation("animation.goblin.breeding_cum_3", true, event)
                    Action.VANISH, Action.AWAIT_PICK_UP -> goblin.createAnimation("animation.goblin.await_pick_up", true, event)
                    Action.STAND_UP -> goblin.createAnimation("animation.goblin.stand_up", false, event)
                    Action.NELSON_INTRO -> goblin.createAnimation("animation.goblin.nelson_intro", true, event)
                    Action.NELSON_CUM -> goblin.createAnimation("animation.goblin.nelson_cum", true, event)
                    else -> {}
                }
            }
        }

        return PlayState.CONTINUE
    }

    @JvmStatic
    fun registerControllers(goblin: GoblinEntity, data: AnimatableManager.ControllerRegistrar) {
        if (goblin.actionController == null) {
            goblin.initAnimationControllers()
        }

        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> handler@{ sound ->
            val key = sound.keyframeData.sound
            when (key) {
                "catchEh" -> {
                    goblin.sendChatMessage("ehh..")
                    goblin.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "catchAkward" -> {
                    goblin.sendChatMessage("awkward..")
                    goblin.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "catchWell" -> {
                    goblin.sendChatMessage("well...")
                    goblin.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "catchRather" -> {
                    goblin.sendChatMessage("would you rather have this stupid... thing?")
                    goblin.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "catchMe" -> {
                    goblin.sendChatMessage("...or use me?~")
                    goblin.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "catchDone" -> if ("bj" == goblin.getEntityData().get(BaseGirlEntity.GIRL_HAND_STATES)) {
                    goblin.setCurrentAction(Action.CATCH_BJ)
                }
                "catchBjDone" -> {
                    goblin.setCurrentAction(Action.CATCH_BJ_IDLE)
                    if (goblin.isControlledByLocalPlayer()) {
                        val player = ClientHooks.localPlayer()!! // java-npe
                        BaseGirlEntity.openInventoryGui(player, goblin, arrayOf("use her", "take ur stuff back"), null, false)
                    }
                }
                "paizuriChoice" -> {
                    goblin.sendChatMessage("good choice!~")
                    goblin.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "paizuriBoth" -> {
                    goblin.sendChatMessage("...for both of us!")
                    goblin.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "paizruiUse" -> {
                    goblin.sendChatMessage("now use me like a fuck toy!~")
                    goblin.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "paizuriSwitch" -> if (!goblin.random.nextBoolean()) {
                    goblin.paizuriPoseSuffix = if ("" == goblin.paizuriPoseSuffix) "2" else ""
                }
                "touch" -> goblin.playRandomSoundAtVolume(SoundHandler.MISC_TOUCH, 3.0f)
                "pound" -> {
                    goblin.playRandomSound(SoundHandler.MISC_POUNDING)
                    if (goblin.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                    }
                }
                "paizuri_startDone" -> {
                    goblin.setCurrentAction(Action.PAIZURI_IDLE)
                    if (goblin.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "paizuriFastDone" -> goblin.setCurrentAction(Action.PAIZURI_SLOW)
                "paizuriFastReady" -> if (goblin.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    goblin.setCurrentAction(Action.PAIZURI_FAST_CONTINUES)
                }
                "paizuriFastContinuesReady" -> if (goblin.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    goblin.resetAnimationControllerOffset()
                }
                "smallPound" -> {
                    goblin.playRandomSoundAtVolume(SoundHandler.MISC_POUNDING, 0.25f)
                    if (goblin.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02f.toDouble())
                    }
                }
                "paizruiCam" -> if (goblin.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    player.xRot = 70.0f
                    player.xRotO = 70.0f
                }
                "blackScreen" -> if (goblin.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "paizuriCumDone" -> goblin.setCurrentAction(Action.NULL)
                "cumSound" -> goblin.playRandomSoundAtVolume(SoundHandler.MISC_SMALLINSERTS, 3.0f)
                "jumpCam" -> if (goblin.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    player.yRot = goblin.getYawRotation() + 170.0f
                    player.xRot = -20.0f
                    player.yHeadRot = player.yRot
                    ClientHooks.setThirdPersonView(2)
                }
                "breedingHmm" -> {
                    if (goblin.isControlledByLocalPlayer()) {
                        val player = ClientHooks.localPlayer()!! // java-npe
                        player.yRot = goblin.getYawRotation() + 180.0f
                        player.xRot = -15.0f
                        player.yHeadRot = player.yRot
                        ClientHooks.setThirdPersonView(0)
                    }

                    goblin.sendChatMessage("hmm...")
                    goblin.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "breedingFound" -> {
                    goblin.sendChatMessage("guess we found a worthy breeding partner!")
                    goblin.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "breedingEnough" -> {
                    goblin.sendChatMessage("Eh.. go pin him down, before he runs off!")
                    goblin.playRandomSound(SoundHandler.MISC_PLOB)
                }
                "breedingCam2", "breedingIntroDone" -> {
                    // jar-faithful fall-through: breedingCam2 sets the camera, then runs breedingIntroDone
                    if (key == "breedingCam2" && goblin.isControlledByLocalPlayer()) {
                        val player = ClientHooks.localPlayer()!! // java-npe
                        ClientHooks.setThirdPersonView(2)
                        player.yRot = goblin.getYawRotation() - 120.0f
                        player.xRot = -30.0f
                    }

                    goblin.setCurrentAction(Action.BREEDING_SLOW_0)
                    if (goblin.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "breeding_slow1Done" -> {
                    if (goblin.random.nextBoolean()) {
                        goblin.breedingSideLeft = !goblin.breedingSideLeft
                    }

                    if (goblin.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                        goblin.setCurrentAction(Action.BREEDING_FAST_0)
                        goblin.breedingFastReadyPose = false
                    }
                }
                "breeding_fast1Done" -> {
                    goblin.setCurrentAction(Action.BREEDING_SLOW_0)
                    if (goblin.isControlledByLocalPlayer()) {
                        goblin.breedingFastReadyPose = false
                    }
                }
                "breeding_fast1Ready" -> if (goblin.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    goblin.breedingFastReadyPose = true
                    goblin.resetAnimationControllerOffset()
                    goblin.actionController!!.setTickOffset(0.0) // java-npe
                }
                "cum" -> goblin.playRandomSoundAtVolume(SoundHandler.MISC_SMALLINSERTS, 3.0f)
                "breeding_intro_3Done" -> goblin.setCurrentAction(Action.BREEDING_SLOW_2)
                "breeding_3_wiggle" -> if (goblin.random.nextBoolean()) {
                    goblin.actionController!!.setTickOffset(0.0) // java-npe
                }
                "breeding_fast_3Done" -> if (goblin.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    goblin.setCurrentAction(Action.BREEDING_SLOW_2)
                }
                "breeding_intro_2Done" -> goblin.setCurrentAction(Action.BREEDING_1)
                "breeding_cumCam" -> if (goblin.isControlledByLocalPlayer()) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    ClientHooks.setThirdPersonView(0)
                    player.yRot = goblin.getYawRotation() + 180.0f
                    player.xRot = -15.0f
                    player.yHeadRot = player.yRot
                    ClientHooks.setThirdPersonView(0)
                }
                "neslon_introDone" -> {
                    goblin.setCurrentAction(Action.NELSON_SLOW)
                    if (goblin.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "nelson_slowDone" -> if (goblin.random.nextBoolean()) {
                    goblin.nelsonSlowBasePose = !goblin.nelsonSlowBasePose
                }
                "neslon_fastSwitch" -> {
                    if (!goblin.isControlledByLocalPlayer()) {
                        goblin.nelsonAltPose = true
                        return@handler
                    }

                    if (HandlePlayerMovement.isJumping) {
                        goblin.nelsonAltPose = true
                    }
                }
                "neslon_fastBackSwitch" -> {
                    if (!goblin.isControlledByLocalPlayer()) {
                        goblin.actionController!!.setTickOffset(0.0) // java-npe
                    } else if (HandlePlayerMovement.isJumping) {
                        goblin.actionController!!.setTickOffset(0.0) // java-npe
                    }
                }
                "nelsonFastDone" -> {
                    goblin.nelsonAltPose = false
                    if (goblin.isControlledByLocalPlayer()) {
                        goblin.setCurrentAction(Action.NELSON_SLOW)
                    }
                }
                "nelson_cumDone" -> if (goblin.isControlledByLocalPlayer()) {
                    goblin.resetCameraAndPhysics()
                    goblin.setCurrentAction(Action.NULL)
                }
            }
        }
        goblin.movementController!!.setTransitionLengthTicks(10.0) // java-npe
        goblin.registerControllers(data, soundListener)
    }
}
