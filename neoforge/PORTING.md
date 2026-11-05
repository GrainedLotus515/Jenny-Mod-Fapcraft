# PORTING.md: Fapcraft (`sexmod`), Forge 1.12.2 / GeckoLib 3 to NeoForge 1.21.1 / Kotlin / GeckoLib 4.9.3

This document is the contract between the agents doing the port. Every rule in it applies to every slice. If a rule and the Java code disagree, follow the rule and write down the deviation in your slice's final summary.

- Old source: `src/main/java/com/trolmastercard/sexmod/`. There are 371 `.java` files and about 67.5k lines. The brief said 381; the actual count is 371.
- New source: `neoforge/src/main/kotlin/com/trolmastercard/sexmod/`, in the same sub-packages, with one `.kt` file per Java file and the same file and class name.
- Toolchain: NeoForge 21.1.265, Kotlin for Forge 5.12 (`modLoader="kotlinforforge"`), Kotlin 2.4, GeckoLib 4.9.3 for neoforge-1.21.1, Mojang mappings with Parchment 2024.11.17, Java 21.
- Behaviour docs: `DOCUMENTATION.md`, `docs/systems/*.md`, `docs/characters/*.md`. Read the character doc for every girl you port.

---

## 1. Architecture of the old mod (summary)

### 1.1 Subsystems

| Subsystem | Main classes |
|---|---|
| Bootstrap | `Main` (`@Mod`, `@SidedProxy`, FML lifecycle, `setConfigs()` reads `config/sexmod.json` by character offset, `onWorldClosed` clears all statics), `proxy/CommonProxy`, `proxy/ClientProxy` (key bindings, preload world, client commands, particle) |
| Girl entity framework | `entity/BaseGirlEntity` plus static-helper "split-outs" (`GirlSyncedState`, `GirlNbtIO`, `GirlLookup`, `GirlChat`, `GirlSoundBroadcast`, `GirlBoneMath`, `GirlCustomParts`, `GirlPartCodec`, `GirlAnimationVariants`, `GirlPlayerPlacement`, `GirlBedPlacement`, `GirlBlockScan`), `Action` (scene state machine), `NpcType` (girl catalogue), `entity/ai/*` (goals) |
| Characters | Jenny, Ellie, Bia, Luna ("Cat"), Slime and WildSlime, Bee, Allie, Kobold (tribes, mining, eggs), Goblin (queen, throw, carry), Galath (flight, combat, energy balls, riding), Manglelie, Dragon (energy ball), BasicGirl ("pyrocinical"). Large ones are split into `XxxAnimations`, `XxxInteractions` and similar static helpers |
| Rendering (GeckoLib 3) | `client/model/*` (AnimatedGeoModel subclasses), `client/renderer/*` (`GirlRenderer` is the 1044-line core and `GirlRendererBase` adds per-bone tint), `util/Player*Renderer` (player-form renderers), item renderers (`GeoItemRenderer` used as TEISR), trail and ribbon renderers |
| GUI | `client/gui/*` (screens, HUDs, 3 containers + `GuiHandler`), `reskin/*` (custom skin browser) |
| Custom models | `ServerWhitelistManager`, `ModelArchive`, `ModelCfgParser`, `ModelResources`, `ReskinManager` (injects geo and animations into `GeckoLibCache` at runtime), `DownloadServerModelPacket`, `UploadModelStringPacket`, `SexSceneEntity` (`custom_model`) |
| Networking | `networking/PacketHandler` (SimpleNetworkWrapper `"sexmodchannel"`, 53 registrations, 46 classes) |
| Persistence | 6 `WorldSavedData` (`GirlSavedData`, `GirlWorldData`, `BeeWorldData`, `AllieWorldData`, `ConfigWorldGenHandler`, `KoboldManager.TribeWorldSavedData`), player `getEntityData()` NBT, item `getTagCompound()` |
| Worldgen | `worldgen/ConfigWorldGenHandler` (`IWorldGenerator` plus biome rules) and `GirlHouseGenerator` (places `structures/*.nbt` templates) |
| Commands | server: `locatenearestgoblinlair`, `reloadcustommodels` (op 2). Client (`IClientCommand`): `whitelistserver`, `setmodelcode`, `futa` |
| Keybinds | `ClientProxy.keyBindings[0..2]`: LWJGL2 codes 34 = G "Interact with your goblin", 76 = NUMPAD5 "open character customisation menu" (`docs/gui.md` says L, which is wrong; LWJGL2 76 is KEY_NUMPAD5), 54 = RSHIFT "Leave sex scene". Category "Sex mod" |
| Events | `util/ForgeEventHandler.registerAll(boolean client)` registers about 50 handler instances on `MinecraftForge.EVENT_BUS` |

### 1.2 Entity class hierarchy

```
EntityCreature
└─ BaseGirlEntity (IAnimatable)                       ── synced keys 99..110
   ├─ AbstractGirlNpcEntity  (inventory, armor keys 111..117, ITEM_HANDLER cap)
   │  ├─ JennyEntity, EllieEntity, BiaEntity, LunaEntity          (NPC girls)
   │  └─ AbstractPlayerGirlEntity  (OWNER_UUID 118; "player-form" girls: the
   │     │                          horny-potion transformation, owned by a player)
   │     ├─ JennyPlayerEntity, ElliePlayerEntity, BiaPlayerEntity, LunaPlayerEntity,
   │     │  SlimePlayerEntity, BeePlayerEntity, AlliePlayerEntity, GalathPlayerEntity
   │     └─ AbstractKoboldPlayerEntity (keys 119..121)
   │        ├─ KoboldPlayerEntity  (122)
   │        └─ GoblinPlayerEntity  (122, 126)
   ├─ AbstractNpcOnlyEntity (keys 119..121, model-code DNA)
   │  ├─ KoboldEntity (122..129, IInventory)
   │  └─ GoblinEntity (122..126)
   ├─ BeeEntityBase (IInventory, 111) └─ BeeEntity (112)
   ├─ AllieEntity (111), SlimeEntity (111..113), GalathEntity (111..122, multipart),
   │  ManglelieEntity (111..115)
Other entities: SexSceneEntity (EntityLivingBase, IAnimatable, "custom_model"),
  KoboldEggEntity (EntityLivingBase, IAnimatable), KoboldEggProjectileEntity (EntityEnderPearl),
  WildSlimeEntity / DragonEntity / BasicGirlEntity (EntityLiving), SexEntity (Entity, Luna's hook),
  SexEntityPart (MultiPartEntityPart). CummyEntity is NOT an entity; it is an event handler.
```

The old code used explicit DataParameter IDs (`createKey(...).getSerializer().createKey(N)`) on purpose, so that sibling classes share a slot. For example, `AbstractNpcOnlyEntity.CURRENT_ACTION` (119) is the same slot as `AbstractKoboldPlayerEntity.SKIN_COLOR_NAME` (119), which lets `PlayerKoboldRenderer` read `KoboldEntity.CURRENT_ACTION` on a `KoboldPlayerEntity`. 1.21 cannot do this; §2.6 explains the replacement.

### 1.3 How GeckoLib 3 is used

- Entities implement `IAnimatable` and own an `AnimationFactory`. On the client, the constructor creates three `AnimationController`s (`action` with transition 0, `movement` 5, `eyes` 10). They all share one predicate, `animationPredicate(AnimationEvent)`, which switches on `controller.getName()` and `getCurrentAction()`.
- Scenes are driven by sound keyframes. Every girl's `registerControllers` installs an `ISoundListener`; keyframe names such as `paymentDone`, `bjiMSG3` and `xxx_cumDone` send chat lines, play sounds and call `setCurrentAction`/`resetCameraAndPhysics`. This is the primary scene progression, so it must be ported exactly.
- Controller fields are mutated directly: `transitionLengthTicks` (set every frame from `Action.transitionTick`), `tickOffset = 0` (`ResetControllerPacket` syncs all observers), `setAnimationSpeed`, `clearAnimationCache`, and `getCurrentAnimation().animationName` / `.animationLength`.
- Models (`GirlModelBase` → `GirlModel<T>` → `XxxNpcModel`):
  - `GirlModelBase` swaps in `GirlAnimationProcessor` (a name-to-bone cache) by reflection.
  - `GirlModel` picks the geo file by `OUTFIT_INDEX` from `getModelLocations()`. In `setLivingAnimations` it hides and shows bone groups (armor vs nude, the `IGirlModelInfo` lists), Steve vs Alex arms, and the `steve` bone. It also turns `neck`/`head` for head-look and pins anchored girls.
- Molang: `GeckoLibCache.getInstance().parser.setValue("size"|"pitch"|"armpitch"|"armyaw"|"yaw", v)`.
- Renderers: `GirlRenderer` overrides the whole `doRender` and duplicates GeckoLib's pipeline:
  - Anchored girls are drawn at `TARGET_POS` with their yaw pinned.
  - `renderRecursively` is rewritten per bone. It draws the held item on `weapon` and the payment item on `itemRenderer`, forces opacity on genital bones, hides `Head2` in first person, applies armor-material tint (packed into alpha), hides custom-part bones, and emits cube vertices with per-bone colour (`GirlRendererBase`, `BodyParts`).
  - A second pass draws the `steve` bone with the interaction player's tinted skin (`SkinTextureCache`).
  - After rendering, `applyCameraBone` computes bone positions (`GirlBoneMath`, using a manual MatrixStack) for camera and effects.
  - `GeoLayerRenderer` subclass: `GirlLayerRenderer`. Item renderers: `AlliesLampRenderer`, `DragonStaffRenderer`, `GalathCoinRenderer`, `KoboldEggItemRenderer`, `SummonItemRenderer`.

### 1.4 Networking

`SimpleNetworkWrapper` with sequential IDs. Most packets are client→server and look up girls by UUID (`BaseGirlEntity.getServerGirlEntity`). Handlers run on the main thread via `addScheduledTask`. The send API is `PacketHandler.networkWrapper.sendToServer / sendTo / sendToAllTracking(entity | TargetPoint) / sendToAllAround`. The full list is in §2.7.

### 1.5 Action state machine

`Action` is an enum of about 170 values with fields `transitionTick`, `hasPlayer`, `autoBlink`, pitch clamps, `flipGirlYaw`, `useBoyCam`, `hideNameTag`, and optionally `length` + `followUp`. It is stored as `name()` in the `CUR_ACTION` string key.

Transitions come from three sources:
1. Sound keyframes (client) → `setCurrentAction` → `ChangeDataParameterPacket` → server.
2. Server timed follow-ups (`tickFollowUpTransitions`).
3. Input: sneak = next action, jump with a full meter = cum action.

A scene starts with `PlayerActionPacket`/GUI → `ChangeDataParameterPacket("animationFollowUp")` → `KoboldStatePacket` → lerp to target → anchored → `startActionFromHandState()`. A scene ends at the `*_cumDone` keyframe → `resetCameraAndPhysics()` → single-argument `ResetGirlPacket` (full reset).

### 1.6 Sided code

`@SideOnly(CLIENT)` is spread through entity classes. About 50 common files call `Minecraft.getMinecraft()` directly, usually inside sound listeners and predicates. Client-only event types are subscribed from classes registered on the common path. `SexWorldClient` is a fake `WorldClient` used only to preload models at startup; entity code checks `world instanceof SexWorldClient`.

### 1.7 Persistence, GUI, worldgen, config

- **GUI**: `IGuiHandler` IDs 0 and 1 locate the girl by block position. Containers (`ChestContainer`, `GirlInventoryContainer`, `GirlInventoryContainer2`, `GirlInventorySlot`) live in `client/gui` but are common classes. HUDs are `Gui` subclasses drawn in `RenderGameOverlayEvent`.
- **Worldgen**: `GameRegistry.registerWorldGenerator`. Per-chunk biome rules place structure templates and remember them in `ConfigWorldGenHandler` saved data.
- **Config**: `config/sexmod.json`, hand-parsed.
- **Persistence**: see the table in §1.1.

---

## 2. Cross-slice conventions (mandatory)

### 2.1 Files, names, visibility

1. **Same package, same class name, same file name.** `entity/JennyEntity.java` becomes `entity/JennyEntity.kt`. Nested Java classes stay nested with the same names: a static nested class becomes a Kotlin nested `class`, and an inner (non-static) class becomes an `inner class`.
2. **Keep every mod-defined member name**: methods, fields, constants, enum constants, parameter order. Java getters and setters stay functions (`getGirlId()`, `setCurrentAction(a)`). Do not turn mod-defined members into Kotlin properties.
3. **Java fields** become Kotlin properties with the same name. All non-private mutable fields in classes extending MC, GeckoLib or NeoForge classes are declared `@JvmField var`. This prevents accessor clashes with inherited getters and keeps field access cheap. Constants: `const val` for primitives and String, otherwise `@JvmField val`.
4. **Visibility.** Java `private` stays `private`. Java `protected` and package-private members become **public**, because Kotlin `protected` does not grant same-package access. The exception is overrides of vanilla, NeoForge or GeckoLib methods, which keep their inherited visibility.
5. **Statics.**
   - A class whose members are all static (`final class X { private X(){} ... }`) becomes a Kotlin `object X`.
   - A class with both static and instance members puts the statics in a `companion object`.
   - Put `@JvmStatic` on every function in a companion or object, and `@JvmField` on every non-const property. This is uniform and cheap, and it keeps call sites as `X.foo()`.
6. **Overloads stay overloads.** Do not merge Java overloads into one function with default arguments; subclasses in other slices override them.
7. **Enums stay enums** with the same constants and fields (`@JvmField val` / `@JvmField var` for mutable ones such as `Action.length` and `ticksPlaying`). `name()` becomes `.name`, `ordinal()` becomes `.ordinal`, `values()`/`valueOf()` are unchanged.
8. **Interfaces** stay interfaces, with default methods as Kotlin bodies.
9. **Never use `data class`** for a ported class unless the Java overrode `equals`/`hashCode`.
10. **Unavoidable renames.** If a mod method now clashes with an inherited 1.21 member:
    - Same meaning and compatible signature: add `override`.
    - Final, or an incompatible return type: rename by inserting `Girl`, e.g. `getCustomName(): String` becomes `getGirlCustomName()`.
    - Every rename must be listed in your slice summary.

    Known renames:

    | Java | Kotlin | Reason |
    |---|---|---|
    | `BaseGirlEntity.getCustomName(): String` | `getGirlCustomName()` | `Entity.getCustomName(): Component` |
    | `getLootTable(): ResourceLocation` (all girls) | `override fun getDefaultLootTable(): ResourceKey<LootTable>` | `Mob.getLootTable` is final |
    | `canDespawn()` | `override fun removeWhenFarAway(d: Double)` | API change |
    | `getEyeHeight()` overrides | `getDefaultDimensions(pose)` returning `.withEyeHeight(h)` | final in 1.21 |
    | `getEntityBoundingBox()` override | `override fun makeBoundingBox(): AABB` | `getBoundingBox` is final |
    | `setDead()` override | `override fun remove(reason: RemovalReason)` (call `super.remove`) | API change; callers use `discard()` |
    | `playSound(SoundEvent)` in `BaseGirlEntity` / `AbstractPlayerGirlEntity` | add `override` (keep the Java body) | collides with `Entity.playSound(SoundEvent)` |

