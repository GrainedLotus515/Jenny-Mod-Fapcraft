package com.trolmastercard.sexmod.entity

import com.mojang.datafixers.util.Pair
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.GirlInventoryScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.client.model.GirlAnimationController
import com.trolmastercard.sexmod.client.renderer.GirlRenderer
import com.trolmastercard.sexmod.client.renderer.SexSceneRenderer
import com.trolmastercard.sexmod.entity.ai.DoorInteractAiGoal
import com.trolmastercard.sexmod.entity.ai.WatchClosestGirlGoal
import com.trolmastercard.sexmod.networking.ChangeDataParameterPacket
import com.trolmastercard.sexmod.networking.KoboldStatePacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.ResetControllerPacket
import com.trolmastercard.sexmod.networking.ResetGirlPacket
import com.trolmastercard.sexmod.networking.TargetPoint
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.LootTableHandler
import com.trolmastercard.sexmod.util.MatrixStack
import com.trolmastercard.sexmod.util.Point2D
import com.trolmastercard.sexmod.util.Reference
import com.trolmastercard.sexmod.util.SceneDebug
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.GirlRegistry
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.ConcurrentModificationException
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.TemptGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ThrownEnderpearl
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animatable.GeoEntity
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationProcessor
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState
import software.bernie.geckolib.animation.RawAnimation
import software.bernie.geckolib.model.GeoModel
import software.bernie.geckolib.util.GeckoLibUtil

/**
 * Base class for every Fapcraft girl — NPCs (Jenny, Bia, Luna, Ellie, Slime,
 * Bee, Goblin, Allie, Kobold, Galath, Manglelie) and the horny-potion
 * player-form variants (subclasses of [AbstractPlayerGirlEntity]).
 *
 * <b>Shared state</b> lives in the vanilla [SynchedEntityData]. In 1.12 the
 * keys below used <b>explicit serializer IDs 99..110</b> so the same keys were
 * compatible across the whole girl hierarchy — including
 * [AbstractNpcOnlyEntity]'s keys 119..121. 1.21 assigns dense per-class ids,
 * so the keys that siblings really shared are hoisted into this class
 * (`SHARED_*`, PORTING.md §2.6) and defined for every girl.
 *
 * <b>Key semantics</b> (all synced to the client automatically):
 * - [GIRL_ID] — persistent girl UUID, minted on first access.
 * - [CUR_ACTION] — the current [Action], advanced by
 *   [setCurrentAction] (client writes route via `ChangeDataParameterPacket`).
 * - [GIRL_HAND_STATES] — the scene-entry hand-state, written by the
 *   client as `"animationFollowUp"` and consumed by each girl's
 *   `startActionFromHandState()` dispatcher after the dismount lerp completes.
 * - [INTERACTION_PARTNER_UUID] — the player bound to the current
 *   scene ("null" string when unset).
 * - [IS_ANCHORED] / [TARGET_POS] / [YAW_ROTATION] —
 *   anchor lock: while anchored the girl is held at target position/yaw
 *   every tick in [updateAITasks].
 * - [MASTER] — the owning player's UUID (follow mode).
 * - [OUTFIT_INDEX] — 0 = nude, 1 = dressed.
 * - [WALK_SPEED], [CUSTOM_MODEL_KEY], [CUSTOM_NAME] —
 *   walk state, whitelisted custom-model code, custom display name.
 *
 * <b>Scene lifecycle</b> (see [Action] for the state machine):
 * 1. Entry: client `doAction` sets `GIRL_HAND_STATES` via
 *    `ChangeDataParameterPacket` and sends `KoboldStatePacket`;
 *    the server calls `setDismounted()` (per-girl flag) and the girl
 *    lerps ~40 ticks to `TARGET_POS` (see `RotationHelper.lerpVec3d` — INT
 *    variant!), then anchors and calls `startActionFromHandState()` to pick
 *    the scene action.
 * 2. Progression: sound-keyframe transitions in `registerControllers`
 *    (primary) + SERVER-side follow-ups in [tickFollowUpTransitions]
 *    + input (sneak/jump via `HandlePlayerMovement`).
 * 3. End: the cum action's `xxx_cumDone` keyframe calls
 *    [resetCameraAndPhysics] -> `resetLocalPlayerClientState()`
 *    -> single-arg `ResetGirlPacket` = FULL reset (player physics +
 *    girl release, `reinitTasks()`).
 *
 * <b>Pitfalls:</b>
 * - On the client, data-manager writes MUST go through
 *   [changeDataParameterFromClient] — writing the
 *   data manager directly on the client never reaches the server.
 * - [tick] (1.12 `onUpdate`) must never remove the girl — the original
 *   jar has no removal there; a deobf-regression did and made girls
 *   vanish on benign tick exceptions.
 * - [resetGirlState] (R-Shift) sends the two-arg TRUE packet
 *   (player-only reset); the natural scene end uses the single-arg packet.
 *   See `ResetGirlPacket`.
 *
 * Port notes (1.21 / GeckoLib 4): `updateAITasks()` stays the mod's public
 * per-AI-tick hook and is invoked from [customServerAiStep]; the GL4 animation
 * contract is PORTING.md §2.8. COMMON class: client-only work goes through
 * [ClientHooks] or the nested [Client] object.
 */
abstract class BaseGirlEntity : PathfinderMob, GeoEntity {
    private val animationFactory: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    @JvmField
    var wanderGoal: WaterAvoidingRandomStrollGoal? = null

    @JvmField
    var watchClosestGirlGoal: WatchClosestGirlGoal? = null

    @JvmField
    var cameraOriginPos: Vec3? = null

    @JvmField
    var cameraYaw: Float = 0f

    /** 1.12 `entityDataManager` field; the vanilla synced-data store. */
    val entityDataManager: SynchedEntityData
        get() = this.entityData

    @JvmField
    var pathNavigator: PathNavigation

    @JvmField
    var homePos: Vec3 = Vec3.ZERO

    @JvmField
    var activeEnderPearl: ThrownEnderpearl? = null

    @JvmField
    var scaleFactor: Float = 1.0f

    @JvmField
    var isSpecialState: Boolean = false

    private var locallyRegistered: Boolean = false

    @JvmField
    var boneOffsetCache: HashMap<String, Vec3> = HashMap()

    @JvmField
    var actionController: GirlAnimationController<BaseGirlEntity>? = null

    @JvmField
    var movementController: GirlAnimationController<BaseGirlEntity>? = null

