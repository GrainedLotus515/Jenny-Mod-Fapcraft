package com.trolmastercard.sexmod.item

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.model.GirlAnimationController
import com.trolmastercard.sexmod.client.particle.DragonBreathParticle
import com.trolmastercard.sexmod.client.renderer.GalathCoinRenderer
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.networking.InformOfOwnershipPacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SpawnEnergyBallParticlesPacket
import com.trolmastercard.sexmod.registry.ModItems
import com.trolmastercard.sexmod.util.GirlSavedData
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.VectorMath
import java.util.UUID
import java.util.function.Consumer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import software.bernie.geckolib.animatable.GeoItem
import software.bernie.geckolib.animatable.client.GeoRenderProvider
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.AnimationState
import software.bernie.geckolib.animation.PlayState
import software.bernie.geckolib.animation.RawAnimation
import software.bernie.geckolib.util.GeckoLibUtil

/**
 * <b>Role.</b> The Galath coin — binds a defeated succubus to her victor
 * (ownership via [GirlSavedData]) and summons/dismisses her.
 *
 * <b>Flow.</b> Right-click starts a 4-second summon window (activation time in
 * the player's entity data); on the client, energy particles stream from the
 * coin to the summon point; after the window the server spawns the
 * [GalathEntity] ([writeCooldownNBT] SERVER branch) and grants
 * ownership. Right-clicking the owned Galath with the coin sends her back into it
 * ([tickDeSummon]): de-summon animation, particle stream into the coin, then removal;
 * the summon/de-summon particle visuals run client-side while the timestamps
 * advance on both sides.
 *
 * <b>Pitfalls.</b> The timing keys ([ACTIVATION_TIME_KEY],
 * [DEACTIVATION_TIME_KEY], [DE_SUMMON_ANIMATION_KEY]) are
 * the single source of truth for the animation state machine — both sides must
 * agree on the 4000/1000/3000 ms windows or the girl desyncs. The client-side
 * [GirlSavedData.debugEnabled] mirrors ownership; see
 * [InformOfOwnershipPacket].
 *
 * Port notes: `player.getEntityData()` is `player.persistentData` (same keys);
 * the registry/model/TEISR hookup moved to `ModItems` / `ClientProxy`
 * ([createGeoRenderer]); the instance `@SubscribeEvent` handlers live in [Events].
 */
class GalathCoinItem : Item(Properties().stacksTo(1)), GeoItem {
    private val animationFactory: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    @JvmField
    var controller: AnimationController<GalathCoinItem>? = null

    override fun use(world: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val entityData = player.persistentData
        val result = InteractionResultHolder.fail(player.getItemInHand(hand))
        if (entityData.getLong(DEACTIVATION_TIME_KEY) != 0L) {
            return result
        } else if (entityData.getLong(ACTIVATION_TIME_KEY) != 0L) {
            return result
        } else if (!this.canSummon(world, player)) {
            world.playLocalSound(player.x, player.y, player.z, SoundHandler.MISC_BEEW[0], SoundSource.PLAYERS, 1.0f, 1.0f, false)
            return InteractionResultHolder.success(player.getItemInHand(hand))
        } else {
            world.playLocalSound(player.x, player.y, player.z, SoundHandler.MISC_WEOWEO[1], SoundSource.PLAYERS, 1.0f, 1.0f, false)
            entityData.putLong(ACTIVATION_TIME_KEY, System.currentTimeMillis())
            return InteractionResultHolder.success(player.getItemInHand(hand))
        }
    }

    fun canSummon(world: Level, player: Player): Boolean =
        if (!world.isClientSide) !GirlSavedData.hasOwner(player.uuid) else !GirlSavedData.debugEnabled

