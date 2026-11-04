package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.api.SkinColor
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.model.AllieModel
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import com.trolmastercard.sexmod.client.renderer.GirlRendererBase
import com.trolmastercard.sexmod.entity.api.IGoblin
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.ResetGirlPacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.EyeColor
import com.trolmastercard.sexmod.util.HairColor
import com.trolmastercard.sexmod.util.AbstractPlayerKoblinGoboldRenderer
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.VectorMath
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RenderFrameEvent
import net.neoforged.neoforge.client.event.RenderHandEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.event.RenderPlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState

/**
 * <b>Role.</b> Player-form goblin (horny-potion transformation) that can itself
 * be picked up and thrown by another player ([IGoblin] owner binding): while
 * carried the transformed player is glued 2 blocks above the carrier and
 * rendered as a fake player; nelson / paizuri scenes via owner commands.
 *
 * <b>State.</b> `OWNER_UUID_STRING` (122) = carrying player, `PREGNANT`
 * (126); both are the hoisted `SHARED_STRING_122` / `SHARED_BOOL_126`.
 *
 * 1.21 notes: eye height 0.75 is [getDefaultDimensions]; [getOwnerPlayer]
 * overrides the base (the carrier, as in 1.12). The client halves of
 * [GoblinCarryEvents] live in [GoblinCarryEvents.ClientEvents]; see there for
 * the fake-player render deviation.
 */