### 2.2 Kotlin rules for a faithful translation

- **Port line by line.** Keep the Java control flow, ordering, magic numbers, float/double distinctions and comments that explain behaviour. Keep the KDoc of every class: convert the Javadoc and keep the "Pitfall" notes.
- **No stubbing.** A `TODO()` or an empty body is allowed only when the 1.21 API truly has no equivalent. Mark it with `// PORT-GAP: <reason>` and list it in your summary.
- **Null safety.**
  - A Java field that can be null becomes `T?`. Values from MC getters that can return null become `T?` (for example `level.getPlayerByUUID`, `level.getEntity(id)`, `getVehicle()`, `getTarget()`, `Minecraft.player`, `Minecraft.level`, `Minecraft.screen`, `getEffect(...)`, `getCapability(...)`).
  - Use `?.`, `?:`, early `return`, or copy into a local `val` for smart casts.
  - `!!` is allowed only where the Java would also throw an NPE at that point and the crash is intended. Add a `// java-npe` comment.
  - Treat platform types from vanilla as non-null unless they are annotated `@Nullable` or listed above.
- **Super-constructor callbacks.** `defineSynchedData` and `registerGoals` run inside the Entity/Mob super constructor, before Kotlin property initializers. A Kotlin initializer (even `= null`) re-assigns the field afterwards. Therefore:
  - Do not assign instance fields inside those methods. Fields that Java assigned there (for example `wanderGoal`, `watchClosestGirlGoal`, `pathNavigator`, `entityDataManager`) must be `lateinit var` or be moved into `init {}`.
  - In 1.21, `Mob` calls `registerGoals()` only when `!level.isClientSide`. Any client-side read of a goal field must check `::field.isInitialized`.
  - Declare fields before the `init {}` blocks that use them (Kotlin runs initializers in declaration order).
- **Operators and literals.**
  - Java `==` on objects is `===` in Kotlin; Java `.equals()` is `==`. Java string `==` may be ported as `==`.
  - Java `switch` fall-through must be reproduced explicitly; Kotlin `when` has no fall-through. Watch `return` inside nested switches in the sound listeners.
  - `++x` / `x++` inside expressions must be expanded. Bit operators become `and`/`or`/`xor`/`inv()`/`shl`/`shr`/`ushr`.
  - Numeric conversions are explicit (`.toFloat()`, `.toInt()`). Java `(int)` on a float or double truncates toward zero, and so does `.toInt()`. Use `java.lang.Math` functions to keep exact semantics.
- **Language features.** `synchronized` becomes `synchronized(lock){}` or `@Synchronized`; `volatile` becomes `@Volatile`; varargs become `vararg`; Java arrays become `Array<T>` / `IntArray`.
- **Raw types.** Raw generic types become star projections (`AnimationState<*>`, `List<*>`). Unchecked casts get `@Suppress("UNCHECKED_CAST")`.
- **Common helpers.** Add `Main.rl(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath("sexmod", path)` and use it everywhere instead of `new ResourceLocation("sexmod", ...)`.

### 2.3 Client/server separation (the dedicated server must start)

There is no `@SideOnly` and no `DistExecutor`. A class is either **CLIENT-ONLY** or **COMMON**.

- **CLIENT-ONLY classes**: everything that extends, implements or declares client types (`Screen`, `EntityRenderer`, `GeoEntityRenderer`, `GeoModel` subclasses, `GeoRenderLayer`, `Particle`, `KeyMapping` holders, HUDs, `reskin/*`). Concretely, every class in slices S2 and S3 except those listed as COMMON below, plus the renderers and models in S5. They keep their original package; `util/PlayerJennyRenderer` stays in `util`.
- **COMMON classes in client-looking packages**: `client/gui/ChestContainer`, `GirlInventoryContainer`, `GirlInventoryContainer2`, `GirlInventorySlot`, `GuiHandler`; `client/model/GirlAnimationController`; `client/SexWorldClient`; and `client/model/api/IVanillaModel`. `IVanillaModel` is only an interface, but implementations are client-only.

Rules for COMMON classes (entities, items, blocks, packets, saved data, common event handlers):

- **Rule A.** Never mention a client-only type anywhere in the class: no field types, parameter or return types, locals, casts, `is` checks, or lambda bodies. Never call `Minecraft.getInstance()` directly. The JVM verifier can load a type mentioned in any method of the class, including lambda bodies compiled into it, and that crashes a dedicated server.
- **Rule B.** You may call `@JvmStatic` functions and read or write `@JvmField` statics of client-only classes, but only if their signatures use only common types and the call runs only on the client. Client-only paths are: animation predicates, sound and keyframe handlers, `if (level().isClientSide)` blocks, and client-bound packet handlers. Examples: `HornyMeterHud.showHornyMeter()` and `HandlePlayerMovement.isJumping`.
- **Rule C.** Anything that needs client types goes into a nested `private object Client { ... }` in the same file. A nested object compiles to its own class and is loaded only when first used. Example:

  ```kotlin
  class JennyPlayerEntity : AbstractPlayerGirlEntity { ...
      // in the sound handler (client-only path):
      "paymentMSG2" -> { val prefix = "<" + Client.localPlayerName() + "> " ... }
      private object Client {
          fun localPlayerName(): String = Minecraft.getInstance().player?.gameProfile?.name ?: ""
      }
  }
  ```
- **Rule D.** S1 provides `com.trolmastercard.sexmod.client.ClientHooks` (CLIENT-ONLY `object`; all members `@JvmStatic` and common-typed) for the most frequent needs:
  - `localPlayer(): Player?`, `localPlayerUUID(): UUID?`, `localPlayerName(): String`
  - `clientLevel(): Level?`, `partialTick(): Float`
  - `thirdPersonView(): Int` and `setThirdPersonView(mode: Int)` (0 = first, 1 = back, 2 = front, the 1.12 semantics, mapped to `CameraType`)
  - `isJumpKeyDown(): Boolean`, `isSneakKeyDown(): Boolean`
  - `isScreenOpen(): Boolean`, `closeScreen()`
  - `sessionProfileId(): UUID`
  - `displayChat(text: String)`
  - `getEntityById(id: Int): Entity?`
- **Rule E (event handlers).** A handler instance registered on the common path must not subscribe client-only events (`net.neoforged.neoforge.client.event.*`, which now includes `ClientTickEvent`). Move those methods into a nested `object ClientEvents` in the same handler class; S1 registers it from the client path (see the list in §2.10).
- `@SideOnly(Side.CLIENT)` annotations are deleted. The method stays, but its body must follow rules A–C.
- `SexWorldClient` (the preload world) is removed. S1 replaces the class with a COMMON `object SexWorldClient { @JvmStatic fun isPreloadWorld(level: Level?): Boolean = false }`.
  - Every `x instanceof SexWorldClient` becomes `SexWorldClient.isPreloadWorld(x)`.
  - `ClientProxy.IS_PRELOADING` stays as a `@JvmField var` that is always `false`.
  - `ClientNetHandlerOverride` and `SexNetworkManager` are not ported.
- `ClientServerCheck.getInstance()` stays and uses `ServerLifecycleHooks.getCurrentServer()?.isSameThread`.
- The integration pass must run `./gradlew runServer` and reach "Done". Any `NoClassDefFoundError` or `VerifyError` for `net/minecraft/client/...` is a bug in the slice that owns the class.

### 2.4 1.12 to 1.21 API mapping (the APIs this codebase uses most)

Type renames:

| 1.12 | 1.21 (Mojmap) |
|---|---|
| `World` / `WorldServer` / `WorldClient` | `Level` / `ServerLevel` / `ClientLevel` |
| `EntityPlayer` / `EntityPlayerMP` / `EntityPlayerSP` | `Player` / `ServerPlayer` / `LocalPlayer` |
| `EntityLivingBase` / `EntityLiving` / `EntityCreature` | `LivingEntity` (abstract: implement `getArmorSlots`, `getItemBySlot`, `setItemSlot`, `getMainArm`) / `Mob` / `PathfinderMob` |
| `EntityEnderPearl`, `ItemFishingRod`, `BlockFire` | `ThrownEnderpearl`, `FishingRodItem`, `FireBlock` |
| `MultiPartEntityPart` / `IEntityMultiPart` | `net.neoforged.neoforge.entity.PartEntity<T>`; override `isMultipartEntity()=true`, `getParts()`, and `setId()` to number parts like `EnderDragon` |
| `Vec3d`, `AxisAlignedBB`, `RayTraceResult` | `Vec3`, `AABB`, `HitResult` / `EntityHitResult` / `BlockHitResult` |
| `EnumFacing`, `EnumHand`, `IBlockState`, `MathHelper` | `Direction`, `InteractionHand`, `BlockState`, `Mth` |
| `NBTTagCompound` / `NBTTagList` | `CompoundTag` / `ListTag` |
| `DataParameter` / `EntityDataManager` / `DataSerializers.VARINT` / `OPTIONAL_UNIQUE_ID` (Guava `Optional`) | `EntityDataAccessor` / `SynchedEntityData` / `EntityDataSerializers.INT` / `OPTIONAL_UUID` (`java.util.Optional`) |
| `Potion` / `PotionEffect` / `PotionType` | `MobEffect` (use `Holder<MobEffect>`) / `MobEffectInstance` / `Potion` |
| `SoundCategory`, `TextFormatting`, `ITextComponent`, `TextComponentString` / `Translation` | `SoundSource`, `ChatFormatting`, `Component`, `Component.literal` / `Component.translatable` |
| `IInventory`, `InventoryPlayer`, `Container` | `Container`, `Inventory`, `AbstractContainerMenu` |
| `IItemHandler`, `ItemStackHandler`, `SlotItemHandler`, `CapabilityItemHandler` | the same names in `net.neoforged.neoforge.items.*`; entity capability `Capabilities.ItemHandler.ENTITY` |
| `GuiScreen` / `GuiContainer` / `GuiButton` / `GuiListExtended` / `Gui` | `Screen` / `AbstractContainerScreen` / `Button.builder(...).bounds(...).build()` / `ObjectSelectionList` or `ContainerObjectSelectionList` / a plain class drawing with `GuiGraphics` |
| `FontRenderer`, `KeyBinding`, `ModelBase` / `ModelRenderer`, `Render<T>` / `RenderLiving` | `Font`, `KeyMapping`, `Model` / `ModelPart` (build via `MeshDefinition` / `LayerDefinition.create(...).bakeRoot()`), `EntityRenderer<T>` / `MobRenderer` |
| `ItemCameraTransforms.TransformType` | `ItemDisplayContext` |
| `Template` / `TemplateManager` / `PlacementSettings` | `StructureTemplate` / `StructureTemplateManager` / `StructurePlaceSettings` |
| `javax.vecmath.Vector3f` / `Vector4f` / `Matrix4f` | `org.joml.Vector3f` / `Vector4f` / `Matrix4f`. vecmath `m03`/`m13`/`m23` (row, col) is joml `m30()`/`m31()`/`m32()` (col, row) |
| `FMLCommonHandler...getMinecraftServerInstance()` | `ServerLifecycleHooks.getCurrentServer()` |
| `ByteBuf` + `ByteBufUtils` | `FriendlyByteBuf` / `RegistryFriendlyByteBuf` (`writeUtf`, `writeUUID`, `writeBlockPos`, `writeNbt`, `ItemStack.OPTIONAL_STREAM_CODEC`) |

Member and override mapping (entities):

