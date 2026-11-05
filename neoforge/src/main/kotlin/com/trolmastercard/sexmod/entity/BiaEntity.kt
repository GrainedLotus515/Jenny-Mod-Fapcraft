package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.entity.api.IEllie
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendCompanionHomePacket
import com.trolmastercard.sexmod.networking.SendGirlToSexPacket
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.IBeddableSexGirl
import com.trolmastercard.sexmod.util.LootTableHandler
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SceneDebug
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.Vector4d
import com.trolmastercard.sexmod.util.VectorMath
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.UUID
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * Bia NPC — the catgirl with talk, headpat, strip, and bed scenes
 * (anal + prone doggy).
 *
 * <b>Scene entry</b> (shared with Jenny/Luna/Kobold): client `doAction`
 * sets `animationFollowUp` (GIRL_HAND_STATES) via
 * `ChangeDataParameterPacket` and sends `KoboldStatePacket`; the
 * server calls `setDismounted()` ([yFlag]), then
 * [updateAITasks] lerps her to `TARGET_POS` for ~40 ticks
 * (`sceneEntryLerpTicks` counter), anchors her and calls [startActionFromHandState] which dispatches
 * on GIRL_HAND_STATES: talk -> TALK_HORNY dialogue, then the anal/doggy menu;
 * strip -> STRIP animation.
 *
 * <b>Bed scenes</b> (anal/doggy): [startActionFromHandState] sends
 * `SendGirlToSexPacket` -> [goToSexBed] walks her to the
 * nearest bed (`walkingToBed`/`zFlag` walk state), anchors her, and
 * [handleAnalState] runs the scene pickup: when the player stands
 * within 1 block, a <b>jar-verified 22-tick countdown</b> (`contactCountdown`)
 * runs before the scene action (ANAL_START / PRONE_DOGGY_INTRO) begins.
 *
 * <b>Pitfall:</b> [handleAnalState] MUST assign `contactCountdown = 22` on
 * first contact — the original static field was `j = 22` on
 * `BaseGirlEntity`. A deobf regression set `contactCountdown = -1` instead,
 * which permanently stalled every Bia bed scene at ANAL_WAIT/SITDOWNIDLE.
 * The player-girl twin `BiaPlayerEntity.handleBiaAnalState` kept the
 * correct `ar = 22` — use it as the reference.
 *
 * Port notes: `onDeath` is [die]; the brown wool drop (1.12 wool meta 12) is
 * `Items.BROWN_WOOL`; `processInteract` is [mobInteract].
 */
class BiaEntity : AbstractGirlNpcEntity, IEllie, IBeddableSexGirl {
    /** Set by `setDismounted()`: true while the scene-entry lerp runs (40 ticks). */
    @JvmField
    var yFlag: Boolean = false

    /** Dismount-lerp tick counter (0..40). */
    @JvmField
    var sceneEntryLerpTicks: Int = 0

    /** Bed-walk state: true while walking to the bed (re-path at 60/120). */
    @JvmField
    var walkingToBed: Boolean = false

    /** Bed-walk tick counter. */
    @JvmField
    var zFlag: Int = 0

    /** One-shot no-gravity cleanup guard. */
    @JvmField
    var noClipCleanupPending: Boolean = true

    /**
     * Scene-pickup countdown for `handleAnalState()`: -1 = waiting for
     * first player contact, 22 = counting down to the scene action, 0 = start.
     * <b>Must be set to 22 on first contact</b> (jar-verified; the original
     * static field was `j = 22`).
     */
    @JvmField
    var contactCountdown: Int = -1

    /** Set while a bed scene transition is in flight (suppresses the camera reset). */
    @JvmField
    var bedTransitionActive: Boolean = false

    @JvmField
    val BED_YAWS: IntArray = intArrayOf(0, 180, -90, 90)

