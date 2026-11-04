package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.entity.api.IEllie
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendCompanionHomePacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.potion.HornyPotion
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.LootTableHandler
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.VectorMath
import java.util.UUID
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * Ellie NPC — the tall "mommy" girl with a hug
 * (hug/hugselected/hugidle) dialogue phase and three scenes: cowgirl,
 * missionary (both on a bed) and the carried "Face fuck" (carry intro/slow/
 * fast/cum).
 *
 * <b>Scene entry.</b> [setDismounted] (invoked by
 * `KoboldStatePacket.Handler.sendState` — see the packet) binds the
 * interaction player, faces her by `playerYaw - 180`, and starts
 * [Action.CARRY_INTRO] anchored. [doAction] is the
 * client-side scene chooser: it sets [Action.HUGSELECTED] and writes the
 * chosen scene into `GIRL_HAND_STATES` via
 * `changeDataParameterFromClient("animationFollowUp", ...)`; once the
 * hug dialogue finishes, the server-side [startActionFromHandState] dispatches on that value
 * (`"Missionary"` -> [Action.MISSIONARY_START], `"cowgirl"`
 * -> [Action.COWGIRLSTART]) and positions the player.
 *
 * <b>Bed flow.</b> After the scene, `handleSitUpTimer` walks her off the
 * bed to a random sit pose (see [getRandomSitPose]), anchors her and
 * enters [Action.SITDOWN]; `handleSitDownTimer` (110 ticks) leads
 * to [Action.SITDOWNIDLE] where [handleSitIdle] waits for a
 * nearby player. The horny potion route ([handleHornyPotion]) makes
 * her dash to the player and start the hug chain instead.
 *
 * <b>Pitfalls.</b> [setCurrentAction] refuses re-entry into
 * loop phases while the cum animation plays and arms the carry timer
 * (`carryIntroTicks` = 0) on [Action.CARRY_INTRO]. The sit-state
 * timers are plain counters: `yFlag` (hugidle, 150), `sitDownTicks`
 * (sitdown, 109), `sitUpTicks` (sit-up, 79) and `zFlag` (dash, 16)
 * — reset them all in [resetSitState]. [startActionFromHandState] must only run
 * after the hug dialogue; `GIRL_HAND_STATES` carries the scene choice
 * from the client packet.
 *
 * Port notes: the 1.12 `@SideOnly(CLIENT) onUpdate` override is [tick] with its
 * body guarded by `level().isClientSide` (dedicated-server semantics). The
 * dynamic 1.12 `getEyeHeight()` (1.53 crouched under a block, else 1.9) is
 * cached in [eyeBedBlocked] and applied through [getDefaultDimensions] +
 * `refreshDimensions()` whenever it changes.
 */
class EllieEntity : AbstractGirlNpcEntity, IEllie {
    @JvmField
    var carryIntroTicks: Int = -1

    @JvmField
    var aq: Boolean = false

    @JvmField
    var hugDialogueDone: Boolean = false

    @JvmField
    var firstSitCleanupDone: Boolean = false

    @JvmField
    var standTicks: Int = -1

    @JvmField
    var yFlag: Int = -1

    @JvmField
    var sitDownTicks: Int = -1

    @JvmField
    var sitUpTicks: Int = -1

    @JvmField
    var sitUpActive: Boolean = false

    @JvmField
    var sitPose: Array<Any>? = null

    @JvmField
    var zFlag: Int = -1

    @JvmField
    var carryVariant: Int = 1

    @JvmField
    var alternateMoan: Boolean = false