| 1.12 | 1.21 |
|---|---|
| `world`, `world.isRemote` | `level()`, `level().isClientSide` |
| `posX` / `prevPosX` / `lastTickPosX` | `getX()` / `xo` / `xOld` (same for Y and Z) |
| `motionX` (60 uses) | `deltaMovement` (`setDeltaMovement(x, y, z)`); keep the mod's own `setVelocity(...)` helpers |
| `rotationYaw` / `prevRotationYaw` / `rotationPitch` / `prevRotationPitch` | `getYRot()`/`setYRot()`, `yRotO`, `getXRot()`/`setXRot()`, `xRotO` |
| `renderYawOffset` / `prevRenderYawOffset` / `rotationYawHead` / `prevRotationYawHead` | `yBodyRot` / `yBodyRotO` / `yHeadRot` / `yHeadRotO` |
| `ticksExisted`, `isDead`, `setDead()`, `onGround` | `tickCount`, `isRemoved` / `!isAlive`, `discard()`, `onGround()` |
| `getPositionVector()`, `getPosition()`, `getLookVec()` | `position()`, `blockPosition()`, `getLookAngle()` |
| `getRidingEntity()`, `isRiding()`, `dismountRidingEntity()` | `getVehicle()`, `isPassenger()`, `stopRiding()` |
| `setPositionAndRotation(...)` / `setPositionAndUpdate(...)` / `setPositionAndRotationDirect(...,steps,b)` | `absMoveTo(...)` / `teleportTo(...)` / `lerpTo(x,y,z,yRot,xRot,steps)` |
| `getUniqueID()` / `getPersistentID()` | `getUUID()` |
| `player.getName()` (String) | `player.gameProfile.name` (or `name.string` for display) |
| `getEntityData()` (player custom NBT) | `getPersistentData()`. Keep the same keys. No data attachment is needed |
| `getDataManager()` / `entityDataManager` field | `entityData` (inside the hierarchy) / `getEntityData()`. `BaseGirlEntity` keeps `val entityDataManager: SynchedEntityData get() = entityData` |
| `entityInit()` + `dataManager.register(K, v)` | `override fun defineSynchedData(builder: SynchedEntityData.Builder)` + `builder.define(K, v)` |
| `onUpdate()` / `onLivingUpdate()` / `updateAITasks()` | `tick()` / `aiStep()` / `customServerAiStep()` |
| `writeEntityToNBT` / `readEntityFromNBT` (27 uses) | `addAdditionalSaveData(tag)` / `readAdditionalSaveData(tag)` |
| `processInteract(player, hand): Boolean` | `mobInteract(player, hand): InteractionResult` (true becomes `InteractionResult.sidedSuccess(level().isClientSide)`, false becomes `PASS`; called on both sides as before) |
| `attackEntityFrom(src, amt)` | `hurt(src, amt)` |
| `applyEntityAttributes()` | companion `@JvmStatic fun createAttributes(): AttributeSupplier.Builder` (§2.5) |
| `initEntityAI()`, `tasks.addTask(p, g)`, `targetTasks`, `tasks.removeTask(g)` | `registerGoals()`, `goalSelector.addGoal(p, g)`, `targetSelector`, `goalSelector.removeGoal(g)` |
| `EntityAIBase`: `shouldExecute` / `shouldContinueExecuting` / `startExecuting` / `resetTask` / `updateTask` / `setMutexBits(n)` | `Goal`: `canUse` / `canContinueToUse` / `start` / `stop` / `tick` / `setFlags(EnumSet)` (1 = MOVE, 2 = LOOK, 4 = JUMP) |
| `EntityAISwimming`, `EntityAITempt(e,s,false,set)`, `EntityAIWanderAvoidWater`, `EntityAIWatchClosest2`, `EntityAIAvoidEntity`, `EntityAINearestAttackableTarget` | `FloatGoal`, `TemptGoal(e, s, Ingredient.of(...items), false)`, `WaterAvoidingRandomStrollGoal`, `InteractGoal`, `AvoidEntityGoal`, `NearestAttackableTargetGoal` |
| `getNavigator()`, `tryMoveToXYZ`, `clearPath`, `noPath`, `Path.getCurrentPathIndex` / `getCurrentPathLength` / `getPathPointFromIndex` | `getNavigation()`, `moveTo(x,y,z,speed)`, `stop()`, `isDone()`, `getNextNodeIndex()` / `getNodeCount()` / `getNode(i)` |
| `setSize(w, h)` | sizes go in `EntityType.Builder.sized` (§2.5). For runtime resize, override `getDefaultDimensions(pose)` and call `refreshDimensions()` |
| `isImmuneToFire = true` | `override fun fireImmune() = true` |
| `getEntityAttribute(SharedMonsterAttributes.X).setBaseValue(v)` | `getAttribute(Attributes.X)?.baseValue = v` |
| `world.getPlayerEntityByUUID(u)` (97 uses), `world.spawnEntity(e)` | `level().getPlayerByUUID(u)`, `level().addFreshEntity(e)` |
| `world.playSound(null, x,y,z, s, cat, v, p)` | `level().playSound(null, x,y,z, s, SoundSource.X, v, p)` |
| `world.spawnParticle(EnumParticleTypes.X, ...)` | client: `level().addParticle(ParticleTypes.Y, ...)`; server: `(level() as ServerLevel).sendParticles(...)`. Use the `ParticleCompat` table (§2.12) |
| `world.setBlockState(p, s, f)`, `getBlockState` | `setBlock(p, s, f)`, `getBlockState` |
| `player.sendMessage(c)` / `sendStatusMessage(c, true)` | `sendSystemMessage(c)` / `displayClientMessage(c, true)` |
| `entity.dimension` (int) | `WorldUtils.getDimensionId(level())`: 0 = overworld, -1 = nether, 1 = end, otherwise `dimension().location().hashCode()`. Inverse: `WorldUtils.getLevelById(server, id)` |
| `DamageSource.causePlayerDamage(p)`, `GENERIC`, `getTrueSource()`, `getImmediateSource()` | `damageSources().playerAttack(p)`, `.generic()`, `source.entity`, `source.directEntity`. Custom sources (`GalathDamageSource`, `SuccubusDamageSource`) take a `Holder<DamageType>` from `data/sexmod/damage_type/*.json` |
| Item `onItemRightClick` / `onItemUse` / `itemInteractionForEntity` / `addInformation` / `onUpdate` | `use` / `useOn` / `interactLivingEntity` / `appendHoverText(stack, ctx, list, flag)` / `inventoryTick` |
| `stack.getMetadata()` / `setItemDamage(meta)` used as a variant | `ItemNbt.getMeta(stack)` / `ItemNbt.setMeta(stack, v)` (stored in `DataComponents.CUSTOM_MODEL_DATA`, so item model `overrides` can use `custom_model_data`) |
| `a.getItem()==b.getItem() && a.getMetadata()==b.getMetadata()` | `ItemStack.isSameItem(a, b)` (after the Flattening, metadata is item identity) |
| `stack.getTagCompound()` / `setTagCompound(t)` / `hasTagCompound()` | `ItemNbt.getTag(stack): CompoundTag?` (copy) / `ItemNbt.setTag(stack, t)` / `ItemNbt.hasTag(stack)`, using `DataComponents.CUSTOM_DATA`. **Re-set after mutation.** Keep NBT keys (`sexmodUses`, `user`, ...) |
| `stack.writeToNBT` / `new ItemStack(nbt)` | `stack.saveOptional(level().registryAccess())` / `ItemStack.parseOptional(registryAccess, tag)` |
| `ItemStackHandler.serializeNBT()` | `serializeNBT(registryAccess)` |
| NBT setters/getters | `setInteger`/`getInteger` → `putInt`/`getInt`; `setUniqueId`/`getUniqueId`/`hasUniqueId` → `putUUID`/`getUUID`/`hasUUID`; `hasKey` → `contains`; `getTagList(n, t)` → `getList(n, t)`; `appendTag` → `add` |
| `I18n.format(k, args)` | client: `I18n.get(k, *args)`; for messages sent to players: `Component.translatable(k, *args)` |
| `LootTableList.register(rl)` | `ResourceKey.create(Registries.LOOT_TABLE, rl)` |
| `Biomes.X` (sets of `Biome`) | `ResourceKey<Biome>` via `level.getBiome(pos).is(key)`. `PLAINS`→`PLAINS`, `FOREST`/`FOREST_HILLS`→`FOREST`, `BIRCH_FOREST`→`BIRCH_FOREST`, `MUTATED_BIRCH_FOREST`→`OLD_GROWTH_BIRCH_FOREST`, `ROOFED_FOREST`→`DARK_FOREST`, `TAIGA`→`TAIGA`, `COLD_TAIGA`→`SNOWY_TAIGA`, `REDWOOD_TAIGA`→`OLD_GROWTH_PINE_TAIGA`, `SWAMPLAND`/`MUTATED_SWAMPLAND`→`SWAMP`, `BEACH`→`BEACH`, `STONE_BEACH`→`STONY_SHORE`, `RIVER`→`RIVER`, `OCEAN`→`OCEAN`, `DEEP_OCEAN`→`DEEP_OCEAN`, `HELL`→`NETHER_WASTES` |
| Flattened blocks and items (wool, planks, logs, red flower, stained, beds, dyes by meta) | separate 1.21 items and blocks. Map through `DyeColor`/wool ID tables (`EyeAndKoboldColor.getColorByWoolId` keeps its int API). Red flower meta 0 is `Items.POPPY` |

Client (inside CLIENT-ONLY classes or `object Client`):

| 1.12 | 1.21 |
|---|---|
| `Minecraft.getMinecraft()`, `mc.world`, `mc.gameSettings`, `mc.renderEngine`, `mc.fontRenderer`, `mc.getRenderManager()` | `Minecraft.getInstance()`, `mc.level`, `mc.options`, `mc.textureManager`, `mc.font`, `mc.entityRenderDispatcher` |
| `mc.displayGuiScreen(s)`, `mc.currentScreen`, `mc.addScheduledTask(r)` | `mc.setScreen(s)`, `mc.screen`, `mc.execute(r)` |
| `gameSettings.thirdPersonView` (int) | `options.cameraType` / `options.setCameraType(CameraType.entries[i])` |
| `mc.getRenderPartialTicks()` | `mc.timer.getGameTimeDeltaPartialTick(true)` |
| `mc.getSession().getProfile().getId()` | `mc.user.profileId` |
| `KeyBinding.isPressed()` / `isKeyDown()` / `getKeyCode()` | `consumeClick()` / `isDown()` / `key.value` (or `matches(keyCode, scanCode)`) |
| `Keyboard.getEventKey()`, `Mouse.getEventDWheel()` | `Screen.keyPressed(keyCode, scanCode, mods)`, `mouseScrolled(x, y, sx, sy)` |
| `mc.entityRenderer.loadEntityShader(null)` | `mc.gameRenderer.checkEntityPostEffect(null)` |
| `ObfuscationReflectionHelper.getPrivateValue(C, o, "srgOrMcp")` | `ObfuscationReflectionHelper.getPrivateValue(C, o, "mojmapName")`. Production runs on Mojang names, e.g. `ItemInHandRenderer.mainHandHeight` / `oMainHandHeight` |

Event mapping (game bus = `NeoForge.EVENT_BUS`):

| 1.12 | 1.21 NeoForge |
|---|---|
| `TickEvent.ClientTickEvent` / `ServerTickEvent` / `PlayerTickEvent` with `phase` START/END | `ClientTickEvent.Pre`/`.Post`, `ServerTickEvent.Pre`/`.Post`, `PlayerTickEvent.Pre`/`.Post` |
| `RenderTickEvent` | `RenderFrameEvent.Pre`/`.Post` |
| `RenderWorldLastEvent` | `RenderLevelStageEvent` with stage `AFTER_WEATHER`. Use `event.poseStack`, subtract the camera position, partial tick from `event.partialTick.getGameTimeDeltaPartialTick(false)` |
| `RenderGameOverlayEvent.Pre`/`Post` | `RenderGuiEvent.Pre`/`.Post` (`event.guiGraphics`) |
| `RenderHandEvent` / `RenderSpecificHandEvent`, `RenderPlayerEvent.Pre` | `RenderHandEvent`, `RenderPlayerEvent.Pre` |
| `EntityViewRenderEvent.CameraSetup` | `ViewportEvent.ComputeCameraAngles` |
| `InputUpdateEvent` | `MovementInputUpdateEvent` |
| `MouseEvent` (cancel look) | `CalculatePlayerTurnEvent` (set sensitivity to -1/3 to freeze); `InputEvent.MouseButton.Pre` / `MouseScrollingEvent` for clicks and wheel |
| `KeyInputEvent` | `InputEvent.Key` |
| `ClientChatEvent` | `ClientChatEvent` (client package) |
| `GuiOpenEvent`, `InitGuiEvent` + `ActionPerformedEvent` | `ScreenEvent.Opening`, `ScreenEvent.Init.Post` + `event.addListener(Button.builder(label){...}.build())` |
| `FMLNetworkEvent.ClientConnectedToServerEvent` / `ClientDisconnectionFromServerEvent` | `ClientPlayerNetworkEvent.LoggingIn` / `LoggingOut` |
| `LivingUpdateEvent` | `EntityTickEvent.Pre` (filter `LivingEntity`) |
| `LivingHurtEvent`, `LivingAttackEvent` | `LivingIncomingDamageEvent` (`setAmount`, `setCanceled`) |
| `LivingDamageEvent` | `LivingDamageEvent.Pre` (`newDamage`) |
| `LivingDeathEvent`, `LivingHealEvent`, `AttackEntityEvent`, `EntityMountEvent`, `LootTableLoadEvent` | same names |
| `EntityJoinWorldEvent`, `WorldEvent.Load`/`Save`/`Unload` | `EntityJoinLevelEvent`, `LevelEvent.Load`/`Save`/`Unload` |
| `PlayerSleepInBedEvent`, `PlayerWakeUpEvent` | `CanPlayerSleepEvent`, `PlayerWakeUpEvent` |
| `PlayerEvent.PlayerLoggedIn`/`Out`, `PlayerRespawnEvent`, `PlayerChangedDimensionEvent` | the same nested classes in `PlayerEvent` |
| `BlockEvent.BreakEvent`, `BlockEvent.PlaceEvent` | `BlockEvent.BreakEvent`, `BlockEvent.EntityPlaceEvent` |
| `PlayerInteractEvent.*` (RightClickBlock, RightClickItem, EntityInteract, EntityInteractSpecific, LeftClick*) | same |
| `EnderTeleportEvent` | `EntityTeleportEvent.EnderPearl` |
| `ProjectileImpactEvent.Arrow` | `ProjectileImpactEvent` (filter `AbstractArrow`) |
| `LivingSpawnEvent.CheckSpawn` | `MobSpawnEvent.PositionCheck` |
| `GetCollisionBoxesEvent` | delete (the handler body is empty) |
| `PlayerSPPushOutOfBlocksEvent` | no equivalent; `// PORT-GAP` (integration may add a mixin) |
| `FMLServerStartingEvent` commands / `FMLServerStoppedEvent` | `RegisterCommandsEvent` / `ServerStoppedEvent` |

Other event-bus facts:
- `@SubscribeEvent` methods on registered **instances** keep working: `NeoForge.EVENT_BUS.register(PlayerIds())`.
- `register()` **throws** if the object has no `@SubscribeEvent` methods. Never register an empty handler; Winchester is the known case.
- Cancellation is `event.isCanceled` / `event.setCanceled(true)`.

### 2.5 Entities, registries, attributes

Entity constructors mirror the Java constructors one to one as **secondary constructors**, with no primary constructor:

```kotlin
class JennyEntity : AbstractGirlNpcEntity, IEllie, IBeddableSexGirl {
    /** Registry factory (used by ModEntities). */
    constructor(type: EntityType<out JennyEntity>, level: Level) : super(type, level) {
        // body of Java JennyEntity(World) minus setSize (size lives in ModEntities)
    }
    /** Kept for Java call sites and reflection (NpcType / ClothingScreen use getConstructor(Level)). */
    constructor(level: Level) : this(ModEntities.JENNY.get(), level)

    companion object {
        @JvmField val yFlag: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(JennyEntity::class.java, EntityDataSerializers.BOOLEAN)
        @JvmStatic fun createAttributes(): AttributeSupplier.Builder =
            AbstractGirlNpcEntity.createAttributes() /* .add(...) as in applyEntityAttributes */
    }
    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder); builder.define(yFlag, false)
    }
}
```

