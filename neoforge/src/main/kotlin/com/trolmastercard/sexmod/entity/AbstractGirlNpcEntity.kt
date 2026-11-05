package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.entity.ai.GirlFollowGoal
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.PlayerActionPacket
import com.trolmastercard.sexmod.networking.SendCompanionHomePacket
import com.trolmastercard.sexmod.networking.SetNewHomePacket
import com.trolmastercard.sexmod.util.Reference
import java.util.UUID
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.items.ItemStackHandler

/**
 * <b>Role.</b> Base class for the NPC (non-player) girls that follow and fight
 * alongside a player — Jenny, Bia, Luna, Ellie, Bee, Goblin and the rest.
 * Adds a 7-slot inventory ([ItemStackHandler]), weapon/bow/armor slots
 * synced via the data manager, an attack-mode parameter and the follow AI
 * ([GirlFollowGoal]).
 *
 * <b>State.</b> Synced keys `WEAPON/BOW/HELMET_SLOT/CHEST_SLOT/LEGS_SLOT/BOOTS_SLOT`
 * (1.12 IDs 117..112) mirror [inventory] slots 0..5 every AI tick;
 * `ATTACK_MODE` (1.12 ID 111) is set by the follow goal (0 = idle, 1 = melee,
 * 2 = bow). They are referenced by the client renderer and [GirlFollowGoal].
 *
 * <b>Pitfalls.</b> [updateAITasks] force-sets the living-entity flags
 * (1.12 `HAND_STATES`) to `1` every tick (combat hand-raise flag) — do not
 * "fix" this. The `downed` flag is cleared here when the girl has no master;
 * combat downed-state transitions live in [GirlFollowGoal.DownedGirlEvents].
 *
 * Port note: the 1.12 `hasCapability`/`getCapability` overrides exposing
 * [inventory] as `ITEM_HANDLER` are replaced by `ModEntities`' registration of
 * `Capabilities.ItemHandler.ENTITY` -> `girl.inventory` for every girl type.
 */
abstract class AbstractGirlNpcEntity : BaseGirlEntity {
    @JvmField
    var nextAttack: Int = 1

    @JvmField
    var slashSwordRot: Int = 0

    @JvmField
    var stabSwordRot: Int = 0

    @JvmField
    var holdBowRot: Int = 0

    @JvmField
    var swordOffsetStab: Vec3 = Vec3.ZERO

    @JvmField
    var downed: Boolean = false

    @JvmField
    var inventory: ItemStackHandler = ItemStackHandler(7)

    protected constructor(type: EntityType<out AbstractGirlNpcEntity>, level: Level) : super(type, level) {
        if (this.inventory.getStackInSlot(0) === ItemStack.EMPTY) {
            this.inventory.setStackInSlot(0, ItemStack(Items.IRON_SWORD))
        }

        if (this.inventory.getStackInSlot(1) === ItemStack.EMPTY) {
            this.inventory.setStackInSlot(1, ItemStack(Items.BOW))
        }
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(ATTACK_MODE, 0)
        builder.define(WEAPON, ItemStack.EMPTY)
        builder.define(BOW, ItemStack.EMPTY)
        builder.define(HELMET_SLOT, ItemStack.EMPTY)
        builder.define(CHEST_SLOT, ItemStack.EMPTY)
        builder.define(LEGS_SLOT, ItemStack.EMPTY)
        builder.define(BOOTS_SLOT, ItemStack.EMPTY)
    }

    override fun registerGoals() {
        super.registerGoals()
        this.goalSelector.addGoal(1, GirlFollowGoal(this))
    }

    open fun onArriveHome() {
    }

