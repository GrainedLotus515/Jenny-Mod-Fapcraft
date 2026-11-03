package com.trolmastercard.sexmod.registry

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import com.trolmastercard.sexmod.entity.AllieEntity
import com.trolmastercard.sexmod.entity.AlliePlayerEntity
import com.trolmastercard.sexmod.entity.BasicGirlEntity
import com.trolmastercard.sexmod.entity.BeeEntity
import com.trolmastercard.sexmod.entity.BeePlayerEntity
import com.trolmastercard.sexmod.entity.BiaEntity
import com.trolmastercard.sexmod.entity.BiaPlayerEntity
import com.trolmastercard.sexmod.entity.DragonEntity
import com.trolmastercard.sexmod.entity.EllieEntity
import com.trolmastercard.sexmod.entity.ElliePlayerEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.entity.GalathPlayerEntity
import com.trolmastercard.sexmod.entity.GoblinEntity
import com.trolmastercard.sexmod.entity.GoblinPlayerEntity
import com.trolmastercard.sexmod.entity.JennyEntity
import com.trolmastercard.sexmod.entity.JennyPlayerEntity
import com.trolmastercard.sexmod.entity.KoboldEggEntity
import com.trolmastercard.sexmod.entity.KoboldEntity
import com.trolmastercard.sexmod.entity.KoboldPlayerEntity
import com.trolmastercard.sexmod.entity.LunaEntity
import com.trolmastercard.sexmod.entity.LunaPlayerEntity
import com.trolmastercard.sexmod.entity.ManglelieEntity
import com.trolmastercard.sexmod.entity.SexEntity
import com.trolmastercard.sexmod.entity.SexSceneEntity
import com.trolmastercard.sexmod.entity.SlimeEntity
import com.trolmastercard.sexmod.entity.SlimePlayerEntity
import com.trolmastercard.sexmod.entity.WildSlimeEntity
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.SpawnPlacementTypes
import net.minecraft.world.entity.SpawnPlacements
import net.minecraft.world.level.levelgen.Heightmap
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

/**
 * Entity type registry (replaces 1.12 `SexModEntities.registerEntities`).
 *
 * Holder names are the UPPER_SNAKE form of the entity ID. Tracking ranges:
 * the 1.12 50-block range maps to `clientTrackingRange(4)`, the 100-block range
 * (player-form girls) to `clientTrackingRange(7)`; all use `updateInterval(1)`.
 * Player-form girls do not receive velocity updates (1.12 `sendVelocityUpdates = false`).
 *
 * Also handles [EntityAttributeCreationEvent] (every `createAttributes()`),
 * [RegisterSpawnPlacementsEvent] for the naturally spawning girls and
 * [RegisterCapabilitiesEvent] (`Capabilities.ItemHandler.ENTITY` -> `girl.inventory`
 * for every [AbstractGirlNpcEntity] type, replacing the 1.12 `getCapability` override).
 *
 * `KoboldEggProjectileEntity` is intentionally not registered (it was never
 * registered in 1.12 and has no spawn site; see PORTING.md §2.5).
 */
