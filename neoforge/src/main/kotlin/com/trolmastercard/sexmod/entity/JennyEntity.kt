package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.entity.api.IEllie
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendCompanionHomePacket
import com.trolmastercard.sexmod.networking.SendGirlToSexPacket
import com.trolmastercard.sexmod.networking.SetPlayerForGirlPacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.potion.HornyPotion
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.IBeddableSexGirl
import com.trolmastercard.sexmod.util.Reference
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.VectorMath
import java.util.UUID
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.Vec3i
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * Jenny NPC — the blond sword/bow girl with blowjob, boobjob, doggy and
 * paizuri scenes.
 *
 * <b>Scene entry</b> (shared with Bia/Luna/Kobold): client
 * `doAction` sets `animationFollowUp` via
 * `ChangeDataParameterPacket` (GIRL_HAND_STATES) and sends
 * `KoboldStatePacket`; the server calls `setDismounted()`
 * (`sceneEntryLerpActive`), then [updateAITasks] lerps her to
 * `TARGET_POS` for ~40 ticks (`sceneEntryLerpTicks` counter), anchors her
 * (`IS_ANCHORED`) and calls [startActionFromHandState] which dispatches on
 * GIRL_HAND_STATES.
 *
 * <b>Payment gate:</b> without the horny potion effect
 * ([yFlag], server-side in [tick]), the first scene
 * request goes through [Action.PAYMENT] — the girl demands emeralds
 * before starting. With the potion active, the scene starts directly.
 *
 * <b>Scenes</b>: blowjob/boobjob play in place; doggy walks her to the
 * nearest bed ([IBeddableSexGirl.goToSexBed], [zFlag] walk
 * state) then anchors and starts DOGGYSTART.
 *
 * <b>Pitfall:</b> the dismount lerp MUST keep
 * `RotationHelper.lerpVec3d(pos, target, 40 - sceneEntryLerpTicks)` — the INT (step)
 * variant. The double variant flings the girl 40x and she vanishes (see
 * `RotationHelper` class doc). Also keep the `snapInPending` snap-in logic
 * (binds the interaction player when the player is within half a block) —
 * it is what starts the standing doggy scene.
 *
 * Port notes: `yFlag` (1.12 key 118 declared on `BaseGirlEntity.class`) is
 * defined in this companion (PORTING.md §2.6); `processInteract` is
 * [mobInteract]; `getEyeHeight` is [getDefaultDimensions].
 */
class JennyEntity : AbstractGirlNpcEntity, IEllie, IBeddableSexGirl {
    /** Set by `setDismounted()`: true while the scene-entry lerp runs (40 ticks). */
    @JvmField
    var zFlag: Boolean = false

    /** Set by `setDismounted()`: true while the scene-entry lerp runs. */
    @JvmField
    var sceneEntryLerpActive: Boolean = false

    /** Snap-in state: true while waiting for the player to walk within 0.5 blocks (doggy). */
    @JvmField
    var snapInPending: Boolean = false

    /** Dismount-lerp tick counter (0..40). */
    @JvmField
    var sceneEntryLerpTicks: Int = 0

    /** Bed-walk tick counter (re-path at 60/120). */
    @JvmField
    var bedWalkTicks: Int = 0

    @JvmField
    var doggyHard: Boolean = false

    @JvmField
    var fastMoanCount: Int = 0