    /**
     * Called every AI tick. Regenerates the girl (heal + heart
     * particles) every 80 ticks when hurt, clears the `downed` flag when
     * unbounded, and re-syncs the inventory slots into the data manager so the
     * client renderer and follow-goal see current equipment.
     *
     * SERVER-side for the heal/particle branch (uses [ServerLevel]).
     */
    override fun updateAITasks() {
        super.updateAITasks()
        if (this.tickCount % 80 == 0 && this.health != this.maxHealth) {
            if (!this.hasMaster()) {
                this.heal(1.0f)
            } else {
                val mobs = this.level().getEntitiesOfClass(
                    Monster::class.java,
                    AABB(
                        this.x - 7.0,
                        this.y - 1.0,
                        this.z - 7.0,
                        this.x + 7.0,
                        this.y + 1.0,
                        this.z + 7.0,
                    ),
                )
                val healAmount = if (mobs.isEmpty()) 4 else 1
                this.heal(healAmount.toFloat())
                (this.level() as ServerLevel).sendParticles(
                    ParticleTypes.HEART,
                    this.x,
                    this.y + 1.0 + Reference.RANDOM.nextDouble(),
                    this.z,
                    healAmount,
                    1.0,
                    1.0,
                    1.0,
                    Reference.RANDOM.nextGaussian(),
                )
            }
        }

        if (this.downed && !this.hasMaster()) {
            this.downed = false
        }

        this.entityData.set(DATA_LIVING_ENTITY_FLAGS, 1.toByte())
        this.entityData.set(WEAPON, this.inventory.getStackInSlot(0))
        this.entityData.set(BOW, this.inventory.getStackInSlot(1))
        this.entityData.set(HELMET_SLOT, this.inventory.getStackInSlot(2))
        this.entityData.set(CHEST_SLOT, this.inventory.getStackInSlot(3))
        this.entityData.set(LEGS_SLOT, this.inventory.getStackInSlot(4))
        this.entityData.set(BOOTS_SLOT, this.inventory.getStackInSlot(5))
    }

    /**
     * CLIENT-side action dispatch from the interaction GUI:
     * `followme` binds the acting player as master (via
     * `ChangeDataParameterPacket` through `changeDataParameterFromClient`),
     * `stopfollowme`/`gohome` send the girl home, `equipment`
     * opens the equipment GUI and `setnewhome` stores a new home position.
     * Ordering: the packet names must stay in sync with [GirlFollowGoal]
     * and the GUI action strings.
     */
    override fun doAction(action: String, uuid: UUID) {
        if ("action.names.followme" == action) {
            this.changeDataParameterFromClient("master", uuid.toString())
        } else if ("action.names.stopfollowme" == action) {
            this.goHome()
        } else if ("action.names.equipment" == action) {
            val playerUuid = ClientHooks.localPlayerUUID()!! // java-npe: Minecraft.player on the client
            PacketHandler.networkWrapper.sendToServer(PlayerActionPacket(this.getGirlId(), playerUuid))
        } else if ("action.names.gohome" == action) {
            this.goHome()
            PacketHandler.networkWrapper.sendToServer(SendCompanionHomePacket(this.getGirlId()))
        } else if ("action.names.setnewhome" == action) {
            this.onArriveHome()
            PacketHandler.networkWrapper.sendToServer(SetNewHomePacket(this.getGirlId(), Vec3.atLowerCornerOf(this.blockPosition())))
        }
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        tag.put("inventory", this.inventory.serializeNBT(this.registryAccess()))
        super.addAdditionalSaveData(tag)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        this.inventory.deserializeNBT(this.registryAccess(), tag.getCompound("inventory"))
    }

    companion object {
        @JvmField
        val WEAPON: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(AbstractGirlNpcEntity::class.java, EntityDataSerializers.ITEM_STACK)

        @JvmField
        val BOW: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(AbstractGirlNpcEntity::class.java, EntityDataSerializers.ITEM_STACK)

        @JvmField
        val HELMET_SLOT: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(AbstractGirlNpcEntity::class.java, EntityDataSerializers.ITEM_STACK)

        @JvmField
        val CHEST_SLOT: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(AbstractGirlNpcEntity::class.java, EntityDataSerializers.ITEM_STACK)

        @JvmField
        val LEGS_SLOT: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(AbstractGirlNpcEntity::class.java, EntityDataSerializers.ITEM_STACK)

        @JvmField
        val BOOTS_SLOT: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(AbstractGirlNpcEntity::class.java, EntityDataSerializers.ITEM_STACK)

        @JvmField
        val ATTACK_MODE: EntityDataAccessor<Int> = SynchedEntityData.defineId(AbstractGirlNpcEntity::class.java, EntityDataSerializers.INT)

        /** Companions are not inherited (PORTING.md §2.5). */
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = BaseGirlEntity.createAttributes()
    }
}
