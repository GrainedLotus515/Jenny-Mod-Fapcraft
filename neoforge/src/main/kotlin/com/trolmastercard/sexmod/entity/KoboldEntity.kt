package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.api.KoboldNames
import com.trolmastercard.sexmod.client.gui.GuiHandler
import com.trolmastercard.sexmod.client.gui.TribeNameScreen
import com.trolmastercard.sexmod.client.renderer.KoboldRenderer
import com.trolmastercard.sexmod.entity.ai.DoorInteractAiGoal
import com.trolmastercard.sexmod.entity.ai.WatchClosestGirlGoal
import com.trolmastercard.sexmod.entity.api.IEllie
import com.trolmastercard.sexmod.entity.api.IKobold
import com.trolmastercard.sexmod.item.DragonStaffItem
import com.trolmastercard.sexmod.networking.GetTribeUiValuesPacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SpawnParticlePacket
import com.trolmastercard.sexmod.networking.TargetPoint
import com.trolmastercard.sexmod.potion.HornyPotion
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.KoboldManager
import com.trolmastercard.sexmod.util.KoboldTask
import com.trolmastercard.sexmod.util.ParticleCompat
import com.trolmastercard.sexmod.util.Point2D
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SceneDebug
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.TribeState
import com.trolmastercard.sexmod.util.Vector4d
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.ConcurrentModificationException
import java.util.Optional
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.Container
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.TemptGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.items.ItemStackHandler
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * Kobold NPC — the tribe creature with oral, anal and mating scenes, plus the
 * full tribe AI (tasks, mining, combat, breeding, follow modes).
 *
 * <b>Tribe system:</b> kobolds belong to `KoboldManager.Tribe` objects
 * keyed by tribe UUID (`TRIBE_ID` data parameter). The tribe leader AI,
 * task assignment and saved positions are managed by
 * `KoboldManager`/`KoboldTask`.
 *
 * <b>Scene entry</b> (shared with Jenny/Bia/Luna): client `doAction`
 * sets `animationFollowUp` (GIRL_HAND_STATES) via
 * `ChangeDataParameterPacket` and sends `KoboldStatePacket`; the
 * server calls `setDismounted()` (`sitLerpActive`), then
 * [isSitting] lerps her to `TARGET_POS` for ~40 ticks
 * (`sitLerpTicks` counter), anchors her and calls [startActionFromHandState] which dispatches
 * on GIRL_HAND_STATES: oral -> STARTBLOWJOB, anal -> KOBOLD_ANAL_START,
 * mating -> MATING_PRESS_START. Without the horny potion effect (or a
 * master match), the scene first passes through [Action.PAYMENT]
 * (the player pays).
 *
 * <b>Pitfalls:</b>
 * - The dismount lerp in [isSitting] MUST use
 *   `RotationHelper.lerpVec3d(pos, target, 40 - sitLerpTicks)` (INT step
 *   variant) — the double variant flings the kobold and it vanishes.
 * - [startActionFromHandState] splits on the potion/master flags exactly as written —
 *   the payment gate is intentional, not a bug.
 * - Do not add `setDead`/removal logic to `tick` —
 *   the girl hierarchy must never self-remove on benign tick errors.
 *
 * 1.21 notes: the 1.12 `IInventory` is [Container]; `spawnHealth` (the static,
 * world-shared MAX_HEALTH) is applied in the constructor because 1.21
 * attribute suppliers are built once at registration; the eye height (0.94)
 * moved to [getDefaultDimensions]; the static Forge handlers live in
 * [KoboldTribeEvents] (client tick in [KoboldTribeEvents.ClientEvents]).
 */
class KoboldEntity : AbstractNpcOnlyEntity, IEllie, Container, IKobold {
    @JvmField
    var inventory: ItemStackHandler = ItemStackHandler(27)

    @JvmField
    var lastRenderedAction: String? = null

    @JvmField
    var menuActionInFlight: Boolean = false

    @JvmField
    var attackCooldown: Int = 0

    @JvmField
    var animationTicks: Int = 0

    @JvmField
    var sitLerpActive: Boolean = false

    @JvmField
    var sitLerpTicks: Int = 0

    @JvmField
    var healthRegenTicks: Int = 0

    @JvmField
    var nearestDistance: Float = Float.MAX_VALUE

    /** Dead data in the jar — no in-class references. */
    @JvmField
    var TOXIC_LINES: Array<String> = arrayOf(
        "What the fuck did you just fucking say about me, you little bitch? I'll have you know I graduated top of my class in the Navy Seals, and I've been involved in numerous secret raids on Al-Quaeda, and I have over 300 confirmed kills. I am trained in gorilla warfare and I'm the top sniper in the entire US armed forces. You are nothing to me but just another target. I will wipe you the fuck out with precision the likes of which has never been seen before on this Earth, mark my fucking words. You think you can get away with saying that shit to me over the Internet? Think again, fucker. As we speak I am contacting my secret network of spies across the USA and your IP is being traced right now so you better prepare for the storm, maggot. The storm that wipes out the pathetic little thing you call your life. You're fucking dead, kid. I can be anywhere, anytime, and I can kill you in over seven hundred ways, and that's just with my bare hands. Not only am I extensively trained in unarmed combat, but I have access to the entire arsenal of the United States Marine Corps and I will use it to its full extent to wipe your miserable ass off the face of the continent, you little shit. If only you could have known what unholy retribution your little \"clever\" comment was about to bring down upon you, maybe you would have held your fucking tongue. But you couldn't, you didn't, and now you're paying the price, you goddamn idiot. I will shit fury all over you and you will drown in it. You're fucking dead, kiddo.",
        "suck my iron cock you worthless piece of shit!",
        "you'll die a fucking virgin!",
        "not even Johnny sins would wanna stick his cock up ur ass",
        "fuck you with ur borderline illegal fetishes!",
        "ur cum tastes terrible!",
        "I've always faked my orgasms when having sex with you!",
        "Not even Jenny would fuck you for 6 diamonds!",
        "U look like u'd use a shovel to mine diamonds, fucking idiot!",
        "Why tf does ur cock smell like my asshole???",
        "do all of us a favor and hit [ALT]+[F4]!",
        "I'm about to say the N word!",
        "you are under attack retard",
        "Eat my ass!",
        "my tongue is longer than ur fucking dick bitch!",
        "Ligma titties!",
        "touch some grass bitch!",
    )

