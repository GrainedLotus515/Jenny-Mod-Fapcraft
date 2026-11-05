package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.client.model.JennyModel
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
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> Player-form Bia — the catgirl with anal (prepare/wait/start/
 * slow/fast/cum) and prone-doggy (sitdown/sitdownidle/intro/insert/soft/hard/
 * cum) bed scenes plus headpats.
 *
 * <b>Scene flow.</b> [handleActionRequest] starts the bed
 * scenes (`anal` -> [Action.ANAL_PREPARE], `doggy` ->
 * [Action.SITDOWN]); headpats come via owner command. The core waiting
 * mechanic is [handleBiaAnalState] (ticked from
 * [tick]): while in [Action.ANAL_WAIT]/[Action.SITDOWNIDLE]
 * with a player within 1 block, it runs a countdown that MUST start at
 * `ac = 22` (jar-verified) — a deobf regression to `-1` permanently
 * stalled every Bia bed scene. When the countdown expires the scene locks the
 * player in (noClip/noGravity) and either starts [Action.ANAL_START] or
 * positions for the prone doggy ([Action.PRONE_DOGGY_INTRO] + anchor).
 *
 * <b>Pitfalls.</b> `contactCountdown` is the countdown field: `-1` = idle,
 * `22` = first contact. [setCurrentAction] forbids
 * re-entering loop phases while the cum animation plays. The countdown is
 * reset to `-1` in [resetLocalPlayerClientState] so a scene
 * exit re-arms it.
 */
class BiaPlayerEntity : AbstractPlayerGirlEntity {
    @JvmField
    var contactCountdown: Int = -1

    @JvmField
    var flySecondFrame: Boolean = false