    @JvmField
    var eyesController: GirlAnimationController<BaseGirlEntity>? = null

    @JvmField
    var animationVariantMap: HashMap<String, Pair<Int, Int>> = HashMap()

    @JvmField
    var cachedAnimationProcessor: AnimationProcessor<*>? = null

    @JvmField
    var boneTrackingList: MutableList<String> = ArrayList()

    /** Ticks the current action has played, indexed `[server, client]` — this girl's own counter. */
    @JvmField
    val actionTicks: IntArray = intArrayOf(0, 0)

    @JvmField
    var lastTickAction: Action? = null

    /** Decoded [TARGET_POS] / [INTERACTION_PARTNER_UUID] memo, keyed by the raw data-manager string. */
    @JvmField
    var cachedTargetPosRaw: String? = null

    @JvmField
    var cachedTargetPos: Vec3 = Vec3.ZERO

    @JvmField
    var cachedInteractionPlayerRaw: String? = null

    @JvmField
    var cachedInteractionPlayerUUID: UUID? = null

    @JvmField
    var customPartsData: List<Map.Entry<BoneType, Map.Entry<List<String>, Int>>>? = null

    /**
     * 1.12 `EntityLivingBase.jumpMovementFactor` (air acceleration, default
     * 0.02). 1.21 has no field; [getFlyingSpeed] returns it so the goals that
     * tune it (`GirlGotoGoal`) keep working.
     */
    @JvmField
    var jumpMovementFactor: Float = 0.02f

    protected constructor(type: EntityType<out BaseGirlEntity>, level: Level) : super(type, level) {
        if (level.isClientSide) {
            this.initAnimationControllers()
        }

        if (!level.isClientSide || !SexWorldClient.isPreloadWorld(level)) {
            val navigator = this.getNavigation()
            if (navigator is GroundPathNavigation) {
                // 1.12 PathNavigateGround.setBreakDoors(true): the node evaluator treats doors as passable
                navigator.setCanOpenDoors(true)
            }
        }

        // 1.12 entityInit assigned this from inside the super constructor
        this.pathNavigator = this.getNavigation()
    }

    /** Sets the walk-mode state (WALK/FAST_WALK/RUN) stored in the data manager. */
    fun setWalkSpeed(state: BaseGirlEntityState) {
        GirlSyncedState.setWalkSpeed(this, state)
    }

    /** @return the current walk-mode state */
    fun getWalkType(): BaseGirlEntityState = GirlSyncedState.getWalkType(this)

    /** CLIENT: routes a data-manager write to the server (`ChangeDataParameterPacket`). */
    open fun changeDataParameterFromClient(key: String, value: String?) {
        PacketHandler.networkWrapper.sendToServer(ChangeDataParameterPacket(this.getGirlId(), key, value))
    }

    /** @return this girl's persistent UUID, minted on first access if unset */
    fun getGirlId(): UUID = GirlSyncedState.getGirlId(this)

    /** @return the current scene action (state stored in the data manager) */
    fun getCurrentAction(): Action = GirlSyncedState.getCurrentAction(this)

    /**
     * Sets the girl's current scene action (CLIENT: routed through a
     * ChangeDataParameterPacket; SERVER: applied directly, resetting the
     * action's tick counter). ATTACK is only allowed from [Action.NULL].
     * @param action the target state; null is treated as [Action.NULL]
     */
    open fun setCurrentAction(action: Action?) {
        GirlSyncedState.setCurrentAction(this, action)
    }

    /** @return the outfit/model index (0 = nude, 1 = dressed) */
    open fun getOutfitIndex(): Int = GirlSyncedState.getOutfitIndex(this)

    /** Sets the outfit index; on the client this is routed through a packet. */
    open fun setOutfitIndex(index: Int) {
        GirlSyncedState.setOutfitIndex(this, index)
    }

    open fun isCustomType(): Boolean = false

    /** @return the player this girl is currently having sex with, or null */
    open fun getPlayerEntity(): Player? = GirlSyncedState.getPlayerEntity(this)

    /** CLIENT: one block from the local player toward the girl. */
    open fun getVectorTowardPlayer(): Vec3 = GirlPlayerPlacement.getVectorTowardPlayer(this)

    /** @return UUID of the player bound to this girl's scene, or null */
    open fun getInteractionPlayerUUID(): UUID? = GirlSyncedState.getInteractionPlayerUUID(this)

    open fun setInteractionPlayerUUID(uuid: UUID?) {
        GirlSyncedState.setInteractionPlayerUUID(this, uuid)
    }

    open fun setInteractionPlayer(player: Player) {
        GirlSyncedState.setInteractionPlayer(this, player)
    }

    /** @return the anchored target position ("0|0|0" if unset), used by the renderer/state machine */
    open fun getTargetPosition(): Vec3 = GirlSyncedState.getTargetPosition(this)

    /** Sets the target position; on the client routed through a packet. */
    open fun setTargetPosition(pos: Vec3) {
        GirlSyncedState.setTargetPosition(this, pos)
    }

    open fun setTargetPositionDirect(pos: Vec3) {
        GirlSyncedState.setTargetPositionDirect(this, pos)
    }

    /** @return the anchored yaw rotation of the girl */
    open fun getYawRotation(): Float = GirlSyncedState.getYawRotation(this)

    open fun setYawRotation(yaw: Float) {
        GirlSyncedState.setYawRotation(this, yaw)
    }

    /** Anchors the girl to [getTargetPosition]; on the client routed through a packet. */
    open fun setAnchored(anchored: Boolean) {
        GirlSyncedState.setAnchored(this, anchored)
    }

    /** @return true while the girl is locked to her target position/yaw */
    open fun isAnchored(): Boolean = GirlSyncedState.isAnchored(this)

    /** 1.12 `canDespawn()` = false. */
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    /** CLIENT: creates the action(0) / movement(5) / eyes(10) controllers, all driven by [animationPredicate]. */
    open fun initAnimationControllers() {
        this.actionController = GirlAnimationController(this, "action", 0) { this.animationPredicate(it) }
        this.movementController = GirlAnimationController(this, "movement", 5) { this.animationPredicate(it) }
        this.eyesController = GirlAnimationController(this, "eyes", 10) { this.animationPredicate(it) }
    }

