# Port notes (decisions made during the port)

Running log of contract deviations each slice reported. Read together with PORTING.md.

## S1a: entity framework

- `BaseGirlEntity.getCustomName()` → `getGirlCustomName()`. `onUpdate` → `tick()`, `setDead` → `remove(reason)`, `getLootTable` → `getDefaultLootTable()`.
- `updateAITasks()` is still a public open method; `customServerAiStep()` calls `super` then `updateAITasks()`.
- `entityDataManager` is a getter property; `pathNavigator` is a `@JvmField` set in the constructor.
- `renderCustomModelTransform(scene, entity, partialTicks)` lost the `Minecraft mc` parameter (common classes can't mention client types).
- Signature changes: `changeDataParameterFromClient(key, value: String?)`, `spawnParticlesAround(ParticleOptions, girl[, count])`, `findNearestStructureBlock` / `findBlocksInRadius` take `HashSet<ResourceKey<Biome>>?`, `getYawRotation(): Float`.
- Hoisted synced keys on `BaseGirlEntity`: `SHARED_STRING_119`, `SHARED_BLOCKPOS_120`, `SHARED_STRING_121`, `SHARED_FLOAT_122`, `SHARED_STRING_122`, `SHARED_BOOL_126` (old names kept as aliases; owners set defaults in their constructors).
- `jumpMovementFactor` field, returned from `getFlyingSpeed()`.
- `AbstractGirlNpcEntity`: no capability overrides; `ModEntities` registers `Capabilities.ItemHandler.ENTITY` → `inventory`.
- `AbstractPlayerGirlEntity`: `canBeCollidedWith` → `isPickable`, `getEntityBoundingBox` → `makeBoundingBox`, `playStepSound(BlockState)`, `getHandModel(): IVanillaModel?`, `playerMotionOffset` is a joml `Vector2f`, `OWNER_UUID` lives here. `updateEyeHeight()` is a no-op (PORT-GAP).
- `SexEntityPart(parent: Entity, ...)`; damage routes to `SexEntityPart.IEntityMultiPart.attackEntityFromPart(part, source, amount)` (GalathEntity implements it).
- `SuccubusDamageSource` uses damage type `sexmod:succubus` (needs `data/sexmod/damage_type/succubus.json`, message id `galath`).
- `GirlFollowAiBase.girl` is an open `val`. Goals tick every tick (`requiresUpdateEveryTick`).
- `LivingHurt`/`LivingAttack` → `LivingIncomingDamageEvent`; `LivingDamageEvent` → `LivingDamageEvent.Pre`.
- `PlayerGirlEvents.ClientEvents` holds the client halves.
- Bed targets match `#minecraft:beds`.
- PORT-GAPs: client push-out cancel in `GirlBedInteraction`; Punch knockback on girl arrows; `GirlAiBase.setMating` / `setIsWillingToMate` (dead code).
- Kotlin 2.4 emits no bytecode for default-value initializers (`= null`, `0`, `false`), so those fields survive assignment in `registerGoals` / `defineSynchedData`. Non-default initializers are still re-run (PORTING.md §2.2 is too strict).

## S1b: infrastructure

- Registries in `registry/*.kt`. `ModEntities` holders are UPPER_SNAKE entity ids (`JENNY`, `PLAYER_JENNY`, `KOBOLD_EGG`, `CUSTOM_MODEL`, `LUNA_HOOK`, `ENERGY_BALL`, ...). `ModItems`: `ALLIES_LAMP`, `DRAGON_STAFF`, `TRIBE_EGG`, `GALATH_COIN`, `EDITOR_WAND` (alias `NPC_EDITOR_WAND`), `KOBOLD_EGG_ITEM`, `LUNA_ROD`, `WINCHESTER_ITEM` (alias `WINCHESTER`), `<ID>_SPAWN_EGG`, `SPAWN_EGGS`. `ModBlocks.FIRE`, `ModEffects.HORNY_POTION` / `HORNY_POTION_TYPE`, `ModMenus.GIRL_EQUIPMENT` / `LUNA_EQUIPMENT` / `GIRL_CHEST`, `ModCreativeTabs.MAIN`, `ModSounds.HOLDERS`.
- `PacketHandler.networkWrapper`: `sendToServer(msg)`, `sendTo(msg, ServerPlayer)`, `sendToAll(msg)`, `sendToAllTracking(msg, Entity | TargetPoint)`, `sendToAllAround(msg, TargetPoint)`, `sendToDimension(msg, Int)`. Every packet registers as `X.TYPE` / `X.STREAM_CODEC` / `X.handle(p, ctx)`.
- `@SubscribeEvent` methods inside event `object`s are deliberately NOT `@JvmStatic`; the object instance is registered.
- Allies Lamp chest loot lives in `LootTableHandler.AlliesLampLoot`; `AlliesLampItem` must not add it again.
- No data components: wand state and kobold egg colour use `ItemNbt.getMeta` (custom model data). Item properties `sexmod:active` / `minecraft:cast` registered in `ClientProxy`.
- GeckoLib item renderers registered via `RegisterClientExtensionsEvent`.
- `BuildInfo` falls back to the mod container version. `setFullBrightLightmap` is a PORT-GAP no-op.
- Called against contract: `GuiHandler.openGui`, `ChestContainer(id, inv, buf)`, screens `(menu, inv, title)`, `Command*.register(dispatcher)`, `RenderHandler.register(event)`, `DownloadServerModelPacket(List<String>)`, the `ServerWhitelistManager.isGlobalRenderingDisabled()` method.

## S6: Kobold and Goblin

- Integrator TODO: register `ModEntities.KOBOLD_EGG_PROJECTILE` (MISC, 0.25×0.25) + `ThrownItemRenderer`.
- `GoblinEntity.openGoblinPadForPlayer(uuid)` (dropped the `mc` param; a temporary `(Any?, UUID?)` overload exists for `SexSceneKeyHandler`).
- Calls `GirlRendererBase.clearBoneColors()` and `AbstractPlayerKoblinGoboldRenderer.clearRenderCache()` directly (S2 must expose as `@JvmStatic`).
- `KoboldEntity` / `GoblinEntity` companions re-export `CURRENT_ACTION`, `ACTION_TARGET_POS`, `APPEARANCE_DNA`.
- Client tick handlers: `KoboldTribeEvents.ClientEvents`, `GoblinEventHandler.ClientEvents`, `GoblinCarryEvents.ClientEvents`.
- `TribeWorldSavedData` → `SavedData` named `tribes`.
- Flattening: egg colour via `ItemNbt.setMeta`; logs via `#minecraft:logs`; log→sapling map (default oak); mining drops = first loot drop.
- Goblin stolen item drops only on real removal (not chunk unload). Carried goblin players: render cancelled + drawn later instead of being marked dead.
- `EntityTeleportEvent.EnderEntity` for the kobold pearl. `spawnHealth` applied in constructor.
- PORT-GAP: reviving a carrier entity removed client-side.