- Abstract classes expose `protected constructor(type: EntityType<out X>, level: Level)` plus any extra Java constructors with `(type, level, ...)` prepended. `AbstractPlayerGirlEntity` has `(type, level)` and `(type, level, uuid: UUID)`.
- Every concrete player girl has a public `constructor(level: Level, uuid: UUID)`, because `UpdatePlayerModelPacket` reflects on `getConstructor(Level::class.java, UUID::class.java)`.
- Every concrete NPC girl has a public `constructor(level: Level)`.
- Extra Java constructors such as `DragonEntity(World, GalathEntity, Vec3d)` become `constructor(level, galath, direction) : this(level) { ... }`.
- `NpcType.npcClass` / `playerClass` stay `Class<out ...>`.
- Every concrete `LivingEntity` subclass has a companion `@JvmStatic fun createAttributes(): AttributeSupplier.Builder`, even if it only delegates to its parent. Companions are not inherited, so call the parent explicitly: `BaseGirlEntity.createAttributes()`.
  - Base: `PathfinderMob.createMobAttributes().add(MAX_HEALTH, 20.0).add(MOVEMENT_SPEED, 0.5).add(FOLLOW_RANGE, 30.0)`.
  - If Java registered an extra attribute (for example `ATTACK_DAMAGE` via `getAttributeMap().registerAttribute`), `.add` it.
  - Non-Mob living entities use `LivingEntity.createLivingAttributes()`.

**Registries (S1 owns all of them).** One Kotlin `object` per registry type in `com.trolmastercard.sexmod.registry`, each with a `@JvmStatic fun register(bus: IEventBus)` called from `Main`:

- **`ModEntities`**: `DeferredRegister.create(Registries.ENTITY_TYPE, "sexmod")`. Holder names are the UPPER_SNAKE form of the ID. Factories are lambdas `{ t, l -> JennyEntity(t, l) }`. It also handles `EntityAttributeCreationEvent`, `RegisterSpawnPlacementsEvent` (`ON_GROUND`, `MOTION_BLOCKING_NO_LEAVES`, `Mob::checkMobSpawnRules`) and `RegisterCapabilitiesEvent` (`Capabilities.ItemHandler.ENTITY` → `girl.inventory` for every `AbstractGirlNpcEntity` type; replaces `getCapability` overrides).

  Tracking ranges: 50 blocks → `clientTrackingRange(4)`, 100 blocks → `clientTrackingRange(7)`, all with `updateInterval(1)`.

  | id | class | MobCategory | size w×h | tracking | spawn egg (bg, fg) |
  |---|---|---|---|---|---|
  | jenny | JennyEntity | CREATURE | 0.49×1.95 | 4 | 3286592, 12655237 |
  | ellie | EllieEntity | CREATURE | 0.6×1.8 | 4 | 1447446, 9961472 |
  | slime | SlimeEntity | CREATURE | 0.6×1.8 | 4 | 13167780, 8244330 |
  | bia | BiaEntity | CREATURE | 0.49×1.65 | 4 | 7488816, 7254603 |
  | bee | BeeEntity | CREATURE | 0.3×1.5 | 4 | 16701032, 4400155 |
  | luna | LunaEntity | CREATURE | 0.6×1.8 | 4 | 7881787, 7940422 |
  | allie | AllieEntity | CREATURE | 0.5×1.0 | 4 | (none) |
  | kobold | KoboldEntity | CREATURE | 0.5×0.99 | 4 | (none) |
  | kobold_egg | KoboldEggEntity | MISC | 0.5×0.5 | 4 | (none) |
  | goblin | GoblinEntity | CREATURE | 0.5×0.99 | 4 | 39424, 19456 |
  | galath | GalathEntity | CREATURE | 0.6×1.8 | 4 | 16711680, 16711680 |
  | manglelie | ManglelieEntity | AMBIENT | 0.6×1.8 | 4 | 16382457, 8485574 |
  | custom_model | SexSceneEntity | MISC | 0.6×1.8 | 4 | (none) |
  | player_jenny, player_ellie, player_slime, player_bia, player_bee, player_allie, player_kobold, player_goblin, player_luna, player_galath | XxxPlayerEntity | MISC | 0.01×0.01 | 7, `setShouldReceiveVelocityUpdates(false)` | (none) |
  | friendly_slime | WildSlimeEntity | CREATURE | 0.51×0.51 (dynamic) | 4 | (none) |
  | luna_hook | SexEntity | MISC | 0.25×0.25 | 4 | (none) |
  | energy_ball | DragonEntity | MISC | 0.5×0.5 | 4 | (none) |
  | pyrocinical | BasicGirlEntity | AMBIENT | 0.6×1.8 | 4 | (none) |

  `KoboldEggProjectileEntity` was never registered in 1.12 and has no spawn site. Port the class and its `EnderTeleportHandler`, but do not register it unless S6 finds a spawn site; if it does, register it as `kobold_egg_projectile`, MISC, 0.25×0.25.

  Natural spawns (S3 writes the JSON as `data/sexmod/neoforge/biome_modifier/*.json`, type `neoforge:add_spawns`):
  - slime: weight 10, 1–1, CREATURE, `minecraft:swamp`
  - bee: weight 5, CREATURE, `minecraft:forest`
  - pyrocinical: weight 3, AMBIENT, `#minecraft:is_nether`
  - manglelie: weight 5, AMBIENT, `#minecraft:is_nether`

- **`ModItems`** (`DeferredRegister.createItems`). Item classes have a **no-argument constructor** that passes their own `Item.Properties` to `super` (stack size and durability come from the Java constructor, e.g. LunaRod `durability(64)`).
  - IDs: `allies_lamp`, `dragon_staff`, `tribe_egg`, `galath_coin`, `npc_editor_wand`, `kobold_egg_item`, `luna_rod`, `winchester`.
  - `winchester` was never registered in 1.12, but 1.21 items cannot be constructed unregistered. Register it and leave it out of the creative tab.
  - Spawn eggs: `<id>_spawn_egg` as `DeferredSpawnEggItem` for the 9 entities with colours above.
  - The Java singletons stay accessible from a companion: `@JvmStatic val ALLIES_LAMP: AlliesLampItem get() = ModItems.ALLIES_LAMP.get()`, and the same for `DRAGON_STAFF`, `TRIBE_EGG`, `GALATH_COIN`, `EDITOR_WAND`, `KOBOLD_EGG_ITEM`, `LUNA_ROD`, `WINCHESTER_ITEM`.
- **`ModBlocks`**: `fire` → `SexFireBlock()`, with no BlockItem (same as 1.12). `SexFireBlock.FIRE` is a companion getter.
- **`ModSounds`**: registers every element of every `SoundHandler` array.
  - `SoundHandler` creates its arrays eagerly as `Array<SoundEvent>` via `SoundEvent.createVariableRangeEvent(rl)`.
  - Name rule (load-bearing): `field.lowercase().replace('_','.')` + "." + (part[2] if there are more than 2 parts, else part[1]) + index. Example: `GIRLS_JENNY_GIGGLE[3]` → `girls.jenny.giggle.giggle3`.
  - `ModSounds` does `SOUND_EVENTS.register(path) { existingInstance }`, so the identities of `SoundHandler.MISC_PLOB[0]` and the others are preserved.
  - `SoundHandler.registerSounds()` remains as a no-op kept for call sites.
- **`ModEffects`**: `MOB_EFFECTS` holds `horny_potion` → `HornyPotion()`, a MobEffect with category BENEFICIAL and colour 16736968. `POTIONS` holds `horny_potion` → `Potion(MobEffectInstance(HORNY_POTION, 3600), MobEffectInstance(MobEffects.CONFUSION, 200, 1))`. The brewing recipe goes in `RegisterBrewingRecipesEvent`: `addMix(Potions.MUNDANE, Items.POPPY, HORNY_POTION_TYPE)`. `HornyPotion.HORNY_POTION: Holder<MobEffect>` and `HornyPotion.POTION_TYPE: Holder<Potion>` are companion getters.
- **`ModMenus`** (`Registries.MENU`, `IMenuTypeExtension.create { id, inv, buf -> X(id, inv, buf) }`):
  - `girl_equipment` → `ChestContainer`
  - `luna_equipment` → `GirlInventoryContainer2`
  - `girl_chest` → `GirlInventoryContainer`
- **`ModCreativeTabs`**: `main` (title "Fapcraft", icon allies_lamp) with all items except winchester, plus spawn eggs. Also adds spawn eggs to vanilla `SPAWN_EGGS`.
- **`ModAttachments` / `ModDataComponents`**: not needed. Player NBT uses `getPersistentData()` and item NBT uses `CUSTOM_DATA` / `CUSTOM_MODEL_DATA` through `util/ItemNbt`. Do not create them unless integration needs them.

### 2.6 Synced entity data (replacing explicit IDs)

1.21 IDs are dense, per class, and assigned by `SynchedEntityData.defineId(OwnerClass::class.java, serializer)` in static init. Explicit IDs are impossible.

- Every key becomes `@JvmField val NAME: EntityDataAccessor<T> = SynchedEntityData.defineId(DeclaringClass::class.java, ...)` in the **companion of the class that registers it** (not the class literal the Java passed). It is defined in that class's `defineSynchedData` with the Java default.
- Keys must be defined in companions, never in a separate `object`, so that JVM static-init order follows the class hierarchy.
- **Hoisted (aliased) keys.** The following sibling aliases were real in 1.12. They are defined once in the `BaseGirlEntity` companion, defined for every girl in `BaseGirlEntity.defineSynchedData` with a neutral default, and the old names become aliases (`@JvmField val CURRENT_ACTION = BaseGirlEntity.SHARED_STRING_119`). The owning subclass sets its Java default in its constructor via `entityData.set(...)`.

  | Shared key (BaseGirlEntity) | Aliases |
  |---|---|
  | `SHARED_STRING_119` | `AbstractNpcOnlyEntity.CURRENT_ACTION`, `AbstractKoboldPlayerEntity.SKIN_COLOR_NAME` |
  | `SHARED_BLOCKPOS_120` | `AbstractNpcOnlyEntity.ACTION_TARGET_POS`, `AbstractKoboldPlayerEntity.EYE_COLOR` |
  | `SHARED_STRING_121` | `AbstractNpcOnlyEntity.APPEARANCE_DNA`, `AbstractKoboldPlayerEntity.MODEL_CODE` |
  | `SHARED_FLOAT_122` | `KoboldEntity.SIZE_SCALAR`, `KoboldPlayerEntity.SIZE_SCALAR` |
  | `SHARED_STRING_122` | `GoblinEntity.OWNER_UUID`, `GoblinPlayerEntity.OWNER_UUID_STRING` |
  | `SHARED_BOOL_126` | `GoblinEntity.PREGNANT`, `GoblinPlayerEntity.PREGNANT` |

- Reading a non-hoisted key on an entity that is not an instance of its declaring class is now a crash (ClassCastException or AIOOBE). In `ChangeDataParameterPacket` "pregnant", guard with `if (girl is SlimeEntity)`. If you find another cross-hierarchy read, report it; integration will hoist it.
- On the client, writes must still go through `changeDataParameterFromClient` / `ChangeDataParameterPacket`, unchanged.

### 2.7 Networking

Every Java packet becomes a Kotlin `class` implementing `CustomPacketPayload`:
- The constructors are the Java non-default constructors, with identical parameter order and Java types mapped (`Vec3d`→`Vec3`, `EnumFacing`→`Direction`, `NBTTagCompound`→`CompoundTag`, `EntityPlayer`→`Player`). Callers do not change.
- The Java no-argument constructor and `isValid` are dropped; a successful decode means the packet is valid.
- The companion holds `TYPE`, `STREAM_CODEC` and `handle`.
- The wire format is free (no 1.12 compatibility), but semantics must match. Java "null" string sentinels are kept.

```kotlin
package com.trolmastercard.sexmod.networking

class SetPlayerCamPacket(@JvmField val camX: Float, @JvmField val camY: Float, @JvmField val camMode: Int)
    : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SetPlayerCamPacket> = TYPE
    fun write(buf: FriendlyByteBuf) { buf.writeFloat(camX); buf.writeFloat(camY); buf.writeInt(camMode) }

    companion object {
        @JvmField val TYPE = CustomPacketPayload.Type<SetPlayerCamPacket>(Main.rl("set_player_cam"))
        @JvmField val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SetPlayerCamPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SetPlayerCamPacket(buf.readFloat(), buf.readFloat(), buf.readInt()) })

        /** Runs on the main thread (NeoForge default) – the old addScheduledTask body goes here directly. */
        @JvmStatic fun handle(packet: SetPlayerCamPacket, ctx: IPayloadContext) {
            Client.apply(packet)                       // client-bound: client logic in nested object (Rule C)
        }
    }
    private object Client {
        fun apply(p: SetPlayerCamPacket) { val mc = Minecraft.getInstance(); /* ... */ }
    }
}
```

Handler details:
- Server-bound handlers use `val player = ctx.player() as ServerPlayer`, which replaces `ctx.getServerHandler().player`.
- Bidirectional handlers branch on `ctx.flow().isServerbound`.
- Packets carrying `ItemStack` use `StreamCodec<RegistryFriendlyByteBuf, X>` with `ItemStack.OPTIONAL_STREAM_CODEC`.
- Large strings (model data, block sets) use `buf.writeUtf(s, PacketHandler.MAX_STRING)` with `MAX_STRING = 262144`.

**`PacketHandler`** (S1) is an `object`:
- `@JvmStatic fun register(event: RegisterPayloadHandlersEvent)` uses `event.registrar("1")` and `playToServer` / `playToClient` / `playBidirectional(TYPE, STREAM_CODEC) { p, c -> X.handle(p, c) }` for every row below.
- `@JvmField val networkWrapper: NetworkWrapper` keeps call sites as `PacketHandler.networkWrapper.xxx(...)`:
  - `sendToServer(msg)` → `PacketDistributor.sendToServer`
  - `sendTo(msg, player: ServerPlayer)` → `sendToPlayer`
  - `sendToAll(msg)` → `sendToAllPlayers`
  - `sendToAllTracking(msg, entity: Entity)` → `sendToPlayersTrackingEntity`
  - `sendToAllTracking(msg, point: TargetPoint)` → `sendToPlayersTrackingChunk(level, ChunkPos(BlockPos.containing(x,y,z)))`
  - `sendToAllAround(msg, point: TargetPoint)` → `sendToPlayersNear(level, null, x, y, z, range)`
  - `sendToDimension(msg, dimensionId: Int)`
- New class `networking/TargetPoint(dimension: Int, x: Double, y: Double, z: Double, range: Double)` replaces `NetworkRegistry.TargetPoint`. It resolves its level through `WorldUtils.getLevelById`.

Packet table (ID is the `Main.rl` path; direction is where it is handled):

