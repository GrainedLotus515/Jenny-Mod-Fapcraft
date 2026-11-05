package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.client.model.SlimeModel
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendCompanionHomePacket
import com.trolmastercard.sexmod.networking.SetPlayerForGirlPacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.Reference
import com.trolmastercard.sexmod.util.SoundHandler
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.core.Vec3i
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> Player-form Jenny — the horny-potion transformation of the Jenny
 * NPC. Implements her scene set: blowjob (suck/thrust/cum), doggy on a bed
 * (start/wait/slow/fast/cum) and paizuri (start/slow/fast/cum).
 *
 * <b>Scene flow.</b> Owner-command actions (`blowjob`/`boobjob`)
 * teleport the players in via [AbstractPlayerGirlEntity.teleportPlayerToGirl]
 * and start the intro action; the GeckoLib sound-keyframe handler in
 * [registerControllers] advances each phase on the
 * animation sound keyframes (e.g. `bjiDone` -> [Action.SUCKBLOWJOB],
 * `doggyGoOnBedDone` -> [Action.WAITDOGGY]). The doggy bed phase
 * waits in [updateAITasks] for the player to come within 1 block, then
 * starts [Action.DOGGYSTART]. Cum ends via the
 * `bjcDone`/`paizuri_cumDone`/`doggyCumDone` sounds which call
 * `resetCameraAndPhysics()` (single-arg `ResetGirlPacket` path).
 *
 * <b>Pitfalls.</b> [setCurrentAction] forbids re-entering a
 * loop phase while the cum animation plays (doggy/cum and similar) — keep the
 * guards. `doggyfastReady` resets the animation offset and sets the
 * "hard" flag while the player jumps; the `doggyHard`/`paizuriCameraAligned` flags gate the
 * hard variant and the one-shot paizuri camera reposition.
 */
class JennyPlayerEntity : AbstractPlayerGirlEntity {
    @JvmField
    var flySecondFrame: Boolean = false

    @JvmField
    var doggyHard: Boolean = false

    @JvmField
    var fastMoanCount: Int = 0

    @JvmField
    var paizuriCameraAligned: Boolean = false

    /** Registry factory (used by ModEntities); 1.12 protected `JennyPlayerEntity(World)`. */
    constructor(type: EntityType<out JennyPlayerEntity>, level: Level) : super(type, level)

    constructor(level: Level, uuid: UUID) : super(ModEntities.PLAYER_JENNY.get(), level, uuid)

    override fun getScaleFactor(): Float = 1.75f

    override fun getLeftArmAngle(): Float = 35.0f

    override fun getRightArmAngle(): Float = 140.0f

    /** 1.12 `getEyeHeight()` = 1.64. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(1.64f)

    /**
     * CLIENT/SERVER: starts the doggy bed scene — strips the outfit, sets the
     * camera yaw from the anchor and enters [Action.STARTDOGGY].
     */
    override fun handleInteraction() {
        this.setCurrentAction(Action.STARTDOGGY)
        this.entityData.set(OUTFIT_INDEX, 0)
        this.cameraYaw = this.entityData.get(YAW_ROTATION)
    }

    override fun blocksOutfitToggle(): Boolean = false

    /** CLIENT. */
    override fun getHandModel(index: Int): IVanillaModel? = Client.handModel()

    override fun getHandTexture(index: Int): String =
        if (index == 0) "textures/entity/jenny/hand_nude.png" else "textures/entity/jenny/hand.png"

    /**
     * SERVER: owner-command entry point. `boobjob` strips and starts
     * [Action.PAIZURI_START]; `blowjob` starts [Action.STARTBLOWJOB].
     * Both broadcast the action to tracking players and teleport the acting
     * player into the scene.
     */
    override fun handleOwnerCommand(command: String, uuid: UUID) {
        if ("action.names.boobjob" == command) {
            this.entityData.set(OUTFIT_INDEX, 0)
            this.setCurrentAction(Action.PAIZURI_START)
            this.sendActionPacket(0, Action.PAIZURI_START)
            this.teleportPlayerToGirl(uuid)
        }

        if ("action.names.blowjob" == command) {
            this.setCurrentAction(Action.STARTBLOWJOB)
            this.sendActionPacket(this.getOutfitIndex(), Action.PAIZURI_START)
            this.teleportPlayerToGirl(uuid)
        }
    }

