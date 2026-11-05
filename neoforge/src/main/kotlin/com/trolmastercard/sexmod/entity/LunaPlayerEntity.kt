package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.client.model.LunaModel
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendCompanionHomePacket
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.VectorMath
import java.util.UUID
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
 * <b>Role.</b> Player-form Luna — the catgirl with touch-boobs
 * (intro/slow/fast/cum), cowgirl-sitting (intro/slow/fast/cum) and headpat
 * scenes. Her [handleLunaOwner] drives the cowgirl sitting scene with
 * a 25-tick approach countdown (`catWaitTicks`) instead of the Bia-style contact
 * countdown.
 *
 * <b>Scene flow.</b> `touchboobs`/`headpat` come in via
 * [handleOwnerCommand]; the interaction menu also offers
 * them. The cowgirl scene starts from [handleInteraction] ->
 * [Action.WAIT_CAT]; [handleLunaOwner] then counts ticks while
 * the nearest player stays within 1.25 blocks — at tick 25 (SERVER) the
 * player is locked in, rotated to face her and [Action.COWGIRL_SITTING_INTRO]
 * starts; the CLIENT mirrors the countdown with
 * [setFishingLevelFor] (unlock movement at 0,
 * third-person at 25).
 *
 * <b>Pitfalls.</b> `catWaitTicks` resets to 0 whenever the action leaves
 * `WAIT_CAT` (see [tick]). [setCurrentAction]
 * forbids re-entering loop phases while the cum animation plays. The
 * movement controller uses a 10-tick transition length.
 */
class LunaPlayerEntity : AbstractPlayerGirlEntity {
    @JvmField
    var catWaitTicks: Int = 0

    @JvmField
    var flySecondFrame: Boolean = false

    @JvmField
    var alternateSlowFrame: Boolean = false

    @JvmField
    var `as`: Boolean = false

    /** Registry factory (used by ModEntities); 1.12 protected `LunaPlayerEntity(World)`. */
    constructor(type: EntityType<out LunaPlayerEntity>, level: Level) : super(type, level)

    constructor(level: Level, uuid: UUID) : super(ModEntities.PLAYER_LUNA.get(), level, uuid)

    override fun getScaleFactor(): Float = 1.6f

    /** 1.12 `getEyeHeight()` = 1.34. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(1.34f)

    /** CLIENT. */
    override fun getHandModel(index: Int): IVanillaModel? = Client.handModel()

    override fun getHandTexture(index: Int): String = "textures/entity/cat/hand.png"

    /**
     * SERVER: owner commands — `touchboobs` strips, broadcasts and starts
     * [Action.TOUCH_BOOBS_INTRO]; `headpat` plays the headpat
     * animation. Both teleport the acting player into the scene.
     */
    override fun handleOwnerCommand(command: String, uuid: UUID) {
        if ("action.names.touchboobs" == command) {
            this.sendActionPacket(0, Action.TOUCH_BOOBS_INTRO)
            this.setCurrentAction(Action.TOUCH_BOOBS_INTRO)
            this.entityData.set(OUTFIT_INDEX, 0)
            this.teleportPlayerToGirl(uuid)
        }

        if ("action.names.headpat" == command) {
            this.setCurrentAction(Action.HEAD_PAT)
            this.teleportPlayerToGirl(uuid)
        }
    }

    /**
     * CLIENT: starts the cowgirl-sitting approach ([Action.WAIT_CAT]).
     */
    override fun handleInteraction() {
        this.setCurrentAction(Action.WAIT_CAT)
    }

