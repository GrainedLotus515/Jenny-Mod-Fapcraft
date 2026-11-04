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

## S4: Jenny, Ellie, Bia, Luna, Slime, Bee, Allie

- Integrator TODO: remove `bus.register(LunaRodItem.Events)` / `AlliesLampItem.Events` from `ForgeEventHandler` (empty objects throw).
- `BeeEntityBase` implements 1.21 `Container`; `getField`/`setField`/`getFieldCount` stay plain methods.
- Sound handlers use `I18n.get` (client path); server-reachable code uses a private `i18n()` that returns the key.
- Runtime-changing eye heights (Ellie, Ellie player, wild slime) cached + `refreshDimensions()`.
- Luna headpat reward is `ItemStack.EMPTY`; Lure level = fishing time reduction / 5.
- `SexEntity` (luna hook) sets its owner after construction and isn't saved to disk.
- Bee uses `FlyingMoveControl(this, 10, false)`. Two `HORNY_FLAG` keys kept separate.
- New API: `EllieEntity.eyeBedBlocked`, `ElliePlayerEntity.eyeCrouched`, `AllieWorldData.DATA_NAME/load/factory`.
- Known (kept from 1.12): Luna hook self-deletes on remote clients; `ElliePlayerEntity.updateAITasks` NPE when the owner is offline.

## S3: UI, input/camera, custom models, commands, worldgen

- Integrator TODO: add `io.github.realburst:unrar5j:v2.0.4` (implementation + jarJar) for `ReskinScanner` / `ReskinReader`.
- Damage types `sexmod:succubus` and `sexmod:galath` (message id `galath`); succubus in `#minecraft:bypasses_armor` / `#bypasses_invulnerability`.
- Biome modifiers for natural spawns: slime (swamp), bee (forest), pyrocinical + manglelie (nether).
- Worldgen: new overworld chunks queued on load and generated at overworld tick end; lairs and kobold spawns overworld-only. Seed reproduces 1.12's actual `>> 2 + 1L` (shift by 3). `forceSpawn` dropped.
- HUDs draw in `RenderGuiEvent.Post`.
- Custom models/reskins live in mod maps; S2 models must check `ModelResources.getCustomBakedModel(rl)`, `ReskinManager.getGeoOverride(rl)`, `ReskinManager.getAnimationOverride(rl)` first. Custom model ids lowercased.
- `DownloadServerModelPacket` has a leading request/chunk boolean; whitelist check moved to the handler.
- `GirlInventoryScreen.actionPerformed` / `onButtonClick` take a button id. `GirlScreenBase.renderEntityPreview` and `GirlCameraHelper.applyCameraTransform` gained 1.21 render params.
- Contract issues: `custom_model` registered at 0.6×1.8 but Java sizes it 0.1×0.1; `ServerWhitelistManager`, `UnknownScreen`, `CommandFuta` are COMMON.

## S5: Galath, Manglelie, Dragon, BasicGirl

- Integrator TODO: delete `DragonStaffItem.Events` registration in `ForgeEventHandler` (no handlers → bus throws).
- Revived dead renderer code: Galath/Manglelie `doRender*`, `getBoneWorldPos*`, `renderMasterbateEffect`, `getWingColor` are now overrides of `doRenderEntity`, `getBoneWorldPos`, `onBoneRenderStart`, `getAdditionalOverlayColor` (old names delegate). The deobf had lost their override names, so the dash, POV and wing passes never drew in 1.12-remap.
- POV/geometry statics take `PoseStack` + `MultiBufferSource`, pose at the 1.12 origin (`translateToPlayerOrigin`). `DynamicTrailRenderer` GL_POLYGON → TRIANGLE_FAN, camera-relative.
- `GalathEntity` multipart: `IEntityMultiPart`, `isMultipartEntity`, `getParts`, `setId` numbering parts after the parent.
- `ManglelieEntity.getYawRotation` inlines the threesome check (model class is client-only).
- `DragonEntity.shouldBeSaved() = false`. Explosions use `ExplosionInteraction.TNT`.
- PORT-GAPs: `RequestRidingPacket` chunk removal; Galath star ring line width; Manglelie fire renders while hidden; `SummonItemRenderer` dev-float branch hard-wired.
- Latent: `GirlModel.getItemStackForBone` reads NPC armour keys (crash if ever called for Galath/Manglelie). `BeeWorldData.load` / `GirlWorldData` never read back (check SavedData registration).