    @JvmField
    var hardLoopVariant: Int = 1

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out BiaPlayerEntity>, level: Level) : super(type, level)

    constructor(level: Level, uuid: UUID) : super(ModEntities.PLAYER_BIA.get(), level, uuid)

    override fun getScaleFactor(): Float = 1.5f

    /** 1.12 `getEyeHeight()` = 1.5. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(1.5f)

    override fun handleInteraction() {
    }

    /**
     * Handles the local scene-start requests: `anal` and `doggy`
     * strip Bia and enter the bed-scene intro actions. Returns true when
     * handled so [AbstractPlayerGirlEntity.doAction] does not forward to
     * the server.
     */
    override fun handleActionRequest(actionName: String): Boolean {
        if ("anal" == actionName) {
            this.setCurrentAction(Action.ANAL_PREPARE)
            this.setOutfitIndex(0)
            return true
        } else if ("doggy" == actionName) {
            this.setCurrentAction(Action.SITDOWN)
            this.setOutfitIndex(0)
            return true
        } else {
            return false
        }
    }

    /** CLIENT. */
    override fun openBedsideInteractionMenu() {
        openInventoryGui(ClientHooks.localPlayer()!!, this, arrayOf("anal", "doggy"), false) // java-npe: Minecraft.player
    }

    /**
     * SERVER: owner command `headpat` — teleports the acting player in
     * and plays the headpat animation.
     */
    override fun handleOwnerCommand(command: String, uuid: UUID) {
        if ("action.names.headpat" == command) {
            this.teleportPlayerToGirl(uuid)
            this.setCurrentAction(Action.HEAD_PAT)
            this.sendActionPacket(this.getOutfitIndex(), Action.HEAD_PAT)
        }
    }

    /** CLIENT. */
    override fun getHandModel(index: Int): IVanillaModel? = Client.handModel()

    override fun getHandTexture(index: Int): String = "textures/entity/bia/hand.png"

    override fun getLeftArmAngle(): Float = 35.0f

    override fun getRightArmAngle(): Float = 140.0f

    override fun blocksOutfitToggle(): Boolean = false

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        openInventoryGui(player, this, arrayOf("action.names.headpat"), false)
        return true
    }

    override fun setCurrentAction(action: Action?) {
        if (this.getCurrentAction() !== Action.ANAL_CUM || action !== Action.ANAL_FAST && action !== Action.ANAL_SLOW) {
            if (this.getCurrentAction() !== Action.PRONE_DOGGY_CUM || action !== Action.PRONE_DOGGY_HARD && action !== Action.PRONE_DOGGY_SOFT) {
                super.setCurrentAction(action)
            }
        }
    }

    override fun getNextAction(action: Action): Action? {
        if (action === Action.ANAL_SLOW) {
            return Action.ANAL_FAST
        } else {
            return if (action === Action.PRONE_DOGGY_INTRO) Action.PRONE_DOGGY_INSERT else null
        }
    }

    override fun getCumAction(action: Action): Action? {
        if (action === Action.ANAL_SLOW || action === Action.ANAL_FAST) {
            return Action.ANAL_CUM
        } else {
            return if (action !== Action.PRONE_DOGGY_SOFT && action !== Action.PRONE_DOGGY_HARD) null else Action.PRONE_DOGGY_CUM
        }
    }

    override fun tick() {
        super.tick()
        this.handleBiaAnalState()
    }

    /**
     * CLIENT: on top of the inherited reset, re-arms the anal countdown to
     * `-1` so the next scene restarts from first contact.
     */
    override fun resetLocalPlayerClientState() {
        super.resetLocalPlayerClientState()
        this.contactCountdown = -1
    }

    /** CLIENT. */
    fun isOwnPlayer(player: Player): Boolean = ClientHooks.localPlayerUUID()!! == player.uuid // java-npe: Minecraft.player

    /**
     * BOTH sides, every tick: the bed-scene contact countdown (see class
     * doc — `contactCountdown` must start at 22). On first contact (countdown -1)
     * the client enables interaction and unlocks movement while the server
     * binds the interaction player; when the countdown expires the scene locks
     * the player in and starts [Action.ANAL_START] (anal) or the prone
     * doggy intro (sitdown). SERVER performs the action/position changes; the
     * CLIENT only mirrors the horny-meter/UI side.
     */
    fun handleBiaAnalState() {
        val action = this.getCurrentAction()
        if (action === Action.ANAL_WAIT || action === Action.SITDOWNIDLE) {
            val player = this.getNearestPlayer()
            if (player != null) {
                if (!(player.distanceTo(this) > 1.0f)) {
                    if (!this.level().isClientSide || this.isOwnPlayer(player)) {
                        if (this.contactCountdown == -1) {
                            if (this.level().isClientSide) {
                                BeeScreen.enableInteraction()
                                HandlePlayerMovement.setMovementLock(false)
                            } else {
                                this.setInteractionPlayerUUID(player.uuid)
                            }

                            this.contactCountdown = 22
                        } else if (--this.contactCountdown <= 0) {
                            this.contactCountdown = -1
                            player.noPhysics = true
                            player.setNoGravity(true)
                            if (action === Action.ANAL_WAIT) {
                                if (!this.level().isClientSide) {
                                    this.setCurrentAction(Action.ANAL_START)
                                    val pos = this.getTargetPosition().add(VectorMath.rotateByYaw(-0.3, -1.0, -0.5, this.getYawRotation()))
                                    player.teleportTo(pos.x, pos.y, pos.z)
                                } else if (this.isControlledByLocalPlayer()) {
                                    HornyMeterHud.showHornyMeter()
                                }
                            } else {
                                val yaw = this.getYawRotation()
                                player.yRot = yaw
                                player.xRot = 60.0f
                                if (!this.level().isClientSide) {
                                    this.setOutfitIndex(0)
                                    this.setCurrentAction(Action.PRONE_DOGGY_INTRO)
                                    val targetPos = this.getTargetPosition()
                                    val followPos = targetPos.add(VectorMath.rotateByYaw(0.0, 0.0, 1.0, yaw))
                                    this.setTargetPosition(followPos)
                                    val owner = this.getOwnerPlayer()
                                    owner?.teleportTo(followPos.x, followPos.y, followPos.z)

                                    val playerPos = targetPos.add(VectorMath.rotateByYaw(0.0, 1.1875 - player.eyeHeight, 0.5, yaw))
                                    player.teleportTo(playerPos.x, playerPos.y, playerPos.z)
                                    this.setAnchored(true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * CLIENT: while the prone-doggy hard loop plays, re-rolls the variant
     * suffix (`hardLoopVariant` in 1..3) so consecutive hard loops use different
     * animations.
     */
    override fun resetAnimationControllerTicks() {
        super.resetAnimationControllerTicks()
        if (this.getCurrentAction() === Action.PRONE_DOGGY_HARD) {
            val oldState = this.hardLoopVariant

            do {
                this.hardLoopVariant = this.random.nextInt(3) + 1
            } while (oldState == this.hardLoopVariant)
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        when (event.controller.name) {
            "eyes" -> {
                if (this.getCurrentAction() === Action.NULL && this.getCurrentAction().autoBlink) {
                    this.createAnimation("animation.bia.fhappy", true, event)
                } else {
                    this.createAnimation("animation.bia.null", true, event)
                }
            }
            "movement" -> {
                if (this.getCurrentAction() !== Action.NULL) {
                    this.createAnimation("animation.bia.null", true, event)
                } else if (this.playerRiding) {
                    this.createAnimation("animation.bia.sit", true, event)
                } else {
                    val movement = this.movementController!! // java-npe: created with the controllers
                    if (movement.getCurrentAnimationName()?.contains("fly") == true && this.playerOnGround) {
                        this.flySecondFrame = !this.flySecondFrame
                    }

                    if (!this.playerOnGround) {
                        this.createAnimation("animation.bia.fly" + (if (this.flySecondFrame) "2" else ""), true, event)
                    } else if (Math.abs(this.playerMotionOffset.x) + Math.abs(this.playerMotionOffset.y) > 0.0f) {
                        if (this.playerSprinting) {
                            movement.setAnimationSpeed(1.2)
                            this.createAnimation("animation.bia.run", true, event)
                        } else if (this.playerMotionOffset.y >= -0.1f) {
                            movement.setAnimationSpeed(1.2)
                            this.createAnimation("animation.bia.fastwalk", true, event)
                        } else {
                            movement.setAnimationSpeed(1.2)
                            this.createAnimation("animation.bia.backwards_walk", true, event)
                        }
                    } else {
                        this.createAnimation("animation.bia.idle", true, event)
                    }
                }
            }
            "action" -> when (this.getCurrentAction()) {
                Action.NULL -> this.createAnimation("animation.bia.null", true, event)
                Action.STRIP -> this.createAnimation("animation.bia.strip", false, event)
                Action.ATTACK -> this.createAnimation("animation.bia.attack" + this.nextAttack, false, event)
                Action.BOW -> this.createAnimation("animation.bia.bowcharge", false, event)
                Action.RIDE -> this.createAnimation("animation.bia.ride", true, event)
                Action.SIT -> this.createAnimation("animation.bia.sit", true, event)
                Action.THROW_PEARL -> this.createAnimation("animation.bia.throwpearl", false, event)
                Action.DOWNED -> this.createAnimation("animation.bia.downed", true, event)
                Action.TALK_HORNY -> this.createAnimation("animation.bia.talk_horny", false, event)
                Action.TALK_IDLE -> this.createAnimation("animation.bia.talk_idle", true, event)
                Action.TALK_RESPONSE -> this.createAnimation("animation.bia.talk_response", true, event)
                Action.ANAL_PREPARE -> this.createAnimation("animation.bia.anal_prepare", false, event)
                Action.ANAL_WAIT -> this.createAnimation("animation.bia.anal_wait", true, event)
                Action.ANAL_START -> this.createAnimation("animation.bia.anal_start", true, event)
                Action.ANAL_SLOW -> this.createAnimation("animation.bia.anal_slow", true, event)
                Action.ANAL_FAST -> this.createAnimation("animation.bia.anal_fast", true, event)
                Action.ANAL_CUM -> this.createAnimation("animation.bia.anal_cum", false, event)
                Action.HEAD_PAT -> this.createAnimation("animation.bia.headpat", false, event)
                Action.SITDOWN -> this.createAnimation("animation.bia.sitdown", false, event)
                Action.SITDOWNIDLE -> this.createAnimation("animation.bia.sitdownidle", true, event)
                Action.PRONE_DOGGY_INTRO -> this.createAnimation("animation.bia.prone_doggy_intro", true, event)
                Action.PRONE_DOGGY_INSERT -> this.createAnimation("animation.bia.prone_doggy_insert", true, event)
                Action.PRONE_DOGGY_SOFT -> this.createAnimation("animation.bia.prone_doggy_soft", true, event)
                Action.PRONE_DOGGY_HARD -> this.createAnimation("animation.bia.prone_doggy_hard" + this.hardLoopVariant, true, event)
                Action.PRONE_DOGGY_CUM -> this.createAnimation("animation.bia.prone_doggy_cum", true, event)
                else -> {}
            }
        }

        return PlayState.CONTINUE
    }

    /**
     * CLIENT: registers the controllers and the GeckoLib sound listener that
     * advances the anal/prone-doggy/headpat scenes. Key transitions:
     * `anal_prepareDone` -> [Action.ANAL_WAIT],
     * `anal_startDone`/`anal_fastDone` -> [Action.ANAL_SLOW]
     * (jump keeps the fast loop), `doggySwitch` -> hard variant,
     * `anal_cumDone`/`doggy_cumDone` ->
     * `resetCameraAndPhysics()`. `talk_responseDone` exits the talk
     * scene (`resetGirlState()`) and either strips or dispatches the
     * chosen scene via [startActionFromHandState].
     *
     * Note: 1.12 added the controllers in the order movement, eyes, action (kept).
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        if (this.actionController == null) {
            this.initAnimationControllers()
        }

        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            // Java switch fall-through: anal_fastDone -> anal_startDone
            val analStartDone = {
                this.setCurrentAction(Action.ANAL_SLOW)
                if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
            }
            when (sound.keyframeData.sound) {
                "attackDone" -> if (++this.nextAttack == 3) {
                    this.nextAttack = 0
                }
                "stripMSG1" -> {
                    this.sendGirlChatMessage("Hihi~")
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_BIA_GIGGLE))
                }
                "sexUiOn" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
                "pearl" -> PacketHandler.networkWrapper.sendToServer(SendCompanionHomePacket(this.getGirlId()))
                "talk_hornyMSG1" -> {
                    this.sendChatMessage("Heyaaa~")
                    this.playSound(SoundHandler.GIRLS_BIA_HEY[3])
                }
                "talk_hornyMSG2" -> {
                    this.sendChatMessage("I am Hornyyyyy~")
                    this.playSound(SoundHandler.GIRLS_BIA_GIGGLE[2])
                }
                "talk_hornyMSG3" -> {
                    this.sendChatMessage("So...")
                    this.playSound(SoundHandler.GIRLS_BIA_BREATH[0])
                }
                "talk_hornyMSG4" -> {
                    this.sendChatMessage("Are we gonna have some fun nyaa?")
                    this.playSound(SoundHandler.GIRLS_BIA_HUH[0])
                }
                "talk_responseMSG1" -> {
                    this.sendChatMessage("Huh?!...")
                    this.playSound(SoundHandler.GIRLS_BIA_HUH[2])
                }
                "talk_responseMSG2" -> {
                    this.sendChatMessage("I... uhm...")
                    this.playSound(SoundHandler.GIRLS_BIA_BREATH[1])
                }
                "talk_responseMSG3" -> {
                    this.sendChatMessage("yes~")
                    this.playSound(SoundHandler.GIRLS_BIA_GIGGLE[0])
                }
                "talk_responseDone" -> {
                    this.resetGirlState()
                    if (this.entityData.get(OUTFIT_INDEX) != 0) {
                        this.setCurrentAction(Action.STRIP)
                    } else {
                        this.startActionFromHandState()
                    }
                }
                "anal_prepareMSG1" -> this.playSound(SoundHandler.MISC_PLOB[0])
                "anal_prepareMSG2" -> this.playSound(SoundHandler.MISC_BEDRUSTLE[0])
                "anal_prepareDone" -> {
                    this.setCurrentAction(Action.ANAL_WAIT)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                    }
                }
                "anal_startMSG1" -> {
                    this.playSound(SoundHandler.GIRLS_BIA_MMM[3])
                    this.playSound(SoundHandler.MISC_POUNDING[34])
                }
                "anal_fastMSG1" -> {
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }

                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }

                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.5f)
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_BIA_AHH))
                }
                "anal_slowMSG1", "anal_startMSG2" -> {
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }

                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.5f)
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_BIA_AHH))
                }
                "anal_fastDone" -> if (this.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    analStartDone()
                }
                "anal_startDone" -> analStartDone()
                "anal_cumMSG2" -> this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_BIA_AHH))
                "anal_cumBlackScreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "doggy_cumDone", "anal_cumDone" -> {
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                    }

                    this.resetCameraAndPhysics()
                }
                "headpatMSG1" -> {
                    this.sendChatMessage("Ooh headpats!")
                    this.playSound(SoundHandler.GIRLS_BIA_BREATH[0])
                }
                "headpatMSG2" -> {
                    this.sendChatMessage("Hmmm.... :D")
                    this.playSound(SoundHandler.GIRLS_BIA_MMM[0])
                }
                "headpatMSG3" -> {
                    this.sendChatMessage("huh...?")
                    this.playSound(SoundHandler.GIRLS_BIA_HUH[0])
                }
                "headpatMSG4" -> {
                    this.sendChatMessage("Tanku hehe")
                    this.playSound(SoundHandler.GIRLS_BIA_GIGGLE[1])
                }
                "headpatDone" -> if (this.isLocalPlayerNearby()) {
                    this.resetCameraAndPhysics()
                }
                "sitdownMSG1" -> {
                    this.sendChatMessage("come here big boy~")
                    this.playRandomSound(SoundHandler.GIRLS_BIA_BREATH)
                }
                "sitdownDone" -> this.setCurrentAction(Action.SITDOWNIDLE)
                "slide" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_SLIDE))
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.005)
                    }
                }
                "pound" -> this.playRandomSound(SoundHandler.MISC_POUNDING)
                "doggyMoan" -> {
                    this.playRandomSound(if (this.random.nextBoolean()) SoundHandler.GIRLS_BIA_AHH else SoundHandler.GIRLS_BIA_MMM)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04)
                    }
                }
                "doggySwitch" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    this.setCurrentAction(Action.PRONE_DOGGY_HARD)
                }
                "doggyReset" -> if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                    this.resetAnimationControllerOffset()
                }
                "cum" -> this.playRandomSoundAtVolume(SoundHandler.MISC_INSERTS, 6.0f)
                "orgasm1" -> this.playSound(SoundHandler.GIRLS_BIA_MMM[6])
                "orgasm2" -> this.playSound(SoundHandler.GIRLS_BIA_MMM[7])
                "openSexUI" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
            }
        }
        val action = this.actionController!! // java-npe: initAnimationControllers created it
        action.registerSoundListener(soundListener)
        controllers.add(this.movementController!!) // java-npe
        controllers.add(this.eyesController!!) // java-npe
        controllers.add(action)
    }

    /** Client-only helpers (PORTING.md §2.3 rule C). */
    private object Client {
        fun handModel(): IVanillaModel = JennyModel()
    }

    companion object {
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractPlayerGirlEntity.createAttributes()
    }
}
