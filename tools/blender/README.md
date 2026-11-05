# Blender → Blockbench → Fapcraft

**Working, with a specific modelling rule: surfaces must be made of planar rectangular quads.** This pipeline exports those faces as rotated, zero-thickness Bedrock cubes, just like the existing models. It also imports existing cuboids/planes with a rigid armature for editing. It does **not** turn an arbitrary sculpt, ordinary UV sphere, triangle mesh, or skinned character into an equivalent Minecraft entity. Four vertices alone do not make a face representable: a trapezoid or warped quad cannot be encoded as one rectangular Bedrock plane.

The tested runtime is NeoForge 1.21.1 with GeckoLib **4.9.3**, using Bedrock geometry **1.12.0**. The exact proof and its limits are in [EVIDENCE.md](EVIDENCE.md). No production model or texture is replaced by this commit.

## Artist workflow

1. Start Blender with an empty scene (delete the default cube). Tested with Blender **5.2.3 LTS**; other versions have not been verified.
2. Switch to the **Scripting** workspace. In the Text Editor, choose **Open**, open `tools/blender/pipeline.py`, then choose **Run Script** (Alt-P). Do this once per Blender session. This adds **Fapcraft Bedrock geometry** to the File → Import and File → Export menus. No external Blender add-on is required.
3. Choose **File → Import → Fapcraft Bedrock geometry** and open the girl's existing `.geo.json` from `neoforge/src/main/resources/assets/sexmod/geo/<girl>/`. Save your working file as a `.blend` somewhere separate from the game resources. Alternatively open the supplied `evidence/curved_shell.blend` to experiment with the new mesh example, then run `pipeline.py` to enable the menus.
4. The Outliner contains a **Bedrock (16 pixels = 1 block)** root, a **Bedrock skeleton**, and mesh pieces. Keep the root's rotation and scale. The root converts the file's Y-up pixel coordinates to Blender's Z-up block coordinates. **16 texture/model pixels = 1 Minecraft block = 1 Blender world unit** with this root. Imported meshes' local coordinates are in pixels; the root supplies the 1/16 scale. Do not apply the root transform.
5. Keep every existing **bone name**, its parent, and its pivot unless you are deliberately changing the animation rig. Code and animation files refer to exact names such as `head`, `torso`, `boobs`, `boobL`, `boyCam`, `girlCam`, `hornL` and the wing bones. Renaming or deleting these can break gameplay even if the file opens. Existing animations remain separate files; this tool exports geometry only.
6. For existing pieces, edit vertices in **Edit Mode** while retaining the rectangular box/plane shape and the eight source vertices (some coincide on planes). Move or rotate pieces in Object Mode. These pieces carry cube tags so they remain one cube and retain inflate, signed dimensions and their original enabled faces. Do not subdivide or delete their vertices or faces. Imported inflate is displayed in the mesh; the exporter subtracts it when reconstructing the base dimensions. To change inflate consistently, reimport a file with the new inflate value; changing a custom inflate tag alone also changes the reconstructed base size.
7. To build a new surface, make a new mesh, parent it **directly to the Bedrock root**, and apply its object scale. The easiest exact curved surface is a cylinder wall: rectangular strips arranged around a curve. Remove triangle/n-gon caps. The supplied shell is 32 strips × 4 rows. Keep faces flat, with right angles and opposite edges parallel. Ordinary sphere latitude faces taper and will be rejected. For compound curved anatomy, design rectangular patches or explicitly accept a separate approximation/remeshing step; this exporter supplies no automatic approximation.
8. Add an **Armature modifier** targeting Bedrock skeleton. In Object Data → Vertex Groups, assign **every vertex to exactly one existing bone group at weight 1.0**. All four vertices of a face must use the same bone. Duplicate vertices at joints when adjacent faces belong to different bones. Weight blending, shape keys, deformation constraints, pose translation/scaling and animation export are not supported. Apply mesh modifiers before export; the exporter rejects modifiers other than Armature. Use XYZ Euler pose rotation. For new bones, use Edit Mode, keep the rest bone pointing along the root's local +Y with zero roll, and place its head at the intended pivot.
9. In UV Editing, give every new face an **axis-aligned rectangular UV island**. Mirrored UV rectangles are allowed. Skewed, diagonal or warped islands are rejected. Islands can overlap or share texture space. The exporter tries cyclic UV corner orderings for new faces; imported cubes must retain their corner ordering. Use one texture atlas per model. The root's Custom Properties `texture_width` and `texture_height` are the atlas dimensions in pixels; `identifier` is the geometry name. When changing resolution, also update the UV layout to the intended pixel coordinates.
10. For texture preview, open the girl's PNG from `neoforge/src/main/resources/assets/sexmod/textures/` and assign an Image Texture material to the meshes. Imported geometry does not automatically locate or assign textures. Geometry export reads the active UV map and root texture dimensions; Blender materials themselves are not exported. Save your painted atlas as PNG separately. Set image interpolation to **Closest** for a pixel-art preview.
11. Choose **File → Export → Fapcraft Bedrock geometry**, saving to a new `name.geo.json`. Leave the rig in the base pose you want written as the model's bone rotations. The exporter reads mesh data in its rest space; do not Apply Pose as Rest Pose or bake the Armature modifier. An error message means the model violates a format rule; fix it instead of trusting a partial file. Files are written only after all geometry has passed checks.
12. In Blockbench choose **File → Open Model**, select the exported `.geo.json`, and open it as Bedrock geometry if prompted. Import the matching PNG into the Textures panel. Check the silhouette, UV seams, pivots and bone names. Save a `.bbmodel` copy if you want a Blockbench working project. This environment tested Blockbench's actual cube importer function headlessly, with editor data stubs; it did not automate the full Blockbench GUI.
13. To use the result for an **existing girl**, back up her file and copy your export to the exact `.geo.json` path referenced by her model in `neoforge/src/main/resources/assets/sexmod/geo/<girl>/`. Keep the original geometry identifier and bone names. If you enlarge the silhouette, adjust visible bounds in Blockbench so the model is not culled too early. Put the atlas at the texture path already referenced by that girl's renderer/model, with matching dimensions. Keep her animation files in `animations/<girl>/`. From the repository root run:

    ```sh
    nix develop -c sh -c 'cd neoforge && ./gradlew build'
    ```

    The mod jar appears in `neoforge/build/libs/`. Launch your development client with `nix develop -c sh -c 'cd neoforge && ./gradlew runClient'` and inspect the girl in a test world. A new geometry filename alone does not create a new entity: a new character also needs entity/model/renderer registration, texture and animation resource references in code.