    /** 1.12 `onUpdate(stack, world, entity, slot, selected)`. */
    override fun inventoryTick(stack: ItemStack, world: Level, entity: Entity, slot: Int, selected: Boolean) {
        super.inventoryTick(stack, world, entity, slot, selected)
        if (entity is Player) {
            val player = entity
            val entityData = player.persistentData
            val activationTime = entityData.getLong(ACTIVATION_TIME_KEY)
            val deactivationTime = entityData.getLong(DEACTIVATION_TIME_KEY)
            val now = System.currentTimeMillis()
            this.writeCooldownNBT(player, entityData, now, activationTime)
            this.tickDeSummon(player, entityData, now, deactivationTime)
            if (deactivationTime != 0L && now > deactivationTime + 4000L) {
                entityData.putLong(DEACTIVATION_TIME_KEY, 0L)
                entityData.putBoolean(DE_SUMMON_ANIMATION_KEY, false)
            }

            if (world.isClientSide) {
                this.isSummonWindow(player, now, activationTime)
                this.isCooldownElapsed(player, now, deactivationTime)
            }
        }
    }

    /**
     * SERVER: the coin was used on the owned Galath at `startTime`. Once the coin has wound up
     * (the client starts its particle stream from her to the coin at the same moment) she plays
     * the de-summon animation in place, and when the window closes she is removed together with
     * her Manglelie and the ownership, so the coin can summon her again.
     *
     * The 1.12 source calls the summon branch ([writeCooldownNBT]) for the de-summon timer as
     * well, which spawns a second Galath in front of the player. Its own description text, the
     * `desummon` animation and the particle stream running from her *target position* show
     * that the de-summon half was lost; this restores it from the death path
     * (`GalathEntity.onLivingDeath`).
     */
    fun tickDeSummon(player: Player, entityData: CompoundTag, now: Long, startTime: Long) {
        if (startTime == 0L || player.level().isClientSide) {
            return
        }

        val galath = BaseGirlEntity.getServerGirlEntity(GirlSavedData.getOwnerOf(player)) as? GalathEntity
        if (now > startTime + ANIMATION_START_DELAY && !entityData.getBoolean(DE_SUMMON_ANIMATION_KEY)) {
            entityData.putBoolean(DE_SUMMON_ANIMATION_KEY, true)
            if (galath != null && galath.getCurrentAction() != Action.GALATH_DE_SUMMON) {
                deSummonGalath(galath)
                PacketHandler.networkWrapper.sendToAllTracking(SpawnEnergyBallParticlesPacket(galath.getGirlId(), player.uuid), galath)
            }
        }

        if (now > startTime + SUMMON_DURATION && galath != null) {
            GirlSavedData.updateMangleliePartner(galath)
        }
    }

    /** CLIENT. */
    fun isCooldownElapsed(player: Player, now: Long, startTime: Long) {
        if (startTime != 0L) {
            if (now > startTime + 1000L && now < startTime + 3000L) {
                var galath: GalathEntity? = null

                try {
                    for (girl in BaseGirlEntity.getGirlEntityList()) {
                        if (!girl.isRemoved && girl.level().isClientSide && girl is GalathEntity && player == girl.getMasterPlayer()) {
                            galath = girl
                            break
                        }
                    }
                } catch (cme: ConcurrentModificationException) {
                    // ignored: entity list mutated during iteration; retried on the next tick
                }

                if (galath != null) {
                    val targetPos = galath.getTargetPosition().add(0.0, 1.5, 0.0)
                    val eyePos = player.position().add(0.0, player.eyeHeight.toDouble(), 0.0)
                    val coinPos = eyePos.add(
                        VectorMath.rotateByYaw(
                            ((if (player.mainHandItem.item == GALATH_COIN) 1 else -1) * 0.1f).toDouble(),
                            (-0.01f + player.xRot * 0.0015f).toDouble(),
                            0.0,
                            player.yBodyRot,
                        ),
                    )
                    val progress = (now - startTime - 1000L).toFloat() / 2000.0f
                    val lerpedPos = RotationHelper.lerpVec3dDouble(targetPos, coinPos, progress.toDouble())
                    Client.addBreathParticle(player, lerpedPos)
                }
            }
        }
    }

    /** CLIENT. */
    fun handleCoinClick(player: Player) {
        if (ClientHooks.localPlayerUUID()!! == player.uuid) { // java-npe
            GirlSavedData.debugEnabled = true
        }
    }

