package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.api.SkinColor
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.renderer.GirlRendererBase
import com.trolmastercard.sexmod.entity.ai.DoorInteractAiGoal
import com.trolmastercard.sexmod.entity.ai.WatchClosestGirlGoal
import com.trolmastercard.sexmod.entity.api.IGoblin
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.EyeColor
import com.trolmastercard.sexmod.util.Point2D
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.fml.loading.FMLEnvironment
import org.joml.Vector2f
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * Goblin NPC: shoulder-carry / throw companion, gold thief and the lair
 * queen (throne, guards, breeding, pregnancy). The per-subsystem logic lives
 * in [GoblinThrow], [GoblinQueenAi], [GoblinInteractions], [GoblinAppearance],
 * [GoblinPersistence] and [GoblinAnimations]; this class keeps the state and
 * the dispatch order.
 *
 * 1.21 notes: `setDead` is `remove(reason)` (the stolen item only drops when
 * the removal destroys the entity, not on chunk unload); `canBeCollidedWith`
 * is `isPickable`; `fall` is `causeFallDamage`; `setFire` is `igniteForTicks`;
 * `jump` is `jumpFromGround`; `inPortal = false` clears `portalProcess`; eye
 * height 0.75 moved to [getDefaultDimensions]. `OWNER_UUID` / `PREGNANT` are
 * the hoisted `SHARED_STRING_122` / `SHARED_BOOL_126`.
 */