## S2: Rendering core

- Hook API for subclass renderers: `GirlRenderer<T : BaseGirlEntity>(ctx, model, shadowOffset)`; render state in `renderCtx` (`poseStack`, `bufferSource`, `buffer()`, `packedLight`, `packedOverlay`, `partialTick`, `switchTexture(rl)`, `restoreBuffer()`, `resetTexture()`); overridable `doRenderEntity(...)`, `vanillaRenderBase`, `renderModel`, `renderModelBuffer`, `renderRecursively`, `renderCustomBones`, `onBoneProcessing`, `renderCubeGeometry`, `emitQuad`, `renderHeldItem`, `getBoneWorldPos`, `getAdditionalOverlayColor`, `getBlacklistedBones`. Statics: `renderGirlTint`, `renderNameLabel`, `translateToPlayerOrigin`, `lerpedPosition`, `mc`. `MatrixHelper.applyBoneTransform(poseStack, bone)`.
- Dead 1.12-remap code wired up: `doRenderGoblin`, `doRenderKobold`, `doRenderBasicGirl`, `doRenderSexEntity` (SexEntityRenderer / BasicGirlRenderer drew nothing before).
- Girls use vanilla directional shading (1.12 had GL lighting off) and a translucent no-cull render type.
- Custom parts drawn with their own model texture (1.12 bound the kobold egg texture, treated as a bug).
- Name tags culled at 64 blocks (was 300). Overlay lines use the vanilla line shader. Player girls still show the fire overlay (gap).
- Runtime models go in `GirlModelBase.CUSTOM_BAKED_MODELS` / `CUSTOM_ANIMATIONS` (S3's reskin/model code must feed these).
- Integrator TODO: `ClientProxy` should listen to `RegisterEntitySpectatorShadersEvent` → `ShaderHelper.initOutlineShader(event, girlTypes)`.

## Integration (runtime pass)

Status of the Integrator TODOs / contract issues above:
- Done: `kobold_egg_projectile` + `ThrownItemRenderer`; dead `Events` registrations removed; unrar5j jarJar; temporary `openGoblinPadForPlayer(Any?, UUID?)` overload removed; `custom_model` sized 0.1×0.1 (1.12).
- SavedData (`GirlSavedData`, `GirlWorldData`, `BeeWorldData`, `AllieWorldData`, `TribeWorldSavedData`): loading always worked (`computeIfAbsent` on `LevelEvent.Load`), but nothing was ever written: `LevelEvent.Save` fires after `ServerLevel.saveLevelData()` flushed the data storage. The shells now override `isDirty() = true`. Verified: files written, restart reloads without errors.
- `GirlModelBase` model lookup order: `CUSTOM_BAKED_MODELS` → `ReskinManager.getGeoOverride` → `ModelResources.getCustomBakedModel`; animations: `CUSTOM_ANIMATIONS` → `ReskinManager.getAnimationOverride`.
- `GirlModel.getItemStackForBone` returns EMPTY for non-`AbstractGirlNpcEntity` girls.
- `RegisterEntitySpectatorShadersEvent`: deliberately NOT wired. 1.12 `initOutlineShader()` had no caller and `shaders/post/outline.json` never existed; registering would point spectators at a missing shader.
- `ServerWhitelistManager` / `UnknownScreen` / `CommandFuta` COMMON: left as is (no server class-loading problem observed).
- GeckoLib 4 rejects unknown Molang variables at parse time (GL3 auto-registered them as 0). `MolangVars.registerAll()` (called from `ClientProxy.construct`) pre-registers `size`, `scale`, `pitch`, `yaw`, `armpitch`, `armyaw` and the Blockbench junk identifiers (`nan`, `linear`, easing names) as 0. Unparseable keyframe strings (`"."`, `"50+."`, `"NaN-1*"`, `` "`-1" ``, trailing `size * `) were fixed in the JSON.
- Structures: 1.12 structure NBT relied on block entities for flower pot contents, skull type, bed and banner colours; DFU cannot flatten those in templates (flower pots threw, skulls became `%%FILTER_ME%%`). The palettes were pre-flattened offline from the block entity data.
- `GoblinPersistence.read` returns early when the tag has no `model` (1.21 `/summon` loads an empty tag before `finalizeSpawn`).
- `GalathRenderer.renderWingMesh` guards `is IGalath` (Manglelie reaches it via the revived POV pass and crashed the client).
- Girl "disappearances" in the first smoke test were suffocation at y=-60 inside deepslate, not a despawn bug; `removeWhenFarAway` = false matches 1.12. Allie (NULL action) and wild Manglelie without a mommy discard themselves at tick 1, as in 1.12.
- Dev: `./gradlew runClient -PquickPlay=localhost:25565` joins a server directly.

## Interaction pass

- Plain screens (interaction menu, bee dialogue, clothing, Galath, tribe name, structure command, girl picker) must close with `Minecraft.setScreen(null)`. `player.closeContainer()` sends a container-close packet, and in 1.21 that closes the menu the server has just opened (the equipment and chest buttons appeared to do nothing).
- `DragonBreathParticle`: get the particle atlas from the `TextureManager`. `Minecraft.getTextureAtlas(LOCATION_PARTICLES)` throws.
- Verified in the client: Jenny strip, then a full blowjob scene through to the natural end (reset clears the anchor and the interacting player). R-Shift mid-scene does the full exit. Equipment menu: items move in and the helmet renders. Interaction menus open for Jenny, Ellie, Bia, Luna, Kobold, Galath and the tamed Bee (untamed Bee/Slime/Goblin have no menu, same as 1.12). The horny potion opens the girl picker and the player becomes Jenny. Allies Lamp summons Allie (blocked while the player is in a girl form, same as 1.12). Galath coin summons an owned Galath. Dragon staff fires. `/futa`, `/setmodelcode`, `/whitelistserver` and `/locatenearestgoblinlair` respond.
- Open: after the Allies Lamp summon followed by R-Shift, the client stayed dimmed and chat would not open until a relog. The Allie wish menu did not appear during the ~10 s I waited. The thin vertical line in the sky was seen at some camera positions and could not be reproduced.

## Interaction pass 2

- `GIRL_ID` / `KOBOLD_NAME` were defined with random defaults. 1.21 only syncs entity data that differs from its default, so client and server had different girl IDs and every client->server packet for a code-spawned girl (Allie, scene girls) was dropped. This was the cause of the "Allie wish does nothing" and "stuck after R-Shift" reports. The server now re-sets both values after definition.
- `/kill` in 1.21 deals `generic_kill`, not `fell_out_of_world`. Entities that only accepted out-of-world damage in 1.12 (Galath death cancel, Manglelie, Pyrocinical, energy ball, owned goblins, custom model) now accept both.
- Worldgen: queued new chunks are generated only once their 3x3 neighbourhood is loaded, at most 8 per tick, so house placement no longer forces synchronous chunk generation.
- Verified to the natural end: Jenny (blowjob), Ellie (carry), Bia (talk -> anal at bed), Luna (payment -> bed -> cowgirl), Kobold (oral), Goblin (pick up -> G pad -> nelson), Galath (coin -> cowgirl), Allie (wish, deepthroat). Tribe egg, tribe naming and dragon staff tribe GUI work. Fresh-world houses generate (jenny/ellie/luna/bia seen in the generation data).
- Faithful quirks: the goblin nelson scene leaves the camera in third-person front (1.12 never resets it). The goblin G pad is hold-and-release.
- The thin vertical line in the sky (centre column, looking roughly along +z) appears with every mod client handler disabled and no entities around, so it is not a mod render pass. Left as is.
- Not verified: a player-form (potion) scene with an NPC needs a second player.

## Bug fixes

- `ClientServerCheck`: the 1.21 client main thread is "Render thread" (1.12: "Client thread"), so the name test missed it and every call fell through to the warning branch, spamming the log. "render" now counts as client. The 1.21 `entity-deserializer` thread (server entity loading) and any thread on a dedicated server count as server.
- Dragon Staff on a bed or chest: 1.12 cancelled the block click with FAIL and the client still fell through to the item right-click (tribe GUI with the "mark" quadrant). 1.21 stops at FAIL, so the cancel now returns PASS.