    @JvmField
    val BED_OFFSETS: Array<Array<Vec3>> = arrayOf(
        arrayOf(Vec3(0.5, 0.0, -0.5), Vec3(0.0, 0.0, -1.0)),
        arrayOf(Vec3(0.5, 0.0, 1.5), Vec3(0.0, 0.0, 1.0)),
        arrayOf(Vec3(-0.5, 0.0, 0.5), Vec3(-1.0, 0.0, 0.0)),
        arrayOf(Vec3(1.5, 0.0, 0.5), Vec3(1.0, 0.0, 0.0)),
    )

    @JvmField
    var hardLoopVariant: Int = 1

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out BiaEntity>, level: Level) : super(type, level) {
        this.noClipCleanupPending = true
        this.slashSwordRot = 140
        this.stabSwordRot = 50
        this.holdBowRot = 140
        this.swordOffsetStab = Vec3(0.0, -0.029999997854232782, -0.2)
    }

    /** Kept for Java call sites and reflection (NpcType / ClothingScreen use getConstructor(Level)). */
    constructor(level: Level) : this(ModEntities.BIA.get(), level)

    override fun getDisplayNameText(): String = "Bia"

    override fun getScaleFactor(): Float = -0.2f

    override fun onArriveHome() {
        this.sendChatMessage("I am living here now nya~")
        this.playRandomSound(SoundHandler.GIRLS_BIA_BREATH)
    }

    override fun setDismounted() {
        this.yFlag = true
    }

    /** Client: I18n lookup; on the server (where 1.12 would have crashed) the raw key is returned. */
    private fun i18n(key: String): String = if (this.level().isClientSide) I18n.get(key) else key

    override fun setCurrentAction(action: Action?) {
        val currentAction = this.getCurrentAction()
        if (currentAction === Action.ANAL_CUM || currentAction === Action.PRONE_DOGGY_CUM) {
            this.entityData.set(GIRL_HAND_STATES, "")
        }

        if (currentAction !== Action.ANAL_CUM || action !== Action.ANAL_FAST && action !== Action.ANAL_SLOW) {
            if (currentAction !== Action.PRONE_DOGGY_CUM || action !== Action.PRONE_DOGGY_HARD && action !== Action.PRONE_DOGGY_SOFT) {
                super.setCurrentAction(action)
            }
        }
    }

    /** 1.12 `getLootTable()`. */
    override fun getDefaultLootTable(): ResourceKey<LootTable> = LootTableHandler.BIA_TABLE

    override fun updateAITasks() {
        super.updateAITasks()
        if (this.noClipCleanupPending) {
            this.setNoGravity(false)
            this.noPhysics = false
            this.noClipCleanupPending = false
        }

        if (this.yFlag) {
            this.sceneEntryLerpTicks++
            if (this.position() != this.getTargetPosition() && this.sceneEntryLerpTicks <= 40) {
                this.yRot = this.getYawRotation()

                try {
                    // 1.12 quirk: never throws, so the target is never re-derived here
                    TARGET_POS.equals(null)
                } catch (ex: NullPointerException) {
                    this.setTargetPosition(this.getFrontOffsetVector())
                }

                this.setNoGravity(false)
                val pos = RotationHelper.lerpVec3d(this.position(), this.getTargetPosition(), 40 - this.sceneEntryLerpTicks)
                this.setPos(pos.x, pos.y, pos.z)
            } else {
                this.yFlag = false
                this.sceneEntryLerpTicks = 0
                val interactionUuid = this.getInteractionPlayerUUID()
                val lerpPlayer = if (interactionUuid == null) null else this.level().server!!.playerList.getPlayer(interactionUuid)
                if (lerpPlayer != null) {
                    this.setYawRotation(lerpPlayer.yRot + 180.0f)
                }

                this.entityData.set(IS_ANCHORED, true)
                this.getNavigation().stop()
                this.startActionFromHandState()
            }
        }

        if (this.walkingToBed) {
            if (!(this.position().distanceTo(this.getTargetPosition()) < 0.6) && this.zFlag <= 200) {
                this.zFlag++
                if (this.zFlag == 60 || this.zFlag == 120) {
                    this.getNavigation().stop()
                    this.getNavigation().moveTo(this.getTargetPosition().x, this.getTargetPosition().y, this.getTargetPosition().z, 0.35)
                }
            } else {
                this.walkingToBed = false
                this.entityData.set(IS_ANCHORED, true)
                this.zFlag = 0
                this.noPhysics = true
                this.setNoGravity(true)
                this.setDeltaMovement(0.0, 0.0, 0.0)
                if ("anal" == this.entityData.get(GIRL_HAND_STATES)) {
                    this.setCurrentAction(Action.ANAL_PREPARE)
                    this.setOutfitIndex(0)
                } else {
                    this.setCurrentAction(Action.SITDOWN)
                }
            }
        }
    }

    /** 1.12 `processInteract`. */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        val superResult = super.mobInteract(player, hand)
        if (superResult.consumesAction()) {
            return superResult
        }

        val success = InteractionResult.sidedSuccess(this.level().isClientSide)
        if (this.getCurrentAction() === Action.SITDOWNIDLE) {
            return success
        }

        val stack = player.getItemInHand(hand)
        val isNameTag = stack.item === Items.NAME_TAG
        if (isNameTag) {
            stack.interactLivingEntity(player, this, hand)
            return success
        }

        if (this.level().isClientSide && !this.openInteractionMenu(player)) {
            this.sendChatMessage(this.i18n("bia.dialogue.busy"))
        }

        return success
    }

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        if (this.getInteractionPlayerUUID() == null &&
            (!this.hasMaster() || this.entityData.get(MASTER) == ClientHooks.localPlayerUUID().toString())
        ) {
            val options = arrayOf(
                if (this.entityData.get(OUTFIT_INDEX) == 1) "action.names.strip" else "action.names.dressup",
                "action.names.talk",
                "action.names.headpat",
            )
            openInventoryGui(player, this, options, true)
            return true
        } else {
            return false
        }
    }

    /** CLIENT. */
    fun openBiaInventory(player: Player) {
        openInventoryGui(player, this, arrayOf("action.names.anal", "doggy"), false)
    }

    override fun onInteractionMenuClosed() {
        if (this.isAnchored() && !this.bedTransitionActive) {
            this.resetCameraAndPhysics()
        }

        this.bedTransitionActive = false
    }

    override fun tick() {
        super.tick()
        if (this.level().isClientSide && this.isControlledByLocalPlayer() && this.getCurrentAction() === Action.PRONE_DOGGY_INTRO &&
            !BeeScreen.isBeeScreenVisible()
        ) {
            HornyMeterHud.showHornyMeter()
        }

        this.handleAnalState()
    }

    override fun resetLocalPlayerClientState() {
        super.resetLocalPlayerClientState()
        this.contactCountdown = -1
    }

    fun handleAnalState() {
        val action = this.getCurrentAction()
        if (action === Action.ANAL_WAIT || action === Action.SITDOWNIDLE) {
            val player = this.level().getNearestPlayer(this, 10.0)
            if (player != null) {
                if (!(player.distanceTo(this) > 1.0f)) {
                    if (this.contactCountdown == -1) {
                        SceneDebug.log(
                            SceneDebug.SCENE_ENTRY, "Bia.handleAnalState action=%s contactCountdown==-1 (world remote=%s)",
                            action, this.level().isClientSide,
                        )
                        if (this.level().isClientSide) {
                            BeeScreen.enableInteraction()
                            HandlePlayerMovement.setMovementLock(false)
                        } else {
                            this.setInteractionPlayerUUID(player.uuid)
                        }

                        // jar-faithful countdown: BaseGirlEntity's static j == 22 ticks
                        this.contactCountdown = 22
                        SceneDebug.log(SceneDebug.SCENE_ENTRY, "Bia.handleAnalState: contactCountdown set to 22")
                    } else if (--this.contactCountdown <= 0) {
                        this.contactCountdown = -1
                        SceneDebug.log(
                            SceneDebug.SCENE_ENTRY, "Bia.handleAnalState: countdown done, starting scene action=%s (remote=%s)",
                            action, this.level().isClientSide,
                        )
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
                                val playerPos = targetPos.add(VectorMath.rotateByYaw(0.0, 1.1875 - player.eyeHeight, 0.5, yaw))
                                player.teleportTo(playerPos.x, playerPos.y, playerPos.z)
                                this.setAnchored(true)
                            }
                        }
                    } else if (this.contactCountdown % 5 == 0) {
                        SceneDebug.log(SceneDebug.SCENE_ENTRY, "Bia.handleAnalState: counting down contactCountdown=%d", this.contactCountdown)
                    }
                }
            }
        }
    }

    /** CLIENT. */
    override fun resetAnimationControllerTicks() {
        super.resetAnimationControllerTicks()
        if (this.getCurrentAction() === Action.PRONE_DOGGY_HARD) {
            val oldState = this.hardLoopVariant

            do {
                this.hardLoopVariant = this.random.nextInt(3) + 1
            } while (oldState == this.hardLoopVariant)
        }
    }

    override fun reinitTasks() {
        this.resetWanderGoals()
    }

    override fun doAction(action: String, uuid: UUID) {
        SceneDebug.log(SceneDebug.SCENE_ENTRY, "Bia.doAction %s player=%s (remote=%s)", action, uuid, this.level().isClientSide)
        super.doAction(action, uuid)
        when (action) {
            "action.names.talk" -> {
                val localUuid = ClientHooks.localPlayerUUID()!! // java-npe: Minecraft.player
                this.setInteractionPlayerUUID(localUuid)
                this.changeDataParameterFromClient("playerSheHasSexWith", localUuid.toString())
                this.changeDataParameterFromClient("animationFollowUp", "talkHorny")
                this.triggerAnalAction(uuid)
            }
            "action.names.headpat" -> {
                val localUuid = ClientHooks.localPlayerUUID()!! // java-npe: Minecraft.player
                this.setInteractionPlayerUUID(localUuid)
                this.changeDataParameterFromClient("playerSheHasSexWith", localUuid.toString())
                this.changeDataParameterFromClient("animationFollowUp", "Headpat")
                this.triggerAnalAction(uuid)
            }
            "action.names.anal" -> {
                this.changeDataParameterFromClient("animationFollowUp", "anal")
                this.setCurrentAction(Action.TALK_RESPONSE)
                this.bedTransitionActive = true
            }
            "doggy" -> {
                this.changeDataParameterFromClient("animationFollowUp", "doggy")
                this.setCurrentAction(Action.TALK_RESPONSE)
                this.bedTransitionActive = true
            }
            "action.names.dressup", "action.names.strip" -> this.setCurrentAction(Action.STRIP)
        }
    }

    /** 1.12 `onDeath`: drops 0..3 brown wool (wool meta 12). */
    override fun die(source: DamageSource) {
        super.die(source)
        if (!this.level().isClientSide) {
            val item = ItemEntity(
                this.level(),
                this.x,
                this.y,
                this.z,
                ItemStack(Items.BROWN_WOOL, this.random.nextInt(4)),
            )
            this.level().addFreshEntity(item)
        }
    }

    fun triggerAnalAction(uuid: UUID) {
        this.triggerActionSync(true, true, uuid)
        HandlePlayerMovement.setMovementLock(false)
    }

    fun getBedVector(): Vector4d? {
        var bedPos: BlockPos? = null
        var attempts = 0

        while (!this.isValidBed(bedPos)) {
            bedPos = this.findNearestBed(this.blockPosition(), attempts)
            if (++attempts == 50) {
                break
            }
        }

        if (bedPos != null && attempts != 50) {
            this.wanderGoal?.let { this.goalSelector.removeGoal(it) }
            this.watchClosestGirlGoal?.let { this.goalSelector.removeGoal(it) }
            val bedVec = Vec3(bedPos.x.toDouble(), bedPos.y.toDouble(), bedPos.z.toDouble())
            val bestIndex = GirlBedPlacement.findFreeSideWithRoom(this, bedVec, this.BED_OFFSETS)
            if (bestIndex == -1) {
                this.playSound(SoundHandler.GIRLS_BIA_BREATH[2])
                this.sendChatMessage(this.i18n("jenny.dialogue.nobedinsight"))
                return null
            } else {
                val bedOffset = bedVec.add(this.BED_OFFSETS[bestIndex][0])
                return Vector4d(bedOffset.x, bedOffset.y, bedOffset.z, this.BED_YAWS[bestIndex].toDouble())
            }
        } else {
            this.playSound(SoundHandler.GIRLS_BIA_BREATH[2])
            this.sendChatMessage(this.i18n("jenny.dialogue.nobedinsight"))
            return null
        }
    }

    fun isValidBed(pos: BlockPos?): Boolean {
        if (pos == null) {
            return false
        } else if (WorldUtils.canPlaceStructure(this.level(), pos.north()) && this.level().isEmptyBlock(pos.south())) {
            return true
        } else if (WorldUtils.canPlaceStructure(this.level(), pos.east()) && this.level().isEmptyBlock(pos.west())) {
            return true
        } else {
            return if (WorldUtils.canPlaceStructure(this.level(), pos.south()) && this.level().isEmptyBlock(pos.north())) {
                true
            } else {
                WorldUtils.canPlaceStructure(this.level(), pos.west()) && this.level().isEmptyBlock(pos.east())
            }
        }
    }

    fun findNearestBedVector(): Vector4d? {
        val bedPos = this.getNearestBed(this.blockPosition())
        if (bedPos == null) {
            this.playSound(SoundHandler.GIRLS_BIA_BREATH[2])
            this.sendChatMessage(this.i18n("jenny.dialogue.nobedinsight"))
            return null
        }

        this.wanderGoal?.let { this.goalSelector.removeGoal(it) }
        this.watchClosestGirlGoal?.let { this.goalSelector.removeGoal(it) }
        val bedVec = Vec3(bedPos.x.toDouble(), bedPos.y.toDouble(), bedPos.z.toDouble())
        val bestIndex = GirlBedPlacement.findFreeSide(this, bedVec, this.BED_OFFSETS)
        if (bestIndex == -1) {
            this.playSound(SoundHandler.GIRLS_BIA_BREATH[2])
            this.sendChatMessage(this.i18n("jenny.dialogue.bedobscured"))
            return null
        } else {
            val bedOffset = bedVec.add(this.BED_OFFSETS[bestIndex][0])
            return Vector4d(bedOffset.x, bedOffset.y, bedOffset.z, this.BED_YAWS[bestIndex].toDouble())
        }
    }

    override fun goToSexBed() {
        val stateStr = this.entityData.get(GIRL_HAND_STATES)
        val bedVec4 = if (stateStr == "anal") this.findNearestBedVector() else this.getBedVector()
        if (bedVec4 != null) {
            val pos = Vec3(bedVec4.x, bedVec4.y, bedVec4.z)
            this.setYawRotation(bedVec4.w.toFloat())
            this.setTargetPosition(pos)
            this.cameraYaw = this.getYawRotation()
            this.getNavigation().stop()
            this.getNavigation().moveTo(pos.x, pos.y, pos.z, 0.35)
            this.walkingToBed = true
            this.zFlag = 0
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

    override fun startActionFromHandState() {
        SceneDebug.log(
            SceneDebug.SCENE_ENTRY, "Bia.startActionFromHandState handState=%s action=%s remote=%s",
            this.entityData.get(GIRL_HAND_STATES), this.getCurrentAction(), this.level().isClientSide,
        )
        when (this.entityData.get(GIRL_HAND_STATES)) {
            "talkHorny" -> this.setCurrentAction(Action.TALK_HORNY)
            "Headpat" -> this.setCurrentAction(Action.HEAD_PAT)
            "doggy", "anal" -> {
                this.resetCameraAndPhysics()
                PacketHandler.networkWrapper.sendToServer(SendGirlToSexPacket(this.getGirlId()))
                return
            }
        }

        if (this.level().isClientSide) {
            this.changeDataParameterFromClient("animationFollowUp", "")
        } else {
            this.entityData.set(GIRL_HAND_STATES, "")
        }
    }

    override fun getLeftArmAngle(): Float = 35.0f

    override fun getRightArmAngle(): Float = 140.0f

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        if (SexWorldClient.isPreloadWorld(this.level())) {
            return PlayState.STOP
        }

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
                } else if (this.isPassenger) {
                    this.createAnimation("animation.bia.sit", true, event)
                } else if (Math.abs(this.xo - this.x) + Math.abs(this.zo - this.z) > 0.0) {
                    when (this.getWalkType()) {
                        BaseGirlEntityState.RUN -> this.createAnimation("animation.bia.run", true, event)
                        BaseGirlEntityState.FAST_WALK -> this.createAnimation("animation.bia.fastwalk", true, event)
                        BaseGirlEntityState.WALK -> this.createAnimation("animation.bia.walk", true, event)
                    }

                    this.yRot = this.yHeadRot
                } else {
                    this.createAnimation("animation.bia.idle", true, event)
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
                Action.TALK_HORNY -> this.createAnimation("animation.bia.talk_horny2", true, event)
                Action.TALK_IDLE -> this.createAnimation("animation.bia.talk_idle2", true, event)
                Action.TALK_RESPONSE -> this.createAnimation("animation.bia.talk_response", true, event)
                Action.ANAL_PREPARE -> this.createAnimation("animation.bia.anal_prepare", false, event)
                Action.ANAL_WAIT -> this.createAnimation("animation.bia.anal_wait", false, event)
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
                Action.WAVE_IDLE -> this.createAnimation("animation.bia.wave_idle", true, event)
                Action.WAVE -> this.createAnimation("animation.bia.wave", true, event)
                else -> {}
            }
        }

        return PlayState.CONTINUE
    }

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            // Java switch fall-through: anal_fastMSG1 -> anal_slowMSG1, anal_fastDone -> anal_startDone
            val analSlow = {
                this.addToHornyMeterIfControlled(0.02)

                this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.5f)
                this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_BIA_AHH))
            }
            val analStartDone = {
                this.setCurrentAction(Action.ANAL_SLOW)
                if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
            }
            when (sound.keyframeData.sound) {
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
                    this.resetCameraAndPhysics()
                    this.startActionFromHandState()
                }
                "stripMSG1" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.hihi"))
                    this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_BIA_GIGGLE))
                }
                "sexUiOn" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.showHornyMeter()
                }
                "pearl" -> PacketHandler.networkWrapper.sendToServer(SendCompanionHomePacket(this.getGirlId()))
                "talk_hornyMSG1" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.heya"))
                    this.playRandomSound(SoundHandler.GIRLS_BIA_HEY)
                }
                "talk_hornyMSG2" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.horny"))
                    this.playSound(SoundHandler.GIRLS_BIA_GIGGLE[2])
                }
                "talk_hornyMSG3" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.so"))
                    this.playSound(SoundHandler.GIRLS_BIA_BREATH[0])
                }
                "talk_hornyMSG4" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.fun"))
                    this.playSound(SoundHandler.GIRLS_BIA_HUH[0])
                }
                "talk_hornyDone" -> {
                    SceneDebug.log(
                        SceneDebug.SCENE_ENTRY, "Bia.sound talk_hornyDone (remote=%s, controlled=%s)",
                        this.level().isClientSide, this.isControlledByLocalPlayer(),
                    )
                    this.setCurrentAction(Action.TALK_IDLE)
                    if (this.isControlledByLocalPlayer()) {
                        this.openBiaInventory(ClientHooks.localPlayer()!!) // java-npe: Minecraft.player
                    }
                }
                "talk_responseMSG1" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.huh"))
                    this.playSound(SoundHandler.GIRLS_BIA_HUH[2])
                }
                "talk_responseMSG2" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.iuhm"))
                    this.playSound(SoundHandler.GIRLS_BIA_BREATH[1])
                }
                "talk_responseMSG3" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.yes"))
                    this.playSound(SoundHandler.GIRLS_BIA_GIGGLE[0])
                }
                "talk_responseDone" -> {
                    SceneDebug.log(
                        SceneDebug.SCENE_ENTRY, "Bia.sound talk_responseDone (remote=%s, controlled=%s, handState=%s)",
                        this.level().isClientSide, this.isControlledByLocalPlayer(), this.entityData.get(GIRL_HAND_STATES),
                    )
                    if (this.isControlledByLocalPlayer()) {
                        this.resetGirlState()
                    }

                    this.startActionFromHandState()
                }
                "anal_prepareMSG1" -> this.playSound(SoundHandler.MISC_PLOB[0])
                "anal_prepareMSG2" -> this.playSound(SoundHandler.MISC_BEDRUSTLE[0])
                "anal_prepareDone" -> {
                    SceneDebug.log(SceneDebug.SCENE_ENTRY, "Bia.sound anal_prepareDone (remote=%s)", this.level().isClientSide)
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
                    this.addToHornyMeterIfControlled(0.02)
                    analSlow()
                }
                "anal_slowMSG1", "anal_startMSG2" -> analSlow()
                "anal_fastDone" -> if (this.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    analStartDone()
                }
                "anal_startDone" -> analStartDone()
                "anal_cumMSG2" -> this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_BIA_AHH))
                "blackScreen", "anal_cumBlackScreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "doggy_cumDone", "anal_cumDone" -> {
                    SceneDebug.log(
                        SceneDebug.SCENE_ENTRY, "Bia.sound %s (remote=%s, controlled=%s)",
                        sound.keyframeData.sound, this.level().isClientSide, this.isControlledByLocalPlayer(),
                    )
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                        this.resetCameraAndPhysics()
                    }
                }
                "headpatMSG1" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.headpats"))
                    this.playSound(SoundHandler.GIRLS_BIA_BREATH[0])
                }
                "headpatMSG2" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.hmm"))
                    this.playSound(SoundHandler.GIRLS_BIA_MMM[0])
                }
                "headpatMSG3" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.huh2"))
                    this.playSound(SoundHandler.GIRLS_BIA_HUH[0])
                }
                "headpatMSG4" -> {
                    this.sendChatMessage(I18n.get("bia.dialogue.thankyou"))
                    this.playSound(SoundHandler.GIRLS_BIA_GIGGLE[1])
                }
                "headpatDone" -> this.resetCameraAndPhysics()
                "sitdownMSG1" -> {
                    this.sendChatMessage("come here big boy~")
                    this.playRandomSound(SoundHandler.GIRLS_BIA_BREATH)
                }
                "sitdownDone" -> this.setCurrentAction(Action.SITDOWNIDLE)
                "slide" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_SLIDE))
                    this.addToHornyMeterIfControlled(0.005)
                }
                "pound" -> this.playRandomSound(SoundHandler.MISC_POUNDING)
                "doggyMoan" -> {
                    this.playRandomSound(if (this.random.nextBoolean()) SoundHandler.GIRLS_BIA_AHH else SoundHandler.GIRLS_BIA_MMM)
                    this.addToHornyMeterIfControlled(0.04)
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
            }
        }
        this.registerControllers(controllers, soundListener)
    }

    companion object {
        const val ae: Int = 3

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractGirlNpcEntity.createAttributes()
    }
}