| Packet | id | dir | owner |
|---|---|---|---|
| SendChatMessagePacket | send_chat_message | both | S1 |
| SetPlayerMovementPacket | set_player_movement | S→C | S1 |
| TeleportPlayerPacket | teleport_player | C→S | S1 |
| SendGirlToSexPacket | send_girl_to_sex | C→S | S1 |
| SetPlayerForGirlPacket | set_player_for_girl | C→S | S1 |
| KoboldStatePacket (scene entry; misleading name) | kobold_state | C→S | S1 |
| ResetControllerPacket | reset_controller | both | S1 |
| ResetGirlPacket (inverted flag; see docs) | reset_girl | C→S | S1 |
| ChangeDataParameterPacket | change_data_parameter | C→S | S1 |
| PlayerActionPacket | player_action | C→S | S1 |
| SendCompanionHomePacket | send_companion_home | C→S | S1 |
| SetNewHomePacket | set_new_home | C→S | S1 |
| UploadInventoryToServerPacket | upload_inventory_to_server | C→S | S1 |
| UploadInventoryToServerPacket2 | upload_inventory_to_server2 | C→S | S1 |
| RemoveItemsPacket | remove_items | C→S | S1 |
| UpdatePlayerModelPacket | update_player_model | C→S | S1 |
| SexPromptPacket | sex_prompt | both | S1 |
| StartStandingSexAnimationPacket | start_standing_sex_animation | C→S | S1 |
| SpawnParticlePacket | spawn_particle | S→C | S1 |
| GirlDataPacket | girl_data | S→C | S1 |
| ForcePlayerGirlUpdatePacket | force_player_girl_update | S→C | S1 |
| UpdateVelocityPacket | update_velocity | C→S | S1 |
| UnknownPacket | unknown | both | S1 |
| SetPlayerCamPacket | set_player_cam | S→C | S1 |
| UpdateEquipmentPacket (unregistered in 1.12; register it) | update_equipment | C→S | S1 |
| UploadModelStringPacket | upload_model_string | C→S | S3 |
| DownloadServerModelPacket | download_server_model | both | S3 |
| SummonAlliePacket | summon_allie | C→S | S4 |
| MakeRichWishPacket | make_rich_wish | C→S | S4 |
| BeeOpenChestPacket | bee_open_chest | C→S | S4 |
| CatActivateFishingPacket | cat_activate_fishing | C→S | S4 |
| CatEatingDonePacket | cat_eating_done | C→S | S4 |
| CatThrowAwayItemPacket | cat_throw_away_item | C→S | S4 |
| GalathRapePouncePacket | galath_rape_pounce | C→S | S5 |
| GalathBackOffRapePacket | galath_back_off_rape | C→S | S5 |
| RequestRidingPacket | request_riding | C→S | S5 |
| InformOfOwnershipPacket | inform_of_ownership | S→C | S5 |
| SpawnEnergyBallParticlesPacket | spawn_energy_ball_particles | S→C | S5 |
| SpawnEnergyBallParticlesPacket2 | spawn_energy_ball_particles2 | S→C | S5 |
| ClaimTribePacket | claim_tribe | C→S | S6 |
| GetTribeUiValuesPacket | get_tribe_ui_values | both | S6 |
| SetTribeFollowModePacket | set_tribe_follow_mode | C→S | S6 |
| FallTreePacket | fall_tree | C→S | S6 |
| SendBlocksPacket | send_blocks | both | S6 |
| CancelTaskPacket | cancel_task | C→S | S6 |
| SendEggPacket | send_egg | C→S | S6 |
| MinePacket | mine | C→S | S6 |

- Packets with no arguments stay classes (`class SummonAlliePacket() : CustomPacketPayload`) with `StreamCodec.unit(...)` semantics, implemented as `StreamCodec.of({ _, _ -> }, { SummonAlliePacket() })`.
- `player.openGui(Main.instance, id, world, x, y, z)` becomes `GuiHandler.openGui(player as ServerPlayer, id, world, x, y, z)` (§2.11).

### 2.8 GeckoLib 3 to GeckoLib 4.9.3

Class and API mapping:

| GL3 (1.12) | GL4.9.3 |
|---|---|
| `IAnimatable` on entity / item | `GeoEntity` / `GeoItem` |
| `AnimationFactory(this)`, `getFactory()` | `private val geoCache = GeckoLibUtil.createInstanceCache(this)`, `override fun getAnimatableInstanceCache() = geoCache` |
| `registerControllers(AnimationData data)`, `data.addAnimationController(c)` | `override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar)`, `controllers.add(c)` |
| `new AnimationController(this, name, float ticks, predicate)` | `GirlAnimationController(this, name, ticks.toInt(), handler)` (always the mod subclass; easing becomes `.setOverrideEasingType(...)`) |
| predicate `<E extends IAnimatable> PlayState p(AnimationEvent<E> e)` | girls: `fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState`; `return null` becomes `PlayState.STOP` |
| `e.getController()`, `getPartialTick()`, `getLimbSwingAmount()`, `isMoving()` | the same names on `AnimationState` |
| `e.getExtraDataOfType(EntityModelData.class).get(0)` (`.netHeadYaw` field) | `e.getData(DataTickets.ENTITY_MODEL_DATA)` (record: `.netHeadYaw()`, `.headPitch()`) |
| `setAnimation(new AnimationBuilder().addAnimation(n, LOOP / HOLD_ON_LAST_FRAME / PLAY_ONCE))` | `setAnimation(RawAnimation.begin().thenLoop(n) / .thenPlayAndHold(n) / .thenPlay(n))` (equal RawAnimations do not restart, same as GL3) |
| `controller.transitionLengthTicks = d` | `(controller as GirlAnimationController<*>).setTransitionLengthTicks(d)` |
| `controller.tickOffset = 0` / read | `setTickOffset(0.0)` / `getTickOffset()` |
| `controller.registerSoundListener(l)`, `sound.sound` | `setSoundKeyframeHandler { ev -> ... ev.keyframeData.sound ... }`; `GirlAnimationController.registerSoundListener(handler)` is kept as an alias |
| `controller.clearAnimationCache()` | `forceAnimationReset()` (alias `clearAnimationCache()` kept) |
| `getCurrentAnimation().animationName` / `.animationLength` | `getCurrentAnimationName(): String?` / `getCurrentAnimationLength(): Double` |
| `getAnimationState()` Running / Transitioning / Stopped | `getAnimationState()` → `AnimationController.State.RUNNING` / `TRANSITIONING` / `STOPPED` |
| `factory.getOrCreateAnimationData(id).tick` (`Action.getActionTick`) | `girl.actionController?.lastSeekTime` (captured in `GirlAnimationController.process`) |
| `AnimatedGeoModel<T>` | `GeoModel<T>` |
| `getModelLocation(e)` / `getTextureLocation(e)` / `getAnimationFileLocation(e)` | `getModelResource(e)` / `getTextureResource(e)` / `getAnimationResource(e)`. Kotlin overrides may declare `e: T?` because Java called them with null |
| `setLivingAnimations(e, id, event)` | `override fun setCustomAnimations(e, instanceId: Long, state: AnimationState<T>)` (call super first) |
| `setMolangQueries(a, tick)` | `applyMolangQueries(state, animTime)` |
| `getAnimationProcessor().getBone(n)` → `IBone` | `animationProcessor.getBone(n)` → `GeoBone?` (nullable); `getModelRendererList()` → `getRegisteredBones()` |
| `IBone.setRotationX/Y/Z`, `getRotationX`, `setPositionX`, `setScaleX`, `setHidden`, `getPivotY` | `GeoBone.setRotX/Y/Z`, `getRotX`, `setPosX`, `setScaleX`, `setHidden`, `getPivotY` |
| `GeoBone.isHidden` / `childBones` / `childCubes` / `parent` / `rotationPointX` | `isHidden()` / `getChildBones()` / `getCubes()` / `getParent()` / `getPivotX()` |
| `GeoModel.topLevelBones` (built model) | `BakedGeoModel.topLevelBones()` |
| `GeoCube.quads` / `size` / `pivot` / `rotation`, `GeoQuad.normal` / `vertices`, `GeoVertex.position` / `textureU` / `textureV` | records: `quads()` / `size()` / `pivot()` / `rotation()`, `normal()` / `vertices()`, `position()` / `texU()` / `texV()` |
| `GeckoLibCache.parser.setValue(n, v)` | `MolangVars.set(n, v)` (S1 util, wraps `MathParser.setVariable(n) { v }`) |
| `GeckoLibCache.getGeoModels().put(rl, m)` / `getAnimations()`, `GeoBuilder`, `JsonAnimationUtils` | do not mutate GeckoLib's caches (they are replaced on every reload). Keep the mod's own maps (owned by S3/ReskinManager) and override `GeoModel.getBakedModel(rl)` / `getAnimation(animatable, name)` in `GirlModelBase` and `SexSceneModel` to check them first. Build with `KeyFramesAdapter.GEO_GSON.fromJson(json, Model::class.java)` → `BakedModelFactory.getForNamespace("sexmod").constructGeoModel(GeometryTree.fromModel(model))`; animations: `KeyFramesAdapter.GEO_GSON.fromJson(json, BakedAnimations::class.java)` |
| `GeoEntityRenderer(renderManager, model)` | `GeoEntityRenderer(ctx: EntityRendererProvider.Context, model)` |
| `GeoLayerRenderer.render(entity, limbSwing, ...)` | `GeoRenderLayer<T>(renderer)`: `render(poseStack, animatable, bakedModel, renderType, bufferSource, buffer, partialTick, packedLight, packedOverlay)`; to redraw with another texture use `getRenderer().reRender(...)` |
| `GeoItemRenderer` + `setTileEntityItemStackRenderer` | `GeoItemRenderer<T>(model)` + `GeoItem.createGeoRenderer(consumer)` (template below); item model JSON `"parent": "builtin/entity"` |
| `IGeoRenderer.MATRIX_STACK` (geckolib3 `util.MatrixStack`) in renderers | the `PoseStack` passed in: `RenderUtil.translateMatrixToBone` / `translateToPivotPoint` / `rotateMatrixAroundBone` / `scaleMatrixForBone` / `translateAwayFromPivotPoint` (or `prepMatrixForBone`) |
| geckolib3 `util.MatrixStack` in non-render math (`GirlBoneMath`, `BaseGirlEntity.getBoneMatrixStack`) | `com.trolmastercard.sexmod.util.MatrixStack` (S1): an exact joml port of the GL3 class (`push`/`pop`/`translate(bone)` uses `-posX/16, posY/16, posZ/16`, `moveToPivot`, `rotate` (Z, Y, X order), `scale`, `moveBackFromPivot`, cube variants, `getModelMatrix(): Matrix4f`, `getNormalMatrix(): Matrix3f`) |
| `GirlAnimationProcessor` + reflection in `GirlModelBase` | not ported; the GL4 processor already maps bones by name |
| `GeckoLib.initialize()` | delete |

`GirlAnimationController` (S1, `client/model`, COMMON class):

```kotlin
open class GirlAnimationController<T : GeoAnimatable>(
    animatable: T, name: String, transitionTicks: Int, handler: AnimationStateHandler<T>,
) : AnimationController<T>(animatable, name, transitionTicks, handler) {
    @JvmField var lastSeekTime = 0.0
    fun getTransitionLengthTicks(): Double = transitionLength
    fun setTransitionLengthTicks(ticks: Double) { transitionLength = ticks }
    fun getTickOffset(): Double = tickOffset
    fun setTickOffset(v: Double) { tickOffset = v }
    fun registerSoundListener(h: SoundKeyframeHandler<T>) { setSoundKeyframeHandler(h) }
    fun clearAnimationCache() = forceAnimationReset()
    fun getCurrentAnimationName(): String? = getCurrentAnimation()?.animation()?.name()
    fun getCurrentAnimationLength(): Double = getCurrentAnimation()?.animation()?.length() ?: 0.0
    override fun process(model: GeoModel<T>, state: AnimationState<T>, bones: Map<String, GeoBone>,
                         snapshots: Map<String, BoneSnapshot>, seekTime: Double, crash: Boolean) {
        lastSeekTime = seekTime; super.process(model, state, bones, snapshots, seekTime, crash)
    }
}
```

`BaseGirlEntity` animation contract (S1); S4, S5 and S6 override these exact signatures:

```kotlin
@JvmField var actionController: GirlAnimationController<BaseGirlEntity>? = null   // also movementController, eyesController
open fun initAnimationControllers()            // creates action(0) / movement(5) / eyes(10) with { animationPredicate(it) }
abstract override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar)
open fun registerControllers(controllers: AnimatableManager.ControllerRegistrar,
                             soundListener: AnimationController.SoundKeyframeHandler<BaseGirlEntity>)
abstract fun animationPredicate(event: AnimationState<BaseGirlEntity>): PlayState
open fun handleActionAnimationOverrides(action: Action, animName: String, started: Boolean, event: AnimationState<*>): Boolean
open fun createAnimation(animName: String, loop: Boolean, event: AnimationState<*>, force: Boolean)
open fun createAnimation(animName: String, loop: Boolean, event: AnimationState<*>)
open fun playRandomizedAnimation(animName: String, maxVariants: Int, chance: Float, event: AnimationState<*>, force: Boolean)
open fun playRandomizedAnimation(animName: String, maxVariants: Int, chance: Float, event: AnimationState<*>)
abstract fun getNextAction(action: Action): Action?
abstract fun getCumAction(action: Action): Action?
```

GL4 calls `registerControllers` lazily when the first `AnimatableManager` is created. Keep the "create the controllers if null" logic. Non-girl GeckoLib animatables (`KoboldEggEntity`, `SexSceneEntity`, the items) use their own type parameter.

Item template (S4 AlliesLamp/LunaRod; S5 GalathCoin/DragonStaff/Winchester; S6 KoboldEgg):

```kotlin
class AlliesLampItem : Item(Properties().stacksTo(1)), GeoItem {
    private val geoCache = GeckoLibUtil.createInstanceCache(this)
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) { /* Java body */ }
    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = geoCache
    override fun createGeoRenderer(consumer: Consumer<GeoRenderProvider>) {
        consumer.accept(object : GeoRenderProvider {
            private var renderer: AlliesLampRenderer? = null
            override fun getGeoItemRenderer(): BlockEntityWithoutLevelRenderer =
                renderer ?: AlliesLampRenderer().also { renderer = it }
        })
    }
    companion object { @JvmStatic val ALLIES_LAMP: AlliesLampItem get() = ModItems.ALLIES_LAMP.get() }
    object Events { /* common @SubscribeEvent methods; use ALLIES_LAMP instead of `this` */ }
    object ClientEvents { /* client-only @SubscribeEvent methods */ }
}
```