    @JvmField
    var blockBelowState: BlockState? = null

    @JvmField
    var savedHomeBlockState: BlockState? = null

    @JvmField
    var homeApproachPos: BlockPos? = null

    @JvmField
    var tribeTasksInitialized: Boolean = true

    @JvmField
    var lastStuckCheckPos: Vec3 = Vec3.ZERO

    @JvmField
    var tribeWanderTarget: BlockPos? = null

    @JvmField
    var miningTargetPos: BlockPos? = null

    @JvmField
    var miningCooldown: Int = 0

    @JvmField
    var taskTimer: Int = 0

    @JvmField
    var miningStandTicks: Int = 0

    @JvmField
    var inventoryNotifyCooldown: Int = 0

    @JvmField
    var tribeBlocked: Boolean = false

    @JvmField
    var wanderTarget: BlockPos? = null

    @JvmField
    var wanderTicks: Int = 0

    @JvmField
    var mineCooldown: Int = 24

    @JvmField
    var cooldownTicks: Int = 0

    @JvmField
    var miningDropItem: ItemStack? = null

    @JvmField
    var editedColorManually: Boolean = false

    @JvmField
    var actionCooldown: Int = -1

    @JvmField
    var WildSlimeFaceLayer: Boolean = true

    @JvmField
    var switchVariant: Boolean = false

    @JvmField
    var isRenderEgg: Boolean = false

