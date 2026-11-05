"""Blender regression: export live Edit Mode geometry/UVs without leaving Edit Mode.
Run: nix develop -c blender --background --factory-startup --python-exit-code 1 --python tools/blender/test_edit_export.py
"""
import json
import sys
import tempfile
from pathlib import Path

import bmesh
import bpy

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pipeline

bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
root, rig = pipeline.setup(
    {'identifier': 'geometry.edit_mode_test', 'texture_width': 128, 'texture_height': 128},
    [{'name': 'body', 'pivot': [0, 0, 0]}],
)
mesh = bpy.data.meshes.new('Editable rectangle')
mesh.from_pydata([(0, 4, 0), (4, 4, 0), (4, 0, 0), (0, 0, 0)], [], [(0, 1, 2, 3)])
mesh.uv_layers.new()
for loop, uv in zip(mesh.polygons[0].loop_indices, [(0.25, 1), (0, 1), (0, 0.75), (0.25, 0.75)]):
    mesh.uv_layers.active.data[loop].uv = uv
obj = bpy.data.objects.new('Editable rectangle', mesh)
bpy.context.collection.objects.link(obj)
obj.parent = root
pipeline.bind(obj, rig, 'body')
bpy.ops.object.select_all(action='DESELECT')
obj.select_set(True)
bpy.context.view_layer.objects.active = obj

with tempfile.TemporaryDirectory() as directory:
    path = Path(directory) / 'edit.geo.json'
    original = pipeline.export_geo(path)['minecraft:geometry'][0]['bones'][0]['cubes'][0]
    bpy.ops.object.mode_set(mode='EDIT')
    edit = bmesh.from_edit_mesh(mesh)
    for vertex in edit.verts:
        vertex.co.y += 2
    uv_layer = edit.loops.layers.uv.active
    for face in edit.faces:
        for loop in face.loops:
            loop[uv_layer].uv.x += 0.125
    # Intentionally no update_edit_mesh() or mode switch before exporting.
    changed = pipeline.export_geo(path)['minecraft:geometry'][0]['bones'][0]['cubes'][0]
    assert abs(changed['origin'][1] - original['origin'][1] - 2) < 1e-4, changed
    assert abs(changed['uv']['north']['uv'][0] - original['uv']['north']['uv'][0] - 16) < 1e-4, changed
    assert obj.mode == 'EDIT', 'Export must preserve the artist editing mode'

    edit = bmesh.from_edit_mesh(mesh)
    edit.verts.ensure_lookup_table()
    edit.verts[0].co.z += 0.5
    previous = path.read_bytes()
    try:
        pipeline.export_geo(path)
    except ValueError as error:
        assert 'warped/trapezoidal' in str(error), error
    else:
        raise AssertionError('Live invalid Edit Mode geometry was silently accepted')
    assert path.read_bytes() == previous, 'Failed export must preserve the previous output'
    assert obj.mode == 'EDIT'

print(json.dumps({'edit_mode_geometry': 'PASS', 'edit_mode_uv': 'PASS',
                  'edit_mode_invalid_face_rejected': 'PASS', 'editing_mode_preserved': 'PASS'}, indent=2))
