# Asset renames (Forge 1.12.2 -> NeoForge 1.21.1)

Old tree: `src/main/resources/assets/sexmod/`. New tree: `neoforge/src/main/resources/`.
Code that builds these ResourceLocations or translation keys by hand must use the new value.

## Moved directories

| Old | New | Kind |
|---|---|---|
| `assets/sexmod/textures/items/` | `assets/sexmod/textures/item/` | assets |
| `assets/sexmod/lang/*.lang` | `assets/sexmod/lang/*.json` | assets |
| `assets/sexmod/loot_tables/<n>.json` | `data/sexmod/loot_table/<n>.json` | data (ids unchanged: `sexmod:jenny`, `sexmod:ellie`, `sexmod:slime`, `sexmod:bia`) |
| `assets/sexmod/recipes/` | `data/sexmod/recipe/` | data |
| `assets/sexmod/structures/<n>.nbt` | `data/sexmod/structure/<n>.nbt` | data (ids unchanged: `sexmod:<n>`, load via `StructureTemplateManager`) |

geo/, animations/, sounds/, sounds.json, textures/{entity,gui,player} and the root-level textures
(cross, cummy, line, mark, star) keep their paths. No geo/animation/sound/structure file name needed lowercasing.

## Texture paths

| Old | New | Note |
|---|---|---|
| `sexmod:textures/items/galath_coin/galath_coin.png` | `sexmod:textures/item/galath_coin/galath_coin.png` |  |
| `sexmod:textures/items/wand/wand.png` | `sexmod:textures/item/wand/wand.png` |  |
| `sexmod:textures/items/wand/wand_active.png` | `sexmod:textures/item/wand/wand_active.png` |  |

## Added texture copy

| Old | New | Note |
|---|---|---|
| `sexmod:textures/entity/kobold/egg.png (as item sprite sexmod:entity/kobold/egg)` | `sexmod:textures/item/tribe_egg.png (sprite sexmod:item/tribe_egg)` | original stays in place for entity rendering; 1.21 blocks atlas only stitches textures/block + textures/item |

## Lang files

| Old | New | Note |
|---|---|---|
| `lang/en_id.lang` | `lang/id_id.json` | locale code fixed (en_id is not a vanilla locale; content is Indonesian) |
| `lang/ne_be.lang` | `lang/nl_nl.json` | locale code fixed (ne_be is not a vanilla locale; content is Dutch) |

## Lang keys

| Old | New | Note |
|---|---|---|
| `effect.horny_potion` | `effect.sexmod.horny_potion` |  |
| `entity.allie.name` | `entity.sexmod.allie` |  |
| `entity.bee.name` | `entity.sexmod.bee` |  |
| `entity.bia.name` | `entity.sexmod.bia` |  |
| `entity.ellie.name` | `entity.sexmod.ellie` |  |
| `entity.galath.name` | `entity.sexmod.galath` |  |
| `entity.goblin.name` | `entity.sexmod.goblin` |  |
| `entity.jenny.name` | `entity.sexmod.jenny` |  |
| `entity.luna.name` | `entity.sexmod.luna` |  |
| `entity.manglelie.name` | `entity.sexmod.manglelie` |  |
| `entity.slime.name` | `entity.sexmod.slime` |  |
| `item.allies_lamp.name` | `item.sexmod.allies_lamp` |  |
| `item.dragon_staff.name` | `item.sexmod.dragon_staff` |  |
| `item.galath_coin.name` | `item.sexmod.galath_coin` |  |
| `item.item_map_secret.name` | `item.sexmod.item_map_secret` |  |
| `item.kobold_egg_item.name` | `item.sexmod.kobold_egg_item` |  |
| `item.luna_rod.name` | `item.sexmod.luna_rod` |  |
| `item.npc_editor_wand.name` | `item.sexmod.npc_editor_wand` |  |
| `item.tribe_egg.name` | `item.sexmod.tribe_egg` |  |
| `lingering_potion.effect.horny_potion` | `item.minecraft.lingering_potion.effect.horny_potion` |  |
| `potion.effect.horny_potion` | `item.minecraft.potion.effect.horny_potion` |  |
| `splash_potion.effect.horny_potion` | `item.minecraft.splash_potion.effect.horny_potion` |  |
| `tile.fire_no_spread.name` | `block.sexmod.fire` |  |
| `tipped_arrow.effect.horny_potion` | `item.minecraft.tipped_arrow.effect.horny_potion` |  |

## Recipe ids

| Old | New | Note |
|---|---|---|
| `sexmod:dragonStaff` | `sexmod:dragon_staff` | recipe id |

All other lang keys (action.*, jenny.*, window.*, genderswap.*, ...) are kept verbatim.

## Notes for code porters