    @JvmField
    var moanCooldown: Int = 0

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out KoboldEntity>, level: Level) : super(type, level) {
        // 1.12 entityInit registered the hoisted keys with these defaults
        val color = EyeAndKoboldColor.values()[this.random.nextInt(EyeAndKoboldColor.values().size)]
        this.entityData.set(ACTION_TARGET_POS, BlockPos(color.getMainColor()))
        this.entityData.set(CURRENT_ACTION, DEFAULT_EYE_COLOR.name)
        this.entityData.set(SIZE_SCALAR, 0.0f)
        // 1.12 applyEntityAttributes read the static spawnHealth at construction time
        this.getAttribute(Attributes.MAX_HEALTH)?.baseValue = spawnHealth
        this.health = this.maxHealth
    }

    /** Kept for Java call sites and reflection. */
    constructor(level: Level) : this(ModEntities.KOBOLD.get(), level)

    constructor(level: Level, tribeId: UUID, size: Float) : this(level) {
        this.entityData.set(TRIBE_ID, Optional.of(tribeId))
        this.entityData.set(SIZE_SCALAR, size)
    }

    override fun buildModelCodeDNA(builder: StringBuilder): String = KoboldModelCode.buildModelCodeDNA(null, builder)

    override fun getCustomPartIdList(): ArrayList<Int> = KoboldModelCode.getCustomPartIdList(this)

    override fun getBasePartIdList(): ArrayList<Int> = KoboldModelCode.getBasePartIdList(this)

    override fun setCustomPartList(parts: List<Int>) {
        KoboldModelCode.setCustomPartList(this, parts)
    }

    fun updateModelCodeDNA() {
        KoboldModelCode.updateModelCodeDNA(this)
    }

    override fun getModelPartByIndex(index: Int): Point2D = KoboldModelCode.getModelPartByIndex(this, index)

    override fun getDisplayNameText(): String = this.entityData.get(KOBOLD_NAME)

    override fun getScaleFactor(): Float = 0.2f - (0.25f - this.entityData.get(SIZE_SCALAR))

    /** 1.12 `getEyeHeight()` = 0.94. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(0.94f)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(TRIBE_ID, Optional.empty())
        builder.define(KOBOLD_NAME, KoboldNames.values()[this.random.nextInt(KoboldNames.values().size)].toString())
        builder.define(ARMED, false)
        builder.define(TRIBE_MEMBER, false)
        builder.define(TRIBE_NAME, "null")
        builder.define(TRIBE_ALERTED, false)
        builder.define(MINING_WITH_AXE, false)
    }

    /** 1.12 `initEntityAI` (replaces the base goal set). */
    override fun registerGoals() {
        val watch = WatchClosestGirlGoal(this, Player::class.java, 3.0f, 1.0f)
        this.watchClosestGirlGoal = watch
        this.goalSelector.addGoal(0, FloatGoal(this))
        this.goalSelector.addGoal(2, TemptGoal(this, 0.4, Ingredient.of(*TEMPTATION_ITEMS.toTypedArray()), false))
        this.goalSelector.addGoal(3, DoorInteractAiGoal(this))
        this.goalSelector.addGoal(5, watch)
    }

    /** 1.12 `getJumpUpwardsMotion()` = 0.45. */
    override fun getJumpPower(): Float = 0.45f

    override fun isPushable(): Boolean = true

    /** 1.12 `processInteract`. */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        return if (this.processInteract(player, hand)) InteractionResult.sidedSuccess(this.level().isClientSide) else InteractionResult.PASS
    }

    fun processInteract(player: Player, hand: InteractionHand): Boolean {
        if (this.getInteractionPlayerUUID() != null) {
            return false
        }

        var nameTagStack = player.getItemInHand(InteractionHand.MAIN_HAND)
        if (nameTagStack.item != Items.NAME_TAG) {
            nameTagStack = player.getItemInHand(InteractionHand.OFF_HAND)
        }

        if (nameTagStack.item == Items.NAME_TAG && player.uuid.toString() == this.entityData.get(MASTER)) {
            this.entityData.set(KOBOLD_NAME, nameTagStack.hoverName.string)
            nameTagStack.shrink(1)
            return true
        }

        if (this.entityData.get(ARMED)) {
            return false
        }

        if (this.getCurrentAction() == Action.SLEEP) {
            return false
        }

        var staffStack = player.getItemInHand(InteractionHand.MAIN_HAND)
        if (staffStack.item !== DragonStaffItem.DRAGON_STAFF) {
            staffStack = player.getItemInHand(InteractionHand.OFF_HAND)
        }

        if (!this.hasMaster() && staffStack.item === DragonStaffItem.DRAGON_STAFF) {
            if (!this.level().isClientSide) {
                return true
            }

            val tribeIdOpt = this.entityData.get(TRIBE_ID)
            if (!tribeIdOpt.isPresent) {
                return true
            }

            if (tribeMembers.isNotEmpty()) {
                return true
            }

            this.openTribeNameScreen(tribeIdOpt.get())
            return true
        } else {
            if (this.hasMaster() && staffStack.item === DragonStaffItem.DRAGON_STAFF && this.entityData.get(MASTER) == player.uuid.toString()) {
                // jar-faithful: a deobf regression passed a null mod instance here and
                // crashed NetworkRegistry.getLocalGuiContainer. The menu opens server-side.
                if (player is ServerPlayer) {
                    GuiHandler.openGui(player, 1, this.level(), this.blockPosition().x, this.blockPosition().y, this.blockPosition().z)
                }
                return true
            }

            if (this.level().isClientSide) {
                if (this.hasMaster() && this.entityData.get(MASTER) == player.uuid.toString()) {
                    this.playRandomSounds(SoundHandler.GIRLS_KOBOLD_MASTER)
                }

                this.openInteractionMenu(player)
            } else {
                this.setInteractionPlayerUUID(player.uuid)
                this.getNavigation().stop()
                this.setYawRotation((Math.atan2(this.z - player.z, this.x - player.x) * (180.0 / Math.PI) + 90.0).toFloat())
                this.setTargetPosition(Vec3(this.x, Math.floor(this.y), this.z))
                this.entityData.set(IS_ANCHORED, true)
                this.setCurrentAction(Action.NULL)
            }

            return true
        }
    }

    /** CLIENT. */
    fun openTribeNameScreen(tribeId: UUID) {
        Client.openTribeNameScreen(tribeId)
    }

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        if (this.hasMaster() && player.uuid.toString() == this.entityData.get(MASTER)) {
            openInventoryGui(player, this, arrayOf("anal", "oral", "mating"), null, false)
            return true
        } else if (this.getEffect(HornyPotion.HORNY_POTION) != null) {
            openInventoryGui(player, this, arrayOf("anal", "oral"), null, false)
            return true
        } else {
            openInventoryGui(
                player, this, arrayOf("anal", "oral"),
                arrayOf(ItemStack(Items.GOLD_INGOT, 3), ItemStack(Items.IRON_PICKAXE)), false,
            )
            return true
        }
    }

    /** CLIENT. */
    override fun onInteractionMenuClosed() {
        if (this.menuActionInFlight) {
            this.menuActionInFlight = false
        } else {
            this.setInteractionPlayerUUID(null)
            this.changeDataParameterFromClient("shouldbeattargetpos", "false")
        }
    }

    override fun resetCameraAndPhysics() {
        this.isRenderEgg = false
        super.resetCameraAndPhysics()
    }

    fun triggerActionSync(flag: Boolean, uuid: UUID) {
        super.triggerActionSync(flag, true, uuid)
        HandlePlayerMovement.setMovementLock(false)
    }

    override fun doAction(action: String, uuid: UUID) {
        this.menuActionInFlight = true
        if ("oral" == action) {
            this.changeDataParameterFromClient("animationFollowUp", Action.STARTBLOWJOB.toString())
            this.triggerActionSync(true, uuid)
        }

        if ("anal" == action) {
            this.changeDataParameterFromClient("animationFollowUp", Action.KOBOLD_ANAL_START.toString())
            this.triggerActionSync(true, uuid)
        }

        if ("mating" == action) {
            this.changeDataParameterFromClient("animationFollowUp", Action.MATING_PRESS_START.toString())
            this.triggerActionSync(true, uuid)
        }
    }

    override fun setDismounted() {
        this.sitLerpActive = true
        this.entityData.set(IS_ANCHORED, false)
    }

    /** Clears the renderer's per-bone colour cache (client dist only; the renderer does not exist on a server). */
    override fun clearBoneColors() {
        if (FMLEnvironment.dist.isClient) {
            Client.clearBoneColors()
        }
    }

    fun isSitting(): Boolean {
        if (!this.sitLerpActive) {
            return false
        }

        this.sitLerpTicks++
        this.noPhysics = false
        this.isNoGravity = false
        if (this.sitLerpTicks > 40) {
            SceneDebug.log(
                SceneDebug.SITTING,
                "Kobold.isSitting: lerp done, startActionFromHandState (sitLerpTicks=%d, action=%s, handState=%s)",
                this.sitLerpTicks, this.getCurrentAction(), this.entityData.get(GIRL_HAND_STATES),
            )
            this.sitLerpActive = false
            this.sitLerpTicks = 0
            val interactionUuid = this.getInteractionPlayerUUID()
            val player = if (interactionUuid == null) null else this.level().getPlayerByUUID(interactionUuid)
            if (player == null) {
                this.sitLerpActive = false
                return true
            }

            this.setYawRotation(player.yRot + 180.0f)
            this.entityData.set(IS_ANCHORED, true)
            player.noPhysics = true
            player.isNoGravity = true
            this.noPhysics = true
            this.isNoGravity = true
            this.getNavigation().stop()
            this.startActionFromHandState()
            return true
        }

        this.yRot = this.getYawRotation()
        this.isNoGravity = false
        val pos = RotationHelper.lerpVec3d(this.position(), this.getTargetPosition(), 40 - this.sitLerpTicks)
        this.setPos(pos.x, pos.y, pos.z)
        this.setCurrentAction(Action.NULL)
        val tribeIdOpt = this.entityData.get(TRIBE_ID)
        if (!tribeIdOpt.isPresent) {
            return true
        }

        val tasks = KoboldManager.getTribeTasks(tribeIdOpt.get()) ?: return true

        for (task in tasks) {
            task.addWorker(this)
        }

        return true
    }

    fun handleActionCooldown(tribeId: UUID) {
        KoboldTribeState.handleActionCooldown(this, tribeId)
    }

    override fun updateAITasks() {
        super.updateAITasks()
        this.tribeBlocked = false
        val tribeIdOpt = this.entityData.get(TRIBE_ID)
        if (tribeIdOpt.isPresent) {
            this.handleActionCooldown(tribeIdOpt.get())
            KoboldManager.triggerFastSexAction(tribeIdOpt.get())
            val master = this.getMasterPlayer()
            if (master != null) {
                KoboldManager.assignMaster(tribeIdOpt.get(), master.uuid)
            }
        }

        if (!this.isSitting()) {
            if (this.getInteractionPlayerUUID() == null) {
                if (!this.entityData.get(ARMED)) {
                    if (this.health != this.maxHealth && ++this.healthRegenTicks >= 100) {
                        this.health = this.health + 2.0f
                        this.healthRegenTicks = 0
                        PacketHandler.networkWrapper.sendToAllTracking(
                            SpawnParticlePacket(this.getGirlId(), ParticleCompat.name(ParticleTypes.HEART)), this,
                        )
                    }
                } else {
                    this.healthRegenTicks = 0
                }

                if (!this.entityData.get(IS_ANCHORED)) {
                    this.isNoGravity = false
                }

                if (tribeIdOpt.isPresent) {
                    this.attackCooldown--
                    if (this.getCurrentAction() == Action.ATTACK) {
                        this.getNavigation().stop()
                        this.yRot = this.getYawRotation()
                        this.yHeadRot = this.getYawRotation()
                        this.animationTicks++
                        if (22 == this.animationTicks) {
                            this.onTickEmpty()
                        }

                        if (32 == this.animationTicks) {
                            val targets = KoboldManager.getTribeTargets(tribeIdOpt.get())
                            val toRemove = HashSet<LivingEntity>()

                            for (target in targets) {
                                if (!(target.distanceTo(this) > 2.0f)) {
                                    target.hurt(this.damageSources().mobAttack(this), 5.0f)
                                    if (target.isRemoved) {
                                        toRemove.add(target)
                                    }
                                }
                            }

                            for (target in toRemove) {
                                KoboldManager.removeCombatant(tribeIdOpt.get(), target)
                            }
                        }

                        if (84 <= this.animationTicks) {
                            this.setCurrentAction(Action.NULL)
                            this.entityData.set(IS_ANCHORED, false)
                            this.animationTicks = 0
                        }
                    } else {
                        this.entityData.set(ARMED, this.handleTribeCombat(tribeIdOpt.get(), false))
                        this.entityData.set(TRIBE_MEMBER, KoboldManager.isTribeMember(tribeIdOpt.get(), this))
                        this.entityData.set(TRIBE_ALERTED, KoboldManager.isTribeAlerted(tribeIdOpt.get()))
                        this.handleMasterPresence()
                        this.handleModelSync()
                        this.watchClosestGirlGoal?.isWatching = this.isIdle()
                    }
                }
            }
        }
    }

    override fun tick() {
        super.tick()
        this.handleTribeState()
        this.handleInteraction()
        this.handleIdleState()
        this.handleSleepState()
        this.updateModelCodeDNA()
    }

    fun handleSleepState() {
        if (this.level().isClientSide) {
            if (this.level().gameTime - 300L >= lastTribeAlertTick) {
                if (this.hasMaster()) {
                    if (this.getCurrentAction() == Action.NULL) {
                        if ("" == this.entityData.get(GIRL_HAND_STATES)) {
                            if (!this.entityData.get(TRIBE_ALERTED)) {
                                val masterUuid = this.entityData.get(MASTER)
                                val player = this.level().getNearestPlayer(this, 10.0)
                                if (player == null) {
                                    this.nearestDistance = Float.MAX_VALUE
                                } else if (player.uuid.toString() == masterUuid) {
                                    val dist = this.distanceTo(player)
                                    if (dist < 2.0f && this.nearestDistance > 2.0f) {
                                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_KOBOLD_HEYMASTER))
                                        this.sendChatMessage("Hey master!")
                                        lastTribeAlertTick = this.level().gameTime
                                    }

                                    this.nearestDistance = dist
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun handleIdleState() {
        if (this.level().isClientSide) {
            if (this.getCurrentAction() != Action.SLEEP) {
                if (this.entityData.get(TRIBE_ALERTED)) {
                    if (this.hasMaster()) {
                        val player = this.level().getPlayerByUUID(UUID.fromString(this.entityData.get(MASTER)))
                        if (player != null) {
                            this.handleKoboldOwner(player)
                        }
                    }
                }
            }
        }
    }

    fun handleTribeState() {
        KoboldTribeState.handleTribeState(this)
    }

    override fun startActionFromHandState() {
        val handState = this.entityData.get(GIRL_HAND_STATES)
        SceneDebug.log(
            SceneDebug.SCENE_ENTRY,
            "Kobold.startActionFromHandState handState=%s action=%s remote=%s potion=%s master=%s",
            handState, this.getCurrentAction(), this.level().isClientSide,
            this.getEffect(HornyPotion.HORNY_POTION) != null, this.hasMaster(),
        )
        val hasHornyPotion = this.getEffect(HornyPotion.HORNY_POTION) != null
        var isMasterNear = false
        if (this.hasMaster()) {
            isMasterNear = this.entityData.get(MASTER) == this.getInteractionPlayerUUID().toString()
        }

        if (!hasHornyPotion && !isMasterNear) {
            if (handState == Action.STARTBLOWJOB.toString()) {
                if (this.getCurrentAction() == Action.PAYMENT) {
                    this.setCurrentAction(Action.STARTBLOWJOB)
                } else {
                    this.setCurrentAction(Action.PAYMENT)
                }
            }

            if (handState == Action.KOBOLD_ANAL_START.toString()) {
                if (this.getCurrentAction() == Action.PAYMENT) {
                    this.setCurrentAction(Action.KOBOLD_ANAL_START)
                } else {
                    this.setCurrentAction(Action.PAYMENT)
                }
            }

            if (handState == Action.MATING_PRESS_START.toString()) {
                this.setCurrentAction(Action.MATING_PRESS_START)
            }
        } else {
            if (handState == Action.STARTBLOWJOB.toString()) {
                this.setCurrentAction(Action.STARTBLOWJOB)
            }

            if (handState == Action.KOBOLD_ANAL_START.toString()) {
                this.setCurrentAction(Action.KOBOLD_ANAL_START)
            }

            if (handState == Action.MATING_PRESS_START.toString()) {
                this.setCurrentAction(Action.MATING_PRESS_START)
            }
        }
    }

    fun handleInteraction() {
        if (this.level().isClientSide) {
            val uuid = this.getInteractionPlayerUUID()
            if (uuid != null) {
                if (this.entityData.get(IS_ANCHORED)) {
                    if (this.getCurrentAction() == Action.NULL) {
                        val player = this.level().getPlayerByUUID(uuid)
                        if (player != null) {
                            this.handleKoboldOwner(player)
                        }
                    }
                }
            }
        }
    }

    fun handleKoboldOwner(player: Player) {
        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player.uuid)
        val headPos = Vec3(player.x, player.y + (playerGirl?.eyeHeight ?: player.eyeHeight), player.z)
        val eyePos = Vec3(this.x, this.y + this.eyeHeight, this.z)
        val dist = eyePos.distanceTo(headPos)
        val heightDiff = headPos.y - eyePos.y
        this.xRot = (-(Math.sin(heightDiff / dist) * (180.0 / Math.PI))).toFloat()
    }

    fun onTickEmpty() {
    }

    fun isIdle(): Boolean {
        return if (this.getCurrentAction() != Action.NULL) {
            false
        } else {
            if (Math.abs(this.deltaMovement.x) + Math.abs(this.deltaMovement.z) > 0.01) false else !this.isBlockedByCeiling()
        }
    }

    fun handleMasterPresence() {
        KoboldTribeState.handleMasterPresence(this)
    }

    fun getKickDistance(player: Player, dist: Double): Double = KoboldTribeState.getKickDistance(this, player, dist)

    fun teleportToHome(tribeId: UUID) {
        KoboldTribeState.teleportToHome(this, tribeId)
    }

    fun handleTribeRequest(tribeId: UUID) {
        KoboldTribeState.handleTribeRequest(this, tribeId)
    }

    fun handleTaskAssign(tribeId: UUID) {
        KoboldTribeState.handleTaskAssign(this, tribeId)
    }

    fun handleMemberSync(tribeId: UUID) {
        KoboldTribeState.handleMemberSync(this, tribeId)
    }

    fun handleTaskRequest(tribeId: UUID) {
        KoboldTribeState.handleTaskRequest(this, tribeId)
    }

    fun handleBedRequest(tribeId: UUID) {
        KoboldTribeState.handleBedRequest(this, tribeId)
    }

    fun handleHomeRequest(tribeId: UUID) {
        KoboldTribeState.handleHomeRequest(this, tribeId)
    }

    fun handleHomeRelease(tribeId: UUID) {
        KoboldTribeState.handleHomeRelease(this, tribeId)
    }

    fun handleTribeTasks(tribeId: UUID, tasks: Collection<KoboldTask>) {
        KoboldTribeState.handleTribeTasks(this, tribeId, tasks)
    }

    fun handleTribeTasksInit(tribeId: UUID, tasks: Collection<KoboldTask>) {
        KoboldTribeState.handleTribeTasksInit(this, tribeId, tasks)
    }

    fun handlePlayerDismount(player: Player) {
        KoboldTribeState.handlePlayerDismount(this, player)
    }

    fun getTribeHomePos(tribeId: UUID): BlockPos = KoboldTribeState.getTribeHomePos(this, tribeId)

    fun findStandPos(pos: BlockPos): BlockPos {
        val standPos = this.blockPosition()
        val delta = pos.subtract(standPos)
        if (Math.abs(delta.x) + Math.abs(delta.z) < 20) {
            return pos
        }

        val minAxis = Math.min(Math.abs(delta.x), Math.abs(delta.z)).toDouble()
        val maxAxis = Math.max(Math.abs(delta.x), Math.abs(delta.z)).toDouble()
        val ratio = minAxis / (maxAxis + minAxis)
        val xOffset = ((if (delta.x > 0) 1 else -1) * 20 * (if (minAxis == Math.abs(delta.x).toDouble()) ratio else 1.0 - ratio)).toInt()
        val zOffset = ((if (delta.z > 0) 1 else -1) * 20 * (if (minAxis == Math.abs(delta.z).toDouble()) ratio else 1.0 - ratio)).toInt()
        val candidate = this.blockPosition().offset(xOffset, 0, zOffset)
        return BlockPos(candidate.x, WorldUtils.getHeightAt(this.level(), candidate.x, candidate.z) + 1, candidate.z)
    }

    fun checkTribeHome(tribeId: UUID) {
        KoboldTribeState.checkTribeHome(this, tribeId)
    }

    fun findConnectedLogs(tribeId: UUID, tasks: Collection<KoboldTask>) {
        KoboldTribeState.findConnectedLogs(this, tribeId, tasks)
    }

    fun getTribeStateForTime(): TribeState = KoboldTribeState.getTribeStateForTime(this)

    fun isTribeTaskDone(tribeId: UUID): Boolean = KoboldTribeState.isTribeTaskDone(this, tribeId)

    fun handleTribeCombat(tribeId: UUID, isLeader: Boolean): Boolean = KoboldTribeState.handleTribeCombat(this, tribeId, isLeader)

    fun handleTribeJoin(tribeId: UUID) {
        KoboldTribeState.handleTribeJoin(this, tribeId)
    }

    fun handleHomeTeleport(tribeId: UUID) {
        KoboldTribeState.handleHomeTeleport(this, tribeId)
    }

    fun handleTaskFollow(tribeId: UUID) {
        KoboldTribeState.handleTaskFollow(this, tribeId)
    }

    fun syncTribeBlocks(pos: BlockPos) {
        PacketHandler.networkWrapper.sendToAllTracking(
            SpawnParticlePacket(this.getGirlId(), ParticleCompat.name(ParticleTypes.PORTAL), 30),
            TargetPoint(WorldUtils.getDimensionId(this.level()), this.x, this.y, this.z, 30.0),
        )
        this.setPos((0.5f + pos.x).toDouble(), pos.y.toDouble(), (0.5f + pos.z).toDouble())
        PacketHandler.networkWrapper.sendToAllTracking(
            SpawnParticlePacket(this.getGirlId(), ParticleCompat.name(ParticleTypes.PORTAL), 30),
            TargetPoint(WorldUtils.getDimensionId(this.level()), this.x, this.y, this.z, 30.0),
        )
    }

    fun handleTaskState(tribeId: UUID, task: KoboldTask) {
        KoboldMining.handleTaskState(this, tribeId, task)
    }

    fun handleTaskNavigation(tribeId: UUID, task: KoboldTask) {
        KoboldMining.handleTaskNavigation(this, tribeId, task)
    }

    fun executeMiningTask(task: KoboldTask, tribeId: UUID): BlockPos? = KoboldMining.executeMiningTask(this, task, tribeId)

    fun findTaskBlock(blocks: List<BlockPos>, index: Int, facing: Direction, targetPos: BlockPos): BlockPos? =
        KoboldMining.findTaskBlock(this, blocks, index, facing, targetPos)

    fun handleNearbyPlayerTick(tribeId: UUID) {
        if (!this.canStoreInventory(tribeId, false)) {
            this.handleNearbyPlayer()
        }
    }

    fun handleNearbyPlayer() {
        val player = this.level().getNearestPlayer(this, 15.0)
        if (this.hasMaster() && player != null && player.distanceTo(this) < 2.0f && this.entityData.get(MASTER) == player.uuid.toString()) {
            this.getNavigation().stop()
        } else {
            var target = this.wanderTarget
            if (target == null ||
                Math.sqrt(this.distanceToSqr(target.x.toDouble(), target.y.toDouble(), target.z.toDouble())) > this.getWanderRange() ||
                this.wanderTicks > 100
            ) {
                val xOffset = (if (this.random.nextBoolean()) 1 else -1) * this.random.nextInt(5)
                val zOffset = (if (this.random.nextBoolean()) 1 else -1) * this.random.nextInt(5)
                val height = WorldUtils.getHeightAt(this.level(), this.blockPosition().x + xOffset, this.blockPosition().z + zOffset)
                target = BlockPos(this.blockPosition().x + xOffset, height, this.blockPosition().z + zOffset)
                this.wanderTarget = target
                this.wanderTicks = 0
            }

            if (Math.sqrt(target.distSqr(this.blockPosition())) > 2.0) {
                this.getNavigation().moveTo(target.x.toDouble(), target.y.toDouble(), target.z.toDouble(), 0.35f.toDouble())
                this.tickPathVelocity()
            } else {
                this.wanderTicks++
            }
        }
    }

    fun getWanderRange(): Double = Math.sqrt(800.0)

    fun canStoreInventory(tribeId: UUID, checkOpen: Boolean): Boolean = KoboldInventory.canStoreInventory(this, tribeId, checkOpen)

    fun isTribeChestOpen(tribeId: UUID, checkOpen: Boolean): Boolean = KoboldInventory.isTribeChestOpen(this, tribeId, checkOpen)

    fun assignTaskToKobold(tribeId: UUID, task: KoboldTask): Boolean = KoboldTribeState.assignTaskToKobold(this, tribeId, task)

    fun navigateToTask(tribeId: UUID, task: KoboldTask, pos: BlockPos) {
        KoboldMining.navigateToTask(this, tribeId, task, pos)
    }

    fun startMiningTask(tribeId: UUID, pos: BlockPos, task: KoboldTask) {
        KoboldMining.startMiningTask(this, tribeId, pos, task)
    }

    fun getBlockItem(pos: BlockPos): ItemStack = KoboldMining.getBlockItem(this, pos)

    fun mineBlockAt(pos: BlockPos, tribeId: UUID) {
        KoboldMining.mineBlockAt(this, pos, tribeId)
    }

    fun handleModelSync() {
        KoboldTribeState.handleModelSync(this)
    }

    override fun setCurrentAction(action: Action?) {
        if (this.getCurrentAction() != Action.MATING_PRESS_CUM || action != Action.MATING_PRESS_SOFT && action != Action.MATING_PRESS_HARD) {
            if (this.getCurrentAction() != Action.KOBOLD_ANAL_CUM || action != Action.KOBOLD_ANAL_SLOW && action != Action.KOBOLD_ANAL_FAST) {
                if (this.getCurrentAction() != Action.CUMBLOWJOB || action != Action.SUCKBLOWJOB && action != Action.THRUSTBLOWJOB) {
                    if (action == Action.MATING_PRESS_CUM) {
                        this.actionCooldown = 0
                    }

                    super.setCurrentAction(action)
                }
            }
        }
    }

    /** 1.12 `onDeath`. */
    override fun die(source: DamageSource) {
        super.die(source)
        if (!this.level().isClientSide) {
            val tribeOpt = this.entityData.get(TRIBE_ID)
            if (tribeOpt.isPresent) {
                val tribeId = tribeOpt.get()
                KoboldManager.setTribeLeader(tribeId, this)
                if (this.hasMaster()) {
                    val master = this.level().getPlayerByUUID(UUID.fromString(this.entityData.get(MASTER)))
                    master?.sendSystemMessage(
                        Component.literal(
                            String.format("%s%s%s has perished %suwu", ChatFormatting.RED, this.getDisplayNameText(), ChatFormatting.WHITE, ChatFormatting.RED),
                        ),
                    )
                }
            }
        }
    }

    override fun getNextAction(action: Action): Action? {
        return if (action == Action.SUCKBLOWJOB_BLINK) {
            Action.THRUSTBLOWJOB
        } else {
            if (action == Action.KOBOLD_ANAL_SLOW) Action.KOBOLD_ANAL_FAST else null
        }
    }

    override fun getCumAction(action: Action): Action? {
        return if (action == Action.THRUSTBLOWJOB || action == Action.SUCKBLOWJOB_BLINK) {
            Action.CUMBLOWJOB
        } else if (action == Action.KOBOLD_ANAL_SLOW || action == Action.KOBOLD_ANAL_FAST) {
            Action.KOBOLD_ANAL_CUM
        } else {
            if (action != Action.MATING_PRESS_HARD && action != Action.MATING_PRESS_SOFT) null else Action.MATING_PRESS_CUM
        }
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        KoboldNbtIO.write(this, tag)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        KoboldNbtIO.read(this, tag)
    }

    override fun isBlockedByCeiling(): Boolean {
        if (this.isLocallyRegistered()) {
            return false
        }

        // 1.12 !Block.isPassable(world, pos) == material.blocksMovement()
        return this.level().getBlockState(this.blockPosition().offset(0, 1, 0)).blocksMotion()
    }

    fun hasInventoryItems(): Boolean = KoboldInventory.hasInventoryItems(this)

    fun canAssignTask(task: KoboldTask): Boolean = KoboldInventory.canAssignTask(this, task)

    fun canInsertItem(stack: ItemStack): Boolean = KoboldInventory.canInsertItem(this, stack)

    fun canStoreItems(stacks: List<ItemStack>): Boolean = KoboldInventory.canStoreItems(this, stacks)

    fun canExtractItem(stack: ItemStack): Boolean = KoboldInventory.canExtractItem(this, stack)

    fun canInsertItemStack(handler: ItemStackHandler, stack: ItemStack, simulate: Boolean, extract: Boolean): Boolean =
        KoboldInventory.canInsertItemStack(this, handler, stack, simulate, extract)

    override fun playSoundAtVolume(sound: SoundEvent, volume: Float) {
        val shrink = 0.25f - this.entityData.get(SIZE_SCALAR)
        val progress = (shrink / 0.25f).toDouble()
        val pitch = RotationHelper.lerpDouble(0.9f.toDouble(), 1.1f.toDouble(), progress).toFloat()
        this.playSoundAtPosition(sound, volume, pitch)
    }

    override fun playSound(sound: SoundEvent) {
        this.playSoundAtVolume(sound, 1.0f)
    }

    fun playRandomSounds(sounds: Array<SoundEvent>) {
        this.playRandomSound(sounds, 1.0f)
    }

    fun playRandomSound(sounds: Array<SoundEvent>, volume: Float) {
        this.playSoundAtVolume(sounds[this.random.nextInt(sounds.size)], volume)
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState = KoboldAnimation.animationPredicate(this, event)

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        KoboldAnimation.registerControllers(this, controllers)
    }

    // ---- 1.12 IInventory -> Container ----

    override fun getContainerSize(): Int = 27

    override fun isEmpty(): Boolean = false

    override fun getItem(slot: Int): ItemStack = if (slot >= this.inventory.slots) ItemStack.EMPTY else this.inventory.getStackInSlot(slot)

    override fun removeItem(slot: Int, amount: Int): ItemStack = this.inventory.extractItem(slot, amount, false)

    override fun removeItemNoUpdate(slot: Int): ItemStack =
        this.inventory.extractItem(slot, this.inventory.getStackInSlot(slot).count, false)

    override fun setItem(slot: Int, stack: ItemStack) {
        this.inventory.setStackInSlot(slot, stack)
    }

    override fun getMaxStackSize(): Int = 64

    override fun setChanged() {
    }

    override fun stillValid(player: Player): Boolean = true

    override fun startOpen(player: Player) {
    }

    override fun stopOpen(player: Player) {
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = true

    fun getField(id: Int): Int = id

    fun setField(id: Int, value: Int) {
    }

    fun getFieldCount(): Int = 0

    override fun clearContent() {
    }

    /** The 1.12 static `KoboldEntity.c` Forge handler (registered by `ForgeEventHandler`). */
    class KoboldTribeEvents {
        @SubscribeEvent
        fun onLivingDeath(event: LivingDeathEvent) {
            val kobold = event.entity as? KoboldEntity ?: return
            if (kobold.level().isClientSide) {
                return
            }

            for (slot in 0 until kobold.inventory.slots) {
                val stack = kobold.inventory.getStackInSlot(slot)
                if (stack.item !== Items.AIR) {
                    // 1.12 dropItem(item, count): a fresh stack of the item (metadata / NBT dropped)
                    kobold.spawnAtLocation(ItemStack(stack.item, stack.count))
                }
            }
        }

        /** 1.12 `LivingHurtEvent`. */
        @SubscribeEvent
        fun onLivingHurtPlayer(event: LivingIncomingDamageEvent) {
            val entity = event.entity
            val world = entity.level()
            if (!world.isClientSide) {
                if (entity is KoboldEntity) {
                    val tribeOpt = entity.getEntityData().get(TRIBE_ID)
                    if (tribeOpt.isPresent) {
                        val attacker = event.source.entity
                        if (attacker != null) {
                            if (attacker is LivingEntity) {
                                if (attacker is Player) {
                                    if (attacker.abilities.instabuild) {
                                        return
                                    }

                                    if (attacker == entity.getMasterPlayer()) {
                                        return
                                    }
                                }

                                val master = entity.getMasterPlayer()
                                master?.displayClientMessage(Component.literal(ChatFormatting.RED.toString() + "Your Tribe is under Attack!"), true)

                                KoboldManager.addCombatant(tribeOpt.get(), attacker)
                            }
                        }
                    }
                }
            }
        }

        @SubscribeEvent
        fun onWorldUnload(event: LevelEvent.Unload) {
            try {
                for (girl in BaseGirlEntity.getGirlEntityList()) {
                    if (girl is KoboldEntity) {
                        val tribeOpt = girl.getEntityData().get(TRIBE_ID)
                        if (tribeOpt.isPresent && KoboldManager.isTribeMember(tribeOpt.get(), girl)) {
                            girl.teleportToHome(tribeOpt.get())
                        }
                    }
                }
            } catch (ex: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }
        }

        /** 1.12 `LivingHurtEvent` with `DamageSource.IN_WALL`. */
        @SubscribeEvent
        fun onLivingHurtCancel(event: LivingIncomingDamageEvent) {
            if (event.source.`is`(DamageTypes.IN_WALL)) {
                val entity = event.entity
                if (entity is KoboldEntity) {
                    entity.setPos(entity.x, entity.y + 1.0, entity.z)
                    event.setCanceled(true)
                }
            }
        }

        /** Client half (Rule E): registered by `ForgeEventHandler` on the client only. */
        object ClientEvents {
            @JvmField
            var tickCounter: Int = 0

            @SubscribeEvent
            fun onClientTick(event: ClientTickEvent.Post) {
                val world = com.trolmastercard.sexmod.client.ClientHooks.clientLevel()
                if (world != null) {
                    if (++tickCounter % 20 == 0) {
                        PacketHandler.networkWrapper.sendToServer(GetTribeUiValuesPacket())
                    }
                }
            }
        }
    }

    private object Client {
        fun openTribeNameScreen(tribeId: UUID) {
            net.minecraft.client.Minecraft.getInstance().setScreen(TribeNameScreen(tribeId))
        }

        fun clearBoneColors() {
            KoboldRenderer.clearBoneColors()
        }
    }

    companion object {
        @JvmField
        val DEFAULT_EYE_COLOR: EyeAndKoboldColor = EyeAndKoboldColor.PURPLE

        const val SCALE_0_25: Float = 0.25f
        const val ar: Int = 20
        const val ag: Int = 2
        const val aG: Int = 30
        const val ah: Int = 84
        const val a3: Int = 32
        const val a1: Int = 5
        const val ae: Float = 1.5f
        const val aW: Float = 20.0f
        const val au: Double = 10.0
        const val ay: Double = 2.0
        const val al: Double = 3.0
        const val aQ: Int = 300
        const val aq: Int = 5
        const val aO: Int = 100
        const val aB: Int = 100
        const val ac: Int = 2
        const val am: Float = 2.0f
        const val aw: Int = 300
        const val aj: Float = 0.2f
        const val aH: Double = 0.7
        const val aa: Int = 142
        const val av: Int = 24

        /** Re-export of [AbstractNpcOnlyEntity.CURRENT_ACTION] (body colour name) for `KoboldEntity.X` call sites. */
        @JvmField
        val CURRENT_ACTION: EntityDataAccessor<String> = AbstractNpcOnlyEntity.CURRENT_ACTION

        /** Re-export of [AbstractNpcOnlyEntity.ACTION_TARGET_POS] (eye colour) for `KoboldEntity.X` call sites. */
        @JvmField
        val ACTION_TARGET_POS: EntityDataAccessor<BlockPos> = AbstractNpcOnlyEntity.ACTION_TARGET_POS

        /** Re-export of [AbstractNpcOnlyEntity.APPEARANCE_DNA] for `KoboldEntity.X` call sites. */
        @JvmField
        val APPEARANCE_DNA: EntityDataAccessor<String> = AbstractNpcOnlyEntity.APPEARANCE_DNA

        /** Alias of [BaseGirlEntity.SHARED_FLOAT_122] (1.12 id 122). */
        @JvmField
        val SIZE_SCALAR: EntityDataAccessor<Float> = BaseGirlEntity.SHARED_FLOAT_122

        @JvmField
        val KOBOLD_NAME: EntityDataAccessor<String> = SynchedEntityData.defineId(KoboldEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val ARMED: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(KoboldEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val TRIBE_MEMBER: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(KoboldEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val TRIBE_NAME: EntityDataAccessor<String> = SynchedEntityData.defineId(KoboldEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val TRIBE_ALERTED: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(KoboldEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val MINING_WITH_AXE: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(KoboldEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val TRIBE_ID: EntityDataAccessor<Optional<UUID>> = SynchedEntityData.defineId(KoboldEntity::class.java, EntityDataSerializers.OPTIONAL_UUID)

        /** Global max-health hack: shared by every kobold in the world (see class doc). */
        @JvmField
        var spawnHealth: Double = 69.0

        /** CLIENT: tribe HUD data from [GetTribeUiValuesPacket]. */
        @JvmField
        var tribeMembers: MutableList<Vector4d> = ArrayList()

        @JvmField
        var lastTribeAlertTick: Long = Long.MIN_VALUE

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder =
            AbstractNpcOnlyEntity.createAttributes()
                .add(Attributes.MAX_HEALTH, spawnHealth)
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 30.0)

        @JvmStatic
        fun createKobold(world: Level, tribeId: UUID): KoboldEntity {
            val throwDelay = getRandomThrowDelay()
            return createKoboldWithSpeed(world, tribeId, throwDelay)
        }

        @JvmStatic
        fun createKoboldWithSpeed(world: Level, tribeId: UUID, throwDelay: Float): KoboldEntity {
            spawnHealth = 10.0 - throwDelay * 25.0
            return KoboldEntity(world, tribeId, throwDelay)
        }

        @JvmStatic
        fun getRandomThrowDelay(): Float = (Math.random() * 0.25).toFloat()
    }
}
