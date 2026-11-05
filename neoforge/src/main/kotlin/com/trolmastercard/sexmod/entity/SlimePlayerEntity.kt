package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.client.model.KoboldModel
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SetPlayerForGirlPacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.Reference
import com.trolmastercard.sexmod.util.SoundHandler
import java.util.UUID
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
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
 * <b>Role.</b> Player-form Slime — the transformation with the same blowjob and
 * doggy scene set as the slime NPC (no pregnancy lifecycle).
 *
 * <b>Scene flow.</b> The only owner command is `blowjob` (starts
 * [Action.SUCKBLOWJOB] directly); the doggy scene waits in
 * [Action.WAITDOGGY] inside [updateAITasks] for a player within
 * 1 block, then locks both players in and starts [Action.DOGGYSTART].
 * Progression and scene end run in the sound-keyframe handler
 * (`bjcDone`/`doggyCumDone` -> `resetCameraAndPhysics()`).
 */
class SlimePlayerEntity : AbstractPlayerGirlEntity {
    @JvmField
    var flySecondFrame: Boolean = false

    @JvmField
    var fastMoanCount: Int = 0

    /** Registry factory (used by ModEntities); 1.12 protected `SlimePlayerEntity(World)`. */
    constructor(type: EntityType<out SlimePlayerEntity>, level: Level) : super(type, level)

    constructor(level: Level, uuid: UUID) : super(ModEntities.PLAYER_SLIME.get(), level, uuid)

    override fun getScaleFactor(): Float = 1.6f

    /** 1.12 `getEyeHeight()` = 1.64. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(1.64f)

    override fun canBeInteracted(): Boolean = false

    override fun blocksOutfitToggle(): Boolean = false

    /** CLIENT. */
    override fun getHandModel(index: Int): IVanillaModel? = Client.handModel()

    override fun getHandTexture(index: Int): String = "textures/entity/slime/hand.png"

