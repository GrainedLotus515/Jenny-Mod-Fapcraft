package com.trolmastercard.sexmod.item

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.renderer.AlliesLampRenderer
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.AllieEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.registry.ModItems
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.ItemNbt
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.VectorMath
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.ConcurrentModificationException
import java.util.function.Consumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent
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
 * <b>Role.</b> The Allies lamp — summons an [AllieEntity] over a
 * 95-tick rub animation (3 wishes per stack, tracked in `sexmodUses` NBT).
 *
 * <b>Flow.</b> Right-click starts `sexmodAllieInUse` on the player's
 * entity data; [inventoryTick] advances the tick counter (client shows the
 * rub animation + ramping particles, server spawns the Allie at the lamp offset
 * at tick 95). [InteractionHandler] handles the right-click start (blocked
 * for player-girls / while an Allie holds the same lamp instance) and resets the
 * flag on logout. The lamp is also injected into dungeon/mineshaft loot tables.
 *
 * <b>Pitfall.</b> The `sexmodAllieInUse` flag lives on the *player's*
 * entity data (`getPersistentData()`) and gates the HUD/first-person render
 * ([ClientEvents.onPre]) — it must be reset on logout or the player's
 * HUD stays hidden after a disconnect.
 *
 * Port notes:
 * - The chest-loot injection (1.12 `onLootTableLoad`) lives in
 *   `LootTableHandler.AlliesLampLoot` and is NOT repeated here.
 * - Registration/model events are replaced by `ModItems`; the renderer is
 *   provided through [createGeoRenderer].
 * - Item NBT goes through `ItemNbt` (copy semantics): the incremented
 *   `sexmodUses` tag is written back before the Allie reads it, matching the
 *   1.12 shared-compound behaviour.
 * - `onUpdate` is [inventoryTick]; `addInformation` is [appendHoverText];
 *   `RenderGameOverlayEvent.Pre` is `RenderGuiEvent.Pre`.
 */
