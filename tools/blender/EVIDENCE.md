# Result and verification boundaries

**Yes: Blender meshes can produce this mod's rotated-plane Bedrock geometry, with exact conversion for rectangular quads.** This is a proven constrained modelling pipeline, not a general sculpt/weighted-mesh converter. An ordinary sphere's tapered or warped quads cannot be represented exactly by this format; a new curved cylindrical shell was used to test the representable case. No polymesh feature is required.

The working branch is `blender-pipeline`. All scripts, fixtures, logs and images are in `tools/blender/`. The repository's production resources are restored. The final commit SHA is reported by the agent; this file cannot contain its own commit hash.

## Galath round trip

`prove.py` imports the actual NeoForge Galath resource into Blender, creating **253 real armature bones and 2,759 mesh objects**, assigns UV loops and rigid vertex groups, exports the scene, and saves `evidence/galath-roundtrip.blend`. Its cube meshes include all source vertices, zero dimensions, inflation and negative dimensions. The importer retains cube organization, face labels and non-geometric flags as custom properties; it does not cache original positions, dimensions, rotations or UV arrays for replay. The exporter reconstructs those values from Blender vertices, object/pose transforms and UV loops. An edit check confirms translation changes the export and vertex distortion is rejected.

`compare.py` independently compares the original JSON against `evidence/galath-roundtrip.geo.json`. The full per-bone cube counts and errors are in `evidence/roundtrip-comparison.json`. All **253 bone names, parents and per-bone cube counts** match; total cubes remain **2,759**. The threshold is 0.0001 pixels/degrees.

| Quantity | Maximum absolute error |
| --- | ---: |
| Cube origin | 0.000001861 model pixels |
| Cube size | 0.000002289 model pixels |
| Cube pivot | 0.000001526 model pixels |
| Cube Euler rotation | 0.000006813 degrees |
| Face UV / UV size | 0.000005000 texture pixels |
| Rotated cube corner before bone transforms | 0.000004263 model pixels |
| Bone pivot | 0.000001526 model pixels |
| Bone Euler rotation | 0.000006508 degrees |
| Cube corner after full bone hierarchy | 0.000003799 model pixels |

These are small floating-point differences from Blender's single-precision storage, not bit-identical JSON. The round trip preserves original `inflate` values, description fields and signed dimensions. It is not an animation round trip; external animation JSON is unchanged.

## New curved asset and independent rendering

`prove.py` creates a genuinely new Blender mesh from scratch: a curved, open cylindrical shell with **128 rectangular quads**, a two-bone hierarchy with nonzero rotations, per-face UVs, and an asymmetric colored checker atlas. It creates no Bedrock cube objects before export. The exporter generates **128 rotated planes**, each with one north face, into `evidence/curved_shell.geo.json`.

Open `evidence/curved_shell.blend` for the editable source. Its texture is packed and also saved as `evidence/curved_shell.png`.

- `evidence/source-blender.png`: actual Blender Cycles CPU render of the source mesh and armature, with an emission texture material.
- `evidence/exported-independent.png`: Python/NumPy/Pillow software-rasterized render from only exported JSON, PNG and camera parameters. It imports no Blender APIs or exporter code and reconstructs X reflection, cube rotations, parented bone transforms and UV sampling independently.
- `evidence/render-comparison.json`: **0.999409 silhouette intersection-over-union**; **0.50970/255 mean RGB error** over 68,579 interior pixels; 99th percentile channel error 4/255. The comparison excludes silhouettes and high-contrast texture boundaries to account for Cycles antialiasing versus point sampling. Lighting is intentionally absent so geometry/UV errors remain visible.
- `evidence/rejection-tests.json`: malformed warped quad, skewed UV island and blended vertex weights each cause an export error.
- `evidence/roundtrip-edit-check.json`: imported object translation changes exported geometry; a nonrectangular imported mesh is rejected.

