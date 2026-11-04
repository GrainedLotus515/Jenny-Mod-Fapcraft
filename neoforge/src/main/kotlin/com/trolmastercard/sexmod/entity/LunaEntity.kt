package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.entity.ai.WatchClosestGirlGoal
import com.trolmastercard.sexmod.entity.api.IEllie
import com.trolmastercard.sexmod.item.LunaRodItem
import com.trolmastercard.sexmod.networking.CatActivateFishingPacket
import com.trolmastercard.sexmod.networking.CatEatingDonePacket
import com.trolmastercard.sexmod.networking.CatThrowAwayItemPacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.ResetGirlPacket
import com.trolmastercard.sexmod.networking.SendChatMessagePacket
import com.trolmastercard.sexmod.networking.SendCompanionHomePacket
import com.trolmastercard.sexmod.networking.SendGirlToSexPacket
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.IBeddableSexGirl
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SceneDebug
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.ThreadNames
import com.trolmastercard.sexmod.util.VectorMath
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.UUID
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * Luna NPC — the catgirl who fishes, eats, and has touch-boobs and sitting
 * cowgirl scenes.
 *
 * <b>Scene entry</b> (shared with Jenny/Bia/Kobold): client `doAction`
 * sets `animationFollowUp` (GIRL_HAND_STATES) via
 * `ChangeDataParameterPacket` and sends `KoboldStatePacket`; the
 * server calls `setDismounted()` ([targetLerpActive]), then
 * [updateAITasks] lerps her to `TARGET_POS` for ~40 ticks
 * (`targetLerpTicks` counter), anchors her and calls [startActionFromHandState]:
 * touch_boobs -> PAYMENT (fish!) -> TOUCH_BOOBS_INTRO; sex ->
 * PAYMENT -> `SendGirlToSexPacket` + full `ResetGirlPacket`
 * (jar-faithful order) -> [goToSexBed] walks her to the nearest
 * bed ([walkingToBed]/`bedWalkTicks`), anchors, enters [Action.WAIT_CAT],
 * and [handleNearbyPlayer] starts COWGIRL_SITTING_INTRO once the
 * player stands within 1.25 blocks (25-tick counter `waitCatTicks`).
 *
 * <b>Idle behavior:</b> without a master/interaction she periodically fishes
 * (finds water via [findFishingSpot], casts her rod, may eat the
 * catch; [hookEntity] is the bobber, [castExpiryTick] schedules
 * its removal).
 *
 * <b>Pitfalls:</b>
 * - The dismount/bed lerps MUST use
 *   `RotationHelper.lerpVec3d(pos, target, 40 - counter)` (INT
 *   step variant) — the double variant flings her and she vanishes.
 * - [readAdditionalSaveData] forcing `setNoGravity(false)` on
 *   load is an invented (non-jar) band-aid; keep only if the float-on-
 *   reload symptom reappears.
 * - Data-parameter IDs 118-121 on this hierarchy were explicit in 1.12; in
 *   1.21 they are this class's own keys (PORTING.md §2.6).
 *
 * Port notes: `Items.FISH` meta 0/1 are `COD`/`SALMON`; the free headpat
 * reward (1.12 `null`) is `ItemStack.EMPTY` because the shared
 * `openInventoryGui` takes `Array<ItemStack>`. `ItemFood` checks are the
 * `DataComponents.FOOD` component. `getJumpUpwardsMotion` is [getJumpPower].
 */
class LunaEntity : AbstractGirlNpcEntity, IEllie, IBeddableSexGirl {
    @JvmField
    var heldRod: ItemStack = ItemStack(LunaRodItem.LUNA_ROD)

    @JvmField
    var hookEntity: SexEntity? = null

    @JvmField
    var renderScale: Float = 1.0f

    @JvmField
    var zFlag: Float = 0.0f

    @JvmField
    var fishingIdleTicks: Int = 8000

    @JvmField
    var targetLerpActive: Boolean = false

    @JvmField
    var targetLerpTicks: Int = 0

    @JvmField
    var walkingToBed: Boolean = false

    @JvmField
    var bedWalkTicks: Int = 0

    @JvmField
    var waitCatTicks: Int = 0

    @JvmField
    var fishingSpot: BlockPos? = null

    @JvmField
    var spotDepth: Int = 0

    @JvmField
    var spotChangeTicks: Int = 0

    @JvmField
    var am: Boolean = false