    /** Port: last [isBedBlocked] value baked into the eye height (see class doc). */
    @JvmField
    var eyeBedBlocked: Boolean = false

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out EllieEntity>, level: Level) : super(type, level) {
        this.slashSwordRot = -85
        this.stabSwordRot = -175
        this.holdBowRot = -85
        this.swordOffsetStab = Vec3(-0.1, 0.05, 0.0)
    }

    /** Kept for Java call sites and reflection (NpcType / ClothingScreen use getConstructor(Level)). */
    constructor(level: Level) : this(ModEntities.ELLIE.get(), level)

    override fun onArriveHome() {
        this.sendChatMessage("Okay, I will be residing here then..")
        this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_HUH[0], 6.0f)
    }

    override fun getDisplayNameText(): String = "Ellie"

    /** 1.12 `getLootTable()`. */
    override fun getDefaultLootTable(): ResourceKey<LootTable> = LootTableHandler.ELLIE_TABLE

    fun isBedBlocked(): Boolean {
        return if (this.isLocallyRegistered()) false else this.level().getBlockState(this.blockPosition().offset(0, 2, 0)).block !== Blocks.AIR
    }

    /** 1.12 `getEyeHeight()`: 1.53 while crouched under a block, else 1.9. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions =
        super.getDefaultDimensions(pose).withEyeHeight(if (this.eyeBedBlocked) 1.53f else 1.9f)

    override fun getScaleFactor(): Float = 0.4f

    /**
     * SERVER: the dismount/scene-entry hook called by `KoboldStatePacket`
     * when the player's carry request is accepted. Binds the interaction
     * player, faces her toward him (player yaw - 180) and starts the
     * [Action.CARRY_INTRO] scene anchored; falls back to
     * [resetSitState] when no player is bound.
     */
    override fun setDismounted() {
        val uuid = this.getInteractionPlayerUUID()
        if (uuid == null) {
            this.resetSitState()
        } else {
            val player = this.level().getPlayerByUUID(uuid)
            if (player == null) {
                this.resetSitState()
            } else {
                val yaw = player.yRot - 180.0f
                this.setYawRotation(yaw)
                this.setCurrentAction(Action.CARRY_INTRO)
                this.setAnchored(true)
            }
        }
    }

    override fun shouldRenderNameTag(): Boolean = this.getCurrentAction() !== Action.CARRY_INTRO

    /**
     * CLIENT: the interaction-menu gate. With the sex flag (`dressUp`)
     * offers cowgirl/missionary; otherwise opens the dress-up strip menu when
     * already nude, or the "Face fuck" dialogue when dressed.
     */
    fun canJoinPlayer(player: Player, dressUp: Boolean): Boolean {
        if (dressUp) {
            openInventoryGui(player, this, arrayOf("action.names.cowgirl", "action.names.missionary"), false)
            return true
        } else if (this.entityData.get(OUTFIT_INDEX) == 0) {
            openInventoryGui(player, this, arrayOf("action.names.dressup"), true)
            return true
        } else {
            openInventoryGui(player, this, arrayOf("Face fuck"), true)
            return true
        }
    }

    override fun goHome() {
        super.goHome()
        this.sendChatMessage("stay safe darling~")
        this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_SIGH[1], 6.0f)
    }

    /**
     * CLIENT: scene chooser. Delegates to the NPC defaults (follow/equipment/
     * home), then for the scene actions sets [Action.HUGSELECTED] and
     * writes the follow-up choice into `GIRL_HAND_STATES` via
     * the `animationFollowUp` data-parameter packet; `Face fuck`
     * instead triggers the carry sync through [triggerActionSync].
     * [startActionFromHandState] reads the stored choice server-side.
     */
    override fun doAction(action: String, uuid: UUID) {
        super.doAction(action, uuid)
        this.aq = true
        when (action) {
            "action.names.missionary" -> {
                this.setCurrentAction(Action.HUGSELECTED)
                this.changeDataParameterFromClient("animationFollowUp", "Missionary")
            }
            "action.names.cowgirl" -> {
                this.setCurrentAction(Action.HUGSELECTED)
                this.changeDataParameterFromClient("animationFollowUp", "cowgirl")
            }
            "action.names.dressup", "action.names.strip" -> {
                this.setCurrentAction(Action.STRIP)
                this.changeDataParameterFromClient("animationFollowUp", "")
            }
            "Face fuck" -> {
                this.triggerActionSync(true, true, uuid)
                HandlePlayerMovement.setMovementLock(false)
            }
        }
    }

    override fun alignPlayerToGirl(player: ServerPlayer, teleport: Boolean) {
    }

    /**
     * Guards the state machine: refuses re-entry into loop phases while the
     * corresponding cum animation plays, arms the carry timer
     * (`carryIntroTicks` = 0) on [Action.CARRY_INTRO], and records
     * the sit-up delay (`sitUpTicks` = 79) when
     * [Action.HUGSELECTED] is set SERVER-side.
     */
    override fun setCurrentAction(action: Action?) {
        val currentAction = this.getCurrentAction()
        if (action === Action.HUGSELECTED && !this.level().isClientSide) {
            this.sitUpTicks = 79
        }

        if (currentAction !== Action.MISSIONARY_CUM || action !== Action.MISSIONARY_FAST && action !== Action.MISSIONARY_SLOW) {
            if (currentAction !== Action.COWGIRLCUM || action !== Action.COWGIRLSLOW && action !== Action.COWGIRLFAST) {
                if (currentAction !== Action.CARRY_CUM || action !== Action.CARRY_SLOW && action !== Action.CARRY_FAST) {
                    if (action === Action.CARRY_INTRO) {
                        this.carryIntroTicks = 0
                    }

                    super.setCurrentAction(action)
                }
            }
        }
    }

    /**
     * CLIENT: per-tick scene UI hooks — reopens the sex menu when the
     * `hugDialogueDone` one-shot flag is set (hug dialogue done) and shows the horny
     * meter during the carry loop.
     */
    override fun tick() {
        super.tick()
        val bedBlocked = this.isBedBlocked()
        if (bedBlocked != this.eyeBedBlocked) {
            this.eyeBedBlocked = bedBlocked
            this.refreshDimensions()
        }

        if (this.level().isClientSide) {
            if (this.hugDialogueDone) {
                this.canJoinPlayer(ClientHooks.localPlayer()!!, true) // java-npe: Minecraft.player
                this.hugDialogueDone = false
            }

            this.handleSitIdle()
            this.showHornyMeter()
        }
    }

    /** CLIENT. */
    fun showHornyMeter() {
        if (!HornyMeterHud.isHornyMeterVisible()) {
            if (this.getCurrentAction() === Action.CARRY_SLOW) {
                HornyMeterHud.showHornyMeter()
            }
        }
    }

    /**
     * Ticks the carry-intro timer (`carryIntroTicks`, 110 ticks): when it
     * expires, repositions the interacting player in front of Ellie so the
     * carry scene can start.
     */
    fun handleSitTimer() {
        if (this.carryIntroTicks != -1) {
            if (++this.carryIntroTicks >= 110) {
                this.carryIntroTicks = -1
                if (this.getCurrentAction() === Action.CARRY_INTRO) {
                    val uuid = this.getInteractionPlayerUUID()
                    if (uuid != null) {
                        val player = this.level().getPlayerByUUID(uuid)
                        if (player != null) {
                            val yaw = this.getYawRotation()
                            val pos = this.getTargetPosition().add(
                                VectorMath.rotateByYaw(Vec3(0.0, (2.5625f - player.eyeHeight).toDouble(), -0.3125), 180.0f + yaw),
                            )
                            player.teleportTo(pos.x, pos.y, pos.z)
                        }
                    }
                }
            }
        }
    }

    /** CLIENT. */
    fun handleSitIdle() {
        if (this.getCurrentAction() === Action.SITDOWNIDLE) {
            val player = this.level().getNearestPlayer(this, 10.0)
            if (player != null) {
                if (!(this.distanceTo(player) > 1.5f)) {
                    if (player.uuid == ClientHooks.localPlayerUUID()) {
                        BeeScreen.enableInteraction()
                    }
                }
            }
        }
    }

    override fun updateAITasks() {
        super.updateAITasks()
        this.setFirstSit()
        this.handleHornyPotion()
        this.handleSitUpFinish()
        this.handleSitUpTimer()
        this.handleSitDownTimer()
        this.handleHugTimer()
        this.handleSitTransition()
        this.handleStandTimer()
    }

    fun setFirstSit() {
        if (!this.firstSitCleanupDone) {
            this.firstSitCleanupDone = true
            this.noPhysics = false
            this.setNoGravity(false)
        }
    }

    /**
     * SERVER: the scene dispatcher — reads the scene choice the client stored
     * in `GIRL_HAND_STATES` (the `animationFollowUp` packet) and
     * starts [Action.MISSIONARY_START] or [Action.COWGIRLSTART],
     * stripping her and locking the interacting player into position.
     */
    override fun startActionFromHandState() {
        val handState = this.entityData.get(GIRL_HAND_STATES)
        if ("Missionary" == handState) {
            this.entityData.set(OUTFIT_INDEX, 0)
            this.setCurrentAction(Action.MISSIONARY_START)
            val uuid = this.getInteractionPlayerUUID() ?: return

            val player = this.level().getPlayerByUUID(uuid)
            if (player == null) {
                this.resetCameraAndPhysics()
                return
            }

            player.setNoGravity(true)
            player.noPhysics = true
            var pos = this.getTargetPosition()
            player.yRot = this.getYawRotation()
            val offset = VectorMath.rotateByYaw(Vec3(0.0, 0.0, 0.1), player.yRot)
            pos = pos.add(offset)
            player.teleportTo(pos.x, pos.y, pos.z)
            PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), player as ServerPlayer)
        }

        if ("cowgirl" == handState) {
            this.entityData.set(OUTFIT_INDEX, 0)
            this.setCurrentAction(Action.COWGIRLSTART)
            val uuid = this.getInteractionPlayerUUID() ?: return

            val player = this.level().getPlayerByUUID(uuid)
            if (player == null) {
                this.resetCameraAndPhysics()
                return
            }

            player.setNoGravity(true)
            player.noPhysics = true
            var pos = this.getTargetPosition()
            player.yRot = this.getYawRotation() + 180.0f
            val offset = VectorMath.rotateByYaw(Vec3(0.0, (1.0f - player.eyeHeight).toDouble(), -1.8125), player.yRot)
            pos = pos.add(offset)
            player.teleportTo(pos.x, pos.y, pos.z)
            PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), player as ServerPlayer)
        }
    }

    fun handleStandTimer() {
        if (--this.standTicks == 0) {
            this.startActionFromHandState()
        }
    }

    fun handleSitTransition() {
        if (this.getCurrentAction() === Action.SITDOWNIDLE && this.standTicks < 0) {
            val player = this.level().getNearestPlayer(this, 10.0)
            if (player != null) {
                if (!(this.distanceTo(player) > 1.5f)) {
                    this.standTicks = 20
                    this.setInteractionPlayerUUID(player.uuid)
                }
            }
        }
    }

    fun handleHugTimer() {
        if (--this.yFlag == 0) {
            this.setCurrentAction(Action.HUGIDLE)
        }
    }

    fun handleSitDownTimer() {
        if (--this.sitDownTicks == 0) {
            this.setCurrentAction(Action.SITDOWNIDLE)
        }
    }

    /**
     * SERVER: the sit-up state machine. Runs when the sit-up delay
     * (`sitUpTicks`, 79 ticks) expires: latches `sitUpActive`,
     * un-anchors Ellie, walks her to a random bed sit pose
     * ([getRandomSitPose]), then anchors her and starts
     * [Action.SITDOWN] (110 ticks via `sitDownTicks`) which flows
     * into [Action.SITDOWNIDLE].
     */
    fun handleSitUpTimer() {
        if (--this.sitUpTicks == 0 || this.sitUpActive) {
            this.sitUpActive = true
            this.entityData.set(IS_ANCHORED, false)
            this.setCurrentAction(Action.NULL)
            this.noPhysics = false
            this.setNoGravity(false)
            if (this.sitPose == null) {
                this.sitPose = this.getRandomSitPose()
            }

            val pose = this.sitPose
            if (pose == null) {
                this.sendGirlChatMessage("no bed in sight...")
                this.level().playSound(null, this.blockPosition(), SoundHandler.GIRLS_ELLIE_SIGH[0], SoundSource.NEUTRAL, 6.0f, 1.0f)
                this.resetGirlState()
                this.resetSitState()
            } else {
                val interactionUuid = this.getInteractionPlayerUUID()
                val player = if (interactionUuid == null) null else this.level().getPlayerByUUID(interactionUuid)
                if (player != null) {
                    player.setNoGravity(false)
                    player.noPhysics = false
                }

                val sitPos = pose[0] as Vec3
                val yaw = pose[1] as Int
                if (sitPos.distanceTo(this.position()) > 1.0) {
                    this.getNavigation().moveTo(sitPos.x, sitPos.y, sitPos.z, 0.35f.toDouble())
                    this.tickPathVelocity()
                } else {
                    this.setTargetPosition(sitPos)
                    this.setYawRotation(yaw.toFloat())
                    this.setCurrentAction(Action.SITDOWN)
                    this.entityData.set(IS_ANCHORED, true)
                    this.sitDownTicks = 109
                    this.noPhysics = true
                    this.setNoGravity(true)
                    this.sitUpActive = false
                    this.sitPose = null
                }
            }
        }
    }

    override fun reinitTasks() {
        super.reinitTasks()
        this.yFlag = -1
    }

    /**
     * SERVER: scans the beds around Ellie (expanding radius) and picks the
     * nearest bed side with free space to sit on. Returns a
     * `{position, yaw}` pair or null when no usable bed exists within
     * range.
     */
    fun getRandomSitPose(): Array<Any>? {
        var bestIndex = -1
        var attempts = 0
        val offsets = arrayOf(
            arrayOf(Vec3(0.5, 0.0, -0.18), Vec3(0.0, 0.0, -1.0), Vec3(0.0, 0.0, 1.0)),
            arrayOf(Vec3(0.5, 0.0, 1.18), Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, -1.0)),
            arrayOf(Vec3(-0.18, 0.0, 0.5), Vec3(-1.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)),
            arrayOf(Vec3(1.18, 0.0, 0.5), Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0)),
        )
        val yaws = intArrayOf(0, 180, -90, 90)

        var bedVec: Vec3
        do {
            val bedPos = this.findNearestBed(this.blockPosition(), ++attempts) ?: return null

            bedVec = Vec3(bedPos.x.toDouble(), bedPos.y.toDouble(), bedPos.z.toDouble())

            for (i in offsets.indices) {
                val offsetVec = bedVec.add(offsets[i][1])
                val block = this.level().getBlockState(BlockPos.containing(offsetVec.x, offsetVec.y, offsetVec.z)).block
                val headVec = bedVec.add(offsets[i][2])
                val headState = this.level().getBlockState(BlockPos.containing(headVec.x, headVec.y, headVec.z))
                // 1.12 `Blocks.BED`: every coloured bed (#minecraft:beds)
                if (block === Blocks.AIR && headState.`is`(BlockTags.BEDS)) {
                    if (bestIndex == -1) {
                        bestIndex = i
                    } else {
                        val bestDist = this.blockPosition().distToLowCornerSqr(
                            bedVec.add(offsets[bestIndex][0]).x,
                            bedVec.add(offsets[bestIndex][0]).y,
                            bedVec.add(offsets[bestIndex][0]).z,
                        )
                        val dist = this.blockPosition().distToLowCornerSqr(
                            bedVec.add(offsets[i][0]).x,
                            bedVec.add(offsets[i][0]).y,
                            bedVec.add(offsets[i][0]).z,
                        )
                        if (dist < bestDist) {
                            bestIndex = i
                        }
                    }
                }
            }
        } while (bestIndex == -1)

        val sitOffset = bedVec.add(offsets[bestIndex][0])
        return arrayOf(sitOffset, yaws[bestIndex])
    }

    /**
     * SERVER: the horny-potion route — when Ellie is dosed she locks onto the
     * nearest player, anchors herself, starts the dash ([Action.DASH],
     * `zFlag` = 16) and removes her wander/watch AI so nothing
     * interrupts the approach.
     */
    fun handleHornyPotion() {
        if (this.getEffect(HornyPotion.HORNY_POTION) != null) {
            val player = this.level().getNearestPlayer(this, 10.0)
            if (player != null) {
                this.removeEffect(HornyPotion.HORNY_POTION)
                this.setInteractionPlayerUUID(player.uuid)
                val yaw = (Math.atan2(this.z - player.z, this.x - player.x) * (180.0 / Math.PI)).toFloat()
                this.setYawRotation(yaw)
                this.setTargetPosition(this.position())
                this.entityData.set(IS_ANCHORED, true)
                this.setCurrentAction(Action.DASH)
                this.zFlag = 16
                this.setNoGravity(true)
                this.noPhysics = true
                PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), player as ServerPlayer)
                this.wanderGoal?.let { this.goalSelector.removeGoal(it) }
                this.watchClosestGirlGoal?.let { this.goalSelector.removeGoal(it) }
            }
        }
    }

    /**
     * SERVER: completes the dash (`zFlag` countdown, 16 ticks) by
     * anchoring Ellie in front of the interacting player and starting the hug
     * ([Action.HUG], `yFlag` = 150).
     */
    fun handleSitUpFinish() {
        if (--this.zFlag == 0) {
            val uuid = this.getInteractionPlayerUUID()
            if (uuid == null) {
                this.resetSitState()
            } else {
                val player = this.level().getPlayerByUUID(uuid)
                if (player == null) {
                    this.resetSitState()
                } else {
                    player.setNoGravity(true)
                    player.noPhysics = true
                    val backVec = VectorMath.rotateByYaw(Vec3(0.0, 0.0, -0.5), player.yRot)
                    val sitPos = backVec.add(player.position())
                    this.setTargetPosition(sitPos)
                    this.setYawRotation(player.yRot)
                    this.setCurrentAction(Action.HUG)
                    this.yFlag = 150
                }
            }
        }
    }

    /**
     * SERVER: full sit-state teardown — un-anchors, resets the action to
     * [Action.NULL], unbinds the interaction player and re-enables
     * physics. Resets every sit timer (`sitUpActive`, `yFlag`,
     * `zFlag`, `sitUpTicks`, `sitPose`).
     */
    fun resetSitState() {
        this.entityData.set(IS_ANCHORED, false)
        this.setCurrentAction(Action.NULL)
        this.setInteractionPlayerUUID(null)
        this.noPhysics = false
        this.setNoGravity(false)
        this.sitUpActive = false
        this.yFlag = -1
        this.zFlag = -1
        this.sitUpTicks = -1
        this.sitPose = null
    }

    /**
     * CLIENT: interaction gate — refuses interaction while Ellie is already in
     * a scene (bound to a player or the caller is in one), otherwise opens
     * [canJoinPlayer]. (1.12 `processInteract`.)
     */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (getActiveSceneInfo(player) != null) {
            return InteractionResult.PASS
        }

        if (this.getInteractionPlayerUUID() != null) {
            return InteractionResult.PASS
        }

        if (this.level().isClientSide) {
            this.canJoinPlayer(player, false)
        }

        return InteractionResult.sidedSuccess(this.level().isClientSide)
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

    override fun getNextAction(action: Action): Action? {
        if (action === Action.COWGIRLSLOW) {
            return Action.COWGIRLFAST
        } else if (action === Action.MISSIONARY_SLOW) {
            return Action.MISSIONARY_FAST
        } else {
            return if (action === Action.CARRY_SLOW) Action.CARRY_FAST else null
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        if (SexWorldClient.isPreloadWorld(this.level())) {
            return PlayState.STOP
        }

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
                } else {
                    val moved = Math.abs(this.xo - this.x) + Math.abs(this.zo - this.z)
                    if (moved == 0.0) {
                        this.createAnimation(if (this.isBedBlocked()) "animation.ellie.crouchidle" else "animation.ellie.idle", true, event)
                    } else if (this.isBedBlocked()) {
                        this.createAnimation("animation.ellie.crouchwalk", true, event)
                    } else {
                        when (this.getWalkType()) {
                            BaseGirlEntityState.RUN -> {
                                this.createAnimation("animation.ellie.run", true, event)
                                return PlayState.CONTINUE
                            }
                            BaseGirlEntityState.FAST_WALK -> {
                                this.createAnimation("animation.ellie.fastwalk", true, event)
                                return PlayState.CONTINUE
                            }
                            BaseGirlEntityState.WALK -> this.createAnimation("animation.ellie.walk", true, event)
                        }
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
     * CLIENT: registers the controllers plus the sound listener that advances
     * the hug dialogue, carry, cowgirl and missionary scenes. Key transitions:
     * `hugDone` reopens the scene menu, `stripDone` exits the
     * strip, `cowgirlStartDone`/`missionary_startDone` -> slow
     * loops, jump on the fast-done keyframes keeps the fast loop,
     * `carry_slowDone` re-rolls the variant suffix (`carryVariant`), and
     * `missionary_cumDone`/`cowgirlcumDone`/`carry_cumDone`
     * -> `resetCameraAndPhysics()`.
     */
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        val soundListener = AnimationController.SoundKeyframeHandler<BaseGirlEntity> { sound ->
            when (sound.keyframeData.sound) {
                "becomeNude" -> if (this.isLocalPlayerNearby()) {
                    this.changeDataParameterFromClient("currentModel", if (this.entityData.get(OUTFIT_INDEX) == 1) "0" else "1")
                }
                "stripDone" -> {
                    this.setCurrentAction(null)
                    this.resetCameraAndPhysics()
                    this.startActionFromHandState()
                }
                "hugMSG2" -> {
                    this.sendGirlChatMessage("Hmm...")
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_HMPH[3], 6.0f)
                }
                "hugMSG3" -> {
                    this.sendGirlChatMessage("Hey!")
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_HUH[1], 1.0f)
                }
                "hugMSG4" -> {
                    this.sendGirlChatMessage(I18n.get("ellie.dialogue.mommyhorny"))
                    this.playRandomSoundAtVolume(SoundHandler.GIRLS_ELLIE_MOMMYHORNY, 0.5f)
                }
                "hugMSG5" -> {
                    this.sendGirlChatMessage(I18n.get("ellie.dialogue.whattodo"))
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_HUH[1], 6.0f)
                }
                "hugDone" -> if (this.isControlledByLocalPlayer()) {
                    this.canJoinPlayer(ClientHooks.localPlayer()!!, true) // java-npe: Minecraft.player
                }
                "hugselectedMSG1" -> {
                    this.sendGirlChatMessage(I18n.get("ellie.dialogue.iknow"))
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_HMPH[3], 6.0f)
                }
                "hugselectedMSG2" -> {
                    this.sendGirlChatMessage(I18n.get("ellie.dialogue.followmedarling"))
                    this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_GIGGLE[3], 6.0f)
                    if (this.isControlledByLocalPlayer()) {
                        HandlePlayerMovement.setMovementLock(true)
                    }
                }
                "sitdownMSG1" -> {
                    this.playRandomSoundAtVolume(SoundHandler.GIRLS_ELLIE_COMETOMOMMY, 0.5f)
                    if (this.isLocalPlayerNearby()) {
                        this.sendGirlChatMessage(I18n.get("ellie.dialogue.cometomommy"))
                    }
                }
                "cowgirlStartMSG0" -> this.playSoundAtVolume(SoundHandler.GIRLS_ELLIE_GIGGLE[4], 6.0f)
                "cowgirlStartMSG1" -> if (this.isLocalPlayerNearby()) {
                    this.sendChatMessage(I18n.get("ellie.dialogue.like"))
                    HornyMeterHud.resetHornyMeter()
                }
                "cowgirlStartMSG2" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 6.0f)
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                    this.addToHornyMeterIfControlled(0.02)
                }
                "cowgirlStartDone" -> if (this.isControlledByLocalPlayer()) {
                    this.setCurrentAction(Action.COWGIRLSLOW)
                    HornyMeterHud.showHornyMeter()
                }
                "cowgirlfastMSG1" -> {
                    if (this.alternateMoan) {
                        this.alternateMoan = false
                    } else {
                        this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 6.0f)
                    }

                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                    this.addToHornyMeterIfControlled(0.04)
                }
                "cowgirlfastDone" -> if (this.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    this.setCurrentAction(Action.COWGIRLSLOW)
                }
                "cowgirlfastdomMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.MISC_POUNDING), 0.75f)
                    this.addToHornyMeterIfControlled(0.2)
                }
                "cowgirlcumMSG1" -> {
                    this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 6.0f)
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
                    this.playRandomSoundAtVolume(SoundHandler.GIRLS_ELLIE_GOODBOY, 0.5f)
                    if (this.isControlledByLocalPlayer()) {
                        this.sendChatMessage(I18n.get("ellie.dialogue.goodboy"))
                    }
                }
                "cowgirlcumMSG6", "blackScreen" -> if (this.isControlledByLocalPlayer()) {
                    BeeScreen.enableInteraction()
                }
                "missionary_cumDone", "cowgirlcumDone", "carry_cumDone" -> if (this.isControlledByLocalPlayer()) {
                    HornyMeterHud.resetHornyMeter()
                    this.resetCameraAndPhysics()
                }
                "attackSound" -> this.playSound(SoundEvents.PLAYER_ATTACK_STRONG)
                "attackDone" -> {
                    this.setCurrentAction(Action.NULL)
                    if (++this.nextAttack == 3) {
                        this.nextAttack = 0
                    }
                }
                "pearl" -> PacketHandler.networkWrapper.sendToServer(SendCompanionHomePacket(this.getGirlId()))
                "openSexUi" -> if (this.isLocalPlayerNearby()) {
                    HornyMeterHud.showHornyMeter()
                }
                "missionary_slowMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    if (this.random.nextBoolean() && this.random.nextBoolean()) {
                        this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_MOAN), 6.0f)
                    } else {
                        this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 6.0f)
                    }

                    this.addToHornyMeterIfControlled(0.02)
                }
                "missionary_fastMSG1" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    if (!this.random.nextBoolean() && !this.random.nextBoolean()) {
                        this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 6.0f)
                    } else {
                        this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_MOAN), 6.0f)
                    }

                    this.addToHornyMeterIfControlled(0.05)
                }
                "missionary_startDone" -> if (this.isControlledByLocalPlayer()) {
                    this.setCurrentAction(Action.MISSIONARY_SLOW)
                    HornyMeterHud.showHornyMeter()
                }
                "missionary_fastDone" -> if (this.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    this.setCurrentAction(Action.MISSIONARY_SLOW)
                }
                "bedRustle" -> {
                    this.playSound(SoundHandler.randomSound(SoundHandler.MISC_POUNDING))
                    this.playSound(SoundHandler.MISC_BEDRUSTLE[0])
                }
                "bedRustle1" -> this.playSound(SoundHandler.MISC_BEDRUSTLE[1])
                "missionary_cumMSG1" -> this.playSoundAtVolume(SoundHandler.randomSound(SoundHandler.GIRLS_ELLIE_AHH), 6.0f)
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
                    this.addToHornyMeterIfControlled(0.02)
                }
                "cum" -> {
                    this.playRandomSoundAtVolume(SoundHandler.MISC_INSERTS, 6.0f)
                    this.playRandomSound(SoundHandler.MISC_POUNDING)
                }
                "pound" -> {
                    this.playRandomSound(SoundHandler.MISC_POUNDING)
                    this.addToHornyMeterIfControlled(0.04)
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
            }
        }
        this.registerControllers(controllers, soundListener)
    }

    companion object {
        const val ad: Float = 10.0f
        const val ao: Int = 16
        const val ap: Int = 79
        const val ag: Int = 109
        const val `as`: Int = 150
        const val ar: Int = 20
        const val ab: Int = 110
        const val an: Int = 4

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractGirlNpcEntity.createAttributes()
    }
}