class GoblinEntity : AbstractNpcOnlyEntity, IGoblin {
    @JvmField var isQueen: Boolean = false
    @JvmField var throneYaw: Float = 0.0f
    @JvmField var impregnationTick: Long = -1L
    @JvmField var thronePos: Vec3 = Vec3.ZERO
    @JvmField var guardUUIDs: MutableList<UUID> = ArrayList()
    @JvmField var robTicks: Int = 31520
    @JvmField var heldPlayerDistance: Int = -1
    @JvmField var throwProgress: Int = -1
    @JvmField var gravityInitDone: Boolean = false
    @JvmField var guardPost: BlockPos? = null
    @JvmField var guardPostTicks: Int = 0
    @JvmField var standUpTicks: Int = 0
    @JvmField var throwTickCount: Int = 0
    @JvmField var holdCooldown: Int = -1
    @JvmField var jumpThrowTicks: Int = 0
    @JvmField var pregnantNoticeTick: Long = 0L
    @JvmField var guards: MutableList<GoblinEntity> = ArrayList()
    @JvmField var paizuriStartTicks: Int = -1
    @JvmField var nelsonStartTicks: Int = -1
    @JvmField var previousAction: Action? = null
    @JvmField var fadeScale: Float = 1.0f
    @JvmField var throwCooldown: Int = -1
    @JvmField var breedingSideLeft: Boolean = true
    @JvmField var nelsonSlowBasePose: Boolean = true
    @JvmField var nelsonAltPose: Boolean = false
    @JvmField var paizuriPoseSuffix: String = ""
    @JvmField var breedingFastReadyPose: Boolean = false

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out GoblinEntity>, level: Level) : super(type, level) {
        // 1.12 entityInit defaults of the hoisted keys
        val color = EyeColor.values()[this.random.nextInt(EyeColor.values().size)]
        this.entityData.set(ACTION_TARGET_POS, BlockPos(color.getColor()))
        this.entityData.set(CURRENT_ACTION, DEFAULT_SKIN.name)
        this.entityData.set(OWNER_UUID, "")
        this.entityData.set(PREGNANT, false)
    }

    constructor(level: Level) : this(ModEntities.GOBLIN.get(), level)

    constructor(level: Level, girlId: String, modelPartIndex: Int) : this(level) {
        this.entityData.set(QUEEN_ID, girlId)
        this.entityData.set(APPEARANCE_DNA, this.buildModelCodeDNA(StringBuilder(), modelPartIndex))
    }

    constructor(level: Level, isThrown: Boolean, yaw: Float, pos: Vec3) : this(level) {
        if (isThrown) {
            this.entityData.set(APPEARANCE_DNA, this.buildModelCodeDNA(StringBuilder()))
            this.throneYaw = yaw
            this.thronePos = pos
            this.isQueen = true
            this.setTargetPosition(pos)
            this.setYawRotation(yaw)
            this.setCurrentAction(Action.SIT)
            this.setAnchored(true)
            this.setPos(pos.x, pos.y, pos.z)
        }
    }

    override fun reinitTasks() {
        super.reinitTasks()
        this.setOwnerUUID(null)
        this.noPhysics = false
        this.isNoGravity = false
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(QUEEN_ID, "")
        builder.define(STOLEN_ITEM, ItemStack.EMPTY)
        builder.define(TAMED, false)
    }

    /** Clears the renderer's per-bone colour cache (client dist only). */
    override fun clearBoneColors() {
        if (FMLEnvironment.dist.isClient) {
            Client.clearBoneColors()
        }
    }

    /** 1.12 `setDead()`. */
    override fun remove(reason: RemovalReason) {
        super.remove(reason)
        if (reason.shouldDestroy()) {
            this.setOwnerUUID(null)
            if (!this.level().isClientSide) {
                val heldItem = this.entityData.get(STOLEN_ITEM)
                if (!heldItem.isEmpty) {
                    val item = ItemEntity(this.level(), this.x, this.y, this.z, heldItem)
                    this.level().addFreshEntity(item)
                }
            }
        }
    }

    override fun doAction(action: String, uuid: UUID) {
        GoblinInteractions.doAction(this, action, uuid)
    }

    fun setThrowTarget(uuid: UUID) {
        GoblinInteractions.setThrowTarget(this, uuid)
    }

    fun setPickupTarget(uuid: UUID) {
        GoblinInteractions.setPickupTarget(this, uuid)
    }

    override fun getDisplayNameText(): String = "Goblin"

    /** 1.12 `getEyeHeight()` = 0.75. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(0.75f)

    override fun getScaleFactor(): Float = 0.1f

    override fun setOwnerUUID(ownerUuid: UUID?) {
        if (ownerUuid == null) {
            this.entityData.set(OWNER_UUID, "")
        } else {
            this.entityData.set(OWNER_UUID, ownerUuid.toString())
        }
    }

    override fun getOwnerUUID(): UUID? {
        val ownerStr = this.entityData.get(OWNER_UUID)
        if ("" == ownerStr) {
            return null
        }

        return try {
            UUID.fromString(this.entityData.get(OWNER_UUID))
        } catch (ex: Exception) {
            Main.LOGGER.error("could not parse the owner UUID", ex)
            null
        }
    }

    override fun getHeldPlayerDistance(): Int = this.heldPlayerDistance

    override fun setHeldPlayerDistance(heldPlayerDistance: Int) {
        this.heldPlayerDistance = heldPlayerDistance
    }

    override fun buildModelCodeDNA(builder: StringBuilder): String = GoblinAppearance.buildModelCodeDNA(builder)

    override fun getCustomPartIdList(): ArrayList<Int> = GoblinAppearance.getCustomPartIdList()

    override fun getCustomPartExtraIdList(): List<Int> = GoblinAppearance.getCustomPartExtraIdList()

    override fun getModelPartByIndex(index: Int): Point2D = GoblinAppearance.getModelPartByIndex(index)

    override fun setCustomPartList(parts: List<Int>) {
        GoblinAppearance.setCustomPartList(this, parts)
    }

    fun updateModelCodeDNA() {
        GoblinAppearance.updateModelCodeDNA(this)
    }

    fun buildModelCodeDNA(builder: StringBuilder, partIndex: Int): String = GoblinAppearance.buildModelCodeDNA(builder, partIndex)

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        GoblinPersistence.write(this, tag)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        GoblinPersistence.read(this, tag)
    }

    /** 1.12 `processInteract`. */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult =
        if (this.processInteract(player, hand)) InteractionResult.sidedSuccess(this.level().isClientSide) else InteractionResult.PASS

    fun processInteract(player: Player, hand: InteractionHand): Boolean = GoblinInteractions.processInteract(this, player, hand)

    /** 1.12 `initEntityAI` (replaces the base goal set). */
    override fun registerGoals() {
        val watch = WatchClosestGirlGoal(this, Player::class.java, 2.0f, 1.0f)
        this.watchClosestGirlGoal = watch
        this.goalSelector.addGoal(0, FloatGoal(this))
        this.goalSelector.addGoal(3, DoorInteractAiGoal(this))
        this.goalSelector.addGoal(5, watch)
    }

    /**
     * Every AI tick: dispatches every goblin subsystem —
     * gravity/despawn guard, pickup glue, throw progression, queen AI
     * (steal/breeding), held-state guarding, cooldowns and particle cycles.
     * Ordering is significant; see the individual handlers.
     */
    override fun updateAITasks() {
        super.updateAITasks()
        this.handleGravity()
        handlePickUpState(this)
        this.handleThrowState()
        this.tickGoldSteal()
        this.fleeFromNearestPlayer()
        this.tryStartBreeding()
        this.handleJumpThrow()
        this.handleHoldCooldown()
        this.handleThrowCooldown()
        this.handleHeldParticles()
        this.handleHeldThrow()
        this.handleThrownLand()
        this.handleStandUp()
        this.handleHeldState()
    }

    /** 1.12 `canBeCollidedWith`. */
    override fun isPickable(): Boolean {
        val action = this.getCurrentAction()
        return if (action == Action.THROWN) {
            false
        } else if (action == Action.RUN) {
            super.isPickable()
        } else if (action == Action.AWAIT_PICK_UP) {
            super.isPickable()
        } else if (this.getOwnerUUID() != null) {
            false
        } else {
            if (action != Action.NULL) false else super.isPickable()
        }
    }

    fun handleGoblinOwner(player: Player) {
        GoblinThrow.handleGoblinOwner(this, player)
    }

    fun handleHeldState() {
        GoblinThrow.handleHeldState(this)
    }

    fun getThrowRange(): Double = Math.sqrt(800.0)

    fun handleStandUp() {
        if (this.getCurrentAction() == Action.STAND_UP) {
            if (++this.standUpTicks >= 37) {
                this.standUpTicks = 0
                this.setCurrentAction(Action.NULL)
            }
        }
    }

    override fun setThrowTickCount(throwTickCount: Int) {
        this.throwTickCount = throwTickCount
    }

    override fun getThrowTickCount(): Int = this.throwTickCount

    fun handleThrownLand() {
        if (this.getCurrentAction() == Action.THROWN) {
            if (this.onGround()) {
                val throwTick = this.getThrowTickCount() + 1
                this.setThrowTickCount(throwTick)
                if (throwTick >= 30) {
                    this.setThrowTickCount(0)
                    this.setCurrentAction(Action.STAND_UP)
                }
            }
        }
    }

    fun handleHeldThrow() {
        GoblinQueenAi.handleHeldThrow(this)
    }

    fun handleHeldParticles() {
        GoblinQueenAi.handleHeldParticles(this)
    }

    fun handleThrowCooldown() {
        GoblinQueenAi.handleThrowCooldown(this)
    }

    fun handleHoldCooldown() {
        GoblinQueenAi.handleHoldCooldown(this)
    }

    fun handleJumpThrow() {
        GoblinQueenAi.handleJumpThrow(this)
    }

    fun tryStartBreeding() {
        GoblinQueenAi.tryStartBreeding(this)
    }

    fun ensureGuardsSpawned(): MutableList<GoblinEntity> = GoblinQueenAi.ensureGuardsSpawned(this)

    fun handleGravity() {
        if (!this.gravityInitDone) {
            this.noPhysics = false
            this.isNoGravity = false
            if (!this.isQueen && !this.entityData.get(TAMED) && this.entityData.get(QUEEN_ID) != "" && this.getCurrentAction() == Action.NULL) {
                this.discard()
            }

            this.gravityInitDone = true
        }
    }

    /**
     * Throw flight — advances the throw progress; at tick 15 the
     * goblin is launched from the owner's eye with the owner's aim
     * (pitch/yaw), at tick 39 she lands (THROWN) and unbinds owner +
     * interaction player.
     */
    fun updateThrowProgress() {
        GoblinThrow.updateThrowProgress(this)
    }

    fun fleeFromNearestPlayer() {
        GoblinQueenAi.fleeFromNearestPlayer(this)
    }

    /** 1.12 `jump()`. */
    override fun jumpFromGround() {
        if (this.getCurrentAction() != Action.RUN || this.hasValidPath()) {
            super.jumpFromGround()
        }
    }

    fun hasValidPath(): Boolean {
        val path = this.getNavigation().path ?: return true
        val currentIndex = path.nextNodeIndex
        val length = path.nodeCount
        return if (length != currentIndex && length - 1 != currentIndex) {
            val currentPoint = path.getNode(currentIndex)
            val nextPoint = path.getNode(currentIndex + 1)
            nextPoint.y - currentPoint.y == 1
        } else {
            true
        }
    }

    fun tickGoldSteal() {
        GoblinQueenAi.tickGoldSteal(this)
    }

    fun getModelPartIndex(): Int = getModelCodeParts(this)[7].toInt()

    fun findThrowTarget(player: Player): Int? = GoblinQueenAi.findThrowTarget(this, player)

    fun handleThrowState() {
        GoblinQueenAi.handleThrowState(this)
    }

    override fun tick() {
        this.updateModelCodeDNA()
        handleGoblinThrowAction(this)
        this.updateThrowProgress()
        if (this.getOwnerUUID() != null) {
            // 1.12 inPortal = false
            this.portalProcess = null
        }

        super.tick()
        this.handleShoulderIdle()
        this.tickThrownDespawnFade()
        this.tickVanishFade()
        if (this.level().isClientSide) {
            this.tickPaizuriStartTimer()
            this.tickNelsonStartTimer()
            if (this.getOwnerUUID() != null) {
                this.noPhysics = true
            }
        }
    }

    override fun getPreviousAction(): Action? = this.previousAction

    override fun setPreviousAction(previousAction: Action?) {
        this.previousAction = previousAction
    }

    override fun setThrowProgress(throwProgress: Int) {
        this.throwProgress = throwProgress
    }

    override fun getThrowProgress(): Int = this.throwProgress

    /** 1.12 `setFire(seconds)`: ignored while owned. */
    override fun igniteForTicks(ticks: Int) {
        if (this.getOwnerUUID() == null) {
            super.igniteForTicks(ticks)
        }
    }

    fun tickVanishFade() {
        if (this.getCurrentAction() == Action.VANISH) {
            this.fadeScale -= 0.05f
            if (!(this.fadeScale > 0.0f)) {
                this.discard()
            }
        }
    }

    fun tickThrownDespawnFade() {
        if (!this.entityData.get(TAMED)) {
            if (this.getCurrentAction() == Action.THROWN) {
                if (this.onGround() || this.isInWater) {
                    this.fadeScale = (this.fadeScale - 0.05).toFloat()
                    if (!(this.fadeScale > 0.0f)) {
                        if (!this.level().isClientSide) {
                            this.setCurrentAction(Action.NULL)
                            this.setInteractionPlayerUUID(null)
                            this.setOwnerUUID(null)
                            this.discard()
                        }
                    }
                }
            }
        }
    }

    /** CLIENT. */
    fun tickPaizuriStartTimer() {
        GoblinInteractions.tickPaizuriStartTimer(this)
    }

    /** CLIENT. */
    fun tickNelsonStartTimer() {
        GoblinInteractions.tickNelsonStartTimer(this)
    }

    /**
     * The goblin state machine core — guards the cum actions
     * (paizuri/nelson/breeding), and on the server fires the per-action setup
     * hooks (START_THROWING returns the stolen item, PAIZURI_START/
     * NELSON_INTRO position the player, BREEDING_CUM_0 marks pregnant and
     * arms the release cooldown, NELSON_CUM toggles the pregnancy flag, and
     * leaving PAIZURI_CUM runs [endPaizuriScene]).
     */
    override fun setCurrentAction(action: Action?) {
        val currentAction = this.getCurrentAction()
        if (currentAction != Action.PAIZURI_CUM || action != Action.PAIZURI_SLOW && action != Action.PAIZURI_FAST) {
            if (currentAction != Action.NELSON_CUM || action != Action.NELSON_SLOW && action != Action.NELSON_FAST) {
                if (currentAction != Action.BREEDING_CUM_0 || action != Action.BREEDING_SLOW_0 && action != Action.BREEDING_FAST_0) {
                    if (action == Action.START_THROWING && !this.level().isClientSide) {
                        this.setInteractionPlayerUUID(this.getOwnerUUID())
                        this.returnStolenItem()
                    }

                    if (action == Action.PAIZURI_START && !this.level().isClientSide) {
                        this.handlePlayerInteract()
                    }

                    if (action == Action.NELSON_INTRO && !this.level().isClientSide) {
                        this.handlePlayerLook()
                    }

                    if (this.getCurrentAction() == Action.PAIZURI_CUM && action == Action.NULL && !this.level().isClientSide) {
                        this.endPaizuriScene()
                    }

                    if (action == Action.BREEDING_CUM_0) {
                        this.entityData.set(PREGNANT, true)
                        this.impregnationTick = this.level().gameTime
                        this.pregnantNoticeTick = this.level().gameTime
                    }

                    if (action == Action.BREEDING_CUM_0) {
                        this.throwCooldown = 0
                    }

                    if (action == Action.NELSON_CUM) {
                        this.entityData.set(PREGNANT, true)
                    }

                    if (currentAction == Action.NELSON_CUM && action != Action.NELSON_CUM) {
                        this.entityData.set(PREGNANT, false)
                    }

                    super.setCurrentAction(action)
                }
            }
        }
    }

    fun endPaizuriScene() {
        GoblinInteractions.endPaizuriScene(this)
    }

    fun handlePlayerLook() {
        GoblinInteractions.handlePlayerLook(this)
    }

    fun handlePlayerInteract() {
        GoblinInteractions.handlePlayerInteract(this)
    }

    fun returnStolenItem() {
        GoblinInteractions.returnStolenItem(this)
    }

    /** CLIENT. */
    override fun shouldRenderNameTag(): Boolean {
        return if (this.getCurrentAction() != Action.NULL) {
            false
        } else if (this.getOwnerUUID() != null) {
            false
        } else {
            val local = ClientHooks.localPlayer()!! // java-npe
            if (!this.entityData.get(TAMED) && !local.hasLineOfSight(this)) false else this.getOwnerUUID() == null
        }
    }

    fun handleShoulderIdle() {
        GoblinThrow.handleShoulderIdle(this)
    }

    override fun getNextAction(action: Action): Action? {
        return when (action) {
            Action.PAIZURI_IDLE, Action.PAIZURI_SLOW -> Action.PAIZURI_FAST
            Action.BREEDING_SLOW_0 -> Action.BREEDING_FAST_0
            Action.BREEDING_SLOW_2 -> Action.BREEDING_FAST_2
            Action.NELSON_SLOW -> Action.NELSON_FAST
            else -> null
        }
    }

    override fun getCumAction(action: Action): Action? {
        return when (action) {
            Action.PAIZURI_SLOW, Action.PAIZURI_FAST, Action.PAIZURI_FAST_CONTINUES -> Action.PAIZURI_CUM
            Action.BREEDING_SLOW_0, Action.BREEDING_FAST_0 -> {
                for (goblin in this.guards) {
                    goblin.getCumAction(action)
                }

                Action.BREEDING_CUM_0
            }
            Action.BREEDING_SLOW_2, Action.BREEDING_FAST_2 -> Action.BREEDING_CUM_2
            Action.NELSON_SLOW, Action.NELSON_FAST -> Action.NELSON_CUM
            Action.BREEDING_1 -> Action.BREEDING_CUM_1
            else -> null
        }
    }

    fun isBlockedByCeiling(): Boolean {
        // 1.12 !Block.isPassable(world, pos) == material.blocksMovement()
        return this.level().getBlockState(this.blockPosition().offset(0, 1, 0)).blocksMotion()
    }

    /** 1.12 `fall(distance, multiplier)`. */
    override fun causeFallDamage(fallDistance: Float, multiplier: Float, source: DamageSource): Boolean {
        val action = this.getCurrentAction()
        return if (action != Action.THROWN && action != Action.START_THROWING) {
            super.causeFallDamage(fallDistance, multiplier, source)
        } else {
            false
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState = GoblinAnimations.predicate(this, event)

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        GoblinAnimations.registerControllers(this, controllers)
    }

    private object Client {
        fun clearBoneColors() {
            GirlRendererBase.clearBoneColors() // 1.12 inherited static (KoboldRenderer/GoblinRenderer)
        }
    }

    companion object {
        // Throne offsets, relative to thronePos and keyed by the throne yaw (see the
        // switch blocks in GoblinQueenAi): THRONE_* is the queen's spot beside the
        // throne, THROW_* is where the jump-throw lands her.
        @JvmField val DEFAULT_SKIN: SkinColor = SkinColor.DARK_GREEN
        @JvmField val THRONE_SIZE: Vec3i = Vec3i(11, 6, 11)
        @JvmField val THRONE_OFFSET_YAW_180: Vec3 = Vec3(5.0, 1.0, 9.0)
        @JvmField val THROW_OFFSET_YAW_0: Vec3 = Vec3(3.0, -1.0, 6.0)
        @JvmField val THRONE_OFFSET_YAW_NEG_90: Vec3 = Vec3(1.0, 1.0, 5.0)
        @JvmField val THROW_OFFSET_YAW_90: Vec3 = Vec3(-6.0, -1.0, 3.0)
        @JvmField val THRONE_OFFSET_YAW_0: Vec3 = Vec3(5.0, 1.0, 1.0)
        @JvmField val THROW_OFFSET_YAW_180: Vec3 = Vec3(-3.0, -1.0, -6.0)
        @JvmField val THRONE_OFFSET_YAW_90: Vec3 = Vec3(9.0, 1.0, 5.0)
        @JvmField val BREEDING_PLAYER_OFFSET: Vec3 = Vec3(0.0, -1.0, -4.0)
        @JvmField val GUARD_LEFT_OFFSET: Vec3 = Vec3(1.0, -1.0, -3.0)
        @JvmField val GUARD_RIGHT_OFFSET: Vec3 = Vec3(-1.0, -1.0, -3.0)
        @JvmField val THROW_OFFSET_YAW_NEG_90: Vec3 = Vec3(6.0, -1.0, -3.0)
        const val aj: Int = 39
        const val ae: Int = 15
        const val aE: Int = 8400
        const val aH: Int = 45
        const val ad: Int = 32000
        const val aw: Int = 26
        const val THROW_TICKS_205: Int = 205
        const val aL: Int = 100
        const val aA: Int = 1200
        const val ak: Int = 30
        const val aW: Int = 37
        const val aU: Float = 2.0f
        const val aI: Int = 5
        const val THROW_TICKS_100: Int = 100
        const val aq: Int = 20
        const val aG: Float = 0.825f
        @JvmField val ENTITY_SIZE: Vector2f = Vector2f(0.5f, 0.99f)

        /** 1.12 list (GOLD_INGOT appears twice; harmless in a set). */
        @JvmField
        val GOLD_ITEMS: HashSet<Item> = hashSetOf(
            Items.GOLDEN_HOE, Items.GOLDEN_HORSE_ARMOR, Items.GOLD_INGOT, Items.GOLDEN_APPLE, Items.GOLDEN_AXE,
            Items.GOLDEN_SHOVEL, Items.GOLDEN_PICKAXE, Items.GOLDEN_SWORD, Items.GOLDEN_CARROT, Items.GOLDEN_HELMET,
            Items.GOLDEN_BOOTS, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLD_INGOT, Items.GOLD_NUGGET,
            Items.GOLD_BLOCK, Items.GOLD_ORE,
        )

        /** Alias of [BaseGirlEntity.SHARED_STRING_122] (1.12 id 122). */
        @JvmField val OWNER_UUID: EntityDataAccessor<String> = BaseGirlEntity.SHARED_STRING_122

        @JvmField val QUEEN_ID: EntityDataAccessor<String> = SynchedEntityData.defineId(GoblinEntity::class.java, EntityDataSerializers.STRING)

        @JvmField val STOLEN_ITEM: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(GoblinEntity::class.java, EntityDataSerializers.ITEM_STACK)

        @JvmField val TAMED: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(GoblinEntity::class.java, EntityDataSerializers.BOOLEAN)

        /** Alias of [BaseGirlEntity.SHARED_BOOL_126] (1.12 id 126). */
        @JvmField val PREGNANT: EntityDataAccessor<Boolean> = BaseGirlEntity.SHARED_BOOL_126

        /** Re-exports of the [AbstractNpcOnlyEntity] keys for `GoblinEntity.X` call sites. */
        @JvmField val CURRENT_ACTION: EntityDataAccessor<String> = AbstractNpcOnlyEntity.CURRENT_ACTION
        @JvmField val ACTION_TARGET_POS: EntityDataAccessor<BlockPos> = AbstractNpcOnlyEntity.ACTION_TARGET_POS
        @JvmField val APPEARANCE_DNA: EntityDataAccessor<String> = AbstractNpcOnlyEntity.APPEARANCE_DNA

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractNpcOnlyEntity.createAttributes()

        /** CLIENT: see [GoblinInteractions.openGoblinPadForPlayer]. */
        @JvmStatic
        fun openGoblinPadForPlayer(playerUuid: UUID?): Boolean = GoblinInteractions.openGoblinPadForPlayer(playerUuid)

        /** Compatibility overload for the 1.12 `(Minecraft, UUID)` call site; the first argument is ignored. */
        @JvmStatic
        fun openGoblinPadForPlayer(mc: Any?, playerUuid: UUID?): Boolean = GoblinInteractions.openGoblinPadForPlayer(playerUuid)

        @JvmStatic
        fun hasGoblinWithUUID(uuid: UUID?): Boolean = GoblinInteractions.hasGoblinWithUUID(uuid)

        @JvmStatic
        fun rotateVectorYaw(vec: Vec3, yaw: Float): Vec3 = GoblinThrow.rotateVectorYaw(vec, yaw)

        @JvmStatic
        fun rotateVectorPitchYaw(vec: Vec3, pitch: Float, yaw: Float): Vec3 = GoblinThrow.rotateVectorPitchYaw(vec, pitch, yaw)

        @JvmStatic
        fun getGoblinThrowPos(girl: BaseGirlEntity): Vec3 = GoblinThrow.getGoblinThrowPos(girl)

        @JvmStatic
        fun getGoblinThrowDistance(girl: BaseGirlEntity): Float = GoblinThrow.getGoblinThrowDistance(girl)

        @JvmStatic
        fun getGoblinThrowHeight(girl: BaseGirlEntity): Float = GoblinThrow.getGoblinThrowHeight(girl)

        @JvmStatic
        fun handleGoblinThrowAction(girl: BaseGirlEntity) {
            GoblinThrow.handleGoblinThrowAction(girl)
        }

        /**
         * Pickup glue — while PICK_UP with a bound owner, sticks the goblin
         * to the owner's position, counts down `heldPlayerDistance` (45) and
         * reaches SHOULDER_IDLE (noClip) at 0. Falls back to NULL when the owner
         * vanishes or exceeds 10 blocks.
         */
        @JvmStatic
        fun handlePickUpState(girl: BaseGirlEntity) {
            GoblinThrow.handlePickUpState(girl)
        }
    }
}