## Reproduce the evidence

Run these from the repository root. You can run the complete ordered sequence with `nix develop -c tools/blender/verify.sh`. The Nix development shell includes Blender, Python with NumPy/Pillow/jsonschema, Node, and JDK 21. The two `.blend` fixtures are compressed; generation takes a few seconds on this machine. Generation overwrites files under `evidence/`, including the two fixtures. Do not run generation, comparison, and temporary resource-swap probes simultaneously.

```sh
nix develop -c blender --background --factory-startup --python-exit-code 1 --python tools/blender/prove.py
nix develop -c python tools/blender/compare.py neoforge/src/main/resources/assets/sexmod/geo/galath/galath.geo.json tools/blender/evidence/galath-roundtrip.geo.json --output tools/blender/evidence/roundtrip-comparison.json
nix develop -c python tools/blender/validate.py tools/blender/evidence/galath-roundtrip.geo.json tools/blender/evidence/curved_shell.geo.json
nix develop -c node tools/blender/check_blockbench.cjs tools/blender/evidence/galath-roundtrip.geo.json tools/blender/evidence/curved_shell.geo.json
nix develop -c python tools/blender/render_geo.py
nix develop -c tools/blender/gecko_probe.sh
nix develop -c python tools/blender/prove_build.py
```

`prove_build.py` temporarily replaces Galath, runs the real build, compares the jar resource bytes to the export, and restores the original in `finally`. The Gecko probe uses the project's resolved runtime classpath and GeckoLib's real Gson adapters, hierarchy builder, and baked model factory; it does not mock Minecraft/GeckoLib classes.

The optional isolated client test needs temporary Xvfb and ImageMagick tools. It uses display `:193`, software rendering and a null sound driver, captures startup, stops its process group, and restores the resource files. It does not spawn a girl in a world or prove animation playback. Ensure `:193` is free first:

```sh
nix-shell -p xorg-server imagemagick --run 'nix develop -c python tools/blender/prove_client.py "$(command -v Xvfb)" "$(command -v import)"'
```

## Why this tool rather than mcblend?

[mcblend's own documentation](https://github.com/Nusiq/mcblend/blob/master/docs/modeling/fixing_invalid_cubes.md) says non-cuboids in cube mode are skipped, and recommends either fixing/separating cuboids or using experimental polymeshes. Its separate-and-align operator works on disconnected cuboids. That does not establish an arbitrary quad-mesh-to-plane workflow for this mod. This investigation did not install or execute mcblend, and makes no claim about every mcblend feature. The custom exporter directly tests the rotated-plane representation already used by Galath, avoids polymeshes, and rejects shapes the representation cannot encode exactly.

The vendored Bedrock Studio schema and Blockbench importer excerpt retain their upstream licenses in `vendor/`. Source URLs and hashes are recorded in `vendor/SOURCES.json`.