    /**
     * CLIENT: lazily creates the animation controllers, attaches the girl's
     * sound listener to the action controller and registers the standard
     * action/movement/eyes trio. Girls that drive a different set (Slime,
     * Manglelie) register their controllers by hand.
     * @param controllers the registrar handed to [registerControllers]
     * @param soundListener the girl's keyframe sound handler
     */
    open fun registerControllers(
        controllers: AnimatableManager.ControllerRegistrar,
        soundListener: AnimationController.SoundKeyframeHandler<BaseGirlEntity>,
    ) {
        if (this.actionController == null) {
            this.initAnimationControllers()
        }

        val action = this.actionController!! // java-npe: initAnimationControllers must create it
        action.registerSoundListener(soundListener)
        controllers.add(action)
        controllers.add(this.movementController!!) // java-npe
        controllers.add(this.eyesController!!) // java-npe
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(GIRL_ID, UUID.randomUUID().toString())
        builder.define(OUTFIT_INDEX, 1)
        builder.define(CUR_ACTION, Action.NULL.toString())
        builder.define(GIRL_HAND_STATES, "")
        builder.define(INTERACTION_PARTNER_UUID, "null")
        builder.define(IS_ANCHORED, false)
        builder.define(YAW_ROTATION, 0.0f)
        builder.define(TARGET_POS, "0|0|0")
        builder.define(MASTER, "")
        builder.define(WALK_SPEED, BaseGirlEntityState.WALK.toString())
        builder.define(CUSTOM_MODEL_KEY, "")
        builder.define(CUSTOM_NAME, "")
        // hoisted sibling keys (PORTING.md §2.6); owning subclasses set their defaults in their constructors
        builder.define(SHARED_STRING_119, "")
        builder.define(SHARED_BLOCKPOS_120, BlockPos.ZERO)
        builder.define(SHARED_STRING_121, "")
        builder.define(SHARED_FLOAT_122, 0.0f)
        builder.define(SHARED_STRING_122, "")
        builder.define(SHARED_BOOL_126, false)
    }

    open fun setLocallyRegistered(registered: Boolean) {
        this.locallyRegistered = registered
        if (registered) {
            GirlRegistry.registerGirl(this)
        } else {
            GirlRegistry.unregisterGirl(this)
        }
    }

    open fun isLocallyRegistered(): Boolean = this.locallyRegistered

    open fun canBeInteractedWith(): Boolean = true

    override fun registerGoals() {
        this.goalSelector.addGoal(0, FloatGoal(this))
        this.goalSelector.addGoal(2, TemptGoal(this, 0.4, Ingredient.of(*TEMPTATION_ITEMS.toTypedArray()), false))
        this.goalSelector.addGoal(3, DoorInteractAiGoal(this))
        this.resetWanderGoals()
    }

    /**
     * Creates the wander and watch-closest-player goals and adds both at task
     * priority 5. Girls that remove the two goals from the goal selector on the
     * way to a bed call this to put them back in `reinitTasks()`.
     */
    open fun resetWanderGoals() {
        val wander = WaterAvoidingRandomStrollGoal(this, 0.35)
        val watch = WatchClosestGirlGoal(this, Player::class.java, 3.0f, 1.0f)
        this.wanderGoal = wander
        this.watchClosestGirlGoal = watch
        this.goalSelector.addGoal(5, watch)
        this.goalSelector.addGoal(5, wander)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        GirlNbtIO.write(this, tag)
        super.addAdditionalSaveData(tag)
    }

