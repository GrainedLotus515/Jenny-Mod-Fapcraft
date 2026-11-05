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
- Animation rotations written as constant arithmetic (`"261.0538-360"`, `"360*2"`): GeckoLib 4.9.3's animation compression folds them to a constant after the degrees-to-radians step, so the bone got the value as radians (snaps and spasms in scenes). The 1,720 constant-only expressions in the animation files are now plain numbers; expressions that use Molang variables are unchanged.
- Procedural bone offsets (kobold eye spacing, Galath hair tilt, Manglelie skirt lift, head sway...) added to the bone's current value every frame. GL3 reset unanimated channels each tick; GL4 skips the reset for channels marked changed, and the hooks re-mark them each frame, so the offsets accumulated (kobold eyes drifting apart). `GirlModelBase.handleAnimations` clears the markers before each animation tick, restoring the GL3 reset.
- `Action.getActionTick` added the partial tick to a GL4 seek time that already contains it, so action progress jumped ahead within a tick and slipped back on the next one. The partial tick is no longer added twice.
- Worldgen: new chunks that unload before their neighbours load are parked and re-queued the next time they load, and the pending set is saved with the world, so no chunk loses its house/lair roll.
- `WorldUtils.getNearbyPlayers` now applies the entity tracker's range test (type tracking range, scaled, capped by view distance) instead of returning every player watching the chunk, so girl sounds and Galath chat no longer reach players too far away to see her.
- Girl screens rebuild their buttons every frame; `WidgetFocus` hands focus to the rebuilt button at the same index so Tab and the focus highlight work.
- Sound keyframes on the movement/eyes controllers: 1.12 ignored them silently; GL4 logs a warning each time, so every controller now has a no-op handler and the action controller gets the real one.
- Horny effect icon: 1.12 used icon index (0, 0) of the vanilla sheet (Speed). The client effect extension draws the vanilla Speed sprite.
- Inherited fixes: reskin browser "Remove" removes the selected applied pack (it always removed the first), and the wardrobe no longer prints debug messages in chat.
- Re-check follow-ups: the worldgen data is marked dirty whenever the pending set changes (vanilla writes saved data before `LevelEvent.Save`); `getNearbyPlayers` uses NeoForge's `ChunkMap.getPlayersWatching` (the tracker's real `seenBy` set); Galath hair and Manglelie skirt offsets are taken from the rest pose so render-layer redraws (which skip the animation pass) don't stack them.
- Scene camera: `PositionData` (NPC scenes) and `GirlCameraHelper` (player-girl scenes) pin the local player to the `boyCam` / `girlCam` bone for the length of each frame by writing the position and `xOld/yOld/zOld` (1.12 `lastTickPos`, which the 1.12 view was rendered from). The 1.21 camera interpolates `xo/yo/zo` instead, so every frame it slid from the player's real position toward the bone and snapped back on the next tick (measured in Ellie's scene: jumps of about 0.9 blocks, 20 times a second, which also made the girl look like she stuttered). Both handlers now pin and restore `xo/yo/zo` as well. With that fixed the per-frame bone path is smooth, so no change was needed in the animation controllers.
- "Render world last" passes: 1.12 `RenderWorldLastEvent` ran with depth writes on. `RenderLevelStageEvent` `AFTER_WEATHER` sits inside vanilla's weather block, which outside Fabulous graphics runs with `depthMask(false)`, and the entity render types do not switch the mask back on. Models drawn there (first-person goblin pick-up / shoulder / throw, the player-girl's own body, Galath's dragons, Manglelie POV) were only sorted by quad distance, so flat overlay quads lost to the surface right behind them: the goblin showed one eye, then only an iris, and no mouth during the first-person pick-up. `WorldLastDepthState` turns depth writes on before the mod's handlers of that stage and restores the state after them.
- Galath coin used on the owned Galath: the 1.12 source runs the summon branch for the de-summon timer too (two methods folded into one by the deobfuscation; the flag `sexmod:galath_coin_de_summoning_animation_time` is reset but never set), which spawned a second Galath in front of the player and dropped the old one ("she teleports back to the player"). `GalathCoinItem.tickDeSummon` restores the lost half from the death path: 1 s after the click she plays `GALATH_DE_SUMMON` in place with the particle burst while the client streams particles from her into the coin, and at 4 s she is removed with her Manglelie and the ownership, so the coin can summon her again. A click during a running de-summon no longer restarts the timer. The timing (1 s wind-up, removal at 4 s) mirrors the summon and is a reconstruction, not recovered code.
- Second draws of the same girl in one frame: GL4 `GeoModel.handleAnimations` returns early when the same instance is drawn again at the same frame time, skipping the controllers and `setCustomAnimations`. GL3 ran both for every draw, and the mod's extra passes depend on it: the first-person goblin pass (drawn with `setLocallyRegistered(true)`) hides the body and shows the `steve` arms, the normal entity pass does the opposite. With one goblin loaded the first-person pass kept the entity pass's flags, so the throw showed no arms during the wind-up, then two goblins, one of them hanging in front of the camera until the throw ended. `GirlModelBase.handleAnimations` now clears the manager's last update time first, so every draw runs the pass; the animation time does not advance, so controllers stay on the same frame and keyframe sounds do not repeat. (It only worked before when another girl using the same model was drawn in between.)
- "Name Tribe" screen: the port set `EditBox.isFocused = true` (1.12 `setFocused(true)`) and then asked the screen to focus the field. A 1.21 widget that already reports itself focused is skipped by the screen's focus call, so the field drew its cursor but the screen had no focused child and no key or character reached it until the field was clicked; "set" does nothing while the name is empty, so the screen looked dead. The focus now goes through `Screen.setInitialFocus()` only, which also keeps it on the field when the screen was opened from the keyboard.
- Ellie and Galath hitboxes: both were registered with the vanilla 0.6×1.8 box (1.12 never called `setSize` for them), but their models are taller. Ellie's head reaches 2.27 blocks and Galath's 2.09 (2.54 with horns), so the box ended at their chest, below their 1.9 eye height. Ellie is now 0.6×2.3 and Galath 0.6×2.1. One side effect: Ellie no longer fits through 2-block-high gaps when pathfinding.
- NeoForge range: the mod accepted only 21.1.265 and newer (the version it is built against), so packs on an older 21.1 build, such as ATM10 on 21.1.259, refused to load it. It now accepts any 21.1 build (`neo_version_range` in `gradle.properties`); GeckoLib 4.9.3 still requires 21.1.150 or newer. Two newer-only APIs were replaced: `LootTableLoadEvent.key` (now `event.name`) and `ChunkMap.getPlayersWatching` (now the tracker's `seenBy` set read directly, opened in `META-INF/accesstransformer.cfg`). It compiles against 21.1.1, 21.1.150 and 21.1.259, and a dedicated server on 21.1.259 starts and spawns the girls.