class AlliesLampItem : Item(Properties().stacksTo(1)), GeoItem {
    private val animationFactory: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    @JvmField
    var controller: AnimationController<AlliesLampItem>? = null

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        val created = AnimationController(this, "controller", 2) { event -> this.animationPredicate(event) }
        this.controller = created
        controllers.add(created)
    }

    /** CLIENT. */
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val tag = ItemNbt.getTag(stack)
        if (tag != null) {
            val uses = 3 - tag.getInt(USES_KEY)
            when (uses) {
                0 -> tooltip.add(Component.literal("no wishes left"))
                1 -> tooltip.add(Component.literal("1 wish left"))
                2 -> tooltip.add(Component.literal("2 wishes left"))
            }
        }
    }

    /** CLIENT. */
    fun animationPredicate(event: AnimationState<AlliesLampItem>): PlayState {
        val player = ClientHooks.localPlayer()!! // java-npe: Minecraft.player
        val entityData = player.persistentData
        val inUse = entityData.getBoolean(ALLIE_IN_USE_KEY)
        if (!inUse) {
            event.controller.forceAnimationReset()
            return PlayState.STOP
        } else {
            event.controller.setAnimation(RawAnimation.begin().thenPlayAndHold("animation.lamp.rub"))
            return PlayState.CONTINUE
        }
    }

    /** 1.12 `onUpdate`. */
    override fun inventoryTick(stack: ItemStack, world: Level, entity: Entity, slot: Int, selected: Boolean) {
        if (entity is Player) {
            val player = entity
            val entityData = entity.persistentData
            if (stack === player.mainHandItem || stack === player.offhandItem) {
                val inUse = entityData.getBoolean(ALLIE_IN_USE_KEY)
                val ticks = entityData.getInt(ALLIE_IN_USE_TICKS_KEY)
                if (inUse) {
                    entityData.putInt(ALLIE_IN_USE_TICKS_KEY, ticks + 1)
                    if (ticks > PARTICLE_START_TICK && ticks < SUMMON_TICK) {
                        var progress = ((ticks - PARTICLE_START_TICK).toFloat() / (SUMMON_TICK - PARTICLE_START_TICK)).toDouble()
                        progress = RotationHelper.easeInOutQuad(progress)
                        val offset = Vec3(0.0, player.eyeHeight * (1.0 - progress), 0.0)
                        WorldUtils.spawnParticles(
                            world, ParticleTypes.ENCHANTED_HIT, this.getLampOffset(player).add(offset),
                            (progress * 150.0).toInt(), progress * 0.75, progress,
                        )
                    }

                    if (ticks >= SUMMON_TICK) {
                        WorldUtils.spawnParticles(world, ParticleTypes.ENCHANTED_HIT, this.getLampOffset(player), 150, 0.75, 2.0)
                        entityData.putBoolean(ALLIE_IN_USE_KEY, false)
                        entityData.putInt(ALLIE_IN_USE_TICKS_KEY, 0)
                        if (world.isClientSide) {
                            HandlePlayerMovement.setMovementLock(false)
                        } else {
                            var tag = ItemNbt.getTag(stack)
                            if (tag == null) {
                                tag = CompoundTag()
                            }

                            tag.putInt(USES_KEY, tag.getInt(USES_KEY) + 1)
                            // 1.12 mutated the stack's own compound here (visible to hasLampItem below)
                            ItemNbt.setTag(stack, tag)
                            val allie = AllieEntity(player.level(), player.mainHandItem)
                            allie.setInteractionPlayerUUID(player.uuid)
                            val offset2 = this.getLampOffset(player)
                            allie.absMoveTo(offset2.x, offset2.y, offset2.z, player.yRot + 180.0f, player.xRot)
                            allie.setTargetPosition(allie.position())
                            allie.setYawRotation(player.yRot + 180.0f)
                            allie.setAnchored(true)
                            allie.setNoGravity(true)
                            allie.noPhysics = true
                            player.level().addFreshEntity(allie)
                            val pos = allie.blockPosition().offset(0, -1, 0)
                            if (allie.level().getBlockState(pos).block == Blocks.SAND) {
                                allie.setCurrentAction(Action.SUMMON_SAND)
                            } else {
                                allie.setCurrentAction(if (allie.hasLampItem()) Action.SUMMON else Action.SUMMON_NORMAL)
                            }
                        }
                    }
                }
            }
        }
    }

    fun getLampOffset(player: Player): Vec3 =
        player.position().add(VectorMath.rotateByYaw(Vec3(0.0, 0.0, 2.0), player.yHeadRot))

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = this.animationFactory

    override fun createGeoRenderer(consumer: Consumer<GeoRenderProvider>) {
        consumer.accept(object : GeoRenderProvider {
            private var renderer: AlliesLampRenderer? = null

            override fun getGeoItemRenderer(): BlockEntityWithoutLevelRenderer =
                renderer ?: AlliesLampRenderer().also { renderer = it }
        })
    }

    /**
     * Common event holder named by the contract. The only common 1.12 handler,
     * `onLootTableLoad`, moved to `LootTableHandler.AlliesLampLoot`, so nothing
     * is left. NOTE for integration: `EventBus.register` throws on an object
     * without `@SubscribeEvent` methods, so `ForgeEventHandler` must not register this.
     */
    object Events

    /** Client-only handlers (PORTING.md §2.3 rule E). */
    object ClientEvents {
        /** 1.12 `onPre(RenderGameOverlayEvent.Pre)`: hides the HUD while the lamp is rubbed. */
        @SubscribeEvent
        fun onPre(event: RenderGuiEvent.Pre) {
            val player = Minecraft.getInstance().player ?: return
            val entityData = player.persistentData
            if (entityData.getBoolean(ALLIE_IN_USE_KEY)) {
                event.isCanceled = true
            }
        }
    }

    class InteractionHandler {
        @SubscribeEvent
        fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
            event.entity.persistentData.putBoolean(ALLIE_IN_USE_KEY, false)
        }

        @SubscribeEvent
        fun onRightClickItem(event: PlayerInteractEvent.RightClickItem) {
            val player = event.entity
            val hand = event.hand
            val stack = player.getItemInHand(hand)
            if (!AbstractPlayerGirlEntity.isOwnerPlayer(player)) {
                if (!player.level().isClientSide || HandlePlayerMovement.isSneakingState()) {
                    if (!player.level().isClientSide) {
                        try {
                            for (girl in BaseGirlEntity.getGirlEntityList()) {
                                if (!girl.isRemoved && girl is AllieEntity) {
                                    val lampStack = girl.getEntityData().get(AllieEntity.LAMP_ITEM)
                                    if (stack === lampStack) {
                                        return
                                    }
                                }
                            }
                        } catch (cme: ConcurrentModificationException) {
                            // ignored: entity list mutated during iteration; retried on the next tick
                        }
                    }

                    if (stack.item === ALLIES_LAMP) {
                        val tag = ItemNbt.getTag(stack)
                        if (tag == null || tag.getInt(USES_KEY) < 3) {
                            val entityData = player.persistentData
                            val inUse = entityData.getBoolean(ALLIE_IN_USE_KEY)
                            if (!inUse) {
                                entityData.putBoolean(ALLIE_IN_USE_KEY, true)
                                entityData.putInt(ALLIE_IN_USE_TICKS_KEY, 0)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ALLIE_IN_USE_KEY: String = "sexmodAllieInUse"
        const val ALLIE_IN_USE_TICKS_KEY: String = "sexmodAllieInUseTicks"
        const val USES_KEY: String = "sexmodUses"
        const val ALLIE_ID_KEY: String = "sexmodAllieID"
        const val SUMMON_TICK: Int = 95
        const val PARTICLE_START_TICK: Int = 50
        const val PARTICLE_COUNT: Int = 150
        const val PARTICLE_SPREAD: Float = 0.75f

        @JvmStatic
        val ALLIES_LAMP: AlliesLampItem
            get() = ModItems.ALLIES_LAMP.get()
    }
}
