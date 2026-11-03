package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.GenderSwapScreen
import com.trolmastercard.sexmod.client.model.api.IVanillaModel
import com.trolmastercard.sexmod.networking.ForcePlayerGirlUpdatePacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.ResetGirlPacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.networking.SexPromptPacket
import com.trolmastercard.sexmod.networking.TargetPoint
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.ConcurrentModificationException
import java.util.Hashtable
import java.util.Optional
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ElytraItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector2f

/**
 * <b>Role.</b> Base class for the player-form girls — the entity the player
 * turns INTO when drinking the horny potion. Subclasses (Jenny, Bia, Luna,
 * Ellie, Slime, Bee, Allie, Kobold, Goblin, Galath) mirror their NPC twin's
 * scenes but bind a real player ([OWNER_UUID], 1.12 data-manager id 118) whose
 * model, camera and abilities are replaced by the girl's while the
 * transformation is active. Registered statically in
 * [playerGirlList]/[PLAYER_GIRL_BY_OWNER].
 *
 * <b>Scene flow.</b> Same machinery as [BaseGirlEntity]: actions are
 * dispatched from [doAction] (which routes unknown
 * actions to the server via [SexPromptPacket]), the owner command GUI
 * calls [handleOwnerCommand], and scene entry funnels
 * through [teleportPlayerToGirl] (snaps both players in, anchors
 * the girl, sets `IS_ANCHORED`). Scene exit goes through
 * [resetCameraAndPhysics] -> [resetLocalPlayerClientState]
 * which unlocks movement, un-hides the player and sends
 * [ResetGirlPacket].
 *
 * <b>State.</b> `OWNER_UUID` = owner player UUID; `firstInteraction` = "new player"
 * flag consumed by [SexPromptPacket]; `yFlag` = strip-phase tick
 * threshold (65); `stripTicks` = STRIP countdown (-1 = inactive, toggles the
 * outfit at 65 and ends the strip on the client at 100).
 *
 * <b>Pitfalls.</b> [setCurrentAction] refuses
 * `NULL` while anchored (the "prevented a potential animation break"
 * guard) — do not remove; [resetCameraAndPhysics] only resets
 * physics locally and delegates the player reset to the server. The camera
 * origin is stored in `cameraOriginPos` and must be cleared on every
 * reset path or the next scene repositions the player from a stale anchor.
 * Player girls are no-physics + no-gravity and never collide
 * ([isPickable] = false).
 *
 * Port notes: the 0.01×0.01 size lives in `ModEntities`; 1.12
 * `getEntityBoundingBox()` is [makeBoundingBox]; `canBeCollidedWith()` (ray-trace
 * hit test) is [isPickable]; `setDead()` is [remove]; `playerMotionOffset` is a
 * joml [Vector2f] (javax.vecmath is gone).
 */
abstract class AbstractPlayerGirlEntity : AbstractGirlNpcEntity {
    @JvmField
    var playerMotionOffset: Vector2f = Vector2f(0.0f, 0.0f)

    @JvmField
    var playerSneaking: Boolean = false

    @JvmField
    var playerSprinting: Boolean = false

    @JvmField
    var playerRiding: Boolean = false

    @JvmField
    var playerOnGround: Boolean = true

    @JvmField
    var playerUsingItem: Boolean = false

    @JvmField
    var lastOwnerUuid: UUID? = null

    @JvmField
    var stripTicks: Int = -1

    @JvmField
    var firstInteraction: Boolean = true

    protected constructor(type: EntityType<out AbstractPlayerGirlEntity>, level: Level) : super(type, level) {
        playerGirlList.add(this)
        playerGirlTableDirty = true
    }

    protected constructor(type: EntityType<out AbstractPlayerGirlEntity>, level: Level, uuid: UUID) : this(type, level) {
        this.entityData.set(OWNER_UUID, Optional.of(uuid))
    }

    override fun getTargetNetworkPoint(): TargetPoint {
        return TargetPoint(WorldUtils.getDimensionId(this.level()), this.x, this.y - 0.0, this.z, 50.0)
    }