Model template (S2 owns the base; character models follow this):

```kotlin
class JennyNpcModel : GirlModel<BaseGirlEntity>() {
    override fun getModelLocations(): Array<ResourceLocation> =
        arrayOf(Main.rl("geo/jenny/jennynude.geo.json"), Main.rl("geo/jenny/jennydressed.geo.json"))
    override fun getTextureResource(entity: BaseGirlEntity?) = Main.rl("textures/entity/jenny/jenny.png")
    override fun getAnimationResource(entity: BaseGirlEntity?) = Main.rl("animations/jenny/jenny.animation.json")
    override fun HeadArmor() = arrayOf("armorHelmet")   // IGirlModelInfo names unchanged
}
```

`GirlModelBase<T : GeoAnimatable> : GeoModel<T>`. `GirlModel<T : BaseGirlEntity> : GirlModelBase<T>, IGirlModelInfo`:
- implements `getModelResource(e)` = `getSexWorldTexture(e)`;
- keeps `getTextureLocation()` (no argument) = `getTextureResource(null)`;
- keeps `getSexWorldTexture`, `getItemStackForBone`, `lerpPositions`, `getInterpolatedPosition`, `getBoneOffsetWorld`, `CAMERA_PLACEMENTS`, `BRA_STRING_BONES`, `enableModelCache`.

Renderer template:

```kotlin
class JennyRenderer(ctx: EntityRendererProvider.Context, model: GeoModel<out BaseGirlEntity>, shadowOffset: Double)
    : GirlRenderer<JennyEntity>(ctx, model, shadowOffset)
// GirlRenderer<T : BaseGirlEntity>(ctx, model: GeoModel<out BaseGirlEntity>, shadowOffset: Double)
//     : GeoEntityRenderer<T>(ctx, model as GeoModel<T>), IGirlRenderer   (@Suppress("UNCHECKED_CAST"))
```

`RenderHandler.register(event: EntityRenderersEvent.RegisterRenderers)` (S2) reproduces the Java list exactly, with the same models and shadow offsets, using `event.registerEntityRenderer(ModEntities.X.get()) { ctx -> ... }`. Player-form renderers take `(ctx, model)`.

### 2.9 Rendering: GL11 / GlStateManager to PoseStack / RenderType / VertexConsumer

General translation:
- `pushMatrix`/`popMatrix` → `poseStack.pushPose()`/`popPose()`, still in `try/finally`.
- `translate`/`scale` are unchanged.
- `rotate(a, 1,0,0)` → `poseStack.mulPose(Axis.XP.rotationDegrees(a))`. For an arbitrary axis use `Quaternionf().rotationAxis(Math.toRadians(a).toFloat(), x, y, z)` with a normalised axis.
- `bindTexture(t)` + `Tessellator`/`BufferBuilder.begin(QUADS, POSITION_TEX_COLOR_NORMAL)` → `val vc = bufferSource.getBuffer(RenderType.entityTranslucent(t))`, then `vc.addVertex(pose, x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(poseLast, nx, ny, nz)`.
- Lines → `RenderType.lines()`. Untextured immediate draws → `Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)` + `BufferUploader.drawWithShader(builder.buildOrThrow())` with `RenderSystem.setShader(GameRenderer::getPositionColorShader)`.
- `enableBlend`/`blendFunc`/`color`: pick the RenderType (translucent) or use `RenderSystem.enableBlend()` + `defaultBlendFunc()`, with `setShaderColor` restored to 1 in `finally`.
- `enableLighting`/`disableLighting` → remove. Lighting is `packedLight`; use `LightTexture.FULL_BRIGHT` for "unlit".
- `GL20.glUseProgram(0)` → remove.
- `ShaderHelper.initOutlineShader` → `RegisterEntitySpectatorShadersEvent` (`event.register(type, Main.rl("shaders/post/outline.json"))`) for every girl type.
- `mc.getItemRenderer().renderItem(e, stack, TransformType.X)` → `mc.itemRenderer.renderStatic(e, stack, ItemDisplayContext.X, false, poseStack, bufferSource, level, packedLight, packedOverlay, e.id)`. Afterwards, re-acquire the entity buffer with `bufferSource.getBuffer(renderType)`.
- `DynamicTexture` / `BufferedImage` skins → `NativeImage` + `DynamicTexture` registered via `textureManager.register(rl, tex)`.
- GUI: `drawString` → `guiGraphics.drawString(font, s, x, y, color, shadow)`; `drawRect` → `guiGraphics.fill`; `drawTexturedModalRect` → `guiGraphics.blit(tex, x, y, u, v, w, h)`.

`GirlRenderer` pipeline (S2 implements; S5 subclasses rely on it):
1. **`render(entity, yaw, pt, poseStack, bufferSource, light)`.** Fill `renderCtx` (below). Run the Java `getBoneWorldPos` logic: name-tag gating, horse offset, and anchored target pinning via `poseStack.translate(target - lerped entity position)` and pinning `yBodyRot`/`yHeadRot`/`yRot` to `getYawRotation()`. Then call `super.render`. GL4's `actuallyRender` builds the `AnimationState` with limb swing, sitting and the head clamp; the Java duplicate of that logic is dropped and the behaviour is identical.
2. **`actuallyRender`.** Implements the Java `renderModel` / `renderModelBuffer`: occlusion gate (`RenderOcclusion`), `getActiveBones`, `BodyParts.updateCustomBones`, then all top-level bones except `steve` with the girl texture, then `steve` with `getSkinTexture(girl)` via `renderCtx.switchTexture`. Subclass splits (`body2` in Manglelie) override `renderModelBuffer`.
3. **`renderRecursively` (GL4 signature).** Implements the Java per-bone logic. Per-cube emission goes through overridden `renderCubesOfBone` / `createVerticesOfQuad`, so per-bone colour, the armor alpha and the custom texture-U offset are applied. Keep the zero-size-face normal flip.
4. **`renderFinal`.** `applyCameraBone(entity)` (unchanged semantics; uses `util.MatrixStack` via `GirlBoneMath`), `SexSceneRenderer.renderSexSceneEffects(entity, pt)`, then the optional overlay colour.
5. **`GirlRenderContext`** (S2, `client/renderer`): `poseStack`, `bufferSource`, `renderType`, `buffer`, `packedLight`, `packedOverlay`, `partialTick`, `switchTexture(rl)`, `restoreBuffer()`. It is stored in `@JvmField val renderCtx` on `GirlRenderer`.

   **Hook rule:** every mod-defined `GirlRenderer` / `GirlRendererBase` hook keeps its Java name and parameters, except that `BufferBuilder` / `Tessellator` parameters are **removed** and the hook reads `renderCtx` instead. Examples: `renderHeldItem(bone: GeoBone)`, `renderTradeOverlay(bone)`, `onBoneProcessing(boneName: String, bone: GeoBone)`, `renderCustomBones(bone, r, g, b, a, overlayAlpha: Double)`, `renderCubeGeometry(cube: GeoCube, r, g, b, a, textureVOffset: Double)`. Colours stay floats; convert with `FastColor.ARGB32.colorFromFloat(a, r, g, b)` only at vertex emission.

   Hooks overriding GeckoLib methods use the GeckoLib signature.
6. **Static helpers** used from COMMON code (Rule B):
   - `GirlRenderer.getGeoModelFor(entity: BaseGirlEntity): GeoModel<*>?`. `BaseGirlEntity.getGeoModel()` / `getAnimationProcessor()` delegate here when `level().isClientSide`, and return null on the server.
   - `GirlRenderer.invalidate(uuid)`, `invalidateAll()`, `GirlRendererBase.clearBoneColors()`.
   - `StructureMarkerRenderer.clearMarkers()`.

### 2.10 Bootstrap, proxies, events (S1)

`Main` (`@Mod(Main.MOD_ID) object Main`, keep the existing file):
- `@JvmField val LOGGER`, `@JvmStatic fun rl(path)`.
- `lateinit var proxy: CommonProxy`, created by `Class.forName("...proxy.ClientProxy")` when `FMLEnvironment.dist.isClient`, otherwise `CommonProxy()`.
- `init {}`:
  1. `ModXxx.register(MOD_BUS)` for every registry.
  2. `MOD_BUS.addListener(PacketHandler::register)` (KFF accepts a function reference; otherwise a lambda).
  3. `FMLCommonSetupEvent` → `proxy.preInitRegistries(e)` then `proxy.initRegistries(e)`.
  4. `FMLLoadCompleteEvent` → `proxy.postInit(e)`.
  5. Game bus: `RegisterCommandsEvent` (`CommandLocateGoblinLair.register(d)`, `CommandReloadCustomModels.register(d)`), `ServerStartingEvent` (dedicated server only: `ServerWhitelistManager.loadCustomModels(false)`, replacing `setUpCustomModelsOnServer`), `ServerStoppedEvent` → `onWorldClosed` (body unchanged; the client reset goes through `if (FMLEnvironment.dist.isClient) ClientResetHook.run()`).
  6. `proxy.construct(MOD_BUS)`.
- `setConfigs()` keeps the exact JSON text and the character-offset parsing, at `FMLPaths.CONFIGDIR.get().resolve("sexmod.json")`.
- `printBanner()` is kept; copy `banner.txt` and `sexmod-build.properties`.

`CommonProxy`: `open fun construct(bus)`, `preInitRegistries(event)` (entity/item registration is gone; keep only what remains), `initRegistries(event)` (`setConfigs`, `ForgeEventHandler.registerAll(false)`), `postInit(event)`.

`ClientProxy` overrides these and adds:
- `@JvmField val keyBindings: Array<KeyMapping>` created eagerly: `KeyMapping("Interact with your goblin", GLFW_KEY_G, "Sex mod")`, `KeyMapping("open character customisation menu", GLFW_KEY_KP_5, "Sex mod")`, `KeyMapping("Leave sex scene", GLFW_KEY_RIGHT_SHIFT, "Sex mod")`.
- `@JvmField var IS_PRELOADING = false`.
- Mod-bus listeners: `RegisterKeyMappingsEvent`, `EntityRenderersEvent.RegisterRenderers` → `RenderHandler.register`, `RegisterMenuScreensEvent` (`GIRL_EQUIPMENT` → `::GirlInventoryContainerGui2`, `LUNA_EQUIPMENT` → `::GirlInventoryContainerGui`, `GIRL_CHEST` → `::ChestContainerGui`), `RegisterEntitySpectatorShadersEvent`.
- Game-bus `RegisterClientCommandsEvent` (`CommandWhitelistServer`, `CommandSetModelCode`, `CommandFuta`).
- In `initRegistries`: `GenderSwapScreen.instance = GenderSwapScreen()` and `ForgeEventHandler.registerAll(true)`.
- The preload loop and particle ID 625115 are dropped. Nothing spawns that ID; `DragonBreathParticle` is added directly through `mc.particleEngine.add(...)`.

`ForgeEventHandler.registerAll(client: Boolean)` registers on `NeoForge.EVENT_BUS`. Owners must provide exactly these symbols:

- **COMMON (always):** `GirlCombatProtection()`, `LivingDeathHandler()`, `PlayerIds()`, `HornyPotion.Events`, `DamageCalculation()`, `KoboldEggProjectileEntity.EnderTeleportHandler()`, `GirlFollowGoal.DownedGirlEvents()`, `AlliesLampItem.Events`, `DragonStaffItem.Events`, `NpcEditorWandItem.Events`, `LunaRodItem.Events`, `PlayerGirlEvents()`, `LunaEntity.CreeperAvoidanceHandler()`, `GirlBedInteraction()`, `SexFireBlock.Events`, `KoboldEntity.KoboldTribeEvents()`, `DragonStaffItem.RightClickHandler()`, `KoboldManager.TribeWorldSavedData("tribes")`, `KoboldEggItem.Events`, `GoblinEventHandler()`, `GoblinPlayerEntity.GoblinCarryEvents()`, `AlliesLampItem.InteractionHandler()`, `DebugMode()`, `GalathEntity.GalathLifecycleEvents()`, `GirlSavedData()`, `GalathCoinItem.Events`, `BeeWorldData()`, `AllieWorldData()`, `GirlWorldData()`, `ConfigWorldGenHandler.getInstance()`, `ManglelieEntity.ArrowImpactHandler()`, `NameTagInteractHandler()`.
  - `WinchesterItem` has no handlers and is not registered.
  - "`.Events`" objects replace the Java pattern of registering an item or effect instance.
- **CLIENT (`client == true`):**
  - `ReskinManager.INSTANCE`; `DebugWindow()` only if `shouldShowDebugWindow() && !GraphicsEnvironment.isHeadless()`, otherwise `DebugWindow2.isVisible = false`.
  - `HornyMeterHud()`, `BeeScreen()`, `HandlePlayerMovement()`, `PositionData()`, `GuiOpenHandler()`, `InHandMapRenderer()`, `GirlCameraHelper()`, `SexSceneKeyHandler()`, `GenderSwapScreen()`, `PlayerAllieRenderer.ClientTickHandler()`, `StructureMarkerRenderer()`, `DeprecatedCheckForUpdates()`, `ClothingScreen.KeyAndScrollHandler()`, `ServerWhitelistManager.ChatHandler()`, `EscapeMinigameHud()`, `CummyEntity()`, `GalathFlightHud()`, `GoblinFirstPersonRenderer()` (moved from the common list), `GirlRenderer.SkinCacheInvalidator.INSTANCE`.
  - Split-out client halves (Rule E): `AlliesLampItem.ClientEvents`, `DebugMode.ClientEvents`, `GoblinEventHandler.ClientEvents`, `GalathEntity.GalathLifecycleEvents.ClientEvents`, `GoblinPlayerEntity.GoblinCarryEvents.ClientEvents`, `KoboldEntity.KoboldTribeEvents.ClientEvents`, `PlayerGirlEvents.ClientEvents`.

### 2.11 Persistence, menus, commands

- **SavedData.** `WorldSavedData` becomes `SavedData`:
  - `override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag`.
  - Companion `@JvmStatic fun load(tag, registries): X` and `@JvmStatic fun factory() = SavedData.Factory({ X() }, ::load, null)`.
  - `world.getMapStorage().getOrLoadData(...)` / `setData` → `server.overworld().dataStorage.computeIfAbsent(factory(), name)`. Call `setDirty()` where Java called `markDirty()`.
  - File names: replace `:` with `_` (`sexmod_galath_owner_ship`, `sexmod_customstaticgirlnames`, `sexmod_galath_spawn_list`, `sexmod_static_custom_model_manager`, `sexmod_generation`, `tribes`).
  - Keep the Java constructors that take `name: String` for call sites.
