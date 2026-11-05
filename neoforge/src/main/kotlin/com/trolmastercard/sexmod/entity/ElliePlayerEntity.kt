package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.client.model.EllieModel
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import com.trolmastercard.sexmod.networking.ChangeDataParameterPacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendCompanionHomePacket
import com.trolmastercard.sexmod.networking.SendGirlToSexPacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.networking.SexPromptPacket
import com.trolmastercard.sexmod.networking.TeleportPlayerPacket
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.Reference
import com.trolmastercard.sexmod.util.SoundHandler
import java.util.UUID
import net.minecraft.client.resources.language.I18n
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> Player-form Ellie — the transformation with the same scene set
 * as the NPC (carry "Face fuck", cowgirl, missionary) plus the sit-down
 * interaction flow.
 *
 * <b>Scene flow.</b> `Face fuck` starts the carry via owner command;
 * cowgirl/missionary are chosen in [doAction] which
 * stores the choice in `GIRL_HAND_STATES` (`animationFollowUp`
 * packet). [updateAITasks] then waits in
 * [Action.SITDOWNIDLE] until the nearest player is within 1 block,
 * locks both players in and starts [Action.MISSIONARY_START] or
 * [Action.COWGIRLSTART] (rotating/positioning the player).
 *
 * <b>Pitfalls.</b> [setCurrentAction] forbids re-entering loop
 * phases while the cum animation plays. `hasNoOwner()` (ceiling check
 * over the owner's head) selects the crouched animations and the eye height.
 *
 * Port note: the dynamic 1.12 `getEyeHeight()` is cached in [eyeCrouched] and
 * applied through [getDefaultDimensions] + `refreshDimensions()` on change.
 */
class ElliePlayerEntity : AbstractPlayerGirlEntity {
    @JvmField
    var flySecondFrame: Boolean = false

    @JvmField
    var moanToggle: Boolean = false

    @JvmField
    var carryVariant: Int = 1

    /** Port: last [hasNoOwner] value baked into the eye height. */
    @JvmField
    var eyeCrouched: Boolean = false

    /** Registry factory (used by ModEntities); 1.12 protected `ElliePlayerEntity(World)`. */
    constructor(type: EntityType<out ElliePlayerEntity>, level: Level) : super(type, level)

    constructor(level: Level, uuid: UUID) : super(ModEntities.PLAYER_ELLIE.get(), level, uuid)

    override fun getScaleFactor(): Float = 2.05f

    /** 1.12 `getEyeHeight()`: 1.53 while the owner is under a ceiling, else 1.9. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions =
        super.getDefaultDimensions(pose).withEyeHeight(if (this.eyeCrouched) 1.53f else 1.9f)

    override fun tick() {
        super.tick()
        val crouched = this.hasNoOwner()
        if (crouched != this.eyeCrouched) {
            this.eyeCrouched = crouched
            this.refreshDimensions()
        }
    }

    override fun handleInteraction() {
        this.setCurrentAction(Action.SITDOWN)
    }

    /**
     * SERVER: owner command `Face fuck` — teleports the acting player in
     * and starts the carry intro, broadcasting it to tracking players.
     */
    override fun handleOwnerCommand(command: String, uuid: UUID) {
        if ("Face fuck" == command) {
            this.teleportPlayerToGirl(uuid)
            this.setCurrentAction(Action.CARRY_INTRO)
            this.sendActionPacket(this.getOutfitIndex(), Action.CARRY_INTRO)
        }
    }

    /** CLIENT. */
    override fun getHandModel(index: Int): IVanillaModel? = Client.handModel()

    override fun getHandTexture(index: Int): String =
        if (index == 0) "textures/entity/ellie/hand_nude.png" else "textures/entity/ellie/hand.png"

    override fun canOpenInteractionMenu(): Boolean = true

    /**
     * CLIENT: scene chooser — stores the cowgirl/missionary choice in
     * `GIRL_HAND_STATES` via the `animationFollowUp` packet (the
     * server-side [updateAITasks] consumes it), and forwards any other
     * action to the server as a [SexPromptPacket].
     */
    override fun doAction(action: String, uuid: UUID) {
        if ("action.names.cowgirl" == action) {
            this.changeDataParameterFromClient("animationFollowUp", "Cowgirl")
        } else if ("action.names.missionary" == action) {
            this.changeDataParameterFromClient("animationFollowUp", "Missionary")
        } else if (this.entityData.get(OWNER_UUID).isPresent) {
            PacketHandler.networkWrapper.sendToServer(
                SexPromptPacket(action, uuid, this.entityData.get(OWNER_UUID).get(), this.firstInteraction),
            )
            this.firstInteraction = true
        }
    }

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        openInventoryGui(player, this, arrayOf("Face fuck"), false)
        return true
    }

    /** CLIENT. */
    fun openEllieInventory(player: Player) {
        openInventoryGui(player, this, arrayOf("action.names.cowgirl", "action.names.missionary"), false)
    }

    override fun blocksOutfitToggle(): Boolean = false

    override fun setCurrentAction(action: Action?) {
        val currentAction = this.getCurrentAction()
        if (currentAction !== Action.MISSIONARY_CUM || action !== Action.MISSIONARY_FAST && action !== Action.MISSIONARY_SLOW) {
            if (currentAction !== Action.COWGIRLCUM || action !== Action.COWGIRLSLOW && action !== Action.COWGIRLFAST) {
                super.setCurrentAction(action)
            }
        }
    }

    override fun getNextAction(action: Action): Action? {
        if (action === Action.COWGIRLSLOW) {
            return Action.COWGIRLFAST
        } else if (action === Action.MISSIONARY_SLOW) {
            return Action.MISSIONARY_FAST
        } else {
            return if (action === Action.CARRY_SLOW) Action.CARRY_FAST else null
        }
    }

    override fun getCumAction(action: Action): Action? {
        if (action === Action.COWGIRLFAST || action === Action.COWGIRLSLOW) {
            return Action.COWGIRLCUM
        } else if (action === Action.MISSIONARY_FAST || action === Action.MISSIONARY_SLOW) {
            return Action.MISSIONARY_CUM
        } else {
            return if (action !== Action.CARRY_SLOW && action !== Action.CARRY_FAST) null else Action.CARRY_CUM
        }
    }

    /**
     * SERVER (and CLIENT mirror): waits in [Action.SITDOWNIDLE] for a
     * scene choice (`GIRL_HAND_STATES` = "Missionary"/"Cowgirl") AND a
     * nearby player within 1 block, then locks both players into the scene
     * (movement lock, noClip, noGravity, flying) and starts the chosen intro
     * action with the player positioned and rotated to match.
     */
    override fun updateAITasks() {
        super.updateAITasks()
        if (this.getCurrentAction() === Action.SITDOWNIDLE) {
            val handState = this.entityData.get(GIRL_HAND_STATES)
            if ("Missionary" != handState && "Cowgirl" != handState) {
                return
            }

            val player = this.getNearestPlayer()
            if (player == null ||
                Math.sqrt(player.distanceToSqr(this.getPositionVec3d().x, this.getPositionVec3d().y, this.getPositionVec3d().z)) > 1.0
            ) {
                return
            }

            this.entityData.set(GIRL_HAND_STATES, "")
            this.entityData.set(OUTFIT_INDEX, 0)
            this.setInteractionPlayerUUID(player.uuid)
            val ownerOpt = this.entityData.get(OWNER_UUID)
            val playerMP = if (ownerOpt.isPresent) this.level().getPlayerByUUID(ownerOpt.get()) as ServerPlayer? else null
            PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), player as ServerPlayer)
            if (playerMP != null) {
                PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), playerMP)
                playerMP.abilities.flying = true
            }

            player.moveRelative(0.0f, Vec3.ZERO)
            player.abilities.flying = true
            playerMP!!.noPhysics = true // java-npe: 1.12 dereferenced the owner unconditionally
            player.noPhysics = true
            playerMP.setNoGravity(true)
            player.setNoGravity(true)
            if ("Missionary" == handState) {
                this.setCurrentAction(Action.MISSIONARY_START)
                val pos = this.getPositionVec3d().subtract(0.0, 0.1, 0.0)
                player.absMoveTo(pos.x, pos.y, pos.z, this.getYawRotation(), 60.0f)
                player.teleportTo(pos.x, pos.y, pos.z)
            } else {
                this.setCurrentAction(Action.COWGIRLSTART)
                val pos2 = this.getPositionVec3d().add(
                    Vec3(
                        -Math.sin(this.getYawRotation() * (Math.PI / 180.0)) * 1.8,
                        -0.65,
                        Math.cos(this.getYawRotation() * (Math.PI / 180.0)) * 1.8,
                    ),
                )
                player.absMoveTo(pos2.x, pos2.y, pos2.z, 180.0f + this.getYawRotation(), -30.0f)
                player.teleportTo(pos2.x, pos2.y, pos2.z)
            }
        }
    }

    fun hasNoOwner(): Boolean {
        val player = this.getOwnerPlayer()
        return if (player == null) false else this.level().getBlockState(player.blockPosition().above().above()).block !== Blocks.AIR
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        when (event.controller.name) {
            "eyes" -> {
                if (this.getCurrentAction() === Action.NULL && this.getCurrentAction().autoBlink) {
                    this.createAnimation("animation.ellie.eyes", true, event)
                } else {
                    this.createAnimation("animation.ellie.null", true, event)
                }
            }
            "movement" -> {
                if (this.getCurrentAction() !== Action.NULL) {
                    this.createAnimation("animation.ellie.null", true, event)
                } else if (this.playerRiding) {
                    this.createAnimation("animation.ellie.ride", true, event)
                } else {
                    val movement = this.movementController!! // java-npe: created with the controllers
                    if (movement.getCurrentAnimationName()?.contains("fly") == true && this.playerOnGround) {
                        this.flySecondFrame = !this.flySecondFrame
                    }

                    if (!this.playerOnGround) {
                        this.createAnimation("animation.ellie.fly" + (if (this.flySecondFrame) "2" else ""), true, event)
                    } else if (Math.abs(this.playerMotionOffset.x) + Math.abs(this.playerMotionOffset.y) > 0.0f) {
                        if (this.playerSprinting) {
                            movement.setAnimationSpeed(1.5)
                            this.createAnimation(if (this.hasNoOwner()) "animation.ellie.crouchwalk" else "animation.ellie.run", true, event)
                        } else if (this.playerMotionOffset.y >= -0.1f) {
                            movement.setAnimationSpeed(2.0)
                            this.createAnimation(if (this.hasNoOwner()) "animation.ellie.crouchwalk" else "animation.ellie.fastwalk", true, event)
                        } else {
                            movement.setAnimationSpeed(1.5)
                            this.createAnimation(if (this.hasNoOwner()) "animation.ellie.crouchwalk" else "animation.ellie.backwards_walk", true, event)
                        }
                    } else {
                        this.createAnimation(if (this.hasNoOwner()) "animation.ellie.crouchidle" else "animation.ellie.idle", true, event)
                    }
                }
            }
            "action" -> when (this.getCurrentAction()) {
                Action.NULL -> this.createAnimation("animation.ellie.null", true, event)
                Action.STRIP -> this.createAnimation("animation.ellie.strip", false, event)
                Action.DASH -> this.createAnimation("animation.ellie.dash", false, event)
                Action.HUG -> this.createAnimation("animation.ellie.hug", false, event)
                Action.HUGIDLE -> this.createAnimation("animation.ellie.hugidle", true, event)
                Action.HUGSELECTED -> this.createAnimation("animation.ellie.hugselected", false, event)
                Action.SITDOWN -> this.createAnimation("animation.ellie.sitdown", false, event)
                Action.SITDOWNIDLE -> this.createAnimation("animation.ellie.sitdownidle", true, event)
                Action.COWGIRLSTART -> this.createAnimation("animation.ellie.cowgirlstart", false, event)
                Action.COWGIRLSLOW -> this.createAnimation("animation.ellie.cowgirlslow2", true, event)
                Action.COWGIRLFAST -> this.createAnimation("animation.ellie.cowgirlfast", true, event)
                Action.COWGIRLCUM -> this.createAnimation("animation.ellie.cowgirlcum", true, event)
                Action.ATTACK -> this.createAnimation("animation.ellie.attack" + this.nextAttack, false, event)
                Action.BOW -> this.createAnimation("animation.ellie.bowcharge", false, event)
                Action.RIDE -> this.createAnimation("animation.ellie.ride", true, event)
                Action.SIT -> this.createAnimation("animation.ellie.sit", true, event)
                Action.THROW_PEARL -> this.createAnimation("animation.ellie.throwpearl", false, event)
                Action.DOWNED -> this.createAnimation("animation.ellie.downed", true, event)
                Action.MISSIONARY_START -> this.createAnimation("animation.ellie.missionary_start", false, event)
                Action.MISSIONARY_SLOW -> this.createAnimation("animation.ellie.missionary_slow", true, event)
                Action.MISSIONARY_FAST -> this.createAnimation("animation.ellie.missionary_fast", true, event)
                Action.MISSIONARY_CUM -> this.createAnimation("animation.ellie.missionary_cum", false, event)
                Action.CARRY_INTRO -> this.createAnimation("animation.ellie.carry_intro", false, event)
                Action.CARRY_SLOW -> this.createAnimation("animation.ellie.carry_slow" + this.carryVariant, true, event)
                Action.CARRY_FAST -> this.createAnimation("animation.ellie.carry_fast", true, event)
                Action.CARRY_CUM -> this.createAnimation("animation.ellie.carry_cum", true, event)
                else -> {}
            }
        }

        return PlayState.CONTINUE
    }

    /**
     * CLIENT: registers the controllers plus the sound listener driving the
     * carry/cowgirl/missionary scenes. Key transitions:
     * `sitdownDone` -> [Action.SITDOWNIDLE] + scene menu,
     * `hugDone` -> [Action.HUGIDLE] + menu,
     * `cowgirlStartDone`/`missionary_startDone` -> slow loops,
     * `cowgirlfastReady`/`missionary_fastDone` gate fast on jump,
     * `carry_slowDone` re-rolls the variant suffix (`carryVariant`),
     * `missionary_cumDone`/`cowgirlcumDone`/`carry_cumDone`
     * -> `resetCameraAndPhysics()`. `hugselectedDone` targets
     * the girl's position via `targetPos` packet and sends
     * [SendGirlToSexPacket].
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        if (this.actionController == null) {
            this.initAnimationControllers()
        }

        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            when (sound.keyframeData.sound) {
                "dashMSG1" -> {
                    val closestPlayer = this.level().getNearestPlayer(this, 15.0)
                    if (closestPlayer != null) {
                        val delta = this.position().subtract(closestPlayer.position())
                        val yaw = Math.atan2(delta.z, delta.x).toFloat() * (180.0 / Math.PI).toFloat()
                        this.yRot = yaw
                        this.yHeadRot = yaw
                        this.yBodyRot = yaw
                    }
                }
                "dashReady" -> {
                    // 1.12: `if (isLocalPlayerNearby()) return;` with nothing after it
                }
                "dashDone" -> {
                    this.setCurrentAction(Action.HUG)
                    val player = this.level().getNearestPlayer(this, 15.0)
                    if (player != null) {
                        val yaw = player.yRot
                        this.yRot = yaw
                        this.yHeadRot = yaw
                        this.yBodyRot = yaw
                    }
                }
                "hugMSG1" -> {
                    val localPlayer = ClientHooks.localPlayer()!! // java-npe: Minecraft.player
                    if (localPlayer.uuid == this.getInteractionPlayerUUID()) {
                        PacketHandler.networkWrapper.sendToServer(
                            TeleportPlayerPacket(localPlayer.uuid.toString(), localPlayer.position(), localPlayer.yRot - 80.0f, localPlayer.xRot),
                        )
                    }
                }
                "hugMSG2" -> {
                    this.sendGirlChatMessage("Hmm...")
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_HMPH[3], 3.0f)
                }
                "hugMSG3" -> {
                    this.sendGirlChatMessage("Hey!")
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_AHH[2], 3.0f)
                }
                "hugMSG4" -> {
                    this.sendGirlChatMessage(I18n.get("ellie.dialogue.mommyhorny"))
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_GIGGLE[0], 3.0f)
                }
                "hugMSG5" -> {
                    this.sendGirlChatMessage(I18n.get("ellie.dialogue.whattodo"))
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_HUH[1], 3.0f)
                }
                "hugDone" -> {
                    val hugPlayer = ClientHooks.localPlayer()!! // java-npe: Minecraft.player
                    if (hugPlayer.uuid == this.getInteractionPlayerUUID()) {
                        this.setCurrentAction(Action.HUGIDLE)
                        this.openEllieInventory(hugPlayer)
                    }
                }
                "hugselectedMSG1" -> {
                    this.sendGirlChatMessage(I18n.get("ellie.dialogue.iknow"))
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_MMM[0], 3.0f)
                }
                "hugselectedMSG2" -> {
                    this.sendGirlChatMessage(I18n.get("ellie.dialogue.followmedarling"))
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_GIGGLE[3], 3.0f)
                }
                "hugselectedDone" -> if (this.isLocalPlayerNearby()) {
                    var pos = this.position()
                    pos = pos.add(
                        -Math.sin((this.yRot + 90.0f) * (Math.PI / 180.0)) * -0.7803125f,
                        0.0,
                        Math.cos((this.yRot + 90.0f) * (Math.PI / 180.0)) * -0.7803125f,
                    )
                    pos = pos.add(
                        -Math.sin(this.yRot * (Math.PI / 180.0)) * 0.5296875f,
                        0.0,
                        Math.cos(this.yRot * (Math.PI / 180.0)) * 0.5296875f,
                    )
                    val posStr = pos.x.toString() + "f" + pos.y + "f" + pos.z + "f"
                    PacketHandler.networkWrapper.sendToServer(ChangeDataParameterPacket(this.getGirlId(), "targetPos", posStr))
                    this.resetCameraAndPhysics()
                    PacketHandler.networkWrapper.sendToServer(SendGirlToSexPacket(this.getGirlId()))
                    this.setCurrentAction(Action.NULL)
                }
                "sitdownMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_GIGGLE[3], 3.0f)
                    if (this.isLocalPlayerNearby()) {
                        this.sendGirlChatMessage(I18n.get("ellie.dialogue.cometomommy"))
                    }
                }
                "sitdownDone" -> if (this.hasOwnerUUID()) {
                    this.setCurrentAction(Action.SITDOWNIDLE)
                    this.openEllieInventory(this.level().getPlayerByUUID(this.getOwnerUserUUID()!!)!!) // java-npe
                }
                "missionary_startDone" -> if (this.isControlledByLocalPlayer()) {
                    this.setCurrentAction(Action.MISSIONARY_SLOW)
                    HornyMeterHud.showHornyMeter()
                }
                "cowgirlStartMSG0" -> this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_GIGGLE[4], 3.0f)
                "cowgirlStartMSG1" -> if (this.isLocalPlayerNearby()) {
                    this.sendChatMessage(I18n.get("ellie.dialogue.like"))
                    HornyMeterHud.resetHornyMeter()
                }
                "cowgirlStartMSG2" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 3.0f)
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }
                }
                "cowgirlStartDone" -> if (this.isControlledByLocalPlayer()) {
                    this.setCurrentAction(Action.COWGIRLSLOW)
                    HornyMeterHud.showHornyMeter()
                }
                "cowgirlfastMSG1" -> {
                    if (this.moanToggle) {
                        this.moanToggle = false
                    } else {
                        this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 3.0f)
                    }

                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04)
                    }
                }
                "cowgirlfastReady" -> if (this.isControlledByLocalPlayer()) {
                    if (!HandlePlayerMovement.isJumping) {
                        this.setCurrentAction(Action.COWGIRLSLOW)
                    } else if (Reference.RANDOM.nextInt(4) != 1) {
                        this.actionController!!.clearAnimationCache() // java-npe
                    }
                }
                "cowgirlfastdomMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.2)
                    }
                }
                "cowgirlcumMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 3.0f)
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                }
                "cowgirlcumMSG2" -> {
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_MOAN[5], 3.0f)
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                }
                "cowgirlcumMSG3" -> this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                "cowgirlcumMSG4" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.hideHornyMeter()
                }
                "cowgirlcumMSG5", "missionary_cumMSG2" -> {
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_GIGGLE[4], 3.0f)
                    if (this.isControlledByLocalPlayer()) {
                        this.sendChatMessage(I18n.get("ellie.dialogue.goodboy"))
                    }
                }
                "cowgirlcumMSG6" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "missionary_cumDone", "cowgirlcumDone", "carry_cumDone" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.resetHornyMeter()
                    this.resetCameraAndPhysics()
                }
                "attackDone" -> if (++this.nextAttack == 3) {
                    this.nextAttack = 0
                }
                "pearl" -> PacketHandler.networkWrapper.sendToServer(SendCompanionHomePacket(this.getGirlId()))
                "openSexUi" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
                "missionary_slowMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    if (this.random.nextBoolean() && this.random.nextBoolean()) {
                        this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_MOAN), 3.0f)
                    } else {
                        this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 3.0f)
                    }

                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }
                }
                "missionary_fastMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    if (!this.random.nextBoolean() && !this.random.nextBoolean()) {
                        this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 3.0f)
                    } else {
                        this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_MOAN), 3.0f)
                    }

                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.05)
                    }
                }
                "missionary_fastDone" -> if (this.isControlledByLocalPlayer()) {
                    if (HandlePlayerMovement.isJumping) {
                        this.setCurrentAction(Action.MISSIONARY_FAST)
                    } else {
                        this.setCurrentAction(Action.MISSIONARY_SLOW)
                    }
                }
                "bedRustle" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    this.playSound(SoundHandler.MISC_BEDRUSTLE[0])
                }
                "bedRustle1" -> this.playSound(SoundHandler.MISC_BEDRUSTLE[1])
                "missionary_cumMSG1" -> this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 3.0f)
                "carry_introMSG1" -> {
                    this.sendChatMessage("I'm hungry..")
                    this.playRandomSoundAtVolume(SoundHandler.GIRLS_ELLIE_HMPH, 6.0f)
                }
                "carry_introMSG2" -> {
                    this.sendChatMessage("heh~")
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_GIGGLE[3], 6.0f)
                }
                "lipsound" -> {
                    this.playRandomSound(SoundHandler.GIRLS_ALLIE_LIPSOUND)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.02)
                    }
                }
                "cum" -> {
                    this.playRandomSoundAtVolume(SoundHandler.MISC_INSERTS, 6.0f)
                    this.playRandomSound(SoundHandler.MISC_POUNDING)
                }
                "pound" -> {
                    this.playRandomSound(SoundHandler.MISC_POUNDING)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.addToHornyMeter(0.04)
                    }
                }
                "carry_slowDone" -> {
                    val oldState = this.carryVariant

                    do {
                        this.carryVariant = this.random.nextInt(4) + 1
                    } while (this.carryVariant == oldState)
                }
                "carry_fastDone" -> if (this.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    this.setCurrentAction(Action.CARRY_SLOW)
                }
                "sexUI" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
            }
        }
        this.registerControllers(controllers, soundListener)
    }

    /** Client-only helpers (PORTING.md §2.3 rule C). */
    private object Client {
        fun handModel(): IVanillaModel = EllieModel()
    }

    companion object {
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractPlayerGirlEntity.createAttributes()
    }
}