- **`textures/items/winchester/winchester.png`** is referenced by `SummonItemModel` but never existed in the 1.12 tree either; if it gets added, put it at `textures/item/winchester/winchester.png`.
- **Horny potion effect**: the 1.12 `Potion` registry name was `sexmod:horny potion` (with a space), which is not a valid ResourceLocation. The lang file assumes the 1.21 `MobEffect` is registered as `sexmod:horny_potion` (`effect.sexmod.horny_potion`) and the `Potion` as `sexmod:horny_potion` with base name `horny_potion` (vanilla builds `item.minecraft.<potion|splash_potion|lingering_potion|tipped_arrow>.effect.horny_potion`).
- **Fire block** (`sexmod:fire`): the 1.12 key `tile.fire_no_spread.name` was never used (translation key was `fire`). It now maps to `block.sexmod.fire`. `blockstates/fire.json` references `minecraft:block/fire_*` models, so the block must expose the same `north/east/south/west/up` boolean properties (e.g. extend `FireBlock`/`BaseFireBlock` with those properties).
- **Lang `%d` args**: vanilla `Component.translatable` only accepts `%s`; keys containing other format specifiers have to go through `I18n.get(...)` (String.format). The current files only use `%s`.
- **`ne_be.lang` -> `nl_nl.json`, `en_id.lang` -> `id_id.json`**: the 1.12 file names were not real Minecraft locale codes, so they never loaded. Renamed to the matching vanilla locales (Dutch, Indonesian).
- **Item models**: GeckoLib/BEWLR-rendered items use `"parent": "builtin/entity"`: `allies_lamp`, `dragon_staff`, `galath_coin`, `kobold_egg_item`, `winchester` (plus unused `custom_model`). Their items need a GeckoLib `GeoItemRenderer` via `createGeoRenderer`/`IClientItemExtensions`. Forge `forge_marker`/`defaults` were removed from `kobold_egg_item.json`.
- **`npc_editor_wand`**: 1.12 picked `npc_editor_wand_active` via item metadata 1. The 1.21 model has an override on the predicate `sexmod:active` (value 1 = active). Register it with `ItemProperties.register(EDITOR_WAND, ResourceLocation.fromNamespaceAndPath("sexmod", "active"), ...)`.
- **`luna_rod`**: 1.12 reused the vanilla `fishing_rod` model. The new `models/item/luna_rod.json` mirrors the vanilla fishing rod, including the `minecraft:cast` override. For it to work, register the `minecraft:cast` item property for `LUNA_ROD` (vanilla only registers it for `Items.FISHING_ROD`).
- **`tribe_egg`**: the item model used `sexmod:entity/kobold/egg`, which the 1.21 blocks atlas does not stitch, so it now points at the copy `sexmod:item/tribe_egg`.
- **Kobold egg color**: 1.12 used item metadata (wool color). 1.21 has no metadata, so it has to become a data component.
- **Loot tables**: converted to `type: minecraft:entity` with `uniform` set_count, and the 1.12 pool `name` was dropped. The Allies Lamp chest injection (`LootTableLoadEvent` in 1.12) needs a NeoForge global loot modifier or `LootTableLoadEvent`, which is code, not data.
- **Recipe** `sexmod:dragonStaff` is now `sexmod:dragon_staff` (uppercase is invalid). Results are `{"id", "count"}` and `category: equipment` was added. All ingredient ids (`end_crystal`, `gold_block`, `stick`, `gold_ingot`) are the same in 1.21, and none of them used metadata.
- **sounds.json**: copied unchanged and still valid for 1.21. The 1.21 loader ignores the `category` field. `subtitle` values are bare words (e.g. `moan`) with no lang entry, which is the same behavior as 1.12.

## Structures: DataVersion

All 7 files have a `DataVersion` tag, so 1.21 runs the structure DataFixer on load:

| File | DataVersion | Notes |
|---|---|---|
| alex.nbt | 2865 (1.18.1) | 128x69x129 map-art (mapartcraft) |
| bia.nbt, ellie.nbt, goblin.nbt, jenny.nbt, luna.nbt, ssa.nbt | 1343 (1.12.2) | Use legacy block names such as `minecraft:bed` and `minecraft:log`, which the DataFixer flattens on load. Entities/tile entities are also fixed. |

## Not migrated (intentionally)

- `mcmod.info`, `sexmod-build.properties` (both replaced by `neoforge.mods.toml`/build metadata), `pack.mcmeta` (NeoForge generates the mod pack metadata).
- `models/spider.mp4`, `models/block/fire_no_spread.txt` (not resources). `models/item/stick.json` (invalid JSON with a trailing comma, and no item uses it).
- Sound-folder files that `sounds.json` does not reference: 29 files, namely the helper scripts `*.py` and the `misc/belljingle/Jenny_Bell jingle  (N).mp3` source files. Their names contain spaces, capitals and parentheses, which are invalid paths, and they are unused because `belljingle0.ogg` is the registered sound.
- `banner.txt` **was** copied (to the jar root), because `Main` reads it via `getResourceAsStream("/banner.txt")`.

## Constant expressions folded

Keyframe values that were constant-only arithmetic strings (e.g. `"261.0538-360"`, `"360*2"`) are written as plain numbers in `animations/{bia,cat,ellie,galath,goblin,jenny,manglelie,slime}` (1,720 values). GeckoLib 4.9.3 folds such expressions after its degrees-to-radians conversion, so rotations came out in the wrong unit. Values with Molang variables (`size`, `pitch`, ...) are untouched.