The independent renderer intentionally implements only the fixture's single north-face, zero-depth, no-inflate cubes. It is not advertised as a general Galath renderer. The Galath numeric comparison and GeckoLib bake cover the broader legacy geometry.

## Schema and Blockbench

`validate.py` validates **both exported files** offline against the unmodified vendored Bedrock Studio draft-07 geometry schema and its meta definitions. Result: `evidence/schema-validation.log`.

Blockbench has not been launched as a full GUI here. Instead `check_blockbench.cjs` executes the **actual `parseCube` function (with trailing whitespace normalized) from Blockbench v4.12.6** using a small editor Cube data stub. The harness checks every imported cube's reflected origin, size, enabled faces, UV rectangle and up/down UV inversion. It also checks bone-name uniqueness and parent references. Result: `evidence/blockbench-import.json`: **PASS**, 2,759/128 cubes and 4,992/128 visible faces. This is direct importer-function evidence, not a claim of testing Blockbench's entire application, WebGL renderer, UI, or newest version.

Source: [Blockbench v4.12.6 Bedrock codec](https://github.com/JannisX11/blockbench/blob/v4.12.6/js/io/formats/bedrock.js). Schema: [Bedrock Studio geometry schema](https://github.com/bedrock-studio/bedrock-json-schemas/blob/master/geometry.1.12.0.json). Vendored licenses and source hashes are recorded in `vendor/`.

## Actual mod dependency, build, and client

`GeckoProbe.java` runs against the **project's actual resolved GeckoLib 4.9.3 and Minecraft runtime classes**. It uses `KeyFramesAdapter.GEO_GSON`, `GeometryTree.fromModel`, and `BakedModelFactory.DEFAULT_FACTORY.constructGeoModel`, then traverses the baked bones/cubes/quads. No replacement parser or Minecraft class stubs are used.

`evidence/geckolib-probe.log` records:

```text
Galath: version=V_1_12_0 bones=253 cubes=2759 visible_quads=4992
Fresh:  version=V_1_12_0 bones=2   cubes=128  visible_quads=128
```

`prove_build.py` temporarily swaps the Galath resource for the exported round trip and runs `./gradlew build` inside the root Nix development shell/JDK 21 environment. It then verifies the jar contains **exactly the exported bytes**, and restores the original resource in `finally`. Result: **BUILD SUCCESSFUL**, `evidence/build-swapped.log` and `evidence/build-swap.json` contain matching SHA256 hashes and `original_restored: true`. Gradle reports `test NO-SOURCE`; a successful build by itself does not parse models. That is why the real GeckoLib baker probe is included separately.

`prove_client.py` also starts the **actual mod client on isolated Xvfb display :193**, using software rendering and a null audio device, with round-tripped Galath plus the fresh asset temporarily present in the mod's resource set. `evidence/client.log` identifies Fapcraft 2.0.0-alpha, GeckoLib 4.9.3 and the mod resource reload. `evidence/client-startup.png` shows Minecraft's first-run accessibility screen after startup. `evidence/client-smoke.json` records hashes, verifies the classpath resource bytes, and confirms restoration. The client is stopped deliberately after capture.

**Unverified:** rendering a spawned Galath or the new mesh in a Minecraft world, animation playback, gameplay camera/bone interactions, and the full Blockbench GUI. The client screenshot proves startup only. The fresh fixture is not wired to a new entity renderer; adding a new character still requires the mod's normal registration and resource wiring.

## Important artist limitations

Exact new geometry requires rectangular planar faces and rectangular, axis-aligned UV islands. A surface can curve across many such faces; individual faces cannot taper or warp. Every face is rigidly assigned to one bone, so smooth skinning is unavailable. Keep existing bone names and pivots for the existing animations and code. Export neither materials nor animation data; maintain a separate PNG atlas and the original animation files. Geometry units are pixels, 16 per block. See the artist steps in [README.md](README.md).