    open fun supportsCustomModels(): Boolean = isValidGirl(this)

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        GirlNbtIO.read(this, tag)
    }

    open fun shouldRenderModel(): Boolean = true

    /** Overrides the velocity (used to lock/clear girl motion). */
    open fun setVelocity(x: Double, y: Double, z: Double) {
        this.setDeltaMovement(x, y, z)
    }

    /** Sets the entity velocity from a vector. */
    open fun setVelocity(velocity: Vec3) {
        this.setDeltaMovement(velocity.x, velocity.y, velocity.z)
    }

    open fun getLastTickPosVector(): Vec3 = Vec3(this.xOld, this.yOld, this.zOld)

    /** 1.21 entry point of the 1.12 `updateAITasks()` (SERVER, once per AI tick). */
    override fun customServerAiStep() {
        super.customServerAiStep()
        this.updateAITasks()
    }

    /** Holds the girl at her anchor position when anchored, then re-applies custom parts. */
    open fun updateAITasks() {
        if (this.getInteractionPlayerUUID() != null || this.isAnchored()) {
            SceneDebug.log(
                SceneDebug.AI_TICK,
                "AI %s remote=%s dead=%s tick=%d anchored=%s action=%s target=%s interact=%s",
                this.getDisplayNameText(), this.level().isClientSide, this.isRemoved, this.tickCount,
                this.entityData.get(IS_ANCHORED), this.getCurrentAction(), this.getTargetPosition(),
                this.getInteractionPlayerUUID(),
            )
        } else if (this.tickCount % 40 == 0) {
            SceneDebug.log(
                SceneDebug.AI_TICK,
                "updateAITasks %s remote=%s anchored=%s action=%s target=%s interact=%s",
                this.getDisplayNameText(), this.level().isClientSide, this.entityData.get(IS_ANCHORED),
                this.getCurrentAction(), this.getTargetPosition(), this.getInteractionPlayerUUID(),
            )
        }

        if (this.entityData.get(IS_ANCHORED)) {
            this.setYHeadRot(this.getYawRotation())
            this.absMoveTo(
                this.getTargetPosition().x, this.getTargetPosition().y, this.getTargetPosition().z,
                this.getYawRotation(), 0.0f,
            )
            this.setRot(this.getYawRotation(), this.getXRot())
        }

        if (this.homePos == Vec3.ZERO) {
            this.homePos = Vec3.atLowerCornerOf(this.blockPosition())
        }

        this.updateCustomModelParts()
    }

    /** Called every tick on both sides (1.12 `onUpdate`); advances scene follow-up transitions. */
    override fun tick() {
        // Scene-state heartbeat: bounded logging while a scene is active so the
        // tick continuity of the girl can be verified in the built jar.
        if (this.getInteractionPlayerUUID() != null || this.isAnchored()) {
            if (this.tickCount % 5 == 0) {
                SceneDebug.log(
                    SceneDebug.HEARTBEAT,
                    "HEARTBEAT %s remote=%s dead=%s tick=%d action=%s anchored=%s interact=%s",
                    this.getDisplayNameText(), this.level().isClientSide, this.isRemoved, this.tickCount,
                    this.getCurrentAction(), this.isAnchored(), this.getInteractionPlayerUUID(),
                )
            }
        }
        // Defensive: older buggy builds could leave a girl with a corrupted ride
        // (stale reference to a removed entity, or a ride whose passenger chain is
        // inconsistent). Vanilla's passenger handling NPEs on such state during the
        // tick; clear it before super so old worlds load instead of crashing in
        // LivingEntity. This is a DISMOUNT-ONLY guard: it never removes the
        // girl. The original jar has no setDead anywhere in onUpdate — the
        // previous "harden onUpdate" attempt (be258ed) deleted girls on ANY tick
        // exception, which is what made Jenny/Bia disappear and stay gone on
        // reload. The current narrowed variant still deletes girls whenever an NPE
        // happens while the girl is riding (which is the normal state during
        // follow-mode riding and some scenes). Jar-faithful behavior = tick
        // through, never setDead.
        if (!this.level().isClientSide) {
            try {
                val ride = this.vehicle
                if (ride != null && (ride.isRemoved || !ride.isAlive)) {
                    this.stopRiding()
                }
            } catch (t: Throwable) {
                // corrupt chain — force-dismount via the field-clearing path
                this.stopRiding()
            }
        }
        super.tick()
        this.tickFollowUpTransitions()
    }

    /**
     * Removes server-whitelisted custom parts from the girl's model code and
     * persists the filtered set (SERVER side; respects ServerWhitelistManager).
     */
    open fun updateCustomModelParts() {
        GirlCustomParts.updateCustomModelParts(this)
    }

    /**
     * Advances the scene: when the current action's tick counter reaches its
     * length, transitions to the action's follow-up (SERVER side only).
     * Jar-faithful: has NO hasPlayer reset branch (see DOCUMENTATION 2p).
     *
     * The counter is per-entity ([actionTicks]) so two girls playing the
     * same action no longer share — and double-advance — one enum-constant
     * counter. [Action.ticksPlaying] is still written as a mirror for the
     * legacy per-action reads (Galath's coin/boost scenes).
     */
    open fun tickFollowUpTransitions() {
        val action = this.getCurrentAction()
        val side = if (this.level().isClientSide) 1 else 0
        if (action !== this.lastTickAction) {
            this.lastTickAction = action
            this.actionTicks[0] = 0
            this.actionTicks[1] = 0
        }

        this.actionTicks[side] = this.actionTicks[side] + 1
        action.ticksPlaying[side] = this.actionTicks[side]
        if (this.actionTicks[side] >= action.length) {
            val followUp = action.followUp
            if (followUp != null && !this.level().isClientSide) {
                SceneDebug.log(
                    SceneDebug.ACTIONS, "tickFollowUp %s: followUp %s -> %s",
                    this.getDisplayNameText(), action, followUp,
                )
                this.setCurrentAction(followUp)
            }
        }
    }

    open fun tickPathVelocity() {
        val path = this.getNavigation().path
        if (path != null) {
            if (!this.onGround() && !this.isInWater) {
                val currentIndex = path.nextNodeIndex
                val length = path.nodeCount
                if (length != currentIndex && length - 1 != currentIndex) {
                    val currentPoint = path.getNode(currentIndex)
                    val nextPoint = path.getNode(currentIndex + 1)
                    val delta = Vec3(
                        (nextPoint.x - currentPoint.x).toDouble(),
                        (nextPoint.y - currentPoint.y).toDouble(),
                        (nextPoint.z - currentPoint.z).toDouble(),
                    )
                    this.setDeltaMovement(delta.x / 7.0, this.deltaMovement.y, delta.z / 7.0)
                }
            }
        }
    }

    open fun reinitTasks() {
    }

    /** CLIENT. */
    open fun openInteractionMenu(player: Player): Boolean = false

    open fun setHeldItemOverride(stack: ItemStack) {
        this.useItem = stack
    }

    open fun setItemUseCount(count: Int) {
        this.useItemRemaining = count
    }

    open fun getPreviousPosition(): Vec3 = Vec3(this.xo, this.yo, this.zo)

    open fun getSelf(): BaseGirlEntity = this

    /** 1.12 `setDead()` override; 1.21 removal goes through [remove] (callers use `discard()`). */
    override fun remove(reason: RemovalReason) {
        SceneDebug.log(
            SceneDebug.SET_DEAD,
            "setDead %s remote=%s tick=%d action=%s anchored=%s interact=%s riding=%s pos=%s",
            this.getDisplayNameText(), this.level().isClientSide, this.tickCount, this.getCurrentAction(),
            this.isAnchored(), this.getInteractionPlayerUUID(), this.vehicle, this.position(),
        )
        super.remove(reason)
    }

    /** Clears the master binding and walk state back to WALK (client routed through a packet). */
    open fun goHome() {
        GirlSyncedState.goHome(this)
    }

    open fun alignPlayerToGirl(player: ServerPlayer, teleport: Boolean) {
        GirlPlayerPlacement.alignPlayerToGirl(this, player, teleport)
    }

    open fun snapPlayerToPosition(playerUUID: UUID) {
        GirlPlayerPlacement.snapPlayerToPosition(this, playerUUID)
    }

    open fun triggerActionSync(flag1: Boolean, flag2: Boolean, playerUUID: UUID) {
        if (this.level().isClientSide) {
            PacketHandler.networkWrapper.sendToServer(KoboldStatePacket(this.getGirlId(), playerUUID, flag1, flag2))
        } else {
            KoboldStatePacket.Handler.sendState(this.getGirlId(), playerUUID, flag1, flag2)
        }
    }

    open fun getNearestBed(pos: BlockPos): BlockPos? = GirlBlockScan.getNearestBed(this, pos)

    open fun findNearestBed(origin: BlockPos, radius: Int): BlockPos? = GirlBlockScan.findNearestBed(this, origin, radius)

    /** 1.12 `HAND_STATES` = vanilla `DATA_LIVING_ENTITY_FLAGS`. */
    open fun setHandActiveState() {
        this.entityData.set(DATA_LIVING_ENTITY_FLAGS, 1.toByte())
    }

    open fun clearHandActiveState() {
        this.entityData.set(DATA_LIVING_ENTITY_FLAGS, 0.toByte())
    }

    open fun findNearestStructureBlock(
        origin: BlockPos,
        requiredCount: Int,
        targetBlock: Block,
        maxRadius: Int,
        heightRange: Int,
        allowedBiomes: HashSet<ResourceKey<Biome>>?,
    ): BlockPos? = GirlBlockScan.findNearestStructureBlock(this, origin, requiredCount, targetBlock, maxRadius, heightRange, allowedBiomes)

    open fun findBlocksInRadius(
        center: BlockPos,
        blockClass: Class<*>,
        maxRadius: Int,
        height: Int,
        biomes: HashSet<ResourceKey<Biome>>?,
    ): List<BlockPos> = GirlBlockScan.findBlocksInRadius(this, center, blockClass, maxRadius, height, biomes)

    /** @return true if this girl is bound to a master player UUID */
    open fun hasMaster(): Boolean = GirlSyncedState.hasMaster(this)

    /** @return the master player's UUID, or null if unbounded */
    open fun getMasterUUID(): UUID? = GirlSyncedState.getMasterUUID(this)

    /** @return the master player entity, or null */
    open fun getMasterPlayer(): Player? = GirlSyncedState.getMasterPlayer(this)

    /** 1.12 `getLootTable()`. */
    override fun getDefaultLootTable(): ResourceKey<LootTable> = LootTableHandler.JENNY_TABLE

    /** CLIENT: interaction-menu action dispatch. */
    open fun doAction(action: String, uuid: UUID) {
    }

    /** CLIENT: the animation predicate shared by every controller (switches on the controller name). */
    abstract fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState

    /** CLIENT. */
    open fun handleActionAnimationOverrides(action: Action, animName: String, started: Boolean, event: AnimationState<*>): Boolean = false

    /** CLIENT. */
    open fun createAnimation(animName: String, loop: Boolean, event: AnimationState<*>, force: Boolean) {
        if (force ||
            !Action.isActionComplete(this, event.partialTick) ||
            !this.handleActionAnimationOverrides(this.getCurrentAction(), animName, HandlePlayerMovement.isJumping, event)
        ) {
            val animation = if (loop) RawAnimation.begin().thenLoop(animName) else RawAnimation.begin().thenPlayAndHold(animName)
            event.controller.setAnimation(animation)
            // GL3 `controller.transitionLengthTicks = 0.0`
            event.controller.transitionLength(0)
        }
    }

    /** CLIENT. */
    open fun createAnimation(animName: String, loop: Boolean, event: AnimationState<*>) {
        this.createAnimation(animName, loop, event, false)
    }

    /** CLIENT. */
    open fun playRandomizedAnimation(animName: String, maxVariants: Int, chance: Float, event: AnimationState<*>, force: Boolean) {
        GirlAnimationVariants.playRandomizedAnimation(this, animName, maxVariants, chance, event, force)
    }

    /** CLIENT. */
    open fun playRandomizedAnimation(animName: String, maxVariants: Int, chance: Float, event: AnimationState<*>) {
        this.playRandomizedAnimation(animName, maxVariants, chance, event, false)
    }

    open fun pickRandomVariant(baseVariant: Int, exclude: Int, max: Int, chance: Float): Int =
        GirlAnimationVariants.pickRandomVariant(this, baseVariant, exclude, max, chance)

    /** CLIENT (GL4 calls it lazily when the first animatable manager is created). */
    abstract override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar)

    /**
     * Leaves the current scene: sends ResetGirlPacket(uuid, true) on the client
     * (full reset) or resets the bound player on the server. R-Shift keybind hook.
     */
    open fun resetGirlState() {
        if (this.level().isClientSide && this.isControlledByLocalPlayer()) {
            this.cameraOriginPos = null
            PacketHandler.networkWrapper.sendToServer(ResetGirlPacket(this.getGirlId(), true))
        } else if (!this.level().isClientSide) {
            val uuid = this.getInteractionPlayerUUID()
            val player = if (uuid == null) null else this.level().getPlayerByUUID(uuid) as ServerPlayer?
            ResetGirlPacket.Handler.resetGirls(player)
        }
    }

    /**
     * CLIENT: where the custom-model scene entity is drawn for this girl.
     * Port deviation: the 1.12 `Minecraft mc` first parameter is dropped
     * (client type in a common class); the renderer's `mc` is supplied inside [Client].
     */
    open fun renderCustomModelTransform(scene: SexSceneEntity, entity: LivingEntity, partialTicks: Float): Vec3 =
        Client.getSceneEntityPosition(scene, entity, this, partialTicks)

    /** CLIENT. */
    open fun onInteractionMenuClosed() {
    }

    open fun resetCameraAndPhysics() {
        SceneDebug.log(
            SceneDebug.RESET, "resetCameraAndPhysics %s (remote=%s, action=%s, anchored=%s)",
            this.getDisplayNameText(), this.level().isClientSide, this.getCurrentAction(), this.isAnchored(),
        )
        this.cameraOriginPos = null
        this.setNoGravity(false)
        this.setCurrentAction(null)
        if (this.level().isClientSide) {
            this.resetLocalPlayerClientState()
        }
    }

    /** CLIENT: unlocks player movement, un-hides the player and tells the server to reset the girl. */
    open fun resetLocalPlayerClientState() {
        SceneDebug.log(
            SceneDebug.RESET, "resetLocalPlayerClientState %s (controlled=%s)",
            this.getDisplayNameText(), this.isControlledByLocalPlayer(),
        )
        if (this.isControlledByLocalPlayer()) {
            HandlePlayerMovement.setMovementLock(true)
            ClientHooks.localPlayer()?.setInvisible(false)
            PacketHandler.networkWrapper.sendToServer(ResetGirlPacket(this.getGirlId()))
        }
    }

    /** Resets the animation controller tick offset and notifies the server (ResetControllerPacket). */
    open fun resetAnimationControllerOffset() {
        this.resetAnimationControllerTicks()
        PacketHandler.networkWrapper.sendToServer(ResetControllerPacket(this.getGirlId()))
    }

    /** CLIENT: resets the action controller's tick offset. */
    open fun resetAnimationControllerTicks() {
        this.actionController?.setTickOffset(0.0)
    }

    /** CLIENT. */
    abstract fun getNextAction(action: Action): Action?

    /** CLIENT. */
    abstract fun getCumAction(action: Action): Action?

    open fun getTargetNetworkPoint(): TargetPoint =
        TargetPoint(WorldUtils.getDimensionId(this.level()), this.x, this.y, this.z, 50.0)

    open fun positionPlayerRelative(dx: Double, dy: Double, dz: Double, yaw: Float, pitch: Float) {
        GirlPlayerPlacement.positionPlayerRelative(this, dx, dy, dz, yaw, pitch)
    }

    /** CLIENT: true when the local player is this girl's interaction partner. */
    open fun isControlledByLocalPlayer(): Boolean {
        if (!this.level().isClientSide) {
            return false
        }

        // 1.12 compared both getPersistentID() and getUniqueID(); in 1.21 they are the same UUID
        val localPlayer = ClientHooks.localPlayerUUID() ?: return false
        return localPlayer == this.getInteractionPlayerUUID()
    }

    /**
     * CLIENT: feeds the horny meter from a scene keyframe, but only on the
     * client that actually drives this girl — the meter is a per-client HUD, so
     * copies of the girl on other clients must not move it.
     */
    open fun addToHornyMeterIfControlled(amount: Double) {
        if (this.isControlledByLocalPlayer()) {
            HornyMeterHud.addToHornyMeter(amount)
        }
    }

    open fun startActionFromHandState() {
    }

    /** Stores a custom display name for the girl in the data manager. */
    open fun setCustomNameOverride(name: String) {
        GirlSyncedState.setCustomNameOverride(this, name)
    }

    /** @return the custom display name, or "" if unset (1.12 `getCustomName()`, renamed: clashes with `Entity.getCustomName()`) */
    open fun getGirlCustomName(): String = GirlSyncedState.getCustomName(this)

    abstract fun getDisplayNameText(): String

    /** @return the custom name if set, otherwise the girl's default display name */
    open fun getEffectiveDisplayName(): String = GirlSyncedState.getEffectiveDisplayName(this)

    abstract fun getScaleFactor(): Float

    /** CLIENT. */
    open fun shouldRenderNameTag(): Boolean = true

    /** Broadcasts "&lt;name&gt; message" chat to nearby players (both sides). */
    open fun sendGirlChatMessage(message: String) {
        GirlChat.sendGirlChatMessage(this, message)
    }

    /** Sends the given message as the girl; if not local, additionally calls sendGirlChatMessage. */
    open fun broadcastChatAround(message: String, toAll: Boolean) {
        GirlChat.broadcastChatAround(this, message, toAll)
    }

    open fun sendChatMessage(message: String) {
        GirlChat.sendChatMessage(this, message)
    }

    open fun sendChatMessageToPlayer(uuid: UUID, message: String) {
        GirlChat.sendChatMessageToPlayer(this, uuid, message)
    }

    /** Plays a sound at the girl's position (local world sound; a no-op on the server, as in 1.12). */
    open fun playSoundAtPosition(sound: SoundEvent, volume: Float, pitch: Float) {
        this.level().playLocalSound(
            this.blockPosition().x.toDouble(),
            this.blockPosition().y.toDouble(),
            this.blockPosition().z.toDouble(),
            sound,
            SoundSource.NEUTRAL,
            volume,
            pitch,
            false,
        )
    }

    /** Plays a sound at the girl's position at full volume. */
    override fun playSound(sound: SoundEvent) {
        this.playSoundAtPosition(sound, 1.0f, 1.0f)
    }

    open fun playRandomSound(sounds: Array<SoundEvent>, vararg indices: Int) {
        if (indices.isEmpty()) {
            this.playSound(sounds[this.random.nextInt(sounds.size)])
        } else {
            this.playSoundAtPosition(sounds[indices[this.random.nextInt(indices.size)]], 1.0f, 1.0f)
        }
    }

    open fun playRandomSoundAtVolume(sounds: Array<SoundEvent>, volume: Float) {
        this.playSoundAtPosition(sounds[this.random.nextInt(sounds.size)], volume, 1.0f)
    }

    open fun playSoundAtVolume(sound: SoundEvent, volume: Float) {
        this.playSoundAtPosition(sound, volume, 1.0f)
    }

    /** CLIENT. */
    open fun asGirl(): BaseGirlEntity = this

    /** CLIENT: true when the nearest player within 50 blocks is the local player. */
    open fun isLocalPlayerNearby(): Boolean {
        val player = this.level().getNearestPlayer(this, 50.0) ?: return false
        return player.uuid == ClientHooks.localPlayerUUID()
    }

    open fun getFrontOffsetVector(): Vec3 = GirlPlayerPlacement.getFrontOffsetVector(this)

    /** @return a point `distance` blocks in front of the interacting player's yaw */
    open fun getVectorTowardPlayer(distance: Double): Vec3 = GirlPlayerPlacement.getVectorTowardPlayer(this, distance)

    open fun transformRenderOffset(vec: Vec3, partialTicks: Float): Vec3 = vec

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = this.animationFactory

    /** 1.12 `canBePushed()` = false. */
    override fun isPushable(): Boolean = false

    /**
     * 1.12 `@SideOnly(CLIENT) getAmbientSound()`: the dedicated/integrated
     * server saw the vanilla (null) sound, the client the 1-in-10000 fart.
     */
    override fun getAmbientSound(): SoundEvent? {
        if (!this.level().isClientSide) {
            return super.getAmbientSound()
        }

        if (this.random.nextInt(10000) == 0) {
            val localPlayer = ClientHooks.localPlayer()
            if (this.level().isClientSide && localPlayer != null && localPlayer.position().distanceTo(this.position()) < 10.0) {
                this.sendChatMessage("whopa")
            }

            return SoundHandler.randomSound(SoundHandler.MISC_FART)
        } else {
            return null
        }
    }

    /** See [jumpMovementFactor]. */
    override fun getFlyingSpeed(): Float {
        return if (this.controllingPassenger is Player) super.getFlyingSpeed() else this.jumpMovementFactor
    }

    open fun getLeftArmAngle(): Float = 0.0f

    open fun getRightArmAngle(): Float = 0.0f

    /**
     * CLIENT: computes the world-transform MatrixStack for a named model bone
     * (walks the bone parent chain, applies the girl's yaw when anchored).
     * @return empty stack if the bone is unknown
     */
    open fun getBoneMatrixStack(boneName: String, usePivots: Boolean): MatrixStack =
        GirlBoneMath.getBoneMatrixStack(this, boneName, usePivots)

    open fun applyAdditionalMatrixTransformations(stack: MatrixStack): MatrixStack = stack

    /** CLIENT: @return the cached world offset of a bone, or ZERO (tracking the bone) */
    open fun getCachedBoneOffset(boneName: String): Vec3 = GirlBoneMath.getCachedBoneOffset(this, boneName)

    /** CLIENT: @return the girl's position plus the cached offset of the named bone */
    open fun getBoneWorldPosition(boneName: String): Vec3 = GirlBoneMath.getBoneWorldPosition(this, boneName)

    /** Caches the world position of a bone for later rendering/positioning. */
    open fun setBoneWorldPosition(boneName: String, pos: Vec3) {
        GirlBoneMath.setBoneWorldPosition(this, boneName, pos)
    }

    /** CLIENT: height of the girlCam bone in world units (for camera placement). */
    open fun getCameraBoneHeight(): Float = GirlBoneMath.getCameraBoneHeight(this)

    /** CLIENT. */
    open fun getRenderScaleFactor(): Float = 1.0f

    open fun transformCameraPivotY(y: Float): Float = y

    /**
     * @return the GeckoLib model used by this girl's renderer, or null
     * (always null on the server; on the client it asks `GirlRenderer.getGeoModelFor`).
     */
    open fun getGeoModel(): GeoModel<*>? {
        return if (this.level().isClientSide) GirlRenderer.getGeoModelFor(this) else null
    }

    /** @return the renderer's animation processor, or null when there is no geo model (yet) */
    open fun getAnimationProcessor(): AnimationProcessor<*>? {
        val model = this.getGeoModel()
        return model?.animationProcessor
    }

    open fun isPartEnabled(index: Int): Boolean = GirlCustomParts.isPartEnabled(this, index)

    open fun getModelPartByIndex(index: Int): Point2D = Point2D.ZERO

    open fun setCustomPartList(parts: List<Int>) {
        GirlCustomParts.setCustomPartList(this, parts)
    }

    open fun getCustomPartListCode(): String = GirlCustomParts.getCustomPartListCode(this)

    open fun getBasePartIdList(): ArrayList<Int> = ArrayList()

    open fun buildCustomPartsData(uuid: UUID): List<Map.Entry<BoneType, Map.Entry<List<String>, Int>>> =
        GirlCustomParts.buildCustomPartsData(this, uuid)

    open fun setCustomPartsData(data: List<Map.Entry<BoneType, Map.Entry<List<String>, Int>>>?) {
        GirlCustomParts.setCustomPartsData(this, data)
    }

    open fun setCustomPartValue(index: Int, value: Int) {
        GirlCustomParts.setCustomPartValue(this, index, value)
    }

    open fun setCustomPartListCode(code: String) {
        GirlCustomParts.setCustomPartListCode(this, code)
    }

    open fun getCustomPartIdList(): ArrayList<Int> = ArrayList()

    open fun getCustomPartExtraIdList(): List<Int> = ArrayList()

    open fun setCustomModelCode(modelCode: String) {
        GirlSyncedState.setCustomModelCode(this, modelCode)
    }

    open fun getCustomModelCode(): String = GirlSyncedState.getCustomModelCode(this)

    open fun getCustomPartsSet(): HashSet<String> = GirlPartCodec.parseCustomParts(this.getCustomModelCode())

    /** CLIENT. */
    open fun hasCustomParts(): Boolean = true

    enum class BaseGirlEntityState {
        WALK,
        FAST_WALK,
        RUN,
    }

    /** Client-only helpers (PORTING.md §2.3 rule C); loaded only when first used on the client. */
    private object Client {
        fun openInventoryGui(player: Player, girl: BaseGirlEntity) {
            Minecraft.getInstance().setScreen(GirlInventoryScreen(girl, player))
        }

        fun openInventoryGui(player: Player, girl: BaseGirlEntity, options: Array<String>, rewards: Array<ItemStack>?, dressUp: Boolean) {
            Minecraft.getInstance().setScreen(GirlInventoryScreen(girl, player, options, rewards, dressUp))
        }

        fun getSceneEntityPosition(scene: SexSceneEntity, entity: LivingEntity, girl: BaseGirlEntity, partialTicks: Float): Vec3 =
            SexSceneRenderer.getSceneEntityPosition(Minecraft.getInstance(), scene, entity, girl, partialTicks)
    }

    companion object {
        const val TICK_RATE: Long = 20L

        @JvmField
        var GLOBAL_GIRL_CACHE: HashSet<BaseGirlEntity> = HashSet()

        @JvmField
        val MASTER: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val IS_ANCHORED: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val TARGET_POS: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val YAW_ROTATION: EntityDataAccessor<Float> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val GIRL_ID: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val OUTFIT_INDEX: EntityDataAccessor<Int> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val CUR_ACTION: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val GIRL_HAND_STATES: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val INTERACTION_PARTNER_UUID: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val WALK_SPEED: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val CUSTOM_MODEL_KEY: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val CUSTOM_NAME: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        // ---- Hoisted sibling keys (1.12 explicit ids shared across sibling classes, PORTING.md §2.6) ----

        /** 1.12 id 119: `AbstractNpcOnlyEntity.CURRENT_ACTION` / `AbstractKoboldPlayerEntity.SKIN_COLOR_NAME`. */
        @JvmField
        val SHARED_STRING_119: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        /** 1.12 id 120: `AbstractNpcOnlyEntity.ACTION_TARGET_POS` / `AbstractKoboldPlayerEntity.EYE_COLOR`. */
        @JvmField
        val SHARED_BLOCKPOS_120: EntityDataAccessor<BlockPos> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.BLOCK_POS)

        /** 1.12 id 121: `AbstractNpcOnlyEntity.APPEARANCE_DNA` / `AbstractKoboldPlayerEntity.MODEL_CODE`. */
        @JvmField
        val SHARED_STRING_121: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        /** 1.12 id 122 (float): `KoboldEntity.SIZE_SCALAR` / `KoboldPlayerEntity.SIZE_SCALAR`. */
        @JvmField
        val SHARED_FLOAT_122: EntityDataAccessor<Float> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.FLOAT)

        /** 1.12 id 122 (string): `GoblinEntity.OWNER_UUID` / `GoblinPlayerEntity.OWNER_UUID_STRING`. */
        @JvmField
        val SHARED_STRING_122: EntityDataAccessor<String> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.STRING)

        /** 1.12 id 126: `GoblinEntity.PREGNANT` / `GoblinPlayerEntity.PREGNANT`. */
        @JvmField
        val SHARED_BOOL_126: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(BaseGirlEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val TEMPTATION_ITEMS: List<Item> = listOf(Items.EMERALD, Items.DIAMOND, Items.GOLD_INGOT, Items.ENDER_PEARL)

        /** 1.12 `applyEntityAttributes()`. */
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder =
            PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 30.0)

        @JvmStatic
        fun sendMessageToTrackingPlayers(girl: BaseGirlEntity, message: String) {
            GirlChat.sendMessageToTrackingPlayers(girl, message)
        }

        @JvmStatic
        fun girlPlaySound(girl: BaseGirlEntity, sound: SoundEvent, atPlayer: Boolean) {
            GirlSoundBroadcast.girlPlaySound(girl, sound, atPlayer)
        }

        @JvmStatic
        fun girlPlaySound(girl: BaseGirlEntity, sound: SoundEvent) {
            GirlSoundBroadcast.girlPlaySound(girl, sound)
        }

        @JvmStatic
        fun playRandomSound(girl: BaseGirlEntity, sounds: Array<SoundEvent>) {
            GirlSoundBroadcast.playRandomSound(girl, sounds)
        }

        @JvmStatic
        fun playRandomSound(girl: BaseGirlEntity, sounds: Array<SoundEvent>, atPlayer: Boolean) {
            GirlSoundBroadcast.playRandomSound(girl, sounds, atPlayer)
        }

        @JvmStatic
        fun getGirlEntityList(): List<BaseGirlEntity> = GirlLookup.getGirlEntityList()

        /** CLIENT: opens the girl inventory/interaction screen. */
        @JvmStatic
        fun openInventoryGui(player: Player, girl: BaseGirlEntity) {
            Client.openInventoryGui(player, girl)
        }

        /** CLIENT. */
        @JvmStatic
        fun openInventoryGui(player: Player, girl: BaseGirlEntity, options: Array<String>, rewards: Array<ItemStack>?, dressUp: Boolean) {
            Client.openInventoryGui(player, girl, options, rewards, dressUp)
        }

        /** CLIENT. */
        @JvmStatic
        fun openInventoryGui(player: Player, girl: BaseGirlEntity, options: Array<String>, dressUp: Boolean) {
            Client.openInventoryGui(player, girl, options, null, dressUp)
        }

        @JvmStatic
        fun getPreviousPosition(girl: BaseGirlEntity): Vec3 = Vec3(girl.xo, girl.yo, girl.zo)

        @JvmStatic
        fun getClientGirlEntity(girlId: UUID?): BaseGirlEntity? = GirlLookup.getClientGirlEntity(girlId)

        @JvmStatic
        fun getServerGirlEntity(girlId: UUID?): BaseGirlEntity? = GirlLookup.getServerGirlEntity(girlId)

        @JvmStatic
        fun girlList(girlId: UUID?): ArrayList<BaseGirlEntity> = GirlLookup.girlList(girlId)

        @JvmStatic
        fun getCompanionInteractingWithPlayer(player: Player?): BaseGirlEntity? = GirlLookup.getCompanionInteractingWithPlayer(player)

        @JvmStatic
        fun getGirlByUUID(uuid: UUID): BaseGirlEntity? = GirlLookup.getGirlByUUID(uuid)

        @JvmStatic
        fun getGirlByUUID(uuid: UUID, serverOnly: Boolean?): BaseGirlEntity? = GirlLookup.getGirlByUUID(uuid, serverOnly)

        @JvmStatic
        fun getActiveSceneInfo(uuid: UUID): BaseGirlEntity? = GirlLookup.getActiveSceneInfo(uuid)

        @JvmStatic
        fun getActiveSceneInfo(player: Player): BaseGirlEntity? = GirlLookup.getActiveSceneInfo(player)

        /** CLIENT: sneak input — advances the local player's scene to its next action. */
        @JvmStatic
        fun triggerFastSexAction(uuid: UUID) {
            try {
                for (girl in getGirlEntityList()) {
                    val interactionUuid = girl.getInteractionPlayerUUID()
                    if (interactionUuid != null && interactionUuid == uuid) {
                        val nextAction = girl.getNextAction(girl.getCurrentAction()) ?: return

                        girl.setCurrentAction(nextAction)
                        return
                    }
                }
            } catch (ex: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }
        }

        /** CLIENT: jump input with a full meter — switches the local player's scene to its cum action. */
        @JvmStatic
        fun triggerCumAction(uuid: UUID) {
            try {
                for (girl in getGirlEntityList()) {
                    if (!girl.isRemoved && girl.level().isClientSide) {
                        val interactionUuid = girl.getInteractionPlayerUUID()
                        if (interactionUuid != null && interactionUuid == uuid) {
                            val cumAction = girl.getCumAction(girl.getCurrentAction())
                            if (cumAction != null) {
                                girl.setCurrentAction(cumAction)
                            }
                        }
                    }
                }
            } catch (ex: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }
        }

        @JvmStatic
        fun isValidGirl(entity: Entity?): Boolean {
            return if (entity == null) {
                false
            } else {
                if (entity !is BaseGirlEntity) false else entity !is AbstractPlayerGirlEntity
            }
        }

        /**
         * Spawns one particle around the girl (client-side effect; like 1.12's
         * `world.spawnParticle`, a no-op on the server level).
         */
        @JvmStatic
        fun spawnParticlesAround(particle: ParticleOptions, girl: BaseGirlEntity) {
            val vx = Reference.RANDOM.nextGaussian() * 0.02
            val vy = Reference.RANDOM.nextGaussian() * 0.02
            val vz = Reference.RANDOM.nextGaussian() * 0.02
            girl.level().addParticle(
                particle,
                girl.x + Reference.RANDOM.nextFloat() * girl.bbWidth * 2.0f - girl.bbWidth,
                girl.y + 0.5 + Reference.RANDOM.nextFloat() * girl.bbHeight,
                girl.z + Reference.RANDOM.nextFloat() * girl.bbWidth * 2.0f - girl.bbWidth,
                vx,
                vy,
                vz,
            )
        }

        @JvmStatic
        fun spawnParticlesAround(particle: ParticleOptions, girl: BaseGirlEntity, count: Int) {
            for (i in 0 until count) {
                spawnParticlesAround(particle, girl)
            }
        }

        @JvmStatic
        fun encodePartIdList(parts: List<Int>): String = GirlPartCodec.encodePartIdList(parts)

        @JvmStatic
        fun decodePartIdList(code: String): List<Int> = GirlPartCodec.decodePartIdList(code)

        @JvmStatic
        fun getAllPartIdsForGirl(uuid: UUID): List<Int> = GirlCustomParts.getAllPartIdsForGirl(uuid)

        @JvmStatic
        fun encodeCustomParts(parts: HashSet<String>?): String = GirlPartCodec.encodeCustomParts(parts)
    }
}