object ModEntities {
    @JvmField
    val ENTITY_TYPES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Main.MOD_ID)

    private fun <T : Entity> register(
        id: String,
        factory: EntityType.EntityFactory<T>,
        category: MobCategory,
        width: Float,
        height: Float,
        trackingRange: Int,
        receiveVelocityUpdates: Boolean,
    ): DeferredHolder<EntityType<*>, EntityType<T>> = ENTITY_TYPES.register(id, Supplier {
        EntityType.Builder.of(factory, category)
            .sized(width, height)
            .clientTrackingRange(trackingRange)
            .updateInterval(1)
            .setShouldReceiveVelocityUpdates(receiveVelocityUpdates)
            .build(Main.rl(id).toString())
    })

    // ---- NPC girls (1.12 registerSpawnEntity / registerEggEntity, 50 blocks) ----
    @JvmField val JENNY = register("jenny", { t, l -> JennyEntity(t, l) }, MobCategory.CREATURE, 0.49f, 1.95f, 4, true)
    @JvmField val ELLIE = register("ellie", { t, l -> EllieEntity(t, l) }, MobCategory.CREATURE, 0.6f, 1.8f, 4, true)
    @JvmField val SLIME = register("slime", { t, l -> SlimeEntity(t, l) }, MobCategory.CREATURE, 0.6f, 1.8f, 4, true)
    @JvmField val BIA = register("bia", { t, l -> BiaEntity(t, l) }, MobCategory.CREATURE, 0.49f, 1.65f, 4, true)
    @JvmField val BEE = register("bee", { t, l -> BeeEntity(t, l) }, MobCategory.CREATURE, 0.3f, 1.5f, 4, true)
    @JvmField val LUNA = register("luna", { t, l -> LunaEntity(t, l) }, MobCategory.CREATURE, 0.6f, 1.8f, 4, true)
    @JvmField val ALLIE = register("allie", { t, l -> AllieEntity(t, l) }, MobCategory.CREATURE, 0.5f, 1.0f, 4, true)
    @JvmField val KOBOLD = register("kobold", { t, l -> KoboldEntity(t, l) }, MobCategory.CREATURE, 0.5f, 0.99f, 4, true)
    @JvmField val KOBOLD_EGG = register("kobold_egg", { t, l -> KoboldEggEntity(t, l) }, MobCategory.MISC, 0.5f, 0.5f, 4, true)
    @JvmField val GOBLIN = register("goblin", { t, l -> GoblinEntity(t, l) }, MobCategory.CREATURE, 0.5f, 0.99f, 4, true)
    @JvmField val GALATH = register("galath", { t, l -> GalathEntity(t, l) }, MobCategory.CREATURE, 0.6f, 1.8f, 4, true)
    @JvmField val MANGLELIE = register("manglelie", { t, l -> ManglelieEntity(t, l) }, MobCategory.AMBIENT, 0.6f, 1.8f, 4, true)
    @JvmField val CUSTOM_MODEL = register("custom_model", { t, l -> SexSceneEntity(t, l) }, MobCategory.MISC, 0.6f, 1.8f, 4, true)

    // ---- player-form girls (1.12 registerEntity: 100 blocks, no velocity updates) ----
    @JvmField val PLAYER_JENNY = register("player_jenny", { t, l -> JennyPlayerEntity(t, l) }, MobCategory.MISC, 0.01f, 0.01f, 7, false)
    @JvmField val PLAYER_ELLIE = register("player_ellie", { t, l -> ElliePlayerEntity(t, l) }, MobCategory.MISC, 0.01f, 0.01f, 7, false)
    @JvmField val PLAYER_SLIME = register("player_slime", { t, l -> SlimePlayerEntity(t, l) }, MobCategory.MISC, 0.01f, 0.01f, 7, false)
    @JvmField val PLAYER_BIA = register("player_bia", { t, l -> BiaPlayerEntity(t, l) }, MobCategory.MISC, 0.01f, 0.01f, 7, false)
    @JvmField val PLAYER_BEE = register("player_bee", { t, l -> BeePlayerEntity(t, l) }, MobCategory.MISC, 0.01f, 0.01f, 7, false)
    @JvmField val PLAYER_ALLIE = register("player_allie", { t, l -> AlliePlayerEntity(t, l) }, MobCategory.MISC, 0.01f, 0.01f, 7, false)
    @JvmField val PLAYER_KOBOLD = register("player_kobold", { t, l -> KoboldPlayerEntity(t, l) }, MobCategory.MISC, 0.01f, 0.01f, 7, false)
    @JvmField val PLAYER_GOBLIN = register("player_goblin", { t, l -> GoblinPlayerEntity(t, l) }, MobCategory.MISC, 0.01f, 0.01f, 7, false)
    @JvmField val PLAYER_LUNA = register("player_luna", { t, l -> LunaPlayerEntity(t, l) }, MobCategory.MISC, 0.01f, 0.01f, 7, false)
    @JvmField val PLAYER_GALATH = register("player_galath", { t, l -> GalathPlayerEntity(t, l) }, MobCategory.MISC, 0.01f, 0.01f, 7, false)

    // ---- other entities (1.12 registerEggEntity, 50 blocks) ----
    @JvmField val FRIENDLY_SLIME = register("friendly_slime", { t, l -> WildSlimeEntity(t, l) }, MobCategory.CREATURE, 0.51f, 0.51f, 4, true)
    @JvmField val LUNA_HOOK = register("luna_hook", { t, l -> SexEntity(t, l) }, MobCategory.MISC, 0.25f, 0.25f, 4, true)
    @JvmField val ENERGY_BALL = register("energy_ball", { t, l -> DragonEntity(t, l) }, MobCategory.MISC, 0.5f, 0.5f, 4, true)
    @JvmField val PYROCINICAL = register("pyrocinical", { t, l -> BasicGirlEntity(t, l) }, MobCategory.AMBIENT, 0.6f, 1.8f, 4, true)

    @JvmStatic
    fun register(bus: IEventBus) {
        ENTITY_TYPES.register(bus)
        bus.addListener(EntityAttributeCreationEvent::class.java) { e -> onAttributes(e) }
        bus.addListener(RegisterSpawnPlacementsEvent::class.java) { e -> onSpawnPlacements(e) }
        bus.addListener(RegisterCapabilitiesEvent::class.java) { e -> onCapabilities(e) }
    }

    @JvmStatic
    fun onAttributes(event: EntityAttributeCreationEvent) {
        event.put(JENNY.get(), JennyEntity.createAttributes().build())
        event.put(ELLIE.get(), EllieEntity.createAttributes().build())
        event.put(SLIME.get(), SlimeEntity.createAttributes().build())
        event.put(BIA.get(), BiaEntity.createAttributes().build())
        event.put(BEE.get(), BeeEntity.createAttributes().build())
        event.put(LUNA.get(), LunaEntity.createAttributes().build())
        event.put(ALLIE.get(), AllieEntity.createAttributes().build())
        event.put(KOBOLD.get(), KoboldEntity.createAttributes().build())
        event.put(KOBOLD_EGG.get(), KoboldEggEntity.createAttributes().build())
        event.put(GOBLIN.get(), GoblinEntity.createAttributes().build())
        event.put(GALATH.get(), GalathEntity.createAttributes().build())
        event.put(MANGLELIE.get(), ManglelieEntity.createAttributes().build())
        event.put(CUSTOM_MODEL.get(), SexSceneEntity.createAttributes().build())
        event.put(PLAYER_JENNY.get(), JennyPlayerEntity.createAttributes().build())
        event.put(PLAYER_ELLIE.get(), ElliePlayerEntity.createAttributes().build())
        event.put(PLAYER_SLIME.get(), SlimePlayerEntity.createAttributes().build())
        event.put(PLAYER_BIA.get(), BiaPlayerEntity.createAttributes().build())
        event.put(PLAYER_BEE.get(), BeePlayerEntity.createAttributes().build())
        event.put(PLAYER_ALLIE.get(), AlliePlayerEntity.createAttributes().build())
        event.put(PLAYER_KOBOLD.get(), KoboldPlayerEntity.createAttributes().build())
        event.put(PLAYER_GOBLIN.get(), GoblinPlayerEntity.createAttributes().build())
        event.put(PLAYER_LUNA.get(), LunaPlayerEntity.createAttributes().build())
        event.put(PLAYER_GALATH.get(), GalathPlayerEntity.createAttributes().build())
        event.put(FRIENDLY_SLIME.get(), WildSlimeEntity.createAttributes().build())
        event.put(ENERGY_BALL.get(), DragonEntity.createAttributes().build())
        event.put(PYROCINICAL.get(), BasicGirlEntity.createAttributes().build())
    }

    /** Natural spawns (1.12 `EntityRegistry.addSpawn`); the biome JSON lives in `data/sexmod/neoforge/biome_modifier`. */
    @JvmStatic
    fun onSpawnPlacements(event: RegisterSpawnPlacementsEvent) {
        groundSpawn(event, SLIME.get())
        groundSpawn(event, BEE.get())
        groundSpawn(event, PYROCINICAL.get())
        groundSpawn(event, MANGLELIE.get())
    }

    private fun <T : Mob> groundSpawn(event: RegisterSpawnPlacementsEvent, type: EntityType<T>) {
        event.register(
            type,
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            SpawnPlacements.SpawnPredicate { t, level, reason, pos, random -> Mob.checkMobSpawnRules(t, level, reason, pos, random) },
            RegisterSpawnPlacementsEvent.Operation.REPLACE
        )
    }

    @JvmStatic
    fun onCapabilities(event: RegisterCapabilitiesEvent) {
        itemHandler(event, JENNY.get())
        itemHandler(event, ELLIE.get())
        itemHandler(event, BIA.get())
        itemHandler(event, LUNA.get())
        itemHandler(event, PLAYER_JENNY.get())
        itemHandler(event, PLAYER_ELLIE.get())
        itemHandler(event, PLAYER_SLIME.get())
        itemHandler(event, PLAYER_BIA.get())
        itemHandler(event, PLAYER_BEE.get())
        itemHandler(event, PLAYER_ALLIE.get())
        itemHandler(event, PLAYER_KOBOLD.get())
        itemHandler(event, PLAYER_GOBLIN.get())
        itemHandler(event, PLAYER_LUNA.get())
        itemHandler(event, PLAYER_GALATH.get())
    }

    private fun <T : AbstractGirlNpcEntity> itemHandler(event: RegisterCapabilitiesEvent, type: EntityType<T>) {
        event.registerEntity(Capabilities.ItemHandler.ENTITY, type) { girl, _ -> girl.inventory }
    }
}
