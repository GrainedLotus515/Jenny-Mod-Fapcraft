"""Blender-only reproducible fixture generation. See README for invocation."""
import bpy, sys, json, math
from pathlib import Path
from mathutils import Vector
sys.path.insert(0,str(Path(__file__).resolve().parent))
import pipeline as p
HERE=Path(__file__).resolve().parent
OUT=HERE/'evidence'
REPO=HERE.parent.parent


def clear():
    bpy.ops.object.select_all(action='SELECT'); bpy.ops.object.delete(use_global=False)


def material(image):
    mat=bpy.data.materials.new('Asymmetric UV checker'); mat.use_nodes=True
    n=mat.node_tree.nodes; n.clear()
    tex=n.new('ShaderNodeTexImage'); tex.image=image; tex.interpolation='Closest'
    emission=n.new('ShaderNodeEmission'); emission.inputs['Strength'].default_value=1
    output=n.new('ShaderNodeOutputMaterial')
    mat.node_tree.links.new(tex.outputs['Color'],emission.inputs['Color'])
    mat.node_tree.links.new(emission.outputs[0],output.inputs['Surface'])
    return mat


def camera_render(path):
    scene=bpy.context.scene
    scene.render.engine='CYCLES'; scene.cycles.samples=8
    scene.cycles.device='CPU'
    scene.render.resolution_x=640; scene.render.resolution_y=640; scene.render.resolution_percentage=100
    scene.render.image_settings.file_format='PNG'; scene.render.film_transparent=True
    scene.world.color=(0.035,0.035,0.035)
    scene.view_settings.view_transform='Standard'
    scene.view_settings.look='None'
    camdata=bpy.data.cameras.new('Proof camera'); camdata.type='ORTHO'; camdata.ortho_scale=2.7
    cam=bpy.data.objects.new('Proof camera',camdata); scene.collection.objects.link(cam)
    target=Vector((0,0,0.65)); cam.location=(2.8,-4,2.7)
    cam.rotation_euler=(target-cam.location).to_track_quat('-Z','Y').to_euler()
    scene.camera=cam
    bpy.context.view_layer.update()
    (OUT/'camera.json').write_text(json.dumps({'world_to_camera':[list(row) for row in cam.matrix_world.inverted()],
        'ortho_scale':camdata.ortho_scale,'width':640,'height':640},indent=2))
    scene.render.filepath=str(path); bpy.ops.render.render(write_still=True)