    /**
     * Broadcasts an action change to every entity tracking this girl
     * (50-block radius) via [ForcePlayerGirlUpdatePacket], so clients
     * other than the owner see the scene action too. SERVER-side.
     */
    open fun sendActionPacket(actionId: Int, action: Action) {
        PacketHandler.networkWrapper.sendToAllTracking(
            ForcePlayerGirlUpdatePacket(this.getOwnerUserUUID()!!, actionId, action), // java-npe: encoding a null owner threw in 1.12
            this.getTargetNetworkPoint(),
        )
    }

    open fun resolvePlayerEntity(player: Player?): Player? = player

    open fun isRidingSomething(): Boolean = true

    open fun getOwnerLookVector(vec: Vec3, partialTicks: Float): Vec3 = vec

    /** 1.12 `canBeCollidedWith()` = false (ray-trace hit test). */
    override fun isPickable(): Boolean = false

    open fun canBeInteracted(): Boolean = true

    open fun canMountPlayer(): Boolean = false

    /** CLIENT. */
    open fun openBedsideInteractionMenu() {
    }

    open fun canOpenInteractionMenu(): Boolean = true

    open fun handleActionRequest(actionName: String): Boolean = false

    open fun blocksOutfitToggle(): Boolean = true

    override fun getDisplayNameText(): String {
        val owner = this.entityData.get(OWNER_UUID)
        if (owner.isPresent) {
            val player = this.level().getPlayerByUUID(owner.get())
            if (player != null) {
                return player.gameProfile.name
            }
        }

        return "anonymous horny girl"
    }

    open fun handleInteraction() {
    }

    abstract fun handleOwnerCommand(command: String, uuid: UUID)

    abstract fun getHandModel(index: Int): IVanillaModel?

    abstract fun getHandTexture(index: Int): String

    open fun getHandColor(index: Int): Vec3i = Vec3i(255, 255, 255)

    override fun isPushable(): Boolean = false

    open fun isNotColliding(): Boolean = true