    @JvmField
    var paizuriCameraAligned: Boolean = false

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out JennyEntity>, level: Level) : super(type, level) {
        this.slashSwordRot = 140
        this.stabSwordRot = 50
        this.holdBowRot = 140
        this.swordOffsetStab = Vec3(0.0, -0.029999997854232782, -0.2)
    }

    /** Kept for Java call sites and reflection (NpcType / ClothingScreen use getConstructor(Level)). */
    constructor(level: Level) : this(ModEntities.JENNY.get(), level)

    override fun getDisplayNameText(): String = "Jenny"

    override fun getScaleFactor(): Float = -0.2f

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(yFlag, false)
    }

    override fun onArriveHome() {
        this.sendChatMessage("Alright, this is my new Home~")
        this.playSound(SoundHandler.GIRLS_JENNY_HAPPYOH[1])
    }

    /** 1.12 `getEyeHeight()` = 1.64. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(1.64f)

    override fun getDeathSound(): SoundEvent? = SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_SIGH)

    override fun getHurtSound(source: DamageSource): SoundEvent? = null

    /** Client: I18n lookup; on the server (where 1.12 would have crashed) the raw key is returned. */
    private fun i18n(key: String): String = if (this.level().isClientSide) I18n.get(key) else key

    override fun updateAITasks() {
        super.updateAITasks()
        val player = this.level().getNearestPlayer(this, 15.0)
        if (this.snapInPending && player != null && player.position().distanceTo(this.position()) < 0.5) {
            this.snapInPending = false
            val playerMP = this.server!!.playerList.getPlayer(player.uuid) ?: return // java-npe: getServer() on the server

            this.entityData.set(INTERACTION_PARTNER_UUID, playerMP.uuid.toString())
            playerMP.teleportTo(this.position().x, this.position().y, this.position().z)
            this.alignPlayerToGirl(playerMP, false)
            playerMP.moveRelative(0.0f, Vec3.ZERO)
            this.positionPlayerRelative(0.0, 0.0, 0.4, 0.0f, 60.0f)
            this.cameraOriginPos = null
            this.setCurrentAction(Action.DOGGYSTART)
            PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), playerMP)
        }

        if (this.zFlag) {
            if (!(this.position().distanceTo(this.getTargetPosition()) < 0.6) && this.bedWalkTicks <= 200) {
                this.bedWalkTicks++
                if (this.bedWalkTicks == 60 || this.bedWalkTicks == 120) {
                    this.getNavigation().stop()
                    this.getNavigation().moveTo(this.getTargetPosition().x, this.getTargetPosition().y, this.getTargetPosition().z, 0.35)
                }
            } else {
                this.zFlag = false
                this.entityData.set(IS_ANCHORED, true)
                this.bedWalkTicks = 0
                this.noPhysics = true
                this.setNoGravity(true)
                this.setDeltaMovement(0.0, 0.0, 0.0)
                this.setCurrentAction(Action.STARTDOGGY)
            }
        }

        if (this.sceneEntryLerpActive) {
            this.sceneEntryLerpTicks++
            // 1.12 compared the position with the DataParameter itself (always unequal); kept as-is
            if (!this.position().equals(TARGET_POS) && this.sceneEntryLerpTicks <= 40) {
                this.yRot = this.getYawRotation()
                this.setTargetPosition(this.getFrontOffsetVector())
                this.setNoGravity(false)
                val pos = RotationHelper.lerpVec3d(this.position(), this.getTargetPosition(), 40 - this.sceneEntryLerpTicks)
                this.setPos(pos.x, pos.y, pos.z)
            } else {
                this.sceneEntryLerpActive = false
                this.sceneEntryLerpTicks = 0
                val interactionUuid = this.getInteractionPlayerUUID()
                val lerpPlayer = if (interactionUuid == null) null else this.level().server!!.playerList.getPlayer(interactionUuid)
                if (lerpPlayer != null) {
                    this.setYawRotation(lerpPlayer.yRot + 180.0f)
                }

                this.entityData.set(IS_ANCHORED, true)
                this.getNavigation().stop()
                if (this.entityData.get(yFlag)) {
                    this.startActionFromHandState()
                    return
                }

                this.setCurrentAction(Action.PAYMENT)
            }
        }
    }

    /** 1.12 `processInteract`. */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        val superResult = super.mobInteract(player, hand)
        if (superResult.consumesAction()) {
            return superResult
        }

        if (this.level().isClientSide && !this.openInteractionMenu(player)) {
            this.sendChatMessage(this.i18n("jenny.dialogue.busy"))
        }

        return InteractionResult.sidedSuccess(this.level().isClientSide)
    }

    override fun tick() {
        super.tick()
        if (!this.level().isClientSide) {
            this.entityData.set(yFlag, this.hasEffect(HornyPotion.HORNY_POTION))
        }
    }

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        if (this.getInteractionPlayerUUID() == null &&
            (!this.hasMaster() || this.entityData.get(MASTER) == ClientHooks.localPlayerUUID().toString())
        ) {
            val options = arrayOf(
                "action.names.blowjob",
                "action.names.boobjob",
                "action.names.doggy",
                if (this.entityData.get(OUTFIT_INDEX) == 1) "action.names.strip" else "action.names.dressup",
            )
            if (this.entityData.get(yFlag)) {
                openInventoryGui(player, this, options, true)
                return true
            } else {
                openInventoryGui(
                    player,
                    this,
                    options,
                    arrayOf(
                        ItemStack(Items.EMERALD, 3),
                        ItemStack(Items.ENDER_PEARL, 2),
                        ItemStack(Items.DIAMOND, 2),
                        if (this.entityData.get(OUTFIT_INDEX) == 1) ItemStack(Items.GOLD_INGOT, 1) else ItemStack.EMPTY,
                    ),
                    true,
                )
                return true
            }
        } else {
            return false
        }
    }

    override fun doAction(action: String, uuid: UUID) {
        super.doAction(action, uuid)
        if ("action.names.blowjob" == action) {
            this.changeDataParameterFromClient("animationFollowUp", "blowjob")
            this.setOwner(true, uuid)
        } else if ("action.names.boobjob" == action) {
            this.changeDataParameterFromClient("animationFollowUp", "boobjob")
            this.setOwner(true, uuid)
        } else if ("action.names.doggy" == action) {
            this.changeDataParameterFromClient("animationFollowUp", "doggy")
            this.setOwner(true, uuid)
        } else if ("action.names.strip" == action) {
            this.changeDataParameterFromClient("animationFollowUp", "strip")
            this.setOwner(true, uuid)
        } else if ("action.names.dressup" == action) {
            this.setCurrentAction(Action.STRIP)
        }
    }

    fun setOwner(flag: Boolean, uuid: UUID) {
        super.triggerActionSync(flag, true, uuid)
        HandlePlayerMovement.setMovementLock(false)
    }

    override fun goToSexBed() {
        val bedPos = this.getNearestBed(this.blockPosition())
        if (bedPos == null) {
            this.playSound(SoundHandler.GIRLS_JENNY_HMPH[2])
            this.sendChatMessage(this.i18n("jenny.dialogue.nobedinsight"))
        } else {
            this.wanderGoal?.let { this.goalSelector.removeGoal(it) }
            this.watchClosestGirlGoal?.let { this.goalSelector.removeGoal(it) }
            val bedVec = Vec3(bedPos.x.toDouble(), bedPos.y.toDouble(), bedPos.z.toDouble())
            val bestIndex = GirlBedPlacement.findFreeSide(this, bedVec, GirlBedPlacement.SIDE_OFFSETS)
            if (bestIndex == -1) {
                this.playSound(SoundHandler.GIRLS_JENNY_HMPH[2])
                this.sendChatMessage(this.i18n("jenny.dialogue.bedobscured"))
                return
            }

            val bedOffset = bedVec.add(GirlBedPlacement.SIDE_OFFSETS[bestIndex][0])
            this.setAnchored(false)
            this.setYawRotation(GirlBedPlacement.SIDE_YAWS[bestIndex].toFloat())
            this.setTargetPosition(Vec3(bedOffset.x, bedOffset.y, bedOffset.z))
            this.cameraYaw = this.getYawRotation()
            this.getNavigation().stop()
            this.getNavigation().moveTo(bedOffset.x, bedOffset.y, bedOffset.z, 0.35)
            this.zFlag = true
            this.bedWalkTicks = 0
        }
    }

    override fun setCurrentAction(action: Action?) {
        val currentAction = this.getCurrentAction()
        if (currentAction !== Action.DOGGYCUM || action !== Action.DOGGYSLOW && action !== Action.DOGGYFAST) {
            if (currentAction !== Action.CUMBLOWJOB || action !== Action.THRUSTBLOWJOB && action !== Action.SUCKBLOWJOB) {
                if (currentAction !== Action.PAIZURI_CUM || action !== Action.PAIZURI_SLOW && action !== Action.PAIZURI_FAST) {
                    super.setCurrentAction(action)
                    if (currentAction === Action.STARTBLOWJOB || currentAction === Action.PAIZURI_START) {
                        val uuid = this.getInteractionPlayerUUID()
                        if (uuid != null) {
                            val player = this.level().getPlayerByUUID(uuid)
                            if (player != null) {
                                val offset = VectorMath.rotateByYaw(Vec3(0.0, 0.0, 0.2), this.getYawRotation() + 180.0f)
                                player.teleportTo(player.x + offset.x, player.y, player.z + offset.z)
                            }
                        }
                    }
                }
            }
        }
    }

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

    override fun getNextAction(action: Action): Action? {
        return when (action) {
            Action.SUCKBLOWJOB -> Action.THRUSTBLOWJOB
            Action.DOGGYSLOW -> Action.DOGGYFAST
            Action.PAIZURI_SLOW -> {
                if (this.paizuriCameraAligned) {
                    this.paizuriCameraAligned = false
                    this.positionPlayerRelative(0.0, 0.0, 0.2f.toDouble(), 0.0f, 70.0f)
                }

                Action.PAIZURI_FAST
            }
            else -> null
        }
    }

    override fun setDismounted() {
        this.sceneEntryLerpActive = true
    }

    override fun reinitTasks() {
        this.resetWanderGoals()
    }

    override fun startActionFromHandState() {
        when (this.entityData.get(GIRL_HAND_STATES)) {
            "strip" -> {
                this.resetGirlState()
                this.setCurrentAction(Action.STRIP)
            }
            "blowjob" -> this.setCurrentAction(Action.STARTBLOWJOB)
            "boobjob" -> {
                if (this.entityData.get(OUTFIT_INDEX) != 0) {
                    this.setCurrentAction(Action.STRIP)
                    return
                }

                this.setCurrentAction(Action.PAIZURI_START)
            }
            "doggy" -> {
                if (this.entityData.get(OUTFIT_INDEX) != 0) {
                    this.setCurrentAction(Action.STRIP)
                    this.resetGirlState()
                    return
                }

                this.resetCameraAndPhysics()
                if (this.level().isClientSide) {
                    PacketHandler.networkWrapper.sendToServer(SendGirlToSexPacket(this.getGirlId()))
                } else {
                    this.resetGirlState()
                    this.goToSexBed()
                }
            }
        }

        if (this.level().isClientSide) {
            this.changeDataParameterFromClient("animationFollowUp", "")
        } else {
            this.entityData.set(GIRL_HAND_STATES, "")
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        if (SexWorldClient.isPreloadWorld(this.level())) {
            return PlayState.STOP
        }

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
                } else if (this.isPassenger) {
                    this.createAnimation("animation.jenny.sit", true, event)
                } else if (Math.abs(this.xo - this.x) + Math.abs(this.zo - this.z) > 0.0) {
                    when (this.getWalkType()) {
                        BaseGirlEntityState.RUN -> this.createAnimation("animation.jenny.run", true, event)
                        BaseGirlEntityState.FAST_WALK -> this.createAnimation("animation.jenny.fastwalk", true, event)
                        BaseGirlEntityState.WALK -> this.createAnimation("animation.jenny.walk", true, event)
                    }

                    this.yRot = this.yHeadRot
                } else {
                    this.createAnimation("animation.jenny.idle", true, event)
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
                Action.WAVE -> this.createAnimation("animation.jenny.wave", true, event)
                Action.WAVE_IDLE -> this.createAnimation("animation.jenny.wave_idle", true, event)
                else -> {}
            }
        }

        return PlayState.CONTINUE
    }

    /** CLIENT: the sound-keyframe listener drives the whole scene progression. */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            when (sound.keyframeData.sound) {
                "attackSound" -> this.playSound(SoundEvents.PLAYER_ATTACK_STRONG)
                "attackDone" -> {
                    this.setCurrentAction(Action.NULL)
                    if (++this.nextAttack == 3) {
                        this.nextAttack = 0
                    }
                }
                "becomeNude" -> if (this.isLocalPlayerNearby()) {
                    this.changeDataParameterFromClient("currentModel", if (this.entityData.get(OUTFIT_INDEX) == 1) "0" else "1")
                }
                "stripDone" -> {
                    if (this.entityData.get(GIRL_HAND_STATES) != "boobjob") {
                        this.resetCameraAndPhysics()
                    }

                    this.startActionFromHandState()
                }
                "stripMSG1" -> {
                    this.sendGirlChatMessage(I18n.get("jenny.dialogue.hihi"))
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_GIGGLE))
                }
                "paymentMSG1" -> {
                    this.sendGirlChatMessage(I18n.get("jenny.dialogue.huh"))
                    this.playSound(SoundHandler.GIRLS_JENNY_HUH[1])
                }
                "paymentMSG2" -> {
                    this.playSoundAtVolume(SoundHandler.MISC_PLOB[0], 0.5f)
                    val prefix = "<" + ClientHooks.localPlayerName() + "> "
                    when (this.entityData.get(GIRL_HAND_STATES)) {
                        "strip" -> this.broadcastChatAround(prefix + I18n.get("jenny.dialogue.showBobsandveganapls"), true)
                        "blowjob" -> this.broadcastChatAround(prefix + I18n.get("jenny.dialogue.giveblowjob"), true)
                        "doggy" -> this.broadcastChatAround(prefix + I18n.get("jenny.dialogue.givesex"), true)
                        "boobjob" -> this.broadcastChatAround(prefix + I18n.get("jenny.dialogue.givebooba"), true)
                        else -> this.broadcastChatAround(prefix + "sex pls", true)
                    }
                }
                "paymentMSG3" -> {
                    this.sendGirlChatMessage(I18n.get("jenny.dialogue.hehe"))
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_GIGGLE))
                }
                "sexUiOn" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
                "paymentMSG4" -> this.playSoundAtVolume(SoundHandler.MISC_PLOB[0], 0.25f)
                "paymentDone" -> this.startActionFromHandState()
                "bjiMSG1" -> {
                    this.sendGirlChatMessage(I18n.get("jenny.dialogue.blowjobtext1"))
                    this.playSound(SoundHandler.GIRLS_JENNY_MMM[8])
                    this.cameraYaw = this.yRot + 180.0f
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                    }
                }
                "bjiMSG2" -> {
                    this.sendGirlChatMessage(I18n.get("jenny.dialogue.blowjobtext2"))
                    this.playSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING[8])
                }
                "bjiMSG3" -> {
                    this.sendGirlChatMessage(I18n.get("jenny.dialogue.blowjobtext3"))
                    this.playSound(SoundHandler.GIRLS_JENNY_AFTERSESSIONMOAN[0])
                }
                "bjiMSG4" -> this.playSound(SoundHandler.MISC_BELLJINGLE[0])
                "bjiMSG5" -> {
                    this.sendGirlChatMessage(I18n.get("jenny.dialogue.blowjobtext4"))
                    this.playSoundAtVolume(SoundHandler.GIRLS_JENNY_HMPH[1], 0.5f)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                    }
                }
                "bjiMSG6" -> {
                    this.sendGirlChatMessage(I18n.get("jenny.dialogue.blowjobtext5"))
                    this.playSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING[8])
                }
                "bjiMSG7" -> {
                    this.sendGirlChatMessage(I18n.get("jenny.dialogue.blowjobtext6"))
                    this.playSound(SoundHandler.GIRLS_JENNY_GIGGLE[4])
                }
                "bjiMSG8" -> {
                    this.broadcastChatAround("<" + ClientHooks.localPlayerName() + "> " + I18n.get("jenny.dialogue.blowjobtext7"), true)
                    this.playSoundAtVolume(SoundHandler.MISC_PLOB[0], 0.5f)
                }
                "bjiMSG9" -> {
                    this.sendGirlChatMessage(I18n.get("jenny.dialogue.blowjobtext8"))
                    this.playSound(SoundHandler.GIRLS_JENNY_GIGGLE[2])
                }
                "bjiMSG10" -> if (this.isControlledByLocalPlayer()) {
                    this.positionPlayerRelative(-0.65, -0.8, -0.25, 60.0f, -3.0f)
                }
                "bjiMSG11" -> {
                    if (this.isControlledByLocalPlayer() && HandlePlayerMovement.isJumping) {
                        this.resetAnimationControllerOffset()
                    }

                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_LIPSOUND))
                    this.addToHornyMeterIfControlled(0.02)
                }
                "bjiMSG12" -> {
                    if (Reference.RANDOM.nextInt(5) == 0) {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_BJMOAN))
                    }

                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_LIPSOUND))
                    this.addToHornyMeterIfControlled(0.02)
                }
                "bjtMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_MMM))
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_LIPSOUND))
                    this.addToHornyMeterIfControlled(0.04)
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
                    this.sendChatMessage(I18n.get("jenny.dialogue.doggytext1"))
                    this.playSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING[9])
                }
                "doggyGoOnBedMSG3" -> {
                    this.sendChatMessage(I18n.get("jenny.dialogue.doggytext2"))
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

                    this.addToHornyMeterIfControlled(0.00666)
                }
                "doggyslowMSG2" -> this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_JENNY_LIGHTBREATHING), 0.5f)
                "doggyfastMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                    this.addToHornyMeterIfControlled(0.02)

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
                "boobjob_camera" -> {
                    val uuid = ClientHooks.localPlayerUUID()!! // java-npe: Minecraft.player
                    if (uuid == this.level().getNearestPlayer(this.getSelf(), 2.0)!!.uuid) { // java-npe
                        this.cameraYaw = this.level().getPlayerByUUID(uuid)!!.yRot // java-npe
                        this.setInteractionPlayerUUID(uuid)
                        if (!this.paizuriCameraAligned) {
                            this.paizuriCameraAligned = true
                            this.positionPlayerRelative(-0.7, -0.6, 0.2, 60.0f, -3.0f)
                        }
                    }
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

                    this.addToHornyMeterIfControlled(0.04)
                }
                "paizuriSlowMSG1", "paizuriStartMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    this.addToHornyMeterIfControlled(0.02)
                }
                "paizuri_fastDone" -> {
                    this.setCurrentAction(Action.PAIZURI_SLOW)
                    if (this.isControlledByLocalPlayer() && !this.paizuriCameraAligned) {
                        this.paizuriCameraAligned = true
                        this.positionPlayerRelative(-0.7, -0.6, 0.2, 60.0f, -3.0f)
                    }
                }
                "paizuri_startStep" -> {
                    val state = this.level().getBlockState(this.blockPosition().subtract(Vec3i(0, 1, 0)))
                    this.playSound(state.getSoundType(this.level(), this.blockPosition(), this).stepSound)
                }
                "paizuri_cumStart" -> if (this.isControlledByLocalPlayer() && !this.paizuriCameraAligned) {
                    this.positionPlayerRelative(-0.7, -0.6, 0.2, 60.0f, -3.0f)
                }
            }
        }
        this.registerControllers(controllers, soundListener)
    }

    companion object {
        /** SERVER-maintained flag: true while the girl has the horny potion effect active (1.12 key 118). */
        @JvmField
        val yFlag: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(JennyEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmStatic
        fun createJenny(world: Level): JennyEntity {
            val jenny = JennyEntity(world)
            jenny.isSpecialState = true
            return jenny
        }

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractGirlNpcEntity.createAttributes()
    }
}