    @JvmField
    var castExpiryTick: Long = 0L

    @JvmField
    var fishingDone: Boolean = false

    @JvmField
    var previousPath: Path? = null

    @JvmField
    var fishingAttempts: Int = 0

    @JvmField
    var triedSpots: HashSet<BlockPos?> = HashSet()

    @JvmField
    var touchBoobsVariant: Boolean = false

    @JvmField
    var idleVariant: Boolean = false

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out LunaEntity>, level: Level) : super(type, level) {
        this.slashSwordRot = 230
        this.stabSwordRot = 150
        this.holdBowRot = 320
        this.swordOffsetStab = Vec3(0.0, -0.05999999718368053, 0.10000001192092894)
        if (this.inventory.getStackInSlot(0) === ItemStack.EMPTY) {
            this.inventory.setStackInSlot(0, ItemStack(Items.IRON_AXE))
        }

        if (this.inventory.getStackInSlot(6) === ItemStack.EMPTY) {
            this.inventory.setStackInSlot(6, ItemStack(Items.FISHING_ROD))
        }
    }

    /** Kept for Java call sites and reflection (NpcType / ClothingScreen use getConstructor(Level)). */
    constructor(level: Level) : this(ModEntities.LUNA.get(), level)

    override fun getDisplayNameText(): String = "Luna"

    override fun getScaleFactor(): Float = -0.2f

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(yFlag, 0.0f)
        builder.define(SYNCED_HELD_ITEM, ItemStack.EMPTY)
        builder.define(FISH_ON_LINE, false)
        builder.define(CAUGHT_ITEM, ItemStack.EMPTY)
    }

    override fun onArriveHome() {
        this.sendChatMessage("Love it here owo")
        this.playRandomSound(SoundHandler.GIRLS_LUNA_OWO)
    }

    override fun setCurrentAction(action: Action?) {
        if (this.getCurrentAction() !== Action.COWGIRL_SITTING_CUM || action !== Action.COWGIRL_SITTING_SLOW && action !== Action.COWGIRL_SITTING_FAST) {
            if (this.getCurrentAction() !== Action.TOUCH_BOOBS_CUM || action !== Action.TOUCH_BOOBS_FAST && action !== Action.TOUCH_BOOBS_SLOW) {
                super.setCurrentAction(action)
            }
        }
    }

    override fun setDismounted() {
        this.targetLerpActive = true
    }

    /** 1.12 `getEyeHeight()` = 1.34. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(1.34f)

    /** Client: I18n lookup; on the server (where 1.12 would have crashed) the raw key is returned. */
    private fun i18n(key: String): String = if (this.level().isClientSide) I18n.get(key) else key

    /** 1.12 `processInteract`. */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        val superResult = super.mobInteract(player, hand)
        if (superResult.consumesAction()) {
            return superResult
        }

        val success = InteractionResult.sidedSuccess(this.level().isClientSide)
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
        val options = arrayOf("action.names.sex", "action.names.touchboobs", "action.names.headpat")
        val rewards = arrayOf(ItemStack(Items.COD, 3), ItemStack(Items.SALMON, 2), ItemStack.EMPTY)
        onPlayerApproach(player, this, options, rewards)
        return true
    }

    fun setHeldItemStack(stack: ItemStack) {
        this.entityData.set(CAUGHT_ITEM, stack)
    }

    override fun reinitTasks() {
        this.resetWanderGoals()
    }

    override fun updateAITasks() {
        super.updateAITasks()
        if (!this.hasMaster()) {
            this.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = 1.0
        } else {
            this.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = 0.5
        }

        this.handleFishingPath()
        this.handleFishingIdle()
        this.entityData.set(FISH_ON_LINE, this.hookEntity != null && this.entityData.get(CAUGHT_ITEM) === ItemStack.EMPTY)
        if (this.castExpiryTick == this.level().gameTime && this.hookEntity != null) {
            this.hookEntity?.discard()
            this.hookEntity = null
        }

        if (this.walkingToBed) {
            val dist = this.getTargetPosition().distanceTo(this.position())
            if (!(dist < 0.5) && this.bedWalkTicks <= 200) {
                if (++this.bedWalkTicks == 60 || this.bedWalkTicks == 120) {
                    SceneDebug.log(SceneDebug.SCENE_ENTRY, "Luna: walking to bed, bedWalkTicks=%d dist=%.2f", this.bedWalkTicks, dist)
                    this.getNavigation().stop()
                    this.getNavigation().moveTo(this.getTargetPosition().x, this.getTargetPosition().y, this.getTargetPosition().z, 0.2)
                }
            } else {
                SceneDebug.log(SceneDebug.SCENE_ENTRY, "Luna: arrived at bed, WAIT_CAT (dist=%.2f bedWalkTicks=%d)", dist, this.bedWalkTicks)
                this.walkingToBed = false
                this.bedWalkTicks = 0
                this.entityData.set(IS_ANCHORED, true)
                this.noPhysics = true
                this.setNoGravity(true)
                this.setDeltaMovement(0.0, 0.0, 0.0)
                this.setCurrentAction(Action.WAIT_CAT)
            }
        }

        if (this.targetLerpActive) {
            this.targetLerpTicks++
            if (this.position() != this.getTargetPosition() && this.targetLerpTicks <= 40) {
                this.yRot = this.getYawRotation()
                this.setNoGravity(false)
                val pos = RotationHelper.lerpVec3d(this.position(), this.getTargetPosition(), 40 - this.targetLerpTicks)
                this.setPos(pos.x, pos.y, pos.z)
            } else {
                this.targetLerpActive = false
                this.targetLerpTicks = 0
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

        this.syncHeldItem()
        this.entityData.set(SYNCED_HELD_ITEM, this.inventory.getStackInSlot(6))
    }

    fun syncHeldItem() {
        val heldItem = this.heldRod
        val syncedItem = this.entityData.get(SYNCED_HELD_ITEM)
        if (syncedItem !== ItemStack.EMPTY) {
            val enchantments = EnchantmentHelper.getEnchantmentsForCrafting(syncedItem)
            EnchantmentHelper.setEnchantments(heldItem, enchantments)
        }
    }

    override fun tick() {
        super.tick()
        if (Action.WAIT_CAT === this.getCurrentAction()) {
            this.handleNearbyPlayer()
        } else {
            this.waitCatTicks = 0
        }
    }

    fun handleNearbyPlayer() {
        val player = this.level().getNearestPlayer(this, 10.0)
        if (player != null) {
            if (!(player.distanceTo(this) > 1.25f)) {
                if (this.waitCatTicks % 10 == 0) {
                    SceneDebug.log(
                        SceneDebug.SCENE_ENTRY, "Luna.handleNearbyPlayer remote=%s waitCatTicks=%d playerDist=%.2f",
                        this.level().isClientSide, this.waitCatTicks, player.distanceTo(this),
                    )
                }
                if (this.level().isClientSide) {
                    this.setFishingLevelFor(player, this.waitCatTicks)
                } else if (this.waitCatTicks == 25) {
                    SceneDebug.log(SceneDebug.SCENE_ENTRY, "Luna.handleNearbyPlayer SERVER: ab==25 -> COWGIRL_SITTING_INTRO")
                    this.setInteractionPlayerUUID(player.uuid)
                    player.moveRelative(0.0f, Vec3.ZERO)
                    player.teleportTo(this.position().x, this.position().y, this.position().z)
                    this.setCurrentAction(Action.COWGIRL_SITTING_INTRO)
                    player.yHeadRot = this.getYawRotation() + 180.0f
                    player.yRot = this.getYawRotation() + 180.0f
                    player.yRotO = this.getYawRotation() + 180.0f
                    this.cameraYaw = this.getYawRotation() + 180.0f
                    this.positionPlayerRelative(0.0, (-0.075f).toDouble(), -0.7109375, 0.0f, 0.0f)
                    this.entityData.set(OUTFIT_INDEX, 0)
                }

                this.waitCatTicks++
            }
        }
    }

    /** CLIENT. */
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

    override fun goToSexBed() {
        this.entityData.set(IS_ANCHORED, false)
        this.setCurrentAction(Action.NULL)
        this.fishingDone = true
        val bedPos = this.getNearestBed(this.blockPosition())
        if (bedPos == null) {
            this.playRandomSound(SoundHandler.GIRLS_LUNA_GIGGLE)
            PacketHandler.networkWrapper.sendToAllAround(
                SendChatMessagePacket(
                    "<" + this.getDisplayNameText() + "> Heh.. there is no bed nearby.. but I already ate the fish so nya~ hehe",
                    WorldUtils.getDimensionId(this.level()),
                    this.getGirlId(),
                ),
                this.getTargetNetworkPoint(),
            )
        } else {
            val bedVec = Vec3(bedPos.x.toDouble(), bedPos.y.toDouble(), bedPos.z.toDouble())
            val bestIndex = GirlBedPlacement.findFreeSide(this, bedVec, GirlBedPlacement.SIDE_OFFSETS)
            if (bestIndex == -1) {
                this.playRandomSound(SoundHandler.GIRLS_LUNA_GIGGLE)
                this.sendChatMessage("Heh.. the bed is obscured.. but I already ate the fish so nya~ hehe")
                return
            }

            val bedOffset = bedVec.add(GirlBedPlacement.SIDE_OFFSETS[bestIndex][0])
            this.setYawRotation(GirlBedPlacement.SIDE_YAWS[bestIndex].toFloat())
            this.setTargetPosition(Vec3(bedOffset.x, bedOffset.y, bedOffset.z))
            this.cameraYaw = this.getYawRotation()
            this.getNavigation().stop()
            this.getNavigation().moveTo(bedOffset.x, bedOffset.y, bedOffset.z, 0.2)
            this.walkingToBed = true
            this.bedWalkTicks = 0
        }
    }

    fun dropHeldItem() {
        val item = ItemEntity(this.level(), this.x, this.y, this.z, this.entityData.get(CAUGHT_ITEM))
        val throwVec = VectorMath.rotateByYaw(Vec3(0.0, 0.2f + Math.random() * 0.1f, -0.2f + Math.random() * -0.1f), this.yRot)
        item.setDeltaMovement(throwVec.x, throwVec.y, throwVec.z)
        this.level().addFreshEntity(item)
        this.entityData.set(CAUGHT_ITEM, ItemStack.EMPTY)
    }

    fun clearFishingState() {
        this.fishingSpot = null
        this.spotDepth = 0
        this.spotChangeTicks = 0
        this.am = false
        this.entityData.set(IS_ANCHORED, false)
        this.entityData.set(CAUGHT_ITEM, ItemStack.EMPTY)
        this.isSilent = false
        this.setCurrentAction(Action.NULL)
        if (this.hookEntity != null) {
            this.hookEntity?.discard()
            this.hookEntity = null
        }

        if (this.getInteractionPlayerUUID() == null) {
            val watch = WatchClosestGirlGoal(this, Player::class.java, 3.0f, 1.0f)
            this.watchClosestGirlGoal = watch
            this.goalSelector.addGoal(5, watch)
            if (!this.hasMaster()) {
                val wander = WaterAvoidingRandomStrollGoal(this, 0.35)
                this.wanderGoal = wander
                this.goalSelector.addGoal(5, wander)
            }
        }
    }

    fun onFishingTick() {
        this.clearFishingState()
        if (++this.fishingAttempts >= 3) {
            this.fishingAttempts = 0
            this.fishingIdleTicks = 0
        }
    }

    fun handleFishingIdle() {
        if (!this.hasMaster() && this.getInteractionPlayerUUID() == null && !this.fishingDone) {
            if (!(++this.fishingIdleTicks < 1200.0f)) {
                val hook = this.hookEntity
                if (hook != null && hook.lureTimer == 15) {
                    (this.heldRod.item as LunaRodItem).castFishingRod(this.level(), this, InteractionHand.MAIN_HAND)
                    this.castExpiryTick = this.level().gameTime + 20L
                    val heldItem = this.entityData.get(CAUGHT_ITEM)
                    if (heldItem !== ItemStack.EMPTY) {
                        if (heldItem.has(DataComponents.FOOD)) {
                            this.setCurrentAction(Action.FISHING_EAT)
                        } else {
                            this.setCurrentAction(Action.FISHING_THROW_AWAY)
                        }
                    }
                }

                if (!this.getCurrentAction().toString().lowercase().contains("fishing")) {
                    this.findFishingSpot()
                    this.handleFishingMove()
                }

                val spot = this.fishingSpot
                if (spot != null && this.previousPath == null && this.getNavigation().path == null && !this.isInWater && this.onGround()) {
                    // 1.12 rayTraceBlocks(eyes, spot, stopOnLiquid=true): result unused
                    this.level().clip(
                        ClipContext(
                            this.position().add(0.0, this.eyeHeight.toDouble(), 0.0),
                            Vec3(spot.x.toDouble(), spot.y.toDouble(), spot.z.toDouble()),
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.ANY,
                            this,
                        ),
                    )
                    this.isSilent = true
                    if (this.wanderGoal != null) {
                        this.goalSelector.removeGoal(this.wanderGoal!!)
                        this.wanderGoal = null
                    }

                    if (this.watchClosestGirlGoal != null) {
                        this.goalSelector.removeGoal(this.watchClosestGirlGoal!!)
                        this.watchClosestGirlGoal = null
                    }

                    if (this.getCurrentAction() === Action.NULL) {
                        this.setCurrentAction(Action.FISHING_START)
                        this.setTargetPosition(this.position())
                        this.entityData.set(IS_ANCHORED, true)
                        this.setYawRotation(
                            Math.atan2(this.z - spot.z, this.x - spot.x).toFloat() * (180.0 / Math.PI).toFloat() + 90.0f,
                        )
                    }
                } else {
                    this.previousPath = this.getNavigation().path
                }
            }
        } else {
            if (this.entityData.get(FISH_ON_LINE)) {
                this.clearFishingState()
            }
        }
    }

    fun addCaughtItem() {
        this.triedSpots.add(this.fishingSpot)
        this.clearFishingState()
    }

    fun handleFishingMove() {
        val spot = this.fishingSpot
        if (spot != null) {
            val navigator = this.getNavigation()
            navigator.moveTo(spot.x.toDouble(), spot.y.toDouble(), spot.z.toDouble(), 0.35f.toDouble())
            val path = navigator.path
            if (path != null) {
                if (path.nodeCount > path.nextNodeIndex + 1) {
                    val nextPoint = path.getNode(path.nextNodeIndex + 1)
                    val finalPoint = path.getNode(path.nodeCount - 1)
                    val finalVec = Vec3(finalPoint.x.toDouble(), finalPoint.y.toDouble(), finalPoint.z.toDouble())
                    val pos = BlockPos(nextPoint.x, nextPoint.y, nextPoint.z)
                    if (this.position().distanceTo(finalVec) < 0.75) {
                        navigator.stop()
                        this.setPos(finalVec.x, finalVec.y, finalVec.z)
                    }

                    if (this.level().getBlockState(pos.offset(0, 1, 0)).block === Blocks.WATER) {
                        navigator.stop()
                    }

                    if (this.level().getBlockState(pos).block === Blocks.WATER) {
                        navigator.stop()
                    }

                    if (this.level().getBlockState(pos.offset(0, -1, 0)).block === Blocks.WATER) {
                        navigator.stop()
                    }
                }
            }
        }
    }

    fun findFishingSpot() {
        var attempts = 0
        var spot: BlockPos? = null
        var bestDepth = 0

        while (++attempts < 50) {
            var candidate = this.findNearestStructureBlock(
                this.blockPosition(),
                attempts + 1,
                Blocks.WATER,
                60,
                10,
                HashSet<ResourceKey<Biome>>(
                    listOf(
                        Biomes.RIVER,
                        Biomes.OCEAN,
                        Biomes.DEEP_OCEAN,
                        Biomes.BEACH,
                        Biomes.STONY_SHORE,
                        Biomes.SWAMP,
                        Biomes.SWAMP, // 1.12 MUTATED_SWAMPLAND
                    ),
                ),
            ) ?: break

            while (this.level().getBlockState(candidate.offset(0, 1, 0)).block === Blocks.WATER) {
                candidate = candidate.offset(0, 1, 0)
            }

            var depth = 1

            var probe: BlockPos = candidate
            while (this.level().getBlockState(probe.offset(0, -1, 0)).block === Blocks.WATER) {
                probe = probe.offset(0, -1, 0)
                depth++
            }

            if (!this.triedSpots.contains(candidate)) {
                if (spot == null) {
                    spot = candidate
                    bestDepth = depth
                } else if (depth > bestDepth) {
                    spot = candidate
                    bestDepth = depth
                    if (bestDepth >= 6) {
                        break
                    }
                }
            }
        }

        if (spot != null) {
            if (this.fishingSpot == null || this.spotDepth < bestDepth) {
                this.fishingSpot = spot
                this.spotDepth = bestDepth
            }

            if (this.fishingSpot == spot) {
                this.spotChangeTicks = 0
            } else if (++this.spotChangeTicks > 20) {
                this.fishingSpot = spot
                this.spotDepth = bestDepth
            }
        }
    }

    fun handleFishingPath() {
        val path = this.getNavigation().path
        if (path != null) {
            val finalPoint = path.endNode
            val targetPoint = Node(ThreadNames.roundToInt(this.x), ThreadNames.roundToInt(this.y), ThreadNames.roundToInt(this.z))
            if (finalPoint != null) {
                this.entityData.set(yFlag, finalPoint.distanceTo(targetPoint))
            }
        }
    }

    override fun doAction(action: String, uuid: UUID) {
        SceneDebug.log(SceneDebug.SCENE_ENTRY, "Luna.doAction %s player=%s (remote=%s)", action, uuid, this.level().isClientSide)
        super.doAction(action, uuid)
        if ("action.names.touchboobs" == action) {
            this.setInteractionPlayerUUID(uuid)
            this.triggerActionSync(true, true, uuid)
            this.changeDataParameterFromClient("animationFollowUp", "touch_boobs")
            this.changeDataParameterFromClient("currentModel", "0")
            HandlePlayerMovement.setMovementLock(false)
        }

        if ("action.names.sex" == action) {
            this.setInteractionPlayerUUID(uuid)
            this.triggerActionSync(true, true, uuid)
            this.changeDataParameterFromClient("animationFollowUp", "sex")
            HandlePlayerMovement.setMovementLock(false)
        }

        if ("action.names.headpat" == action) {
            this.setInteractionPlayerUUID(uuid)
            this.triggerActionSync(true, true, uuid)
            HandlePlayerMovement.setMovementLock(false)
            this.changeDataParameterFromClient("animationFollowUp", "headpat")
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

    override fun startActionFromHandState() {
        SceneDebug.log(
            SceneDebug.SCENE_ENTRY, "Luna.startActionFromHandState handState=%s action=%s remote=%s",
            this.entityData.get(GIRL_HAND_STATES), this.getCurrentAction(), this.level().isClientSide,
        )
        when (this.entityData.get(GIRL_HAND_STATES)) {
            "touch_boobs" -> {
                if (this.getCurrentAction() !== Action.PAYMENT) {
                    this.setCurrentAction(Action.PAYMENT)
                    return
                }

                this.setCurrentAction(Action.TOUCH_BOOBS_INTRO)
            }
            "sex" -> {
                if (this.getCurrentAction() !== Action.PAYMENT) {
                    this.setCurrentAction(Action.PAYMENT)
                } else {
                    PacketHandler.networkWrapper.sendToServer(SendGirlToSexPacket(this.getGirlId()))
                    PacketHandler.networkWrapper.sendToServer(ResetGirlPacket(this.getGirlId()))
                }

                return
            }
            "headpat" -> this.setCurrentAction(Action.HEAD_PAT)
        }

        if (this.level().isClientSide) {
            this.changeDataParameterFromClient("animationFollowUp", "")
        } else {
            this.entityData.set(GIRL_HAND_STATES, "")
        }
    }

    override fun playHurtSound(source: DamageSource) {
        this.playRandomSound(SoundHandler.GIRLS_LUNA_OUU)
    }

    override fun getDeathSound(): SoundEvent? =
        if (this.random.nextFloat() * 100.0f > 95.0f) SoundHandler.GIRLS_ALLIE_SCAWY[2] else SoundHandler.GIRLS_LUNA_OUU[12]

    /** 1.12 `getJumpUpwardsMotion()`. */
    override fun getJumpPower(): Float = if (this.isInWater) 1.0f else 0.5f

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState {
        if (SexWorldClient.isPreloadWorld(this.level())) {
            return PlayState.STOP
        }

        when (event.controller.name) {
            "eyes" -> {
                if (this.getCurrentAction() !== Action.NULL) {
                    this.createAnimation("animation.cat.null", true, event)
                } else {
                    this.createAnimation("animation.cat.blink", true, event)
                }
            }
            "movement" -> {
                if (this.getCurrentAction() !== Action.NULL) {
                    this.createAnimation("animation.cat.null", true, event)
                } else if (this.isPassenger) {
                    this.createAnimation("animation.cat.sit", true, event)
                } else if (Math.abs(this.xo - this.x) + Math.abs(this.zo - this.z) > 0.0) {
                    if (this.onGround() && Math.abs(Math.abs(this.yo) - Math.abs(this.y)) < 0.1f) {
                        this.createAnimation(if (this.entityData.get(yFlag) < 3.0f) "animation.cat.walk" else "animation.cat.run", true, event)
                    } else {
                        this.createAnimation("animation.cat.fly", true, event)
                    }

                    this.yRot = this.yHeadRot
                } else {
                    this.createAnimation("animation.cat.idle" + (if (this.idleVariant) "2" else ""), true, event)
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
                Action.TOUCH_BOOBS_SLOW -> this.createAnimation("animation.cat.touch_boobs_slow" + (if (this.touchBoobsVariant) "1" else ""), true, event)
                Action.TOUCH_BOOBS_FAST -> this.createAnimation("animation.cat.touch_boobs_fast", true, event)
                Action.TOUCH_BOOBS_CUM -> this.createAnimation("animation.cat.touch_boobs_cum", false, event)
                Action.WAIT_CAT -> this.createAnimation("animation.cat.wait", false, event)
                Action.COWGIRL_SITTING_INTRO -> this.createAnimation("animation.cat.sitting_intro", false, event)
                Action.COWGIRL_SITTING_SLOW -> this.createAnimation("animation.cat.sitting_slow", true, event)
                Action.COWGIRL_SITTING_FAST -> this.createAnimation("animation.cat.sitting_fast", true, event)
                Action.COWGIRL_SITTING_CUM -> this.createAnimation("animation.cat.sitting_cum", false, event)
                Action.HEAD_PAT -> this.createAnimation("animation.cat.head_pat", true, event)
                else -> {}
            }
        }

        return PlayState.CONTINUE
    }

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
                "idleDone" -> this.idleVariant = this.random.nextInt(10) == 0
                "idle2Done" -> this.idleVariant = false
                "pearl" -> PacketHandler.networkWrapper.sendToServer(SendCompanionHomePacket(this.getGirlId()))
                "start_fishingDone" -> if (this.isLocalPlayerNearby()) {
                    this.setCurrentAction(Action.FISHING_IDLE)
                }
                "rod_shoot" -> if (this.isLocalPlayerNearby()) {
                    PacketHandler.networkWrapper.sendToServer(CatActivateFishingPacket(this.getGirlId()))
                }
                "eat" -> {
                    this.playSoundAtPosition(
                        SoundHandler.randomSound(SoundHandler.MISC_EAT),
                        0.5f + 0.5f * this.random.nextInt(2),
                        (this.random.nextFloat() - this.random.nextFloat()) * 0.2f + 1.0f,
                    )
                    this.renderScale -= 0.33333334f
                }
                "eatPay" -> {
                    this.playSoundAtPosition(
                        SoundHandler.randomSound(SoundHandler.MISC_EAT),
                        0.5f + 0.5f * this.random.nextInt(2),
                        (this.random.nextFloat() - this.random.nextFloat()) * 0.2f + 1.0f,
                    )
                    this.scaleFactor -= 0.33333334f
                }
                "burp" -> this.playSoundAtPosition(SoundEvents.PLAYER_BURP, 0.5f, this.random.nextFloat() * 0.1f + 0.9f)
                "eatingDone" -> {
                    if (this.isLocalPlayerNearby()) {
                        PacketHandler.networkWrapper.sendToServer(CatEatingDonePacket(this.getGirlId()))
                        this.setCurrentAction(Action.NULL)
                    }

                    this.renderScale = 1.0f
                    this.zFlag = 0.0f
                }
                "throw_away" -> {
                    if (this.isLocalPlayerNearby()) {
                        PacketHandler.networkWrapper.sendToServer(CatThrowAwayItemPacket(this.getGirlId()))
                    }

                    this.renderScale = 1.0f
                    this.zFlag = 0.0f
                }
                "renderItem" -> this.zFlag = 1.0f
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
                    SceneDebug.log(
                        SceneDebug.SCENE_ENTRY, "Luna.sound paymentDone (remote=%s, action=%s, nearby=%s)",
                        this.level().isClientSide, this.getCurrentAction(), this.isLocalPlayerNearby(),
                    )
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
                    SceneDebug.log(
                        SceneDebug.SCENE_ENTRY, "Luna.sound touch_boobs_introDone (remote=%s, action=%s, controlled=%s)",
                        this.level().isClientSide, this.getCurrentAction(), this.isControlledByLocalPlayer(),
                    )
                    this.setCurrentAction(Action.TOUCH_BOOBS_SLOW)
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                        HornyMeterHud.showHornyMeter()
                        HandlePlayerMovement.setMovementLock(false)
                    }
                }
                "touch_boobs_slowDone" -> {
                    if (this.touchBoobsVariant) {
                        this.touchBoobsVariant = false
                    } else {
                        this.touchBoobsVariant = Math.random() < 0.5
                    }
                }
                "addCumSlow" -> this.addToHornyMeterIfControlled(0.02f.toDouble())
                "addCumFast" -> this.addToHornyMeterIfControlled(0.04f.toDouble())
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
                "touch_boobs_cumDone" -> {
                    SceneDebug.log(
                        SceneDebug.SCENE_ENTRY, "Luna.sound touch_boobs_cumDone (remote=%s, action=%s, controlled=%s)",
                        this.level().isClientSide, this.getCurrentAction(), this.isControlledByLocalPlayer(),
                    )
                    if (this.isControlledByLocalPlayer()) {
                        HornyMeterHud.resetHornyMeter()
                        this.resetCameraAndPhysics()
                    }
                }
                "resetGirl" -> if (this.isLocalPlayerNearby()) {
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

                    if (addMeter) {
                        this.addToHornyMeterIfControlled(0.02)
                    }
                }
                "sitting_fastMSG1" -> {
                    if (this.random.nextBoolean()) {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_LUNA_HORNINYA))
                    } else {
                        this.playSound(SoundHandler.randomSound(SoundHandler.GIRLS_LUNA_MOAN))
                    }

                    this.addToHornyMeterIfControlled(0.04)
                }
                "sitting_fastDone" -> if (this.isControlledByLocalPlayer() && !HandlePlayerMovement.isJumping) {
                    this.setCurrentAction(Action.COWGIRL_SITTING_SLOW)
                    val headOffset = Vec3(0.0, (-0.075f).toDouble(), -0.7109375)
                    val rotatedHead = VectorMath.rotateByYaw(headOffset, this.getYawRotation() + 180.0f)
                    ClientHooks.localPlayer()!!.setPos( // java-npe: Minecraft.player
                        this.getTargetPosition().x + rotatedHead.x,
                        this.getTargetPosition().y + rotatedHead.y,
                        this.getTargetPosition().z + rotatedHead.z,
                    )
                }
                "sitting_fastTp" -> if (this.isControlledByLocalPlayer()) {
                    val backOffset = Vec3(0.0, -0.160625, -0.9925)
                    val rotatedBack = VectorMath.rotateByYaw(backOffset, this.getYawRotation() + 180.0f)
                    ClientHooks.localPlayer()!!.setPos( // java-npe: Minecraft.player
                        this.getTargetPosition().x + rotatedBack.x,
                        this.getTargetPosition().y + rotatedBack.y,
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
        if (this.actionController == null) {
            this.initAnimationControllers()
        }

        this.movementController!!.setTransitionLengthTicks(10.0) // java-npe: created above
        this.registerControllers(controllers, soundListener)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        this.setNoGravity(false)
    }

    /** Makes every creeper avoid Luna (cats scare creepers). */
    class CreeperAvoidanceHandler {
        /** 1.12 `EntityJoinWorldEvent`. */
        @SubscribeEvent
        fun onEntityJoinWorld(event: EntityJoinLevelEvent) {
            val entity = event.entity
            if (entity is Creeper) {
                entity.goalSelector.addGoal(3, AvoidEntityGoal(entity, LunaEntity::class.java, 6.0f, 1.0, 1.2))
            }
        }
    }

    companion object {
        @JvmField
        val yFlag: EntityDataAccessor<Float> = SynchedEntityData.defineId(LunaEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val SYNCED_HELD_ITEM: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(LunaEntity::class.java, EntityDataSerializers.ITEM_STACK)

        @JvmField
        val FISH_ON_LINE: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(LunaEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val CAUGHT_ITEM: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(LunaEntity::class.java, EntityDataSerializers.ITEM_STACK)

        const val ah: Float = 3.0f
        const val ax: Float = 1200.0f

        /** CLIENT: opens the interaction screen with per-option fish rewards. */
        @JvmStatic
        fun onPlayerApproach(player: Player, girl: BaseGirlEntity, options: Array<String>, rewards: Array<ItemStack>) {
            openInventoryGui(player, girl, options, rewards, true)
        }

        /** 1.12 `applyEntityAttributes`: movement speed 1.0. */
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder =
            AbstractGirlNpcEntity.createAttributes().add(Attributes.MOVEMENT_SPEED, 1.0)
    }
}
