#!/usr/bin/env bash
# Invoke as: nix develop -c tools/blender/verify.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
proof_dir=tools/blender/evidence
blender --background --factory-startup --python-exit-code 1 --python tools/blender/prove.py > "$proof_dir/blender.log" 2>&1
blender --background --factory-startup --python-exit-code 1 --python tools/blender/test_edit_export.py > "$proof_dir/edit-mode-export.log" 2>&1
python tools/blender/compare.py neoforge/src/main/resources/assets/sexmod/geo/galath/galath.geo.json "$proof_dir/galath-roundtrip.geo.json" --output "$proof_dir/roundtrip-comparison.json"
python tools/blender/validate.py "$proof_dir/galath-roundtrip.geo.json" "$proof_dir/curved_shell.geo.json" > "$proof_dir/schema-validation.log" 2>&1
node tools/blender/check_blockbench.cjs "$proof_dir/galath-roundtrip.geo.json" "$proof_dir/curved_shell.geo.json" > "$proof_dir/blockbench-import.json"
python tools/blender/render_geo.py
tools/blender/gecko_probe.sh > "$proof_dir/geckolib-probe.log" 2>&1
python tools/blender/prove_build.py > "$proof_dir/build-swapped.log" 2>&1