- **Entity NBT**: stays a `CompoundTag` with the same keys (§2.4).
- **Menus** (S3, COMMON classes):
  - Each container has the Java constructor with `containerId: Int` prepended (`ChestContainer(containerId, girl, playerInventory: Inventory, uuid)`, `GirlInventoryContainer2(containerId, luna, inv, uuid)`, `GirlInventoryContainer(containerId, playerInventory: Container, girlInventory: Container, player, uuid)`).
  - Each also has `constructor(containerId: Int, inv: Inventory, buf: RegistryFriendlyByteBuf)` that reads `guiId: VarInt, x: Int, y: Int, z: Int` and finds the girl like `getClientGuiElement`.
  - `GuiHandler` becomes an `object` with `@JvmStatic fun openGui(player: ServerPlayer, id: Int, world: Level, x: Int, y: Int, z: Int)`. It finds the girl like `getServerGuiElement`, then calls `player.openMenu(SimpleMenuProvider(...), { buf -> buf.writeVarInt(id); buf.writeInt(x); ... })`.
  - Screens use the `(menu, inv, title)` constructor and take the girl from `menu`.
- **Commands** (S3): keep the class and its logic with `execute(source: CommandSourceStack, args: Array<String>)`. Add companion `@JvmStatic fun register(dispatcher: CommandDispatcher<CommandSourceStack>)` registering `literal(name)` (+ optional `greedyString("args")` split on spaces), with `.requires { it.hasPermission(n) }` from `getRequiredPermissionLevel`. Singletons such as `FUTA_COMMAND` stay.
- **HUDs**: keep them as event-handler classes drawing in `RenderGuiEvent.Post` with `GuiGraphics`. Their static APIs (`HornyMeterHud.showHornyMeter()`, `resetHornyMeter()`, ...) stay `@JvmStatic`.

### 2.12 Small S1 utilities that other slices call

- `util/ItemNbt`: `getTag(stack): CompoundTag?` (copy), `setTag(stack, tag)`, `hasTag(stack)`, `getMeta(stack): Int`, `setMeta(stack, meta)`.
- `util/ParticleCompat`: `name(type: SimpleParticleType): String` (registry ID), `byName(name: String): SimpleParticleType?`. 1.12 → 1.21 table: `CLOUD`→`CLOUD`, `CRIT_MAGIC`→`ENCHANTED_HIT`, `DRAGON_BREATH`→`DRAGON_BREATH`, `EXPLOSION_NORMAL`→`POOF`, `HEART`→`HEART`, `PORTAL`→`PORTAL`, `SLIME`→`ITEM_SLIME`, `SMOKE_NORMAL`→`SMOKE`, `SPELL`→`EFFECT`, `SPELL_WITCH`→`WITCH`, `VILLAGER_ANGRY`→`ANGRY_VILLAGER`, `VILLAGER_HAPPY`→`HAPPY_VILLAGER`, `WATER_BUBBLE`→`BUBBLE`, `WATER_SPLASH`→`SPLASH`, `WATER_WAKE`→`FISHING`. `EnumParticleTypes.X.getParticleName()` becomes `ParticleCompat.name(ParticleTypes.Y)`.
- `util/MolangVars.set(name: String, value: Double)`.
- `util/MatrixStack` (§2.8). `util/WorldUtils.getDimensionId` / `getLevelById` (added to the existing class).
- `client/ClientHooks` (§2.3). `networking/TargetPoint` (§2.7). `client/SexWorldClient` object (§2.3).

### 2.13 Resources

- **S1** copies verbatim into `neoforge/src/main/resources/assets/sexmod/`: `animations/`, `geo/`, `textures/`, `sounds/`, `sounds.json`, plus `banner.txt`, `sexmod-build.properties`, `logo.png`. GeckoLib 4 loads `geo/**/*.json` and `animations/**/*.json` itself.
- **S3** converts the rest:
  - `lang/*.lang` → `lang/*.json`. Keys: `item.X.name` → `item.sexmod.X`; `entity.X.name` / `entity.sexmod.X.name` → `entity.sexmod.X`; effect → `effect.sexmod.horny_potion`. Add `item.minecraft.potion.effect.horny_potion` and the splash/lingering variants. Spawn eggs; key names (the literal English strings).
  - `models/item/*.json`: GeckoLib items get `"parent":"builtin/entity"`; npc_editor_wand overrides on `custom_model_data`; spawn eggs `"parent":"minecraft:item/template_spawn_egg"`.
  - `blockstates/fire.json`.
  - `data/sexmod/loot_table/{jenny,ellie,slime,bia}.json` (1.21 loot format).
  - `data/sexmod/recipe/{dragon_staff,npc_editor_wand}.json` (lowercase names, 1.21 format).
  - `data/sexmod/structure/*.nbt` (from `assets/.../structures`).
  - `data/sexmod/neoforge/biome_modifier/*.json`.
  - `data/sexmod/damage_type/{galath,succubus}.json` (match the Java message IDs).
  - Stray files `models/spider.mp4` and `models/block/fire_no_spread.txt` are not copied.

---

## 3. Slice plan

There are 6 slices of roughly 10–12k Java lines each. Paths are relative to `com/trolmastercard/sexmod/`, and every one of the 371 files appears exactly once. "DROP" means the file is not ported; record that in your summary. Each slice writes only its own files, plus new files assigned to it. No slice edits another slice's files. If a dependency's signature is unclear, code against the Java signature mapped through §2. Each slice summary must list: renames, PORT-GAPs, hoisting requests, deviations, and any new public API added.

### S1: Foundation (about 12.2k lines)
**Owns:** `Main.kt`, the proxies, all `registry/*` (new), `PacketHandler` + `TargetPoint` (new), the event-registration hub, the base entity framework, common utilities, the S1 resource copy, and the new helpers `client/ClientHooks`, `util/MatrixStack`, `util/ItemNbt`, `util/ParticleCompat`, `util/MolangVars`.

- root: `Main`, `BuildInfo`, `MobPredicates`, `SexNetworkManager` (DROP)
- `api/`: `IPositionProvider`, `ITargetProvider`, `KoboldNames`, `LightingType`, `SkinColor`
- `block/`: `SexFireBlock`
- `potion/`: `HornyPotion`
- `proxy/`: `ClientProxy`, `CommonProxy`
- `client/`: `SexWorldClient` (becomes an object), `ClientNetHandlerOverride` (DROP), `package-info` (DROP)
- `client/model/`: `GirlAnimationController`
- `entity/`: `BaseGirlEntity`, `GirlSyncedState`, `GirlNbtIO`, `GirlLookup`, `GirlChat`, `GirlSoundBroadcast`, `GirlPlayerPlacement`, `GirlBedPlacement`, `GirlBlockScan`, `GirlBoneMath`, `GirlCustomParts`, `GirlPartCodec`, `GirlAnimationVariants`, `AbstractGirlNpcEntity`, `AbstractNpcOnlyEntity`, `AbstractPlayerGirlEntity`, `AbstractKoboldPlayerEntity`, `Action`, `NpcType`, `BoneType`, `EyeAndKoboldColor`, `SexModEntities` (becomes a thin delegate to `ModEntities`), `SexEntityPart`, `package-info` (DROP)
- `entity/api/`: `IEllie`, `IGalath`, `IGoblin`, `IKobold`
- `entity/ai/`: `AvoidPlayerGoal`, `DoorInteractAiGoal`, `GirlAiBase`, `GirlFollowAiBase`, `GirlFollowGoal`, `GirlGotoGoal`, `NearestAttackableGirlGoal`, `WatchClosestGirlGoal`
- `networking/`: `PacketHandler`, `ChangeDataParameterPacket`, `KoboldStatePacket`, `ResetGirlPacket`, `ResetControllerPacket`, `SendGirlToSexPacket`, `SetPlayerForGirlPacket`, `SetPlayerMovementPacket`, `SetPlayerCamPacket`, `TeleportPlayerPacket`, `PlayerActionPacket`, `SendChatMessagePacket`, `SendCompanionHomePacket`, `SetNewHomePacket`, `SexPromptPacket`, `StartStandingSexAnimationPacket`, `SpawnParticlePacket`, `UpdateVelocityPacket`, `UpdateEquipmentPacket`, `UploadInventoryToServerPacket`, `UploadInventoryToServerPacket2`, `RemoveItemsPacket`, `GirlDataPacket`, `ForcePlayerGirlUpdatePacket`, `UpdatePlayerModelPacket`, `UnknownPacket`, `package-info` (DROP)
- `util/`: `Reference`, `RotationHelper`, `TrigMath`, `VectorMath`, `Vector2d`, `Vector2f`, `Vector3fSexmodSpecial`, `Vector4d`, `Point2D`, `Rectangle`, `BiDirectionalMap`, `PathUtils`, `WorldUtils`, `EntityLookVectorHelper`, `ClientServerCheck`, `SoundHandler`, `ForgeEventHandler`, `ItemRegistrationHandler` (becomes a no-op kept for call sites), `LootTableHandler`, `LivingDeathHandler`, `GirlRegistry`, `GirlSavedData`, `GirlWorldData`, `SceneDebug`, `ThreadNames`, `DamageCalculation`, `GirlCombatProtection`, `GirlBedInteraction`, `NameTagInteractHandler`, `PlayerIds`, `PlayerGirlEvents`, `IBeddableSexGirl`, `IBoneRotationSupplier`, `SuccubusDamageSource`, `EyeColor`, `HairColor`, `EscapeDirectionKey`, `package-info` (DROP)

**Depends on (blind):**
- every concrete entity's `(EntityType, Level)` constructor and `createAttributes()` (S3–S6);
- item no-argument constructors (S3–S6);
- every packet's `TYPE`/`STREAM_CODEC`/`handle` (all slices);
- the handler symbols in §2.10;
- `GirlInventoryScreen(girl, player[, options, rewards, dressUp])`, `HornyMeterHud` statics, `HandlePlayerMovement.setMovementLock(Boolean)` / `isJumping` (S3);
- `GirlRenderer.getGeoModelFor`, `SexSceneRenderer.getSceneEntityPosition(...)` / `renderSexSceneEffects(...)`, `RenderHandler.register`, `GirlRendererBase.clearBoneColors`, `StructureMarkerRenderer.clearMarkers` (S2);
- `KoboldManager.clearAll()`, `KoboldEntity.tribeMembers` (S6);
- `ConfigWorldGenHandler.getInstance()` / `GENERATION_ENABLED`, `ServerWhitelistManager.loadCustomModels(Boolean)` / `isLoaded`, command classes (S3);
- the ClientEvents objects (S3–S6).

### S2: Rendering core and generic renderers (about 10.2k lines)
**Owns:** the GeckoLib render pipeline, `GirlRenderContext` (new), `RenderHandler`, all models and renderers except Galath, Manglelie and Dragon.

- root: `MatrixHelper`, `SkinFetcher`
- `client/`: `ShaderHelper`
- `client/model/`: `AllieModel`, `AllieNpcModel`, `AlliesLampModel`, `BeeModel`, `BeeNpcModel`, `BiaModel`, `BiaNpcModel`, `CatModel`, `CatNpcModel`, `EllieModel`, `EllieNpcModel`, `GirlAnimationProcessor` (DROP; see §2.8), `GirlModelBase`, `GirlModel`, `GoblinModel`, `GoblinNpcModel`, `JennyModel`, `JennyNpcModel`, `KoboldEggItemModel`, `KoboldEggModel`, `KoboldModel`, `KoboldNpcModel`, `LunaModel`, `ModelBones`, `SexSceneModel`, `SlimeModel`, `SlimeNpcModel`
- `client/model/api/`: `IGirlModelInfo`, `IVanillaModel` (`getModel(): ModelPart`)
- `client/renderer/`: `AlliesLampRenderer`, `BasicGirlRenderer`, `BeeRenderer`, `CustomPartBones`, `EllieRenderer`, `GirlLayerRenderer`, `GirlLeashRenderer`, `GirlOverlayRenderer`, `GirlPlayerRenderer`, `GirlRendererBase`, `GirlRenderer`, `GoblinRenderer`, `JennyRenderer`, `KoboldEggItemRenderer`, `KoboldEggRenderer`, `KoboldRenderer`, `ModelCodeReader`, `RenderOcclusion`, `SexEntityRenderer`, `SexSceneRenderer`, `SkinTextureCache`, `WildSlimeFaceLayer`, `WildSlimeRenderer`
- `client/renderer/api/`: `IGirlRenderer`
- `entity/`: `BodyParts` (render-only despite its package)
- `util/`: `AbstractPlayerKoblinGoboldRenderer`, `AllieRenderer`, `BiaRenderer`, `LunaRenderer`, `SlimeRenderer`, `PlayerAllieRenderer`, `PlayerBeeRenderer`, `PlayerBiaRenderer`, `PlayerEllieRenderer`, `PlayerGoblinRenderer`, `PlayerJennyRenderer`, `PlayerKoboldRenderer`, `PlayerLunaRenderer`, `PlayerSlimeRenderer`, `GoblinFirstPersonRenderer`, `RenderHandler`, `GirlRenderFlags`, `InHandMapRenderer`, `StructureMarkerRenderer`

**Provides (others code against it):** §2.8 / §2.9 contracts, renderer constructors `(ctx, model, shadowOffset)` and `(ctx, model)`, `GirlModel` API, `ModelBones.get(processor, name)` / `setHidden(...)`, `SkinTextureCache`, `StructureMarkerRenderer` static marker API (as in Java), item renderer no-argument constructors (`AlliesLampRenderer()`, `KoboldEggItemRenderer()`).

**Depends on:** S1 entity APIs and synced keys; S4/S6 entity fields read by renderers (`LunaEntity.SYNCED_HELD_ITEM`, `KoboldEntity`/`GoblinEntity` keys, `AbstractPlayerGirlEntity.getHandModel` / `getHandTexture`); S3 `ClothingScreen` preview state, `ModelResources` / `ReskinManager` custom-model maps (for `getBakedModel` overrides); S5 `GalathRenderer` / `ManglelieRenderer` types (only inside `RenderHandler`).

