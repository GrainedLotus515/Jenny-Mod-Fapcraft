package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.registry.ModEntities
import com.trolmastercard.sexmod.util.KoboldManager
import com.trolmastercard.sexmod.util.Reference
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import software.bernie.geckolib.animatable.GeoEntity
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState
import software.bernie.geckolib.animation.RawAnimation
import software.bernie.geckolib.util.GeckoLibUtil

/**
 * <b>Role.</b> The kobold egg — the hatchable egg item's placed entity
 * (KoboldNPC reproduction). Sits for 12000 ticks (with escalating wiggle
 * animations) then hatches into a new [KoboldEntity] joined to the
 * tribe ([KoboldManager]); any damage also kills it.
 *
 * <b>State.</b> Data keys 115 (`EGG_COLOR`) and 116 (`EGG_TYPE`,
 * the age counter — server-incremented each tick). `tribeId` persists
 * the tribe binding. The hatch animation is registered on the shared static
 * controller.
 */
class KoboldEggEntity : LivingEntity, GeoEntity {
    private val factory: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    @JvmField
    var tribeId: UUID? = null

    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out KoboldEggEntity>, level: Level) : super(type, level)

    constructor(level: Level) : this(ModEntities.KOBOLD_EGG.get(), level)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(EGG_COLOR, KoboldEntity.DEFAULT_EYE_COLOR.toString())
        builder.define(EGG_TYPE, 0)
    }

    /**
     * SERVER/CLIENT: ages the egg; at 12000 ticks [spawnHatchExplosion]
     * hatches it. The age counter only advances on the SERVER (the client
     * reads the synced value for the wiggle animations).
     */
    override fun tick() {
        super.tick()
        val eggType = this.entityData.get(EGG_TYPE)
        if (eggType >= 12000) {
            this.spawnHatchExplosion()
        }

        if (!this.level().isClientSide) {
            this.entityData.set(EGG_TYPE, eggType + 1)
        }
    }

    override fun canTrample(state: BlockState, pos: BlockPos, fallDistance: Float): Boolean = false

    override fun hurt(source: DamageSource, amount: Float): Boolean {
        val damaged = super.hurt(source, amount)
        if (!damaged) {
            return false
        }

        this.discard()
        return true
    }

    /**
     * SERVER: the hatch — spawns the explosion particles, creates the kobold
     * (tribe id minted if needed), joins it to the tribe, copies the tribe
     * master/name bindings and removes the egg. [hatchEgg] notifies the master.
     */
    fun spawnHatchExplosion() {
        for (i in 0 until 30) {
            val vx = (if (Reference.RANDOM.nextBoolean()) 1 else -1) * Reference.RANDOM.nextFloat()
            val vy = (if (Reference.RANDOM.nextBoolean()) 1 else -1) * Reference.RANDOM.nextFloat()
            val vz = (if (Reference.RANDOM.nextBoolean()) 1 else -1) * Reference.RANDOM.nextFloat()
            // 1.12 EXPLOSION_NORMAL; a client-side effect (no-op on the server, as in 1.12)
            this.level().addParticle(ParticleTypes.POOF, 0.5 + this.x, 0.5 + this.y, 0.5 + this.z, vx.toDouble(), vy.toDouble(), vz.toDouble())
        }

        if (!this.level().isClientSide) {
            var id = this.tribeId
            if (id == null) {
                id = UUID.randomUUID()
                this.tribeId = id
            }

            val kobold = KoboldEntity.createKobold(this.level(), id)
            KoboldManager.addTribeMember(id, kobold)
            val masterId = KoboldManager.findTribeIdWith(id)
            if (masterId != null) {
                kobold.getEntityData().set(BaseGirlEntity.MASTER, masterId.toString())
            }

            val members = KoboldManager.getTribeMembersList(id)
            var masterName: String? = null

            for (memberKobold in members) {
                val name = memberKobold.getEntityData().get(KoboldEntity.TRIBE_NAME)
                if ("" != name) {
                    masterName = name
                    break
                }
            }

            if (masterName != null) {
                kobold.getEntityData().set(KoboldEntity.TRIBE_NAME, masterName)
            }

            kobold.setPos(0.5 + this.x, this.y, 0.5 + this.z)
            this.level().addFreshEntity(kobold)
            this.hatchEgg(kobold)
            this.level().playSound(null, this.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.5f, 1.0f)
            this.discard()
        }
    }

    /**
     * SERVER: announces the new tribe member to the kobold's master (colored
     * chat + hit/level-up sounds).
     */
    fun hatchEgg(kobold: KoboldEntity) {
        val player = kobold.getMasterPlayer()
        if (player != null) {
            val playerMP = player as ServerPlayer
            val color = KoboldManager.getTribeColor(this.tribeId!!) // java-npe
            player.sendSystemMessage(
                Component.literal(
                    String.format(
                        "%s%s %shas become a %snew tribe member%s!",
                        color.getTextColor(),
                        kobold.getDisplayNameText(),
                        ChatFormatting.WHITE,
                        ChatFormatting.RED,
                        ChatFormatting.WHITE,
                    ),
                ),
            )
            playerMP.connection.send(
                ClientboundSoundPacket(
                    BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.ARROW_HIT_PLAYER), SoundSource.NEUTRAL,
                    player.x, player.y, player.z, 1.0f, 1.0f, this.random.nextLong(),
                ),
            )
            playerMP.connection.send(
                ClientboundSoundPacket(
                    BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.FIREWORK_ROCKET_TWINKLE_FAR), SoundSource.NEUTRAL,
                    player.x, player.y, player.z, 1.0f, 1.0f, this.random.nextLong(),
                ),
            )
        }
    }

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        val controller = AnimationController(this, "controller", 5) { this.animationPredicate(it) }
        animationController = controller
        controllers.add(controller)
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = this.factory

    override fun addAdditionalSaveData(tag: CompoundTag) {
        val id = this.tribeId
        if (id != null) {
            tag.putString("tribeID", id.toString())
        }

        tag.putString("egg_color", this.entityData.get(EGG_COLOR))
        tag.putInt("eggAge", this.entityData.get(EGG_TYPE))
        super.addAdditionalSaveData(tag)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        val tribeIdStr = tag.getString("tribeID")
        if ("" != tribeIdStr) {
            this.tribeId = UUID.fromString(tribeIdStr)
        }

        this.entityData.set(EGG_COLOR, tag.getString("egg_color"))
        this.entityData.set(EGG_TYPE, tag.getInt("eggAge"))
    }

    fun animationPredicate(event: AnimationState<KoboldEggEntity>): PlayState {
        val eggType = this.entityData.get(EGG_TYPE)
        if (12000 - eggType < 20) {
            event.controller.setAnimation(RawAnimation.begin().thenLoop("animation.model.hatch"))
            return PlayState.CONTINUE
        } else {
            val progress = eggType / 12000.0f
            if (progress > 0.98) {
                event.controller.setAnimation(RawAnimation.begin().thenLoop("animation.model.veryfast"))
                return PlayState.CONTINUE
            } else if (progress > 0.85) {
                event.controller.setAnimation(RawAnimation.begin().thenLoop("animation.model.fast"))
                return PlayState.CONTINUE
            } else if (progress > 0.75) {
                event.controller.setAnimation(RawAnimation.begin().thenLoop("animation.model.medium"))
                return PlayState.CONTINUE
            } else if (progress > 0.5) {
                event.controller.setAnimation(RawAnimation.begin().thenLoop("animation.model.slow"))
                return PlayState.CONTINUE
            } else {
                return PlayState.CONTINUE
            }
        }
    }

    override fun getArmorSlots(): Iterable<ItemStack> = ArrayList()

    override fun getItemBySlot(slot: EquipmentSlot): ItemStack = ItemStack.EMPTY

    override fun setItemSlot(slot: EquipmentSlot, stack: ItemStack) {
    }

    override fun getMainArm(): HumanoidArm = HumanoidArm.LEFT

    companion object {
        const val HATCH_TIME: Int = 12000

        @JvmField
        var animationController: AnimationController<KoboldEggEntity>? = null

        @JvmField
        val EGG_COLOR: EntityDataAccessor<String> = SynchedEntityData.defineId(KoboldEggEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val EGG_TYPE: EntityDataAccessor<Int> = SynchedEntityData.defineId(KoboldEggEntity::class.java, EntityDataSerializers.INT)

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = LivingEntity.createLivingAttributes()
    }
}