    override fun canBeInteracted(): Boolean = true

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        openInventoryGui(player, this, arrayOf("action.names.touchboobs", "action.names.headpat"), false)
        return true
    }

    override fun setCurrentAction(action: Action?) {
        if (this.getCurrentAction() !== Action.COWGIRL_SITTING_CUM || action !== Action.COWGIRL_SITTING_SLOW && action !== Action.COWGIRL_SITTING_FAST) {
            if (this.getCurrentAction() !== Action.TOUCH_BOOBS_CUM || action !== Action.TOUCH_BOOBS_FAST && action !== Action.TOUCH_BOOBS_SLOW) {
                super.setCurrentAction(action)
            }
        }
    }

    /**
     * BOTH sides: while in [Action.WAIT_CAT] runs the 25-tick sitting
     * countdown (`catWaitTicks`); any other action resets the counter to 0.
     * SERVER starts [Action.COWGIRL_SITTING_INTRO] at tick 25; CLIENT
     * mirrors the UI unlock at tick 0 and third-person switch at tick 25 via
     * [setFishingLevelFor].
     */
    override fun tick() {
        super.tick()
        if (Action.WAIT_CAT === this.getCurrentAction()) {
            this.handleLunaOwner()
        } else {
            this.catWaitTicks = 0
        }
    }

    /**
     * The cowgirl sitting countdown body (see [tick]). SERVER:
     * at tick 25 binds the interaction player, locks their movement, rotates
     * them to face the girl and starts the intro. CLIENT: unlocks movement at
     * tick 0 and forces third-person at tick 25.
     */
    fun handleLunaOwner() {
        val player = this.getNearestPlayer()
        if (player != null) {
            if (!(Math.sqrt(player.distanceToSqr(this.x, this.getPositionVec3d().y, this.z)) > 1.25)) {
                if (this.level().isClientSide) {
                    this.setFishingLevelFor(player, this.catWaitTicks)
                } else if (this.catWaitTicks == 25) {
                    this.setInteractionPlayerUUID(player.uuid)
                    player.moveRelative(0.0f, Vec3.ZERO)
                    player.teleportTo(this.position().x, this.getPositionVec3d().y, this.position().z)
                    this.setCurrentAction(Action.COWGIRL_SITTING_INTRO)
                    player.yHeadRot = this.getYawRotation() + 180.0f
                    player.yRot = this.getYawRotation() + 180.0f
                    player.yRotO = this.getYawRotation() + 180.0f
                    this.cameraYaw = this.getYawRotation() + 180.0f
                    this.positionPlayerRelative(0.0, (-0.075f).toDouble(), -0.7109375, 0.0f, 0.0f)
                    this.entityData.set(OUTFIT_INDEX, 0)
                }

                this.catWaitTicks++
            }
        }
    }

    /**
     * CLIENT: per-tick mirror of the sitting countdown for the local player —
     * at tick 0 enables interaction and unlocks movement, at tick 25 switches
     * to third-person view.
     */
    fun setFishingLevelFor(player: Player, level: Int) {
        if (level == 0) {
            val localPlayer = ClientHooks.localPlayer()!! // java-npe: Minecraft.player
            if (player.uuid == localPlayer.uuid) {
                BeeScreen.enableInteraction()
                localPlayer.setDeltaMovement(0.0, 0.0, 0.0)
                HandlePlayerMovement.setMovementLock(false)
            }
        }

        if (level == 25) {
            val localPlayer = ClientHooks.localPlayer()!! // java-npe: Minecraft.player
            if (player.uuid == localPlayer.uuid) {
                ClientHooks.setThirdPersonView(2)
            }
        }
    }

    override fun getNextAction(action: Action): Action? {
        if (action === Action.TOUCH_BOOBS_SLOW) {
            return Action.TOUCH_BOOBS_FAST
        } else {
            return if (action === Action.COWGIRL_SITTING_SLOW) Action.COWGIRL_SITTING_FAST else null
        }
    }

    override fun getCumAction(action: Action): Action? {
        if (action === Action.TOUCH_BOOBS_SLOW || action === Action.TOUCH_BOOBS_FAST) {
            return Action.TOUCH_BOOBS_CUM
        } else {
            return if (action !== Action.COWGIRL_SITTING_FAST && action !== Action.COWGIRL_SITTING_SLOW) null else Action.COWGIRL_SITTING_CUM
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        when (event.controller.name) {
            "eyes" -> {
                if (this.getCurrentAction() === Action.NULL && this.getCurrentAction().autoBlink) {
                    this.createAnimation("animation.cat.blink", true, event)
                } else {
                    this.createAnimation("animation.cat.null", true, event)
                }
            }
            "movement" -> {
                if (this.getCurrentAction() !== Action.NULL) {
                    this.createAnimation("animation.cat.null", true, event)
                } else if (this.playerRiding) {
                    this.createAnimation("animation.cat.sit", true, event)
                } else {
                    val movement = this.movementController!! // java-npe: created with the controllers
                    if (movement.getCurrentAnimationName()?.contains("fly") == true && this.playerOnGround) {
                        this.flySecondFrame = !this.flySecondFrame
                    }

                    if (!this.playerOnGround) {
                        this.createAnimation("animation.cat.fly" + (if (this.flySecondFrame) "2" else ""), true, event)
                    } else if (Math.abs(this.playerMotionOffset.x) + Math.abs(this.playerMotionOffset.y) > 0.0f) {
                        if (this.playerSprinting) {
                            movement.setAnimationSpeed(1.5)
                            this.createAnimation("animation.cat.run", true, event)
                        } else if (this.playerMotionOffset.y >= -0.1f) {
                            movement.setAnimationSpeed(2.0)
                            this.createAnimation("animation.cat.fastwalk", true, event)
                        } else {
                            movement.setAnimationSpeed(2.0)
                            this.createAnimation("animation.cat.backwards_walk", true, event)
                        }
                    } else {
                        this.createAnimation("animation.cat.idle", true, event)
                    }
                }
            }
            "action" -> when (this.getCurrentAction()) {
                Action.NULL -> this.createAnimation("animation.cat.null", true, event)
                Action.ATTACK -> this.createAnimation("animation.cat.attack" + this.nextAttack, false, event)
                Action.RIDE, Action.SIT -> this.createAnimation("animation.cat.sit", true, event)
                Action.BOW -> this.createAnimation("animation.cat.bowcharge", false, event)
                Action.THROW_PEARL -> this.createAnimation("animation.cat.throwpearl", true, event)
                Action.DOWNED -> this.createAnimation("animation.cat.downed", true, event)
                Action.FISHING_START -> this.createAnimation("animation.cat.start_fishing", false, event)
                Action.FISHING_IDLE -> this.createAnimation("animation.cat.idle_fishing", true, event)
                Action.FISHING_EAT -> this.createAnimation("animation.cat.eat_fishing", false, event)
                Action.FISHING_THROW_AWAY -> this.createAnimation("animation.cat.throw_away", false, event)
                Action.PAYMENT -> this.createAnimation("animation.cat.payment", false, event)
                Action.TOUCH_BOOBS_INTRO -> this.createAnimation("animation.cat.touch_boobs_intro", false, event)
                Action.TOUCH_BOOBS_SLOW -> this.createAnimation("animation.cat.touch_boobs_slow" + (if (this.alternateSlowFrame) "1" else ""), true, event)
                Action.TOUCH_BOOBS_FAST -> this.createAnimation("animation.cat.touch_boobs_fast", true, event)
                Action.TOUCH_BOOBS_CUM -> this.createAnimation("animation.cat.touch_boobs_cum", false, event)
                Action.WAIT_CAT -> this.createAnimation("animation.cat.wait", false, event)
                Action.COWGIRL_SITTING_INTRO -> this.createAnimation("animation.cat.sitting_intro", false, event)
                Action.COWGIRL_SITTING_SLOW -> this.createAnimation("animation.cat.sitting_slow", true, event)
                Action.COWGIRL_SITTING_FAST -> this.createAnimation("animation.cat.sitting_fast", true, event)
                Action.COWGIRL_SITTING_CUM -> this.createAnimation("animation.cat.sitting_cum", true, event)
                Action.HEAD_PAT -> this.createAnimation("animation.cat.head_pat", true, event)
                else -> {}
            }
        }

        return PlayState.CONTINUE
    }

    /**
     * CLIENT: registers the controllers plus the sound listener that drives
     * the touch-boobs/sitting/headpat scenes. Key transitions:
     * `touch_boobs_introDone` -> [Action.TOUCH_BOOBS_SLOW],
     * `sitting_introDone` -> [Action.COWGIRL_SITTING_SLOW],
     * `fastDone`/`sitting_fastDone` -> slow (jump keeps fast),
     * `touch_boobs_cumDone`/`resetGirl` ->
     * `resetCameraAndPhysics()`, `paymentDone` -> [startActionFromHandState].
     * `sitting_fastTp`/`sitting_fastDone` reposition the local
     * player relative to the girl's target position.
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
                "idleDone" -> this.`as` = this.random.nextInt(10) == 0
                "idle2Done" -> this.`as` = false
                "pearl" -> PacketHandler.networkWrapper.sendToServer(SendCompanionHomePacket(this.getGirlId()))
                "paymentMSG1" -> {
                    val interactionUuid = this.getInteractionPlayerUUID()
                    if (interactionUuid != null) {
                        this.sendChatMessageToPlayer(interactionUuid, "Here, I know u like fish and yea.. these are for you")
                    } else {
                        // 1.12 looked up a null UUID: no player found, only the warning
                        Main.LOGGER.warn("Player with UUID null not found")
                    }
                    this.playSound(SoundHandler.MISC_PLOB[0])
                }
                "paymentMSG2" -> {
                    this.sendChatMessage("huh~?")
                    this.playRandomSound(SoundHandler.GIRLS_LUNA_HUH)
                }
                "paymentMSG3" -> {
                    this.sendChatMessage("nyyyaaaa~ :D")
                    val soundIds = intArrayOf(1, 7, 10, 11)
                    val soundId = soundIds[this.random.nextInt(soundIds.size)]
                    this.playSound(SoundHandler.GIRLS_LUNA_CUTENYA[soundId])
                }
                "paymentMSG4" -> {
                    this.sendChatMessage("tankuuuu owowowo")
                    this.playRandomSound(SoundHandler.GIRLS_LUNA_OWO)
                }
                "paymentDone" -> {
                    if (this.isLocalPlayerNearby()) {
                        this.startActionFromHandState()
                    }

                    this.scaleFactor = 1.0f
                }
                "breath", "rod_breath" -> this.playRandomSound(SoundHandler.GIRLS_LUNA_LIGHTBREATHING)
                "happyOh" -> this.playRandomSound(SoundHandler.GIRLS_LUNA_HAPPYOH)
                "cutenya3" -> this.playSound(SoundHandler.GIRLS_LUNA_CUTENYA[3])
                "cutenya2" -> this.playSound(SoundHandler.GIRLS_LUNA_CUTENYA[2])
                "huh" -> this.playRandomSound(SoundHandler.GIRLS_LUNA_HUH)
                "hmph" -> this.playRandomSound(SoundHandler.GIRLS_LUNA_HMPH)
                "hehe", "giggle" -> this.playRandomSound(SoundHandler.GIRLS_LUNA_GIGGLE)
                "singing" -> this.playRandomSound(SoundHandler.GIRLS_LUNA_SINGING)
                "touch_boobsMSG1" -> {
                    this.sendChatMessage("comon~ touch me hihi~")
                    this.playRandomSound(SoundHandler.GIRLS_LUNA_GIGGLE)
                }
                "touch" -> this.playRandomSound(SoundHandler.MISC_TOUCH)
                "jump" -> this.playSoundAtVolume(SoundHandler.MISC_JUMP[0], 0.2f)
                "horninya" -> this.playRandomSound(SoundHandler.GIRLS_LUNA_HORNINYA)
                "horninya2", "touch_boobs_cumMSG3", "sitting_cumMSG1" -> {
                    this.playSound(SoundHandler.GIRLS_LUNA_HORNINYA[1])
                    this.playSoundAtVolume(SoundHandler.MISC_CUMINFLATION[0], 5.0f)
                }
                "moan" -> this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_LUNA_MOAN))
                "touch_boobs_introDone" -> {
                    this.setCurrentAction(Action.TOUCH_BOOBS_SLOW)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                        HornyMeterHud.showHornyMeter()
                        HandlePlayerMovement.setMovementLock(false)
                    }
                }
                "touch_boobs_slowDone" -> {
                    if (this.alternateSlowFrame) {
                        this.alternateSlowFrame = false
                    } else {
                        this.alternateSlowFrame = Math.random() < 0.5
                    }
                }
                "addCumSlow" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.addToHornyMeter(0.02f.toDouble())
                }
                "addCumFast" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.addToHornyMeter(0.04f.toDouble())
                }
                "fastDone" -> if (this.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    this.setCurrentAction(Action.TOUCH_BOOBS_SLOW)
                }
                "moanOrNya" -> {
                    if (Math.random() > 0.5) {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_LUNA_MOAN))
                    } else {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_LUNA_HORNINYA))
                    }
                }
                "blackScreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "touch_boobs_cumDone" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.resetHornyMeter()
                    this.resetCameraAndPhysics()
                }
                "resetGirl" -> if (this.isControlledByLocalPlayer()) {
                    this.resetCameraAndPhysics()
                }
                "touch_boobs_cumMSG1" -> this.playSound(SoundHandler.GIRLS_LUNA_HORNINYA[3])
                "touch_boobs_cumMSG2" -> this.playSound(SoundHandler.GIRLS_LUNA_HORNINYA[9])
                "call_playerMSG1" -> {
                    this.playRandomSound(SoundHandler.GIRLS_LUNA_GIGGLE)
                    this.sendChatMessage("come here - big guy hehe~")
                }
                "pounding" -> this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                "sitting_introMSG1" -> {
                    this.playRandomSound(SoundHandler.GIRLS_LUNA_GIGGLE)
                    this.sendChatMessage("hehe~")
                }
                "sitting_introDone" -> if (this.isControlledByLocalPlayer()) {
                    this.setCurrentAction(Action.COWGIRL_SITTING_SLOW)
                    HornyMeterHud.resetHornyMeter()
                    HornyMeterHud.showHornyMeter()
                }
                "sitting_slowMSG1" -> {
                    // 1.12: the inner `break` skips the horny-meter add for the horninya branch
                    var addMeter = true
                    if (this.random.nextBoolean()) {
                        if (this.random.nextBoolean()) {
                            this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_LUNA_HORNINYA))
                            addMeter = false
                        } else {
                            this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_LUNA_MOAN))
                        }
                    } else {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_LUNA_LIGHTBREATHING))
                    }

                    if (addMeter && this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }
                }
                "sitting_fastMSG1" -> {
                    if (this.random.nextBoolean()) {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_LUNA_HORNINYA))
                    } else {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_LUNA_MOAN))
                    }

                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04)
                    }
                }
                "sitting_fastDone" -> if (this.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    this.setCurrentAction(Action.COWGIRL_SITTING_SLOW)
                    val headOffset = Vec3(0.0, (-0.075f).toDouble(), -0.7109375)
                    val rotatedHead = VectorMath.rotateByYaw(headOffset, this.getYawRotation() + 180.0f)
                    ClientHooks.localPlayer()!!.setPos( // java-npe: Minecraft.player
                        this.getTargetPosition().x + rotatedHead.x,
                        this.getTargetPosition().y - 0.0 + rotatedHead.y,
                        this.getTargetPosition().z + rotatedHead.z,
                    )
                }
                "sitting_fastTp" -> if (this.isControlledByLocalPlayer()) {
                    val backOffset = Vec3(0.0, -0.160625, -0.9925)
                    val rotatedBack = VectorMath.rotateByYaw(backOffset, this.getYawRotation() + 180.0f)
                    ClientHooks.localPlayer()!!.setPos( // java-npe: Minecraft.player
                        this.getTargetPosition().x + rotatedBack.x,
                        this.getTargetPosition().y - 0.0 + rotatedBack.y,
                        this.getTargetPosition().z + rotatedBack.z,
                    )
                }
                "headpatMSG1" -> {
                    this.sendChatMessage("huh?~")
                    this.playRandomSound(SoundHandler.GIRLS_LUNA_HUH)
                }
                "headpatMSG2" -> this.playRandomSound(SoundHandler.GIRLS_LUNA_MMM)
                "headpatMSG3" -> {
                    this.sendChatMessage("nya~")
                    this.playSound(SoundHandler.GIRLS_LUNA_HORNINYA[0])
                }
            }
        }
        this.movementController!!.setTransitionLengthTicks(10.0) // java-npe: created above
        this.registerControllers(controllers, soundListener)
    }

    /** Client-only helpers (PORTING.md §2.3 rule C). */
    private object Client {
        fun handModel(): IVanillaModel = LunaModel()
    }

    companion object {
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractPlayerGirlEntity.createAttributes()
    }
}