    override fun handleOwnerCommand(command: String, uuid: UUID) {
        if ("action.names.blowjob" == command) {
            this.sendActionPacket(0, Action.SUCKBLOWJOB)
            this.setCurrentAction(Action.SUCKBLOWJOB)
            this.teleportPlayerToGirl(uuid)
        }
    }

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        openInventoryGui(player, this, arrayOf("action.names.blowjob"), false)
        return true
    }

    override fun setCurrentAction(action: Action?) {
        if (this.getCurrentAction() !== Action.CUMBLOWJOB || action !== Action.THRUSTBLOWJOB && action !== Action.SUCKBLOWJOB) {
            if (this.getCurrentAction() !== Action.DOGGYCUM || action !== Action.DOGGYFAST && action !== Action.DOGGYSLOW) {
                super.setCurrentAction(action)
            }
        }
    }

    override fun getNextAction(action: Action): Action? {
        if (action === Action.SUCKBLOWJOB) {
            return Action.THRUSTBLOWJOB
        } else {
            return if (action === Action.DOGGYSLOW) Action.DOGGYFAST else null
        }
    }

    override fun getCumAction(action: Action): Action? {
        if (action === Action.SUCKBLOWJOB || action === Action.THRUSTBLOWJOB) {
            return Action.CUMBLOWJOB
        } else {
            return if (action !== Action.DOGGYSLOW && action !== Action.DOGGYFAST) null else Action.DOGGYCUM
        }
    }

    /**
     * SERVER (and CLIENT mirror): the doggy bed phase — waits in
     * [Action.WAITDOGGY] for a player within 1 block, locks them in
     * (movement lock, noClip, noGravity, flying) and starts
     * [Action.DOGGYSTART].
     */
    override fun updateAITasks() {
        super.updateAITasks()
        if (this.getCurrentAction() === Action.WAITDOGGY) {
            val player = this.getNearestPlayer()
            if (player != null) {
                if (!(player.position().distanceTo(this.getPositionVec3d()) > 1.0)) {
                    PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), player as ServerPlayer)
                    this.setInteractionPlayerUUID(player.uuid)
                    player.yRot = this.getYawRotation()
                    this.cameraYaw = this.getYawRotation()
                    player.setPos(this.getPositionVec3d().x, this.getPositionVec3d().y, this.getPositionVec3d().z)
                    player.moveRelative(0.0f, Vec3.ZERO)
                    this.positionPlayerRelative(0.0, 0.0, 0.4, 0.0f, 60.0f)
                    this.setCurrentAction(Action.DOGGYSTART)
                    player.setNoGravity(true)
                    player.noPhysics = true
                    val ownerUuid = this.getOwnerUserUUID()
                    val ownerPlayer = if (ownerUuid == null) null else this.level().getPlayerByUUID(ownerUuid)
                    if (ownerPlayer != null) {
                        ownerPlayer.setNoGravity(true)
                        ownerPlayer.abilities.flying = true
                    }

                    player.noPhysics = true
                    player.abilities.flying = true
                }
            }
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        when (event.controller.name) {
            "eyes" -> {
                if (this.getCurrentAction() !== Action.NULL && this.getCurrentAction().autoBlink) {
                    this.createAnimation("animation.slime.fhappy", true, event)
                } else {
                    this.createAnimation("animation.slime.null", true, event)
                }
            }
            "movement" -> {
                if (this.getCurrentAction() !== Action.NULL) {
                    this.createAnimation("animation.slime.null", true, event)
                } else if (this.playerRiding) {
                    this.createAnimation("animation.slime.sit", true, event)
                } else {
                    val movement = this.movementController!! // java-npe: created with the controllers
                    if (movement.getCurrentAnimationName()?.contains("fly") == true && this.playerOnGround) {
                        this.flySecondFrame = !this.flySecondFrame
                    }

                    if (!this.playerOnGround) {
                        this.createAnimation("animation.slime.fly" + (if (this.flySecondFrame) "2" else ""), true, event)
                    } else if (Math.abs(this.playerMotionOffset.x) + Math.abs(this.playerMotionOffset.y) > 0.0f) {
                        if (this.playerSprinting) {
                            this.createAnimation("animation.slime.run", true, event)
                        } else if (this.playerMotionOffset.y >= -0.1f) {
                            this.createAnimation("animation.slime.walk", true, event)
                        } else {
                            this.createAnimation("animation.slime.backwards_walk", true, event)
                        }
                    } else {
                        this.createAnimation("animation.slime.idle", true, event)
                    }
                }
            }
            "action" -> {
                if (this.getCurrentAction() === Action.NULL) {
                    this.createAnimation("animation.slime.null", true, event)
                } else {
                    when (this.getCurrentAction()) {
                        Action.UNDRESS -> this.createAnimation("animation.slime.undress", false, event)
                        Action.DRESS -> this.createAnimation("animation.slime.dress", false, event)
                        Action.STRIP -> this.createAnimation("animation.slime.strip", false, event)
                        Action.SUCKBLOWJOB -> this.createAnimation("animation.slime.blowjobsuck", true, event)
                        Action.THRUSTBLOWJOB -> this.createAnimation("animation.slime.blowjobthrust", true, event)
                        Action.CUMBLOWJOB -> this.createAnimation("animation.slime.blowjobcum", false, event)
                        Action.STARTDOGGY -> this.createAnimation("animation.slime.doggygoonbed", false, event)
                        Action.WAITDOGGY -> this.createAnimation("animation.slime.doggywait", true, event)
                        Action.DOGGYSTART -> this.createAnimation("animation.slime.doggystart", false, event)
                        Action.DOGGYSLOW -> this.createAnimation("animation.slime.doggyslow", true, event)
                        Action.DOGGYFAST -> this.createAnimation("animation.slime.doggyfast", true, event)
                        Action.DOGGYCUM -> this.createAnimation("animation.slime.doggycum", false, event)
                        Action.ATTACK -> this.createAnimation("animation.slime.attack" + this.nextAttack, false, event)
                        Action.BOW -> this.createAnimation("animation.slime.bowcharge", false, event)
                        Action.RIDE -> this.createAnimation("animation.slime.ride", true, event)
                        Action.SIT -> this.createAnimation("animation.slime.sit", true, event)
                        else -> {}
                    }
                }
            }
        }

        return PlayState.CONTINUE
    }

    /**
     * CLIENT: registers the controllers plus the sound listener driving the
     * blowjob/doggy scenes; `bjcDone`/`doggyCumDone` ->
     * `resetCameraAndPhysics()`, `undress`/`dress` ->
     * outfit change + reset. 1.12 controller order: action, eyes, movement (kept).
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        if (this.actionController == null) {
            this.initAnimationControllers()
        }

        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            val soundName = sound.keyframeData.sound
            when (soundName) {
                "attackDone" -> if (++this.nextAttack == 3) {
                    this.nextAttack = 0
                }
                "undress" -> if (this.isLocalPlayerNearby()) {
                    this.entityData.set(OUTFIT_INDEX, 0)
                    this.resetCameraAndPhysics()
                }
                "dress" -> if (this.isLocalPlayerNearby()) {
                    this.entityData.set(OUTFIT_INDEX, 1)
                    this.setCurrentAction(null)
                    this.resetCameraAndPhysics()
                }
                "sexUiOn" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
                "bjiMSG10" -> if (this.isControlledByLocalPlayer()) {
                    this.positionPlayerRelative(-0.4, -0.8, -0.2, 60.0f, -3.0f)
                }
                "bjiMSG11" -> {
                    this.playSoundAtVolume(SoundEvents.SLIME_SQUISH, 0.5f)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }
                }
                "bjiMSG12" -> {
                    if (Reference.RANDOM.nextInt(5) == 0) {
                        this.playSoundAtVolume(SoundEvents.SLIME_JUMP, 0.5f)
                    }

                    this.playSoundAtVolume(SoundEvents.SLIME_SQUISH, 0.5f)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }
                }
                "bjtMSG1" -> {
                    this.playSound(SoundEvents.SLIME_BLOCK_HIT)
                    this.playSound(SoundEvents.SLIME_DEATH)
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
                }
                "bjtReady" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    this.resetAnimationControllerOffset()
                }
                "bjcMSG1" -> this.playSound(SoundEvents.SLIME_JUMP)
                "bjcMSG2" -> {
                    this.playSound(SoundEvents.SLIME_JUMP)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.hideHornyMeter()
                    }
                }
                "doggyslowMSG2" -> this.playSound(SoundEvents.SLIME_BLOCK_HIT)
                "bjcBlackScreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "bjcDone", "doggyCumDone" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.resetHornyMeter()
                    this.resetCameraAndPhysics()
                }
                "doggyGoOnBedMSG1" -> {
                    this.playSound(SoundEvents.SLIME_SQUISH)
                    this.cameraYaw = this.yRot
                }
                "doggyGoOnBedDone" -> {
                    PacketHandler.networkWrapper.sendToServer(
                        SetPlayerForGirlPacket(this.getGirlId(), ClientHooks.localPlayerUUID()!!), // java-npe: Minecraft.player
                    )
                    this.setCurrentAction(Action.WAITDOGGY)
                }
                "doggystartMSG1" -> this.playSound(SoundHandler.MISC_TOUCH[0])
                "doggystartMSG2" -> this.playSound(SoundHandler.MISC_TOUCH[1])
                "doggystartMSG3" -> this.playSoundAtVolume(SoundEvents.SLIME_SQUISH, 0.25f)
                "doggystartMSG4" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_SMALLINSERTS), 1.5f)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                    }
                }
                "doggystartMSG5" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.33f)
                    this.playSound(SoundEvents.SLIME_BLOCK_HIT)
                }
                "doggystartDone" -> {
                    this.setCurrentAction(Action.DOGGYSLOW)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.showHornyMeter()
                    }
                }
                "doggyslowMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.33f)
                    var choice = Reference.RANDOM.nextInt(4)
                    if (choice == 0) {
                        choice = Reference.RANDOM.nextInt(2)
                        if (choice == 0) {
                            this.playSound(SoundEvents.SLIME_JUMP)
                        } else {
                            this.playSound(SoundEvents.SLIME_SQUISH)
                        }
                    } else {
                        this.playSound(SoundEvents.SLIME_BLOCK_HIT)
                    }

                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.00666)
                    }
                }
                "doggyfastMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }

                    this.fastMoanCount++
                    if (this.fastMoanCount % 2 == 0) {
                        val choice2 = Reference.RANDOM.nextInt(2)
                        if (choice2 == 0) {
                            this.playSound(SoundEvents.SLIME_JUMP)
                        } else {
                            this.playSound(SoundEvents.SLIME_SQUISH)
                        }
                    } else {
                        this.playSound(SoundEvents.SLIME_BLOCK_HIT)
                    }
                }
                "doggyfastDone" -> this.setCurrentAction(Action.DOGGYSLOW)
                "doggycumMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.MISC_CUMINFLATION[0], 4.0f)
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 2.0f)
                    this.playSound(SoundEvents.SLIME_DEATH)
                }
            }
        }
        val action = this.actionController!! // java-npe: created above
        action.registerSoundListener(soundListener)
        controllers.add(action)
        controllers.add(this.eyesController!!) // java-npe
        controllers.add(this.movementController!!) // java-npe
    }

    /** Client-only helpers (PORTING.md §2.3 rule C). */
    private object Client {
        fun handModel(): IVanillaModel = KoboldModel()
    }

    companion object {
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractPlayerGirlEntity.createAttributes()
    }
}