    /**
     * SERVER (and CLIENT mirror): the doggy bed phase. While in
     * [Action.WAITDOGGY], waits for the nearest non-owner player within
     * 1 block, locks them into the scene (movement lock, flying, noClip),
     * positions the camera and advances to [Action.DOGGYSTART].
     */
    override fun updateAITasks() {
        super.updateAITasks()
        if (this.getCurrentAction() === Action.WAITDOGGY) {
            val player = this.getNearestPlayer()
            if (player != null &&
                Math.sqrt(player.distanceToSqr(this.getPositionVec3d().x, this.getPositionVec3d().y, this.getPositionVec3d().z)) < 1.0
            ) {
                if (this.isOwnerUUID(player.uuid)) {
                    player.sendSystemMessage(Component.literal(ChatFormatting.DARK_PURPLE.toString() + "sowy no lesbo action yet uwu"))
                    return
                }

                this.setInteractionPlayerUUID(player.uuid)
                player.teleportTo(this.position().x, this.getPositionVec3d().y, this.position().z)
                this.alignPlayerToGirl(player as ServerPlayer, false)
                player.moveRelative(0.0f, Vec3.ZERO)
                player.abilities.flying = true
                val ownerUuid = this.getOwnerUserUUID()
                val ownerPlayer = if (ownerUuid == null) null else this.level().getPlayerByUUID(ownerUuid)
                if (ownerPlayer != null) {
                    ownerPlayer.abilities.flying = true
                }

                this.positionPlayerRelative(0.0, 0.0, 0.4, 0.0f, 60.0f)
                this.cameraOriginPos = null
                this.setCurrentAction(Action.DOGGYSTART)
                PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), player)
            }
        }
    }

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        openInventoryGui(player, this, arrayOf("action.names.blowjob", "action.names.boobjob"), false)
        return true
    }

    /**
     * CLIENT: returns the fast variant of the current loop action
     * (`SUCKBLOWJOB` -> `THRUSTBLOWJOB`, `DOGGYSLOW` ->
     * `DOGGYFAST`, `PAIZURI_SLOW` -> `PAIZURI_FAST`).
     * The paizuri transition repositions the camera once (guarded by
     * `paizuriCameraAligned`).
     */
    override fun getNextAction(action: Action): Action? {
        return when (action) {
            Action.SUCKBLOWJOB -> Action.THRUSTBLOWJOB
            Action.DOGGYSLOW -> Action.DOGGYFAST
            Action.PAIZURI_SLOW -> {
                if (this.paizuriCameraAligned) {
                    this.paizuriCameraAligned = false
                    this.positionPlayerRelative(0.0, 0.0, 0.0, 0.0f, 70.0f)
                }

                Action.PAIZURI_FAST
            }
            else -> null
        }
    }

    /**
     * CLIENT: maps the current loop action to its cum action and repositions
     * the camera for the blowjob cum (pitch 70).
     */
    override fun getCumAction(action: Action): Action? {
        if (action === Action.SUCKBLOWJOB || action === Action.THRUSTBLOWJOB) {
            this.positionPlayerRelative(0.0, 0.0, 0.0, 0.0f, 70.0f)
            return Action.CUMBLOWJOB
        } else if (action === Action.DOGGYSLOW || action === Action.DOGGYFAST) {
            return Action.DOGGYCUM
        } else {
            return if (action !== Action.PAIZURI_FAST && action !== Action.PAIZURI_SLOW) null else Action.PAIZURI_CUM
        }
    }

    /**
     * Guards the action state machine against re-entering a loop phase while
     * the corresponding cum animation is still playing (doggy/blowjob/paizuri).
     */
    override fun setCurrentAction(action: Action?) {
        val currentAction = this.getCurrentAction()
        if (currentAction !== Action.DOGGYCUM || action !== Action.DOGGYSLOW && action !== Action.DOGGYFAST) {
            if (currentAction !== Action.CUMBLOWJOB || action !== Action.THRUSTBLOWJOB && action !== Action.SUCKBLOWJOB) {
                if (currentAction !== Action.PAIZURI_CUM || action !== Action.PAIZURI_SLOW && action !== Action.PAIZURI_FAST) {
                    super.setCurrentAction(action)
                }
            }
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        when (event.controller.name) {
            "eyes" -> {
                if (this.getCurrentAction() === Action.NULL && this.getCurrentAction().autoBlink) {
                    this.createAnimation("animation.jenny.fhappy", true, event)
                } else {
                    this.createAnimation("animation.jenny.null", true, event)
                }
            }
            "movement" -> {
                if (this.getCurrentAction() !== Action.NULL) {
                    this.createAnimation("animation.jenny.null", true, event)
                } else if (this.playerRiding) {
                    this.createAnimation("animation.jenny.sit", true, event)
                } else {
                    val movement = this.movementController!! // java-npe: created with the controllers
                    if (movement.getCurrentAnimationName()?.contains("fly") == true && this.playerOnGround) {
                        this.flySecondFrame = !this.flySecondFrame
                    }

                    if (!this.playerOnGround) {
                        this.createAnimation("animation.jenny.fly" + (if (this.flySecondFrame) "2" else ""), true, event)
                    } else if (Math.abs(this.playerMotionOffset.x) + Math.abs(this.playerMotionOffset.y) > 0.0f) {
                        if (this.playerSprinting) {
                            movement.setAnimationSpeed(1.2f.toDouble())
                            this.createAnimation("animation.jenny.run", true, event)
                        } else if (this.playerMotionOffset.y >= -0.1f) {
                            movement.setAnimationSpeed(1.5)
                            this.createAnimation("animation.jenny.fastwalk", true, event)
                        } else {
                            movement.setAnimationSpeed(1.2f.toDouble())
                            this.createAnimation("animation.jenny.backwards_walk", true, event)
                        }
                    } else {
                        this.createAnimation("animation.jenny.idle", true, event)
                    }
                }
            }
            "action" -> when (this.getCurrentAction()) {
                Action.SUCKBLOWJOB -> this.createAnimation("animation.jenny.blowjobsuck", true, event)
                Action.DOGGYSLOW -> this.createAnimation("animation.jenny.doggyslow", true, event)
                Action.PAIZURI_SLOW -> this.createAnimation("animation.jenny.paizuri_slow", true, event)
                Action.NULL -> this.createAnimation("animation.jenny.null", true, event)
                Action.STRIP -> this.createAnimation("animation.jenny.strip", false, event)
                Action.PAYMENT -> this.createAnimation("animation.jenny.payment", false, event)
                Action.STARTBLOWJOB -> this.createAnimation("animation.jenny.blowjobintro", false, event)
                Action.THRUSTBLOWJOB -> this.createAnimation("animation.jenny.blowjobthrust", true, event)
                Action.CUMBLOWJOB -> this.createAnimation("animation.jenny.blowjobcum", false, event)
                Action.STARTDOGGY -> this.createAnimation("animation.jenny.doggygoonbed", false, event)
                Action.WAITDOGGY -> this.createAnimation("animation.jenny.doggywait", true, event)
                Action.DOGGYSTART -> this.createAnimation("animation.jenny.doggystart", false, event)
                Action.DOGGYFAST -> this.createAnimation("animation.jenny.doggyfast_" + (if (this.doggyHard) "hard" else "soft"), true, event)
                Action.DOGGYCUM -> this.createAnimation("animation.jenny.doggycum", false, event)
                Action.ATTACK -> this.createAnimation("animation.jenny.attack" + this.nextAttack, false, event)
                Action.BOW -> this.createAnimation("animation.jenny.bowcharge", false, event)
                Action.RIDE -> this.createAnimation("animation.jenny.ride", true, event)
                Action.SIT -> this.createAnimation("animation.jenny.sit", true, event)
                Action.THROW_PEARL -> this.createAnimation("animation.jenny.throwpearl", false, event)
                Action.DOWNED -> this.createAnimation("animation.jenny.downed", true, event)
                Action.PAIZURI_START -> this.createAnimation("animation.jenny.paizuri_start", false, event)
                Action.PAIZURI_FAST -> this.createAnimation("animation.jenny.paizuri_fast", true, event)
                Action.PAIZURI_CUM -> this.createAnimation("animation.jenny.paizuri_cum", false, event)
                else -> {}
            }
        }

        return PlayState.CONTINUE
    }

    /**
     * CLIENT: registers the action/movement/eyes controllers and the
     * GeckoLib sound-keyframe handler that drives the whole
     * scene — every phase transition, dialogue line, sound and the horny-meter
     * updates fire on animation sound keyframes here. Scene end: the
     * `bjcDone`/`paizuri_cumDone`/`doggyCumDone` keyframes
     * call `resetCameraAndPhysics()`; `paymentDone` dispatches the
     * selected scene via [startActionFromHandState]. Ordering matters: the listener must be
     * registered before the controllers are added.
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        if (this.actionController == null) {
            this.initAnimationControllers()
        }

        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            when (sound.keyframeData.sound) {
                "attackDone" -> if (++this.nextAttack == 3) {
                    this.nextAttack = 0
                }
                "stripMSG1" -> {
                    this.sendGirlChatMessage("Hihi~")
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_GIGGLE))
                }
                "paymentMSG1" -> {
                    this.sendGirlChatMessage("Huh?")
                    this.playSound(SoundHandler.GIRLS_JENNY_HUH[1])
                }
                "paymentMSG2" -> {
                    this.playSoundAtVolume(SoundHandler.MISC_PLOB[0], 0.5f)
                    val prefix = "<" + ClientHooks.localPlayerName() + "> "
                    when (this.entityData.get(GIRL_HAND_STATES)) {
                        "strip" -> this.broadcastChatAround(prefix + "show Bobs and vegana pls", true)
                        "blowjob" -> this.broadcastChatAround(prefix + "Give me the sucky sucky and these are yours", true)
                        "doggy" -> this.broadcastChatAround(prefix + "Give me the sex pls :)", true)
                        "boobjob" -> this.broadcastChatAround(prefix + "gib boba OwO", true)
                        else -> this.broadcastChatAround(prefix + "sex pls", true)
                    }
                }
                "paymentMSG3" -> {
                    this.sendGirlChatMessage("Hehe~")
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_GIGGLE))
                }
                "sexUiOn" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
                "paymentMSG4" -> this.playSoundAtVolume(SoundHandler.MISC_PLOB[0], 0.25f)
                "paymentDone" -> this.startActionFromHandState()
                "bjiMSG1" -> {
                    this.sendGirlChatMessage("What are you...")
                    this.playSound(SoundHandler.GIRLS_JENNY_MMM[8])
                    this.cameraYaw = 180.0f
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                    }
                }
                "bjiMSG2" -> {
                    this.sendGirlChatMessage("eh... boys...")
                    this.playSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING[8])
                }
                "bjiMSG3" -> {
                    this.sendGirlChatMessage("OHOhh...!")
                    this.playSound(SoundHandler.GIRLS_JENNY_AFTERSESSIONMOAN[0])
                }
                "bjiMSG4" -> this.playSound(SoundHandler.MISC_BELLJINGLE[0])
                "bjiMSG5" -> {
                    this.sendGirlChatMessage("Was this really necessary?!")
                    this.playSoundAtVolume(SoundHandler.GIRLS_JENNY_HMPH[1], 0.5f)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                    }
                }
                "bjiMSG6" -> {
                    this.sendGirlChatMessage("Oh~")
                    this.playSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING[8])
                }
                "bjiMSG7" -> {
                    this.sendGirlChatMessage("You like it?~")
                    this.playSound(SoundHandler.GIRLS_JENNY_GIGGLE[4])
                }
                "bjiMSG8" -> {
                    this.broadcastChatAround("<" + ClientHooks.localPlayerName() + "> Yee", true)
                    this.playSoundAtVolume(SoundHandler.MISC_PLOB[0], 0.5f)
                }
                "bjiMSG9" -> {
                    this.sendGirlChatMessage("Hihihi~")
                    this.playSound(SoundHandler.GIRLS_JENNY_GIGGLE[2])
                }
                "bjiMSG10" -> if (this.isControlledByLocalPlayer()) {
                    this.positionPlayerRelative(-0.4, -0.8, -0.2, 60.0f, -3.0f)
                }
                "bjiMSG11" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_LIPSOUND))
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }
                }
                "bjiMSG12" -> {
                    if (Reference.RANDOM.nextInt(5) == 0) {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_BJMOAN))
                    }

                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_LIPSOUND))
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }
                }
                "bjtMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_MMM))
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_LIPSOUND))
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04)
                    }
                }
                "bjiDone" -> {
                    this.setCurrentAction(Action.SUCKBLOWJOB)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "bjtDone" -> this.setCurrentAction(Action.SUCKBLOWJOB)
                "doggyfastReady" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    this.resetAnimationControllerOffset()
                    this.doggyHard = true
                }
                "bjtReady", "paizuriReady" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    this.resetAnimationControllerOffset()
                }
                "bjcMSG1" -> this.playSound(SoundHandler.GIRLS_JENNY_BJMOAN[1])
                "bjcMSG2" -> {
                    this.playSound(SoundHandler.GIRLS_JENNY_BJMOAN[7])
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.hideHornyMeter()
                    }
                }
                "bjcMSG3" -> this.playSound(SoundHandler.GIRLS_JENNY_AFTERSESSIONMOAN[1])
                "bjcMSG4" -> this.playSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING[0])
                "bjcMSG5" -> this.playSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING[1])
                "bjcMSG6" -> this.playSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING[2])
                "bjcMSG7" -> this.playSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING[3])
                "bjcBlackScreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "bjcDone", "paizuri_cumDone", "doggyCumDone" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.resetHornyMeter()
                    this.resetCameraAndPhysics()
                }
                "doggyGoOnBedMSG1" -> {
                    this.playSound(SoundHandler.MISC_BEDRUSTLE[0])
                    this.cameraYaw = this.yRot
                }
                "doggyGoOnBedMSG2" -> {
                    this.sendChatMessage("what are you waiting for?~")
                    this.playSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING[9])
                }
                "doggyGoOnBedMSG3" -> {
                    this.sendChatMessage("this ass ain't gonna fuck itself...")
                    this.playSound(SoundHandler.GIRLS_JENNY_GIGGLE[0])
                }
                "doggyGoOnBedMSG4" -> this.playSoundAtVolume(SoundHandler.MISC_SLAP[0], 0.75f)
                "doggyGoOnBedDone" -> {
                    PacketHandler.networkWrapper.sendToServer(
                        SetPlayerForGirlPacket(this.getGirlId(), ClientHooks.localPlayerUUID()!!), // java-npe: Minecraft.player
                    )
                    this.setCurrentAction(Action.WAITDOGGY)
                }
                "doggystartMSG1" -> this.playSound(SoundHandler.MISC_TOUCH[0])
                "doggystartMSG2" -> this.playSound(SoundHandler.MISC_TOUCH[1])
                "doggystartMSG3" -> this.playSoundAtVolume(SoundHandler.MISC_BEDRUSTLE[1], 0.5f)
                "doggystartMSG4" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_SMALLINSERTS))
                    this.playSound(SoundHandler.GIRLS_JENNY_MMM[1])
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                    }
                }
                "doggystartMSG5" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.33f)
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_MOAN))
                }
                "doggystartDone" -> {
                    this.setCurrentAction(Action.DOGGYSLOW)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "doggyslowMSG1" -> {
                    this.doggyHard = false
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.33f)
                    var choice = Reference.RANDOM.nextInt(4)
                    if (choice == 0) {
                        choice = Reference.RANDOM.nextInt(2)
                        if (choice == 0) {
                            this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_MMM))
                        } else {
                            this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_MOAN))
                        }
                    } else {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_HEAVYBREATHING))
                    }

                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.00666)
                    }
                }
                "doggyslowMSG2" -> this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING), 0.5f)
                "doggyfastMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }

                    this.fastMoanCount++
                    if (this.fastMoanCount % 2 == 0) {
                        val choice2 = Reference.RANDOM.nextInt(2)
                        if (choice2 == 0) {
                            this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_MOAN))
                        } else {
                            this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_HEAVYBREATHING))
                        }
                    } else {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_AHH))
                    }
                }
                "doggyfastDone" -> {
                    this.doggyHard = false
                    this.setCurrentAction(Action.DOGGYSLOW)
                }
                "doggycumMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.MISC_CUMINFLATION[0], 2.0f)
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 2.0f)
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_MOAN))
                }
                "doggycumMSG2" -> this.playSound(SoundHandler.GIRLS_JENNY_HEAVYBREATHING[4])
                "doggycumMSG3" -> this.playSound(SoundHandler.GIRLS_JENNY_HEAVYBREATHING[5])
                "doggycumMSG4" -> this.playSound(SoundHandler.GIRLS_JENNY_HEAVYBREATHING[6])
                "doggycumMSG5" -> this.playSound(SoundHandler.GIRLS_JENNY_HEAVYBREATHING[7])
                "pearl" -> PacketHandler.networkWrapper.sendToServer(SendCompanionHomePacket(this.getGirlId()))
                "boobjob_camera" -> if (this.isControlledByLocalPlayer() && !this.paizuriCameraAligned) {
                    this.paizuriCameraAligned = true
                    this.cameraYaw = 180.0f
                    this.positionPlayerRelative(-0.7, -0.6, -0.2, 60.0f, -3.0f)
                }
                "paizuri_startDone" -> if (this.isControlledByLocalPlayer()) {
                    this.setCurrentAction(Action.PAIZURI_SLOW)
                    HornyMeterHud.resetHornyMeter()
                    HornyMeterHud.showHornyMeter()
                }
                "paizuriFastMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    if (this.random.nextBoolean()) {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_MMM))
                    } else {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_AHH))
                    }

                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04)
                    }
                }
                "paizuriSlowMSG1", "paizuriStartMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }
                }
                "paizuri_fastDone" -> {
                    this.setCurrentAction(Action.PAIZURI_SLOW)
                    if (this.isControlledByLocalPlayer() && !this.paizuriCameraAligned) {
                        this.paizuriCameraAligned = true
                        this.positionPlayerRelative(-0.7, -0.6, -0.2, 60.0f, -3.0f)
                    }
                }
                "paizuri_startStep" -> {
                    val state = this.level().getBlockState(this.blockPosition().subtract(Vec3i(0, 1, 0)))
                    this.playSound(state.getSoundType(this.level(), this.blockPosition(), this).stepSound)
                }
                "paizuri_cumStart" -> if (this.isControlledByLocalPlayer() && !this.paizuriCameraAligned) {
                    this.positionPlayerRelative(-0.7, -0.6, -0.2, 60.0f, -3.0f)
                }
            }
        }
        this.registerControllers(controllers, soundListener)
    }

    /** Client-only helpers (PORTING.md §2.3 rule C). */
    private object Client {
        fun handModel(): IVanillaModel = SlimeModel()
    }

    companion object {
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractPlayerGirlEntity.createAttributes()
    }
}