    /** CLIENT. */
    fun isSummonWindow(player: Player, now: Long, startTime: Long) {
        if (now > startTime + 1000L && now < startTime + 3000L) {
            val eyePos = player.position().add(0.0, player.eyeHeight.toDouble(), 0.0)
            val coinPos = eyePos.add(
                VectorMath.rotateByYaw(
                    ((if (player.mainHandItem.item == GALATH_COIN) 1 else -1) * 0.1f).toDouble(),
                    (-0.01f + player.xRot * 0.0015f).toDouble(),
                    0.0,
                    player.yBodyRot,
                ),
            )
            val summonPos = eyePos.add(player.lookAngle.normalize().scale(2.0))
            val progress = (now - startTime - 1000L).toFloat() / 2000.0f
            val lerpedPos = RotationHelper.lerpVec3dDouble(coinPos, summonPos, progress.toDouble())
            Client.addBreathParticle(player, lerpedPos)
        }
    }

    fun writeCooldownNBT(player: Player, entityData: CompoundTag, now: Long, startTime: Long) {
        if (startTime != 0L) {
            if (now - startTime > 4000L) {
                entityData.putLong(ACTIVATION_TIME_KEY, 0L)
                val eyePos = player.position().add(0.0, player.eyeHeight.toDouble(), 0.0)
                val summonPos = eyePos.add(player.lookAngle.normalize().scale(2.0))
                val random = player.random

                var i = 0
                while (i < 100.0f) {
                    player.level().addParticle(
                        ParticleTypes.DRAGON_BREATH,
                        summonPos.x, summonPos.y, summonPos.z,
                        ((2.0f * random.nextFloat() - 1.0f) * 0.2f).toDouble(),
                        ((2.0f * random.nextFloat() - 1.0f) * 0.2f).toDouble(),
                        ((2.0f * random.nextFloat() - 1.0f) * 0.2f).toDouble(),
                    )
                    i++
                }

                val world = player.level()
                if (world.isClientSide) {
                    this.handleCoinClick(player)
                } else {
                    val galath = GalathEntity(player.level(), player, summonPos)
                    galath.moveTo(summonPos.x, summonPos.y, summonPos.z)
                    GirlSavedData.grantOwnership(player, galath)
                    player.level().addFreshEntity(galath)
                    if (GirlSavedData.isManglelieOwned(player.uuid)) {
                        galath.canStartPussyLicking()
                    }
                }
            }
        }
    }

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        val coinController = GirlAnimationController<GalathCoinItem>(this, "controller", 0) { this.animationPredicate(it) }
        this.controller = coinController
        controllers.add(coinController)
    }

    /** CLIENT. */
    fun animationPredicate(event: AnimationState<GalathCoinItem>): PlayState {
        val entityData = ClientHooks.localPlayer()!!.persistentData // java-npe
        if (entityData.getLong(ACTIVATION_TIME_KEY) == 0L && entityData.getLong(DEACTIVATION_TIME_KEY) == 0L) {
            event.controller.forceAnimationReset()
            return PlayState.STOP
        } else {
            this.controller?.setAnimation(RawAnimation.begin().thenPlay("animation.galath_coin.summon"))
            return PlayState.CONTINUE
        }
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = this.animationFactory

    override fun createGeoRenderer(consumer: Consumer<GeoRenderProvider>) {
        consumer.accept(object : GeoRenderProvider {
            private var renderer: GalathCoinRenderer? = null

            override fun getGeoItemRenderer(): BlockEntityWithoutLevelRenderer =
                renderer ?: GalathCoinRenderer().also { renderer = it }
        })
    }

    /** The 1.12 instance `@SubscribeEvent` methods (the hub registered the item instance). */
    object Events {
        @SubscribeEvent
        fun onEntityInteract(event: PlayerInteractEvent.EntityInteract) {
            val player = event.entity
            val stack = player.getItemInHand(event.hand)
            if (GALATH_COIN == stack.item) {
                val target = event.target
                if (target is GalathEntity) {
                    if (player.uuid == target.getMasterUUID()) {
                        val entityData = player.persistentData
                        // a de-summon that is already running keeps its timer (1.12 restarted it on every click)
                        if (entityData.getLong(DEACTIVATION_TIME_KEY) == 0L) {
                            player.level().playLocalSound(
                                player.x, player.y, player.z, SoundHandler.MISC_WEOWEO[0], SoundSource.PLAYERS, 1.0f, 1.0f, false,
                            )
                            entityData.putBoolean(DE_SUMMON_ANIMATION_KEY, false)
                            entityData.putLong(DEACTIVATION_TIME_KEY, System.currentTimeMillis())
                        }

                        event.isCanceled = true
                    }
                }
            }
        }

        @SubscribeEvent
        fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
            val player = event.entity
            if (!player.level().isClientSide) {
                val ownerUuid = GirlSavedData.getOwnerOf(player)
                val girl = BaseGirlEntity.getServerGirlEntity(ownerUuid)
                if (girl != null) {
                    GirlSavedData.updateMangleliePartner(girl as GalathEntity)
                    PacketHandler.networkWrapper.sendTo(InformOfOwnershipPacket(false), player as ServerPlayer)
                }
            }
        }
    }

    /** Client-only helpers (PORTING.md §2.3 Rule C). */
    private object Client {
        fun addBreathParticle(player: Player, pos: Vec3) {
            DragonBreathParticle.BREATH_SCALE = 0.2f
            Minecraft.getInstance().particleEngine.add(DragonBreathParticle(player.level() as ClientLevel, pos.x, pos.y, pos.z))
        }
    }

    companion object {
        const val SUMMON_DURATION: Long = 4000L
        const val ANIMATION_START_DELAY: Long = 1000L
        const val ANIMATION_END_DELAY: Long = 3000L
        const val HORIZONTAL_OFFSET: Float = 0.1f
        const val BASE_VERTICAL_OFFSET: Float = -0.01f
        const val PITCH_MULTIPLIER: Float = 0.0015f
        const val SPAWN_DISTANCE: Float = 2.0f
        const val HEIGHT_OFFSET: Float = 1.5f
        const val PARTICLE_VELOCITY: Float = 0.03f
        const val PARTICLE_COUNT: Float = 100.0f
        const val PARTICLE_VELOCITY_SPREAD: Float = 0.2f
        const val PARTICLE_SPAWN_SPREAD: Float = 1.5f
        const val ACTIVATION_TIME_KEY: String = "sexmod:galath_coin_activation_time"
        const val DEACTIVATION_TIME_KEY: String = "sexmod:galath_coin_deactivation_time"
        const val DE_SUMMON_ANIMATION_KEY: String = "sexmod:galath_coin_de_summoning_animation_time"
        const val DESCRIPTION: String =
            "Defeating a succubus makes her accept the victor as her master, granting him a coin to which her soul is bound. Using the coin summons her, offering services on demand. If her master uses the coin on her or goes too far, she returns to the coin"

        @JvmStatic
        val GALATH_COIN: GalathCoinItem
            get() = ModItems.GALATH_COIN.get()

        @JvmStatic
        fun deSummonGalath(galath: GalathEntity) {
            galath.setCurrentAction(Action.GALATH_DE_SUMMON)
            galath.markDespawnedWithPartner()
            galath.setAnchored(true)
            galath.setTargetPosition(galath.position())
            galath.setYawRotation(galath.yRot)
        }

        /** CLIENT. */
        @JvmStatic
        fun summonGalathFor(ownerUuid: UUID?, galath: GalathEntity) {
            val world = galath.level()
            val basePos = if (galath.isAnchored()) galath.getTargetPosition() else galath.position()
            val targetPos = basePos.add(0.0, 1.5, 0.0)
            val random = galath.random

            var i = 0
            while (i < 100.0f) {
                val offset = Vec3(
                    ((random.nextFloat() * 2.0f - 1.0f) * 1.5f).toDouble(),
                    ((random.nextFloat() * 2.0f - 1.0f) * 1.5f).toDouble(),
                    ((random.nextFloat() * 2.0f - 1.0f) * 1.5f).toDouble(),
                )
                val spawnPos = targetPos.add(offset)
                val velocity = offset.scale(-0.03f.toDouble())
                world.addParticle(ParticleTypes.DRAGON_BREATH, spawnPos.x, spawnPos.y, spawnPos.z, velocity.x, velocity.y, velocity.z)
                i++
            }

            if (ClientHooks.localPlayerUUID()!! == ownerUuid) { // java-npe
                GirlSavedData.debugEnabled = false
            }
        }

        @JvmStatic
        fun summonForPlayer(player: Player, galath: GalathEntity) {
            summonGalathFor(player.uuid, galath)
        }
    }
}