class GoblinPlayerEntity : AbstractKoboldPlayerEntity, IGoblin {
    @JvmField var standUpTicks: Int = 0
    @JvmField var throwProgress: Int = -1
    @JvmField var throwTickCount: Int = 0
    @JvmField var previousAction: Action? = Action.NULL
    @JvmField var heldPlayerDistance: Int = -1
    @JvmField var flySecondFrame: Boolean = false
    @JvmField var breedingVariantLeft: Boolean = true
    @JvmField var nelsonSlowVariantTwo: Boolean = true
    @JvmField var nelsonFastVariantC: Boolean = false
    @JvmField var breedingFastVariantC: Boolean = false
    @JvmField var paizuriSlowVariantSuffix: String = ""

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out GoblinPlayerEntity>, level: Level) : super(type, level) {
        this.initGoblinDefaults()
    }

    constructor(level: Level, uuid: UUID) : super(ModEntities.PLAYER_GOBLIN.get(), level, uuid) {
        this.initGoblinDefaults()
    }

    /** 1.12 entityInit defaults of the hoisted keys. */
    private fun initGoblinDefaults() {
        val color = EyeColor.values()[this.random.nextInt(EyeColor.values().size)]
        this.entityData.set(EYE_COLOR, BlockPos(color.getColor()))
        this.entityData.set(SKIN_COLOR_NAME, GoblinEntity.DEFAULT_SKIN.name)
        this.entityData.set(PREGNANT, false)
        this.entityData.set(OWNER_UUID_STRING, "")
    }

    override fun getScaleFactor(): Float = 0.9f

    override fun getHandModel(index: Int): IVanillaModel? = Client.handModel()

    override fun getHandTexture(index: Int): String = "textures/entity/kobold/hand.png"

    override fun getHandColor(index: Int): Vec3i {
        val parts = getModelCodeParts(this)
        return if (parts.size < 8) super.getHandColor(index) else SkinColor.values()[parts[7].toInt()].getColor()
    }

    /**
     * SERVER: owner commands — `anal` starts [Action.NELSON_INTRO],
     * `paizuri` starts [Action.PAIZURI_START]. Both broadcast,
     * strip and teleport the acting player into the scene.
     */
    override fun handleOwnerCommand(command: String, uuid: UUID) {
        if ("anal" == command) {
            this.teleportPlayerToGirl(uuid)
            this.setCurrentAction(Action.NELSON_INTRO)
            this.sendActionPacket(this.getOutfitIndex(), Action.NELSON_INTRO)
            this.setOutfitIndex(0)
        }

        if ("paizuri" == command) {
            this.teleportPlayerToGirl(uuid)
            this.setCurrentAction(Action.PAIZURI_START)
            this.sendActionPacket(this.getOutfitIndex(), Action.PAIZURI_START)
            this.setOutfitIndex(0)
        }
    }

    /** CLIENT. */
    override fun openInteractionMenu(player: Player): Boolean {
        openInventoryGui(player, this, arrayOf("anal", "paizuri"), null, false)
        return true
    }

    override fun resolvePlayerEntity(player: Player?): Player? {
        val uuid = this.getOwnerUUID() ?: return player
        val ownerPlayer = this.level().getPlayerByUUID(uuid)
        return ownerPlayer ?: player
    }

    /** CLIENT. */
    override fun shouldRenderModel(): Boolean =
        this.getOwnerUUID() == null || ClientHooks.localPlayerUUID()!! != this.getOwnerUserUUID() // java-npe

    override fun isRidingSomething(): Boolean {
        val uuid = this.getOwnerUUID()
        return uuid == null
    }

    override fun getOwnerLookVector(vec: Vec3, partialTicks: Float): Vec3 {
        val uuid = this.getOwnerUUID() ?: return vec
        val player = this.level().getPlayerByUUID(uuid) ?: return vec

        val curPos = player.position()
        val prevPos = Vec3(player.xOld, player.yOld, player.zOld)
        return RotationHelper.lerpVec3dDouble(prevPos, curPos, partialTicks.toDouble())
    }

    override fun buildModelCodeDNA(builder: StringBuilder): String {
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 3)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 2)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 2)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 7)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 7)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 5)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, HairColor.values().size - 1)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, SkinColor.values().size - 1)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, EyeColor.values().size - 1)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 0)
        return builder.toString()
    }

    override fun getCustomPartIdList(): ArrayList<Int> = arrayListOf(
        4, 3, 3, 16, 16, 6, HairColor.values().size, SkinColor.values().size, EyeColor.values().size,
    )

    override fun getCustomPartExtraIdList(): List<Int> = listOf(2)

    /** CLIENT (called from the client-side data watch). */
    override fun clearBoneColors() {
        Client.clearBoneColors()
    }

    /** 1.12 `getEyeHeight()` = 0.75. */
    override fun getDefaultDimensions(pose: Pose): EntityDimensions = super.getDefaultDimensions(pose).withEyeHeight(0.75f)

    override fun isAnchored(): Boolean = super.isAnchored() || this.getOwnerUUID() != null

    override fun canPerformAction(action: Action, player: Player): Boolean {
        val uuid = this.getOwnerUUID() ?: return false
        val ownerPlayer = this.level().getPlayerByUUID(uuid) ?: return false

        val yaw = ownerPlayer.yRot
        val offset = if (action == Action.PICK_UP) 180.0f else 0.0f
        val minYaw = ownerPlayer.yRot - 90.0f + offset
        val maxYaw = ownerPlayer.yRot + 90.0f + offset
        if (yaw < minYaw) {
            player.yRot = minYaw
        }

        if (yaw > maxYaw) {
            player.yRot = maxYaw
        }

        val pitch = player.xRot
        val maxPitch = if (action == Action.PICK_UP) 0.0f else 37.5f
        if (pitch > maxPitch) {
            player.xRot = maxPitch
        }

        return true
    }

    override fun getOwnerAimVector(vec: Vec3, partialTicks: Float): Vec3 {
        val uuid = this.getOwnerUUID() ?: return vec
        val player = this.level().getPlayerByUUID(uuid) ?: return vec

        val yaw = RotationHelper.lerp(player.yBodyRotO, player.yBodyRot, partialTicks)
        var aimVec = vec
        var angle = 135.0f
        val action = this.getCurrentAction()
        if (action == Action.PICK_UP) {
            aimVec = Vec3(vec.x, vec.y, -vec.z)
            angle = 175.0f
        } else if (action != Action.START_THROWING) {
            aimVec = aimVec.subtract(0.0, 2.0, 0.0)
        }

        return VectorMath.rotateByYaw(aimVec, yaw + angle)
    }

    /**
     * BOTH sides: dispatches the throw glue (`handleThrowAction`),
     * the throw flight (`updatePlayerThrowProgress`) and the CLIENT
     * local action handling (owner revival, action diffing for the nelson
     * switch).
     */
    override fun tick() {
        GoblinEntity.handleGoblinThrowAction(this)
        GoblinPlayerThrow.updatePlayerThrowProgress(this)
        GoblinPlayerThrow.handleThrowAction(this)
        super.tick()
        if (this.level().isClientSide) {
            GoblinPlayerThrow.handleOwnerThrow(this)
            val action = this.getCurrentAction()
            GoblinPlayerScene.handleLocalAction(this, action)
            GoblinPlayerScene.handleNelsonAction(this, action)
            this.previousAction = action
        }
    }

    override fun shouldRenderWhenOwnerInvisible(): Boolean = this.getOwnerUUID() != null

    override fun updateAITasks() {
        super.updateAITasks()
        GoblinEntity.handlePickUpState(this)
        GoblinPlayerThrow.handlePlayerThrown(this)
        GoblinPlayerThrow.handlePlayerStandUp(this)
    }

    override fun getOwnerUUID(): UUID? {
        val ownerStr = this.entityData.get(OWNER_UUID_STRING)
        if ("" == ownerStr) {
            return null
        }

        return try {
            UUID.fromString(this.entityData.get(OWNER_UUID_STRING))
        } catch (ex: Exception) {
            Main.LOGGER.error("could not parse the owner UUID", ex)
            null
        }
    }

    override fun setOwnerUUID(ownerUuid: UUID?) {
        if (ownerUuid == null) {
            this.entityData.set(OWNER_UUID_STRING, "")
        } else {
            this.entityData.set(OWNER_UUID_STRING, ownerUuid.toString())
        }
    }

    /** 1.12 override: the carrying player (not the transformed owner). */
    override fun getOwnerPlayer(): Player? {
        val uuid = this.getOwnerUUID()
        return if (uuid == null) null else this.level().getPlayerByUUID(uuid)
    }

    override fun setThrowProgress(throwProgress: Int) {
        this.throwProgress = throwProgress
    }

    override fun getThrowProgress(): Int = this.throwProgress

    override fun setThrowTickCount(throwTickCount: Int) {
        this.throwTickCount = throwTickCount
    }

    override fun getThrowTickCount(): Int = this.throwTickCount

    override fun setPreviousAction(previousAction: Action?) {
        this.previousAction = previousAction
    }

    override fun getPreviousAction(): Action? = this.previousAction

    override fun setHeldPlayerDistance(heldPlayerDistance: Int) {
        this.heldPlayerDistance = heldPlayerDistance
    }

    override fun getHeldPlayerDistance(): Int = this.heldPlayerDistance

    override fun reinitTasks() {
        super.reinitTasks()
        this.entityData.set(PREGNANT, false)
        if (this.getOwnerUUID() != null) {
            this.setOwnerUUID(null)
            val player = this.getOwnerPlayer()
            if (player != null) {
                PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(true), player as ServerPlayer)
            }
        }
    }

    override fun setCustomPartList(parts: List<Int>) {
        val builder = StringBuilder()

        for (partId in parts) {
            AbstractNpcOnlyEntity.appendPaddedNumber(builder, partId)
        }

        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 1)
        this.entityData.set(MODEL_CODE, builder.toString())
    }

    override fun getNextAction(action: Action): Action? {
        return when (action) {
            Action.NELSON_SLOW -> Action.NELSON_FAST
            Action.PAIZURI_IDLE, Action.PAIZURI_SLOW -> Action.PAIZURI_FAST
            Action.BREEDING_SLOW_0 -> Action.BREEDING_FAST_0
            Action.BREEDING_SLOW_2 -> Action.BREEDING_FAST_2
            else -> null
        }
    }

    /**
     * Guards the state machine: refuses re-entry into loop phases while the
     * cum animation plays, positions the player for paizuri/nelson on the
     * server and toggles the pregnancy flag (`PREGNANT`) on NELSON_CUM.
     */
    override fun setCurrentAction(action: Action?) {
        val currentAction = this.getCurrentAction()
        if (currentAction != Action.PAIZURI_CUM || action != Action.PAIZURI_SLOW && action != Action.PAIZURI_FAST) {
            if (currentAction != Action.NELSON_CUM || action != Action.NELSON_SLOW && action != Action.NELSON_FAST) {
                if (currentAction != Action.BREEDING_CUM_0 || action != Action.BREEDING_SLOW_0 && action != Action.BREEDING_FAST_0) {
                    if (action == Action.PAIZURI_START && !this.level().isClientSide) {
                        GoblinPlayerScene.handlePlayerInteract(this)
                    }

                    if (action == Action.NELSON_INTRO && !this.level().isClientSide) {
                        GoblinPlayerScene.handlePlayerLook(this)
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

    override fun isPlayerGirl(): Boolean = this.getOwnerUUID() == null

    /**
     * SERVER: the carrier's interaction — resets the girl, un-anchors and
     * unbinds the carrier.
     */
    override fun onOwnerInteract(player: Player) {
        if (player.uuid == this.getOwnerUUID()) {
            ResetGirlPacket.Handler.resetGirl(this)
            this.setAnchored(false)
            this.setCurrentAction(Action.NULL)
            this.setOwnerUUID(null)
        }
    }

    override fun getCumAction(action: Action): Action? {
        return when (action) {
            Action.NELSON_FAST, Action.NELSON_SLOW -> Action.NELSON_CUM
            Action.PAIZURI_SLOW, Action.PAIZURI_FAST, Action.PAIZURI_FAST_CONTINUES -> Action.PAIZURI_CUM
            Action.BREEDING_SLOW_2, Action.BREEDING_FAST_2 -> Action.BREEDING_CUM_2
            Action.BREEDING_1 -> Action.BREEDING_CUM_1
            else -> null
        }
    }

    override fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState = GoblinPlayerAnimations.predicate(this, event)

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        GoblinPlayerAnimations.registerControllers(this, controllers)
    }

    private object Client {
        fun handModel(): IVanillaModel = AllieModel()

        fun clearBoneColors() {
            AbstractPlayerKoblinGoboldRenderer.clearRenderCache() // 1.12 inherited static
            GirlRendererBase.clearBoneColors() // 1.12 inherited static (KoboldRenderer/GoblinRenderer)
        }
    }

    /**
     * Event handler for the goblin transformation: glues the carried player to
     * the carrier's head every tick (`onPlayerTick`) and starts the pickup/throw
     * when a non-transformed player sneaks+clicks a carried goblin player
     * (`onEntityInteract`). The client half ([ClientEvents]) hides the
     * first-person hand while carried, re-applies the head glue every frame and
     * re-renders the carried players.
     */
    class GoblinCarryEvents {
        /** 1.12 `PlayerTickEvent` fired for both phases. */
        @SubscribeEvent
        fun onPlayerTickPre(event: PlayerTickEvent.Pre) {
            handlePlayerOwner(event.entity)
        }

        @SubscribeEvent
        fun onPlayerTickPost(event: PlayerTickEvent.Post) {
            handlePlayerOwner(event.entity)
        }

        @SubscribeEvent
        fun onEntityInteract(event: PlayerInteractEvent.EntityInteract) {
            val player = event.entity
            if (player.isShiftKeyDown) {
                val target = event.target
                if (target is Player) {
                    val targetGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(target.uuid)
                    if (targetGirl is GoblinPlayerEntity) {
                        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player.uuid)
                        if (playerGirl == null) {
                            GoblinPlayerThrow.handlePlayerThrow(targetGirl, event.entity)
                        }
                    }
                }
            }
        }

        /**
         * Client half (Rule E). Deviation: 1.12 marked the carried players `isDead`
         * between frames so the vanilla pass skipped them and re-rendered them in
         * `RenderWorldLastEvent`. Setting a 1.21 entity removed drops it from the
         * level, so the vanilla pass is cancelled through `RenderPlayerEvent.Pre`
         * instead and the players are drawn at `AFTER_WEATHER`.
         */
        object ClientEvents {
            @JvmField
            var playersToRender: HashSet<Player> = HashSet()

            @JvmField
            var renderingFake: Boolean = false

            @SubscribeEvent
            fun onRenderHand(event: RenderHandEvent) {
                val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(Minecraft.getInstance().player?.uuid)
                if (playerGirl != null) {
                    if (playerGirl is IGoblin) {
                        if ((playerGirl as IGoblin).getOwnerUUID() != null) {
                            event.setCanceled(true)
                        }
                    }
                }
            }

            /** 1.12 `RenderTickEvent` with `phase != END`. */
            @SubscribeEvent
            fun onRenderTickSync(event: RenderFrameEvent.Pre) {
                val player = Minecraft.getInstance().player
                if (player != null) {
                    handlePlayerOwner(player)
                }
            }

            /** 1.12 `RenderWorldLastEvent`. */
            @SubscribeEvent
            fun onRenderWorldLast(event: RenderLevelStageEvent) {
                if (event.stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
                    return
                }

                val mc = Minecraft.getInstance()
                val renderManager = mc.entityRenderDispatcher
                if (mc.player != null) {
                    val cameraPos = event.camera.position
                    val partialTicks = event.partialTick.getGameTimeDeltaPartialTick(false)
                    val bufferSource = mc.renderBuffers().bufferSource()

                    for (player in playersToRender) {
                        val delta = player.position().subtract(cameraPos)
                        renderingFake = true
                        try {
                            renderManager.render(
                                player, delta.x, delta.y, delta.z, 69.0f, partialTicks, event.poseStack, bufferSource,
                                renderManager.getPackedLightCoords(player, partialTicks),
                            )
                        } finally {
                            renderingFake = false
                        }
                    }

                    bufferSource.endBatch()
                }
            }

            /** Hides the carried players from the vanilla pass (replaces the 1.12 `isDead` toggle). */
            @SubscribeEvent
            fun onRenderPlayer(event: RenderPlayerEvent.Pre) {
                if (!renderingFake && playersToRender.contains(event.entity)) {
                    event.setCanceled(true)
                }
            }

            /** 1.12 `RenderTickEvent` START. */
            @SubscribeEvent
            fun onRenderTickStart(event: RenderFrameEvent.Pre) {
                clearFakePlayers()
            }

            /** 1.12 `RenderTickEvent` END. */
            @SubscribeEvent
            fun onRenderTickEnd(event: RenderFrameEvent.Post) {
                killFakePlayers()
            }

            fun killFakePlayers() {
                // 1.12 set isDead = true on every fake player; see the class doc for the 1.21 replacement
            }

            fun clearFakePlayers() {
                playersToRender.clear()
                val mc = Minecraft.getInstance()
                val localPlayer = mc.player
                val level = mc.level ?: return
                for (player in level.players()) {
                    if (player !== localPlayer) {
                        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player)
                        if (playerGirl is GoblinPlayerEntity) {
                            if (playerGirl.getOwnerUUID() != null) {
                                val action = playerGirl.getCurrentAction()
                                if (action == Action.THROWN || action == Action.START_THROWING) {
                                    return
                                }

                                playersToRender.add(player)
                            }
                        }
                    }
                }
            }
        }

        companion object {
            @JvmStatic
            fun handlePlayerOwner(player: Player) {
                val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player)
                if (playerGirl is GoblinPlayerEntity) {
                    val action = playerGirl.getCurrentAction()
                    if (action != Action.THROWN) {
                        if (action != Action.START_THROWING || (playerGirl as IGoblin).getThrowProgress() <= 15) {
                            val uuid = playerGirl.getOwnerUUID()
                            if (uuid != null) {
                                val owner = player.level().getPlayerByUUID(uuid)
                                if (owner != null) {
                                    player.noPhysics = true
                                    player.isNoGravity = true
                                    playerGirl.noPhysics = true
                                    playerGirl.isNoGravity = true
                                    player.setPos(owner.x, owner.y + 2.0, owner.z)
                                    player.xOld = owner.xOld
                                    player.yOld = owner.yOld + 2.0
                                    player.zOld = owner.zOld
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val aI: Float = 2.0f

        /** Alias of [BaseGirlEntity.SHARED_STRING_122] (1.12 id 122). */
        @JvmField val OWNER_UUID_STRING: EntityDataAccessor<String> = BaseGirlEntity.SHARED_STRING_122

        /** Alias of [BaseGirlEntity.SHARED_BOOL_126] (1.12 id 126). */
        @JvmField val PREGNANT: EntityDataAccessor<Boolean> = BaseGirlEntity.SHARED_BOOL_126

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractKoboldPlayerEntity.createAttributes()
    }
}