    open fun usesInvertedCamera(): Boolean = false

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(OWNER_UUID, Optional.empty())
    }

    /**
     * Ends the transformation locally: clears the camera origin, re-enables
     * gravity, and (CLIENT) hands off to [resetLocalPlayerClientState].
     * The player-side state (visibility, movement lock, abilities) is only
     * restored when the server processes the resulting [ResetGirlPacket].
     */
    override fun resetCameraAndPhysics() {
        this.cameraOriginPos = null
        this.setNoGravity(false)
        if (this.level().isClientSide) {
            this.resetLocalPlayerClientState()
        }
    }

    /**
     * CLIENT: unlocks player movement, un-hides the player and tells the
     * server to reset the girl. Note the single-arg [ResetGirlPacket]
     * here — that is the FULL reset form (`resetPose=false`); the
     * two-arg form is only used for player-only resets.
     */
    override fun resetLocalPlayerClientState() {
        if (this.isControlledByLocalPlayer() || this.hasOwnerUUID()) {
            HandlePlayerMovement.setMovementLock(true)
            val player = ClientHooks.localPlayer()!! // java-npe: Minecraft.player on the client
            player.setInvisible(false)
            player.setNoGravity(false)
            player.noPhysics = false
            this.entityData.set(IS_ANCHORED, false)
            PacketHandler.networkWrapper.sendToServer(ResetGirlPacket(this.getGirlId()))
        }
    }

    /** CLIENT. */
    override fun hasCustomParts(): Boolean {
        return !this.hasOwnerUUID() || ClientHooks.thirdPersonView() != 0
    }

    /**
     * Grants/revokes flight on the owning player (SERIOUS: uses the player's
     * abilities and sends them to the client). `flightGrantEnabled` is a global kill
     * switch. Called by subclasses' `onTransformationSpawned`/`onTickClient`
     * pairs on transformation enter/exit.
     */
    open fun handleOwnerUUID(allowFly: Boolean) {
        if (flightGrantEnabled) {
            val ownerUuid = this.getOwnerUserUUID()
            if (ownerUuid != null) {
                val player = this.level().getPlayerByUUID(ownerUuid)
                if (player != null) {
                    player.abilities.mayfly = allowFly
                    if (!allowFly) {
                        player.abilities.flying = false
                    }

                    player.onUpdateAbilities()
                }
            }
        }
    }

    /** 1.12 `getEntityBoundingBox()` override: the box sits half a block up. */
    override fun makeBoundingBox(): AABB {
        return super.makeBoundingBox().move(0.0, 0.5, 0.0)
    }

    open fun getNearestPlayer(): Player? {
        val players = this.level().players()
        var nearest: Player? = null

        for (player in players) {
            if (player.uuid != this.entityData.get(OWNER_UUID).get()) {
                if (nearest == null) {
                    nearest = player
                } else {
                    val closestDist = nearest.distanceToSqr(this.getPositionVec3d().x, this.getPositionVec3d().y, this.getPositionVec3d().z)
                    val dist = player.distanceToSqr(this.getPositionVec3d().x, this.getPositionVec3d().y, this.getPositionVec3d().z)
                    if (dist < closestDist) {
                        nearest = player
                    }
                }
            }
        }

        return nearest
    }

    /** CLIENT. */
    override fun isLocalPlayerNearby(): Boolean {
        val player = this.getNearestPlayer() ?: return false
        return player.uuid == ClientHooks.localPlayerUUID()
    }

    open fun getPositionVec3d(): Vec3 = Vec3(this.x, this.y - 0.0, this.z)

    /**
     * SERVER: snaps both the acting player and the girl's owner into the scene
     * around the girl's position, locks their movement (SetPlayerMovementPacket),
     * anchors the girl and records the target position/yaw. This is the standard
     * scene-entry funnel for every owner-command action.
     */
    open fun teleportPlayerToGirl(uuid: UUID) {
        val player = this.level().getPlayerByUUID(uuid) as ServerPlayer // java-npe
        val ownerPlayer = this.level().getPlayerByUUID(this.entityData.get(OWNER_UUID).get()) as ServerPlayer // java-npe
        PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), player)
        PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), ownerPlayer)
        this.setInteractionPlayerUUID(uuid)
        this.yRot = 0.0f
        this.yHeadRot = 0.0f
        player.yRot = 180.0f
        player.yHeadRot = 180.0f
        player.setNoGravity(true)
        player.noPhysics = true
        val pos = this.position()
        player.teleportTo(pos.x, pos.y, pos.z + 1.0)
        player.abilities.flying = true
        ownerPlayer.abilities.flying = true
        this.snapPlayerToPosition(uuid)
        this.entityData.set(IS_ANCHORED, true)
        this.setTargetPosition(pos)
        this.setYawRotation(0.0f)
    }

    override fun playStepSound(pos: BlockPos, state: BlockState) {
        super.playStepSound(pos, state)
    }

    open fun getPlayerCollisionBox(player: Player): AABB = player.boundingBox

    /**
     * Runs every tick on both sides: forces no-physics + no gravity, keeps the
     * girl glued to the owner player's position when not anchored, mirrors the
     * player's swing into the [Action.ATTACK] scene action, and ticks the
     * gender-swap screen client-side.
     */
    override fun tick() {
        this.noPhysics = true
        this.setNoGravity(true)
        super.tick()
        this.tickStripSequence()
        if (this.level().isClientSide) {
            if (this.hasOwnerUUID()) {
                Client.tickGenderSwapScreen()
            }
        }

        val ownerUuid = this.getOwnerUserUUID()
        if (ownerUuid != null && ownerUuid != this.lastOwnerUuid) {
            this.lastOwnerUuid = ownerUuid
            playerGirlTableDirty = true
        }
    }

    /**
     * CLIENT: copied the girl's eye height onto the local player.
     * PORT-GAP: 1.21 `Entity.eyeHeight` is private and derived from the pose
     * dimensions; there is no setter (it was never called in 1.12 either).
     */
    open fun updateEyeHeight() {
    }

    /** CLIENT: true when the local player owns this player-girl. */
    open fun hasOwnerUUID(): Boolean {
        val owner = this.entityData.get(OWNER_UUID)
        return if (!owner.isPresent) false else owner.get() == ClientHooks.localPlayerUUID()
    }

    open fun shouldRenderWhenOwnerInvisible(): Boolean = false

    open fun saveOwnerData(player: Player) {
        val nbt = player.persistentData
        val modelCode = nbt.getString("sexmod:CustomModel" + NpcType.getNpcType(this))
        this.setCustomModelCode(modelCode)
    }

    override fun updateAITasks() {
        rebuildPlayerGirlTableFromWorld()
        this.tickFollowUpTransitions()
        this.updateCustomModelParts()
        val uuid = this.getOwnerUserUUID()
        if (uuid != null) {
            val player = this.level().getPlayerByUUID(uuid)
            if (player == null) {
                this.teleportTo(this.x, 0.0, this.z)
            } else {
                this.saveOwnerData(player)
                if (this.isAnchored()) {
                    val pos = this.getTargetPosition()
                    this.teleportTo(pos.x, pos.y, pos.z)
                } else {
                    this.teleportTo(player.x, player.y + 0.0, player.z)
                }

                val action = this.getCurrentAction()
                if (action === Action.NULL && player.swinging) {
                    this.setCurrentAction(Action.ATTACK)
                }

                if (action === Action.ATTACK && !player.swinging) {
                    this.setCurrentAction(Action.NULL)
                }
            }
        }
    }

    /**
     * Ticks the strip sequence: at tick 65 the outfit index toggles (server),
     * at tick 100 the client ends the strip (camera/third-person reset) and
     * the server returns the girl to [Action.NULL]. `stripTicks` is -1
     * while no strip is running.
     */
    open fun tickStripSequence() {
        if (this.stripTicks != -1) {
            this.stripTicks++
            if (!this.level().isClientSide && this.stripTicks == 65) {
                this.setOutfitIndex(if (this.getOutfitIndex() == 0) 1 else 0)
            }

            if (this.stripTicks >= 100) {
                if (this.getCurrentAction() === Action.STRIP) {
                    if (this.level().isClientSide) {
                        this.handleClientOwner()
                    } else {
                        this.setCurrentAction(Action.NULL)
                    }
                }
            }
        }
    }

    /**
     * CLIENT: forces first-person view, reloads the entity shader and locks
     * movement at the end of the strip animation.
     */
    open fun handleClientOwner() {
        if (this.hasOwnerUUID()) {
            ClientHooks.setThirdPersonView(0)
            Client.reloadEntityShader()
            HandlePlayerMovement.setMovementLock(true)
        }
    }

    open fun isSceneActive(): Boolean = this.isAnchored()

    open fun getOwnerAimVector(vec: Vec3, partialTicks: Float): Vec3 = vec

    open fun canPerformAction(action: Action, player: Player): Boolean = false

    open fun isPlayerGirl(): Boolean = true

    open fun onOwnerInteract(player: Player) {
    }

    /**
     * Guards the action state machine: never allows a transition into
     * [Action.NULL] while the girl is anchored (would desync the scene),
     * and arms the strip countdown (`stripTicks`) when entering
     * [Action.STRIP]. CLIENT writes are routed through
     * `changeDataParameterFromClient` by the super implementation.
     */
    override fun setCurrentAction(action: Action?) {
        if (!this.level().isClientSide && action === Action.NULL && this.isAnchored()) {
            Main.LOGGER.info("prevented a potential animation break")
        } else {
            if (action === Action.STRIP) {
                this.stripTicks = if (this.level().isClientSide) 5 else 0
            }

            super.setCurrentAction(action)
        }
    }

    /**
     * Mirrors the owner player's equipped armor into the girl's data-manager
     * armor slots (elytra maps to the chest slot), so the transformed model
     * renders the same equipment. SERVER or CLIENT; writes are direct data-manager
     * sets.
     */
    open fun syncArmor(player: Player) {
        this.entityData.set(HELMET_SLOT, ItemStack.EMPTY)
        this.entityData.set(CHEST_SLOT, ItemStack.EMPTY)
        this.entityData.set(LEGS_SLOT, ItemStack.EMPTY)
        this.entityData.set(BOOTS_SLOT, ItemStack.EMPTY)

        for (stack in player.armorSlots) {
            val item = stack.item
            if (item is ElytraItem) {
                this.entityData.set(CHEST_SLOT, stack)
            } else if (item is ArmorItem) {
                when (item.equipmentSlot) {
                    EquipmentSlot.HEAD -> this.entityData.set(HELMET_SLOT, stack)
                    EquipmentSlot.CHEST -> this.entityData.set(CHEST_SLOT, stack)
                    EquipmentSlot.LEGS -> this.entityData.set(LEGS_SLOT, stack)
                    EquipmentSlot.FEET -> this.entityData.set(BOOTS_SLOT, stack)
                    else -> {}
                }
            }
        }
    }

    open fun getOwnerUserUUID(): UUID? {
        val owner = this.entityData.get(OWNER_UUID)
        return if (owner.isPresent) owner.get() else null
    }

    open fun getOwnerPlayer(): Player? {
        val uuid = this.getOwnerUserUUID()
        return if (uuid == null) null else this.level().getPlayerByUUID(uuid)
    }

    open fun setOwnerId(uuidOpt: Optional<UUID>) {
        this.entityData.set(OWNER_UUID, uuidOpt)
        playerGirlTableDirty = true
    }

    /** A dead girl has to leave [PLAYER_GIRL_BY_OWNER]; the rebuild only runs while the table is dirty. */
    override fun remove(reason: RemovalReason) {
        super.remove(reason)
        playerGirlTableDirty = true
    }

    open fun onTickClient() {
    }

    open fun onTransformationSpawned() {
    }

    open fun isOwnerUUID(uuid: UUID?): Boolean {
        if (uuid == null) {
            return false
        }

        val playerGirl = getPlayerGirlByUUID(uuid)
        return playerGirl != null
    }

    /**
     * CLIENT-side action dispatch: first asks [handleActionRequest],
     * and if unhandled forwards the action to the server as a
     * [SexPromptPacket] carrying the owner UUID and the `firstInteraction`
     * "first interaction" flag. The flag is consumed (reset to true) on send.
     */
    override fun doAction(action: String, uuid: UUID) {
        if (!this.handleActionRequest(action)) {
            val owner = this.entityData.get(OWNER_UUID)
            if (owner.isPresent) {
                PacketHandler.networkWrapper.sendToServer(SexPromptPacket(action, uuid, owner.get(), this.firstInteraction))
                this.firstInteraction = true
            }
        }
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        val owner = this.entityData.get(OWNER_UUID)
        if (owner.isPresent) {
            tag.putString("owner", owner.get().toString())
        }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        val owner = tag.getString("owner")
        if (owner.isNotEmpty()) {
            try {
                this.entityData.set(OWNER_UUID, Optional.of(UUID.fromString(owner)))
            } catch (exception: IllegalArgumentException) {
                // malformed or missing owner tag (older saves): leave her unowned
                // instead of failing the whole chunk load
            }
        }

        playerGirlList.add(this)
        playerGirlTableDirty = true
    }

    /**
     * Plays a sound at the girl's position; on the client it is a local world
     * sound ([SoundSource.NEUTRAL]), on the server a broadcast to all
     * players ([SoundSource.PLAYERS]).
     */
    override fun playSoundAtPosition(sound: SoundEvent, volume: Float, pitch: Float) {
        val pos = this.getPositionVec3d()
        if (this.level().isClientSide) {
            this.level().playLocalSound(pos.x, pos.y, pos.z, sound, SoundSource.NEUTRAL, volume, pitch, false)
        } else {
            this.level().playSound(null, BlockPos.containing(pos.x, pos.y, pos.z), sound, SoundSource.PLAYERS, volume, pitch)
        }
    }

    override fun playSound(sound: SoundEvent) {
        this.playSoundAtPosition(sound, 1.0f, 1.0f)
    }

    open fun playRandomSound(sounds: Array<SoundEvent>) {
        this.playSoundAtPosition(sounds[this.random.nextInt(sounds.size)], 1.0f, 1.0f)
    }

    override fun playSoundAtVolume(sound: SoundEvent, volume: Float) {
        this.playSoundAtPosition(sound, volume, 1.0f)
    }

    override fun startActionFromHandState() {
    }

    /** Client-only helpers (PORTING.md §2.3 rule C). */
    private object Client {
        fun tickGenderSwapScreen() {
            GenderSwapScreen.instance?.tick()
        }

        /** 1.12 `mc.entityRenderer.loadEntityShader(mc.getRenderViewEntity())`. */
        fun reloadEntityShader() {
            val mc = Minecraft.getInstance()
            mc.gameRenderer.checkEntityPostEffect(mc.cameraEntity)
        }
    }

    companion object {
        const val CUSTOM_MODEL_NBT_KEY: String = "sexmod:CustomModel"
        const val GIRL_SPECIFIC_NBT_KEY: String = "sexmod:GirlSpecific"
        const val ac: Float = 0.0f
        const val am: Int = 100
        const val yFlag: Int = 65

        @JvmField
        var flightGrantEnabled: Boolean = true

        /** 1.12 key 118 (declared with `BaseGirlEntity.class` but registered here; §2.6: defined by the registering class). */
        @JvmField
        val OWNER_UUID: EntityDataAccessor<Optional<UUID>> =
            SynchedEntityData.defineId(AbstractPlayerGirlEntity::class.java, EntityDataSerializers.OPTIONAL_UUID)

        @JvmField
        var PLAYER_GIRL_BY_OWNER: Hashtable<UUID, AbstractPlayerGirlEntity> = Hashtable()

        @JvmField
        var playerGirlList: MutableList<AbstractPlayerGirlEntity> = ArrayList()

        /** Set by every add/remove/death/owner change; cleared by [rebuildPlayerGirlTableFromWorld]. */
        @Volatile
        @JvmField
        var playerGirlTableDirty: Boolean = true

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractGirlNpcEntity.createAttributes()

        @JvmStatic
        fun getPlayerGirlByUUID(uuid: UUID?): AbstractPlayerGirlEntity? {
            return if (uuid == null) null else PLAYER_GIRL_BY_OWNER[uuid]
        }

        @JvmStatic
        fun getPlayerGirlByUUID(player: Player): AbstractPlayerGirlEntity? {
            return PLAYER_GIRL_BY_OWNER[player.uuid]
        }

        @JvmStatic
        fun getPlayerGirlByOwner(uuid: UUID): AbstractPlayerGirlEntity? {
            try {
                for (girl in getGirlEntityList()) {
                    if (!girl.level().isClientSide && girl is AbstractPlayerGirlEntity) {
                        if (uuid == girl.getOwnerUserUUID()) {
                            return girl
                        }
                    }
                }
            } catch (ex: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }

            return null
        }

        /**
         * CLIENT-side entry point used by the camera/escape handlers: looks up the
         * local player's girl entity and resets it.
         */
        @JvmStatic
        fun resetPlayerGirlCamera() {
            val playerGirl = getPlayerGirlByUUID(ClientHooks.localPlayerUUID())
            playerGirl?.resetCameraAndPhysics()
        }

        @JvmStatic
        fun hasPlayerGirlWithUUID(uuid: UUID): Boolean {
            rebuildPlayerGirlTableFromWorld()
            return PLAYER_GIRL_BY_OWNER.containsKey(uuid)
        }

        @JvmStatic
        fun isOwnerPlayer(player: Player?): Boolean {
            return if (player == null) false else hasPlayerGirlWithUUID(player.uuid)
        }

        /**
         * Maintains the static UUID->girl tables: moves girls with a bound owner
         * from [playerGirlList] into [PLAYER_GIRL_BY_OWNER], then prunes dead girls.
         * No-op while [playerGirlTableDirty] is clear - the render path calls
         * this every frame, so every add/remove/death/owner change sets the flag.
         * Tolerates concurrent modification during world teardown.
         */
        @JvmStatic
        fun rebuildPlayerGirlTableFromWorld() {
            if (!playerGirlTableDirty) {
                return
            }

            // cleared before the walk: a change racing with it re-marks the table
            playerGirlTableDirty = false

            try {
                playerGirlList.removeIf { playerGirl ->
                    val ownerUuid = playerGirl.getOwnerUserUUID()
                    if (ownerUuid == null) {
                        false
                    } else {
                        PLAYER_GIRL_BY_OWNER[ownerUuid] = playerGirl
                        true
                    }
                }
            } catch (ex: ConcurrentModificationException) {
                // ignored: girl list mutated while the owner index was rebuilt; the next lookup repairs it
            }

            rebuildPlayerGirlTableInternal()
        }

        /** Prunes removed girls from [PLAYER_GIRL_BY_OWNER] (1.12 `isDead`). */
        @JvmStatic
        fun rebuildPlayerGirlTableInternal() {
            val toRemove = ArrayList<UUID>()

            for (entry in PLAYER_GIRL_BY_OWNER.entries) {
                if (entry.value.isRemoved) {
                    toRemove.add(entry.key)
                }
            }

            for (uuid in toRemove) {
                PLAYER_GIRL_BY_OWNER.remove(uuid)
            }
        }
    }
}