### S3: Client UI, input/camera, custom models, commands, worldgen, data resources (about 10.9k lines)
- root: `PositionData`
- `client/`: `GirlCameraHelper`, `SexSceneKeyHandler`
- `client/gui/`: `BeeDialogueScreen`, `BeeScreen`, `ChestContainerGui`, `ChestContainer`, `ClothingScreen`, `CustomModelList`, `EscapeMinigameHud`, `GalathFlightHud`, `GalathScreen`, `GenderSwapScreen`, `GirlInventoryContainer2`, `GirlInventoryContainerGui2`, `GirlInventoryContainerGui`, `GirlInventoryContainer`, `GirlInventoryScreen`, `GirlInventorySlot`, `GirlScreenBase`, `GuiHandler`, `HornyMeterHud`, `StructureCommandScreen`, `TribeNameScreen`, `UnknownScreen`
- `command/`: `CommandFuta`, `CommandLocateGoblinLair`, `CommandReloadCustomModels`, `CommandSetModelCode`, `CommandWhitelistServer`
- `reskin/`: `BodySlidersScreen`, `NoModelsScreen`, `PackDownloader`, `ReskinApplier`, `ReskinBrowserScreen`, `ReskinEntry`, `ReskinManager`, `ReskinReader`, `ReskinScanner`, `UpdateChecker`
- `util/`: `HandlePlayerMovement`, `DebugMode`, `DebugWindow`, `DebugWindow2`, `GuiOpenHandler`, `ServerWhitelistManager`, `ServerWhitelistFile`, `ModelArchive`, `ModelCfgParser`, `ModelNotifications`, `ModelResources`, `UpdateNotice`, `DeprecatedCheckForUpdates`
- `networking/`: `DownloadServerModelPacket`, `UploadModelStringPacket`
- `entity/`: `SexSceneEntity` (`custom_model`, GeoEntity)
- `item/`: `NpcEditorWandItem`
- `worldgen/`: `ConfigWorldGenHandler`, `GirlHouseGenerator`, `IWorldGen` (kept as an interface)

**Worldgen replacement** (no `IWorldGenerator`):
- `ConfigWorldGenHandler` subscribes `ChunkEvent.Load` (server, `isNewChunk`) and queues the `ChunkPos`.
- It drains the queue in `LevelTickEvent.Post` (server overworld) by calling `generate(random, chunkX, chunkZ, level)`.
- `random` is seeded like 1.12 `GameRegistry.generateWorld`: `r = Random(seed); xs = (r.nextLong() shr 2) + 1; zs = (r.nextLong() shr 2) + 1; r.setSeed((xs*cx + zs*cz) xor seed)`.
- `GirlHouseGenerator` uses `level.structureManager.get(Main.rl(name))` → `template.placeInWorld(level, pos, pos, StructurePlaceSettings().setRotation(rot), level.random, flags)`.
- The Java source of `GirlHouseGenerator` references undeclared `worldServer` and `settings`. Port the intent (overworld server level, default settings), and also do the resource conversion work in §2.13.

**Depends on:** S1 (girl APIs, packets, `GuiHandler` callers, `ClientHooks`), S2 (preview rendering in `ClothingScreen` / `CustomModelList` via the entity render dispatcher, `SkinTextureCache`), S6 (`KoboldManager` tribe data for `TribeNameScreen` / `StructureCommandScreen`), S5 (Galath flight state for `GalathFlightHud`, `GoblinEntity` for `GalathScreen` via S6).

### S4: Girls A: Jenny, Ellie, Bia, Luna, Slime, Bee, Allie (about 11.4k lines)
- `entity/`: `JennyEntity`, `JennyPlayerEntity`, `EllieEntity`, `ElliePlayerEntity`, `BiaEntity`, `BiaPlayerEntity`, `LunaEntity`, `LunaPlayerEntity`, `SlimeEntity`, `SlimePlayerEntity`, `WildSlimeEntity`, `BeeEntityBase`, `BeeEntity`, `BeePlayerEntity`, `AllieEntity`, `AlliePlayerEntity`, `SexEntity` (`luna_hook`)
- `item/`: `AlliesLampItem`, `LunaRodItem`
- `util/`: `AllieWorldData`
- `networking/`: `BeeOpenChestPacket`, `CatActivateFishingPacket`, `CatEatingDonePacket`, `CatThrowAwayItemPacket`, `MakeRichWishPacket`, `SummonAlliePacket`

**Depends on:** the S1 base API (§2.8 animation contract, `BaseGirlEntity` / `AbstractGirlNpcEntity` / `AbstractPlayerGirlEntity` methods as in Java, `SoundHandler`, `PacketHandler`, `ClientHooks`), S3 (`HornyMeterHud`, `HandlePlayerMovement`, `GirlInventoryScreen`, `BeeDialogueScreen`, `GuiHandler`), S2 (`AlliesLampRenderer()`, `IVanillaModel` implementations `JennyModel()` etc. for `getHandModel`).

### S5: Galath, Manglelie, Dragon, BasicGirl and their renderers (about 11.4k lines)
- root: `GalathActionListener`
- `api/`: `IGalathFinish`, `IGalathStart`
- `util/`: `IGalathExecute`, `IGalathUpdate`, `GalathDamageSource`, `BeeWorldData`, `PlayerGalathRenderer`, `GalathGeometryRender`, `RibbonRenderer`, `DynamicTrailRenderer`, `TrailSegment`
- `entity/`: `GalathEntity`, `GalathActions`, `GalathAnimations`, `GalathCombat`, `GalathEnergyBalls`, `GalathFlightData`, `GalathFlightState`, `GalathInteractions`, `GalathNbtIO`, `GalathRiding`, `GalathSceneTicks`, `GalathTamedAi`, `GalathPlayerEntity`, `ManglelieEntity`, `DragonEntity`, `BasicGirlEntity`, `CummyEntity`
- `item/`: `GalathCoinItem`, `DragonStaffItem`, `WinchesterItem`
- `networking/`: `GalathBackOffRapePacket`, `GalathRapePouncePacket`, `RequestRidingPacket`, `SpawnEnergyBallParticlesPacket`, `SpawnEnergyBallParticlesPacket2`, `InformOfOwnershipPacket`
- `client/renderer/`: `GalathRenderer`, `ManglelieRenderer`, `GalathCoinRenderer`, `DragonRenderer`, `DragonStaffRenderer`, `SummonItemRenderer`
- `client/model/`: `GalathNpcModel`, `GalathModel`, `ManglelieNpcModel`, `GalathCoinModel`, `DragonStaffModel`, `SummonItemModel`
- `client/particle/`: `DragonBreathParticle` (extends `TextureSheetParticle`; constructor `(ClientLevel, x, y, z)`; `BREATH_SCALE` stays a `@JvmField` static)

**Depends on:** S1 (base, `PartEntity` contract, damage sources), S2 (`GirlRenderer` hook contract §2.9, `GirlModel`, `BodyParts`, `ModelBones`), S3 (`EscapeMinigameHud`, `GalathFlightHud`, `GirlCameraHelper` statics), S6 (`GoblinEntity` interactions used by Galath, if any).

### S6: Kobold and Goblin families, tribes, eggs (about 11.4k lines)
- `entity/`: `KoboldEntity`, `KoboldAnimation`, `KoboldInventory`, `KoboldMining`, `KoboldModelCode`, `KoboldNbtIO`, `KoboldTribeState`, `KoboldPlayerEntity`, `KoboldPlayerAnimations`, `KoboldEggEntity`, `KoboldEggProjectileEntity`, `GoblinEntity`, `GoblinAnimations`, `GoblinAppearance`, `GoblinEventHandler`, `GoblinInteractions`, `GoblinPersistence`, `GoblinQueenAi`, `GoblinThrow`, `GoblinPlayerEntity`, `GoblinPlayerAnimations`, `GoblinPlayerScene`, `GoblinPlayerThrow`
- `item/`: `KoboldEggItem`, `TribeEggItem`
- `util/`: `KoboldManager`, `KoboldTask`, `KoboldTaskBook`, `KoboldBlockLookup`, `TribeNbtIO`, `TribeBlockTracking`, `TribeState`
- `networking/`: `ClaimTribePacket`, `CancelTaskPacket`, `FallTreePacket`, `GetTribeUiValuesPacket`, `MinePacket`, `SendBlocksPacket`, `SendEggPacket`, `SetTribeFollowModePacket`

**Depends on:** S1 (base, the hoisted keys §2.6, `AbstractNpcOnlyEntity` / `AbstractKoboldPlayerEntity`), S2 (`KoboldRenderer` / `GoblinRenderer` read your keys; `StructureMarkerRenderer` static API for `SendBlocksPacket` client handling; `KoboldEggItemRenderer()`), S3 (`TribeNameScreen`, `StructureCommandScreen`, `GalathScreen` openers; `GuiHandler.openGui` for `KoboldEntity`), `MolangVars` (Kobold "size").

**Flattening work:** `KoboldMining` / `KoboldInventory` metadata switches must map through item identity (wool and dye colours by `DyeColor`).

### Ownership summary
- Registries, networking glue (`PacketHandler`, `TargetPoint`, the send helpers), `Main.kt`, proxies, event registration (`ForgeEventHandler`), base entity framework: **S1**.
- Render pipeline and `RenderHandler`: **S2**.
- Menus and screens, commands, worldgen, `data/` and `lang/` resources: **S3**.
- Character slices own their packets, items, and per-character event handlers (`.Events` / `.ClientEvents`).

---

## 4. Integration order and risks

### 4.1 Integration pass (one agent, after all slices finish)

1. **Compile in dependency order.** Run `./gradlew compileKotlin` and fix errors in this order: S1 (registry, `BaseGirlEntity`, packets) → S2 (`GirlRenderer`, `GirlModel`) → S3 → S4/S5/S6.
   - Reconciliation rule: when a caller and an owner disagree, the owner's signature wins if it follows this document. Otherwise fix the owner.
   - Apply the renames and hoisting requests from the slice summaries.
   - The integration pass alone may add `META-INF/accesstransformer.cfg` (declared in `neoforge.mods.toml`) or mixins (`[[mixins]]`) for PORT-GAPs.
2. **Data generation sanity.** Check that every `ModSounds` ID exists in `sounds.json`, every geo and animation path referenced by models exists, and every lang key is present.
3. **`./gradlew runClient`.** For each spawn egg and player form: spawn, check rendering and animations (idle, walk, eyes), interact, open menus, start a scene, check sound-keyframe progression, cum, and the R-Shift exit (see the `DOCUMENTATION.md` "jar-faithful" checklist). Also: horny potion brewing and transformation, the kobold tribe flow, Galath flight and riding, goblin carry and throw, Luna fishing, Allie lamp wishes, custom model download, worldgen houses (new world).
4. **`./gradlew runServer`** to a dedicated server "Done", then connect a client: no `client`-class loading on the server (§2.3). Repeat the scene smoke test across the network.
5. Old 1.12 worlds are out of scope; nothing has to load them.

### 4.2 Risk list (most likely to break first)

1. **GeckoLib custom render pipeline** (`GirlRenderer` / `GirlRendererBase` / `Galath` / `ManglelieRenderer`). Areas: the Steve-skin second pass (texture switch inside one `actuallyRender`), the per-bone tint and armor alpha, custom-part bone hiding, the `Head2` first-person hide, held items on `weapon`, the `body2` threesome split, and normal flipping on flat cubes. Verify bone rotation signs (GL4 bakes −X/−Y like GL3, but check head-look and custom bone writes visually).
2. **Bone math and camera.** `GirlBoneMath` + `util.MatrixStack` must reproduce GL3 exactly (row/column naming: vecmath `m03` is joml `m30()`). `applyCameraBone` offsets feed `GirlCameraHelper`. Camera overrides move from `CameraSetup`/`RenderTickEvent` to `ViewportEvent.ComputeCameraAngles` and `RenderFrameEvent`; camera position overrides may need a mixin on `Camera.setup`. `SetPlayerCamPacket` uses `CameraType`.
3. **Player movement lock.** `HandlePlayerMovement` maps to `MovementInputUpdateEvent` and `CalculatePlayerTurnEvent`. `PlayerSPPushOutOfBlocksEvent` has no equivalent (gap). The player-girl form relies on `makeBoundingBox`, a hidden vanilla player, and `InHandMapRenderer` reflection on `ItemInHandRenderer` fields.
4. **Synced-data aliasing** (§2.6). A missed sibling read is a runtime ClassCastException, not a compile error. Also watch the Kotlin initializer reset after super-constructor callbacks (§2.2) and `registerGoals` being server-only in 1.21.
5. **Dedicated-server class loading.** About 50 common files used `Minecraft` directly; any leak is fatal on the server.
6. **Custom model system.** Runtime GeckoLib model injection must go through the `getBakedModel` / `getAnimation` overrides because GeckoLib's caches are rebuilt on reload. Payload limits are 32 KiB server-bound and about 1 MiB client-bound; NeoForge splits large payloads only between NeoForge peers, so keep the Java chunking in `DownloadServerModelPacket` / `UploadModelStringPacket`. Archive parsing in `reskin/*` is pure Java and low risk.
7. **Animation JSON on GeckoLib 4.** Watch the loop types, `sound_effects` keyframe parsing, and custom molang variables (`size`, `pitch`, `armpitch`, `armyaw`, `yaw` via `MolangVars`). Missing animation names stop the controller; check the logs.
8. **Worldgen.** Chunk-load hook timing, structure NBT data-fixing from 1.12 (block flattening inside templates), the new `data/sexmod/structure` path, and biome mapping. `CommandLocateGoblinLair` depends on `ConfigWorldGenHandler` saved data.
9. **The Flattening.** Wool and dye metadata in kobold mining and inventory, the GUI payment-item comparison, `RemoveItemsPacket`, and the red-flower brewing ingredient.
10. **Event semantic shifts.** `LivingHurt`/`LivingAttack` both become `LivingIncomingDamage` (ordering and cancellation), tick Pre/Post phases, the button injection in the inventory screen (`ScreenEvent.Init.Post`), and `EventBus.register` throwing on objects with no handlers.
11. **Multipart Galath** (`PartEntity` IDs and hit routing), and `DragonEntity` / `SexEntity` network spawning with extra constructor data. Re-sync anything the Java set in client constructors through synced data or `IEntityWithComplexSpawn`.
12. **Data-driven damage types and loot tables** (JSON required), MobEffect holders, and the potion registry.
13. **Swing debug windows on a headless client** (guard with `GraphicsEnvironment.isHeadless()`). `ShaderHelper` becomes a spectator shader only.
14. **Shared static state across the integrated server and client threads** (girl lists, `GirlRegistry`). Behaviour is unchanged, but `onWorldClosed` must still clear every static listed in `Main`.