def fresh():
    clear()
    # A new open curved shell, built directly as rectangular mesh strips, not imported cubes.
    desc={'identifier':'geometry.curved_shell','texture_width':128,'texture_height':128,
          'visible_bounds_width':4,'visible_bounds_height':4,'visible_bounds_offset':[0,1,0]}
    r,rig=p.setup(desc,[{'name':'body','pivot':[0,0,0],'rotation':[0,12,0]},
                        {'name':'torso','parent':'body','pivot':[0,8,0],'rotation':[8,0,0]}])
    verts=[]; faces=[]; uvs=[]
    strips=32
    for i in range(strips):
        t0=-math.pi*.85+i*math.pi*1.7/strips
        t1=-math.pi*.85+(i+1)*math.pi*1.7/strips
        # Radius 8px, height 20px. Each of 4 horizontal rows is a rectangle.
        for j in range(4):
            y0=j*5; y1=(j+1)*5
            # order: top-left, top-right, bottom-right, bottom-left
            pts=[(8*math.sin(t0),y1,8*math.cos(t0)),(8*math.sin(t1),y1,8*math.cos(t1)),
                 (8*math.sin(t1),y0,8*math.cos(t1)),(8*math.sin(t0),y0,8*math.cos(t0))]
            k=len(verts); verts.extend(pts); faces.append([k,k+1,k+2,k+3])
            u=i/strips; w=1/strips; v=j/4; h=1/4
            uvs.append([(u+w,v+h),(u,v+h),(u,v),(u+w,v)])
    mesh=bpy.data.meshes.new('New curved rectangular-quad shell'); mesh.from_pydata(verts,[],faces); mesh.uv_layers.new()
    obj=bpy.data.objects.new(mesh.name,mesh); bpy.context.collection.objects.link(obj); obj.parent=r
    for f,coords in zip(mesh.polygons,uvs):
        for loop,uv in zip(f.loop_indices,coords): mesh.uv_layers.active.data[loop].uv=uv
    p.bind(obj,rig,'torso')
    img=bpy.data.images.new('UV proof',width=128,height=128)
    pixels=[]
    for y in range(128):
        for x in range(128):
            # colors are linear RGB here; rasterizer reads the saved sRGB texture.
            tile=(x//16+y//16)%2
            pixels.extend((0.8 if tile else 0.06,0.12+0.65*x/127,0.08+0.65*y/127,1))
    img.pixels=pixels; img.filepath_raw=str(OUT/'curved_shell.png'); img.file_format='PNG'; img.save(); img.pack()
    obj.data.materials.append(material(img))
    p.export_geo(OUT/'curved_shell.geo.json')
    # Negative tests must fail instead of silently approximating geometry/UV/weights.
    tests={}
    def rejected(name,mutate,restore):
        mutate()
        try: p.export_geo(OUT/'must_not_exist.geo.json')
        except ValueError as e: tests[name]=str(e)
        else: raise AssertionError(name+' should have failed')
        finally: restore()
    old=obj.data.vertices[0].co.copy()
    rejected('warped_quad',lambda:setattr(obj.data.vertices[0],'co',old+Vector((0.3,0.2,0.4))),lambda:setattr(obj.data.vertices[0],'co',old))
    olduv=obj.data.uv_layers.active.data[0].uv.copy()
    rejected('skewed_uv',lambda:setattr(obj.data.uv_layers.active.data[0],'uv',olduv+Vector((0.01,0.02))),lambda:setattr(obj.data.uv_layers.active.data[0],'uv',olduv))
    rejected('blended_weights',lambda:obj.vertex_groups[0].add([0],0.5,'REPLACE'),lambda:obj.vertex_groups[0].add([0],1,'REPLACE'))
    (OUT/'rejection-tests.json').write_text(json.dumps(tests,indent=2))
    camera_render(OUT/'source-blender.png')
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'curved_shell.blend'))


def roundtrip():
    clear()
    source=REPO/'neoforge/src/main/resources/assets/sexmod/geo/galath/galath.geo.json'
    r,rig=p.import_geo(source)
    p.export_geo(OUT/'galath-roundtrip.geo.json')
    # Check that export responds to real mesh/object edits rather than replaying cached cubes.
    obj=next(o for o in r.children if o.type=='MESH')
    group=obj.vertex_groups[0].name; index=obj['cube_index']
    before=json.loads((OUT/'galath-roundtrip.geo.json').read_text())
    obj.location.x+=1
    edited=p.export_geo(OUT/'edited-check.geo.json')
    a=next(b for b in before['minecraft:geometry'][0]['bones'] if b['name']==group)['cubes'][index]
    b=next(b for b in edited['minecraft:geometry'][0]['bones'] if b['name']==group)['cubes'][index]
    assert abs((b['origin'][0]-a['origin'][0])+1)<1e-4
    obj.location.x-=1
    old=obj.data.vertices[0].co.copy(); obj.data.vertices[0].co.x+=.123
    try: p.export_geo(OUT/'must_not_exist.geo.json')
    except ValueError: pass
    else: raise AssertionError('Imported vertex distortion should be rejected')
    obj.data.vertices[0].co=old
    (OUT/'edited-check.geo.json').unlink()
    (OUT/'roundtrip-edit-check.json').write_text(json.dumps({'translation_changes_export':'PASS','distorted_mesh_rejected':'PASS'},indent=2))
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'galath-roundtrip.blend'))
    print('ROUNDTRIP',len(rig.data.bones),'bones',sum(o.type=='MESH' for o in r.children),'mesh objects')

OUT.mkdir(exist_ok=True)
roundtrip()
fresh()
