"""Run in Blender's Text Editor to install File > Import/Export entries for this session."""
import bpy
import json
import math
from pathlib import Path
from mathutils import Vector, Matrix
from bpy_extras.io_utils import ImportHelper, ExportHelper

# GeckoLib VertexSet order, in its X-reflected, Y-up coordinate system.
CORNERS = [(0,0,0),(0,0,1),(0,1,0),(0,1,1),
           (1,1,0),(1,1,1),(1,0,0),(1,0,1)]
FACES = {'west':[3,2,0,1], 'east':[4,5,7,6], 'north':[2,4,6,0],
         'south':[5,3,1,7], 'up':[3,5,4,2], 'down':[0,6,7,1]}


def root():
    roots = [o for o in bpy.context.scene.objects if o.get('bedrock_root')]
    if len(roots) != 1:
        raise ValueError('Scene must contain exactly one imported/generated Bedrock root')
    return roots[0]


def setup(description, bones):
    r = bpy.data.objects.new('Bedrock (16 pixels = 1 block)', None)
    bpy.context.collection.objects.link(r)
    r['bedrock_root'] = True
    r['description'] = json.dumps(description)
    for key in ('texture_width','texture_height','identifier'): r[key] = description[key]
    r.rotation_euler[0] = math.pi/2
    r.scale = (1/16,)*3
    arm = bpy.data.armatures.new('Bedrock skeleton')
    rig = bpy.data.objects.new('Bedrock skeleton', arm)
    bpy.context.collection.objects.link(rig)
    rig.parent = r
    rig.show_in_front = True
    bpy.context.view_layer.objects.active = rig
    rig.select_set(True)
    bpy.ops.object.mode_set(mode='EDIT')
    for b in bones:
        bone = arm.edit_bones.new(b['name'])
        p = b.get('pivot', [0,0,0])
        bone.head = (-p[0],p[1],p[2])
        bone.tail = bone.head + Vector((0,1,0))
    for b in bones:
        if b.get('parent'):
            arm.edit_bones[b['name']].parent = arm.edit_bones[b['parent']]
    bpy.ops.object.mode_set(mode='OBJECT')
    for b in bones:
        bone = arm.bones[b['name']]
        bone['bedrock'] = json.dumps({k:v for k,v in b.items() if k != 'cubes'})
        pb = rig.pose.bones[b['name']]
        pb.rotation_mode = 'XYZ'
        x,y,z = b.get('rotation',[0,0,0])
        pb.rotation_euler = tuple(map(math.radians,(-x,-y,z)))
    return r,rig


def bind(obj, rig, bone):
    group = obj.vertex_groups.new(name=bone)
    group.add(list(range(len(obj.data.vertices))), 1, 'REPLACE')
    mod = obj.modifiers.new('Rigid bone', 'ARMATURE')
    mod.object = rig


def import_geo(path):
    data = json.loads(Path(path).read_text())
    if data['format_version'] != '1.12.0' or len(data['minecraft:geometry']) != 1:
        raise ValueError('Only single geometry format 1.12.0 is supported')
    geo = data['minecraft:geometry'][0]
    r,rig = setup(geo['description'], geo['bones'])
    tw,th = geo['description']['texture_width'],geo['description']['texture_height']
    for b in geo['bones']:
        for i,c in enumerate(b.get('cubes',[])):
            if not isinstance(c['uv'],dict) or c.get('mirror'):
                raise ValueError('Import requires per-face UV and no cube mirror')
            o,s = c['origin'],c['size']
            p = c.get('pivot',[0,0,0]); pivot = Vector((-p[0],p[1],p[2]))
            inflate = c.get('inflate',b.get('inflate',0))
            low = Vector((-o[0]-s[0]-inflate,o[1]-inflate,o[2]-inflate))
            verts = [low + Vector(tuple(a*(d+2*inflate) for a,d in zip(v,s))) - pivot for v in CORNERS]
            names = list(c['uv'])
            mesh = bpy.data.meshes.new(b['name']+f'_{i}')
            mesh.from_pydata(verts, [], [FACES[n] for n in names])
            mesh.uv_layers.new()
            for face,n in zip(mesh.polygons,names):
                entry = c['uv'][n]
                if entry.get('uv_rotation',0):
                    raise ValueError('UV rotation is outside format 1.12 subset')
                u,v = entry['uv']; w,h = entry['uv_size']
                coords = [(u+w,v),(u,v),(u,v+h),(u+w,v+h)]
                for loop,(a,d) in zip(face.loop_indices,coords):
                    mesh.uv_layers.active.data[loop].uv = (a/tw,1-d/th)
            obj = bpy.data.objects.new(mesh.name,mesh)
            bpy.context.collection.objects.link(obj)
            obj.parent = r
            obj.location = pivot
            x,y,z = c.get('rotation',[0,0,0])
            obj.rotation_euler = tuple(map(math.radians,(-x,-y,z)))
            obj['cube_extra'] = json.dumps({k:v for k,v in c.items() if k not in ('origin','size','uv','rotation','pivot')})
            obj['face_names'] = json.dumps(names)
            obj['uv_extra'] = json.dumps({n:{k:v for k,v in entry.items() if k not in ('uv','uv_size')} for n,entry in c['uv'].items()})
            obj['cube_index'] = i
            obj['had_rotation'] = 'rotation' in c
            obj['had_pivot'] = 'pivot' in c
            bind(obj,rig,b['name'])
    return r,rig


def face_bone(obj,face,rig):
    assigned = []
    for vi in face.vertices:
        weights = [(obj.vertex_groups[g.group].name,g.weight) for g in obj.data.vertices[vi].groups if g.weight > 1e-6]
        if len(weights)!=1 or abs(weights[0][1]-1)>1e-6 or weights[0][0] not in rig.data.bones:
            raise ValueError(f'{obj.name}: every vertex needs exactly one bone at weight 1')
        assigned.append(weights[0][0])
    if len(set(assigned))!=1:
        raise ValueError(f'{obj.name}: face crosses bone groups; split it at the joint')
    return assigned[0]


def uv_rect(obj,face,tw,th):
    if not obj.data.uv_layers.active:
        raise ValueError(f'{obj.name}: missing UV map')
    uv = [Vector((obj.data.uv_layers.active.data[i].uv.x*tw,
                  (1-obj.data.uv_layers.active.data[i].uv.y)*th)) for i in face.loop_indices]
    # Geometry and UV loops must use GeckoLib's corner order, try cyclic permutations.
    for shift in range(4):
        q = uv[shift:]+uv[:shift]
        if max(abs(q[0].x-q[3].x),abs(q[1].x-q[2].x),abs(q[0].y-q[1].y),abs(q[2].y-q[3].y)) < 1e-4:
            return shift, {'uv':list(q[1]),'uv_size':[q[0].x-q[1].x,q[2].y-q[1].y]}
    raise ValueError(f'{obj.name}: UV face must be an axis-aligned rectangle (rotate its UVs 90 degrees if needed)')


def export_geo(path):
    bpy.context.view_layer.update()
    r = root()
    rigs = [o for o in r.children if o.type == 'ARMATURE']
    if len(rigs)!=1: raise ValueError('Expected one skeleton')
    rig = rigs[0]
    if rig.matrix_basis != Matrix.Identity(4) or rig.matrix_parent_inverse != Matrix.Identity(4): raise ValueError('Do not transform the skeleton object')
    desc = json.loads(r['description'])
    for key in ('texture_width','texture_height','identifier'): desc[key] = r[key]
    tw,th = desc['texture_width'],desc['texture_height']
    if tw<=0 or th<=0: raise ValueError('Texture dimensions must be positive')
    bones = {}
    for bone in rig.data.bones:
        b = json.loads(bone.get('bedrock','{}'))
        b['name'] = bone.name
        b['pivot'] = [-bone.head_local.x,bone.head_local.y,bone.head_local.z]
        if bone.parent: b['parent'] = bone.parent.name
        else: b.pop('parent',None)
        # Rest axes must stay parallel to Y; pivots may be moved in Edit Mode.
        if any(abs(bone.matrix_local[i][j]-(1 if i==j else 0))>1e-5 for i in range(3) for j in range(3)):
            raise ValueError('Keep rest bone axes along +Y; use Pose Mode for rotation')
        pose = rig.pose.bones[bone.name]
        if pose.constraints: raise ValueError('Bone constraints must be removed before geometry export')
        if pose.rotation_mode!='XYZ' or pose.location.length>1e-6 or any(abs(v-1)>1e-6 for v in pose.scale):
            raise ValueError('Use XYZ pose rotation only; move pivots in Edit Mode')
        rot = pose.rotation_euler
        degrees = [-math.degrees(rot.x),-math.degrees(rot.y),math.degrees(rot.z)]
        if 'rotation' in b or max(map(abs,degrees)) > 1e-6: b['rotation'] = degrees
        bones[bone.name] = b
    if any(o.type=='MESH' and o.parent!=r for o in bpy.context.scene.objects):
        raise ValueError('Parent every mesh directly to the Bedrock root (Object, Keep Transform); remove other scene meshes')
    for obj in sorted(r.children,key=lambda o:(o.get('cube_index',0),o.name)):
        if obj.type != 'MESH': continue
        if obj.data.shape_keys or obj.constraints: raise ValueError('Apply/remove shape keys and object constraints before export')
        armatures=[m for m in obj.modifiers if m.type=='ARMATURE']
        if len(armatures)!=1 or armatures[0].object!=rig or not armatures[0].show_viewport or not armatures[0].show_render:
            raise ValueError('Every mesh needs one enabled Armature modifier targeting Bedrock skeleton')
        if any(m.type!='ARMATURE' for m in obj.modifiers):
            raise ValueError(f'{obj.name}: apply mesh modifiers before export')
        if any(abs(s-1)>1e-6 for s in obj.scale): raise ValueError('Apply mesh object scale before export')
        if 'face_names' in obj:
            if obj.rotation_mode!='XYZ' or obj.delta_location.length>1e-6 or max(map(abs,obj.delta_rotation_euler))>1e-6 or any(abs(v-1)>1e-6 for v in obj.delta_scale):
                raise ValueError('Imported cubes need XYZ rotation and no delta transforms')
            if obj.matrix_parent_inverse != Matrix.Identity(4): raise ValueError('Keep imported cube parenting transforms intact')
            names = json.loads(obj['face_names'])
            if len(names)!=len(obj.data.polygons): raise ValueError('Imported cube topology changed; remove cube tags to export as new faces')
            points = [v.co for v in obj.data.vertices]
            if len(points)!=8: raise ValueError('Imported cube needs its eight source vertices')
            lo = points[0].copy()
            hi = Vector((points[4].x,points[2].y,points[1].z))
            # Verify actual vertices still form the same oriented cuboid; no cached geometry export.
            for v,corner in zip(points,CORNERS):
                expected = Vector(tuple(hi[i] if corner[i] else lo[i] for i in range(3)))
                if (v-expected).length>1e-4: raise ValueError(f'{obj.name}: imported cube is no longer rectangular')
            p = obj.location
            c = json.loads(obj['cube_extra'])
            bn = face_bone(obj,obj.data.polygons[0],rig)
            inflate = c.get('inflate',bones[bn].get('inflate',0))
            lo += Vector((inflate,)*3)
            hi -= Vector((inflate,)*3)
            c['origin'] = [-hi.x-p.x,lo.y+p.y,lo.z+p.z]
            c['size'] = list(hi-lo)
            rot = obj.rotation_euler
            rotated = max(map(abs,rot))>1e-7
            if obj['had_pivot'] or rotated: c['pivot'] = [-p.x,p.y,p.z]
            if obj['had_rotation'] or rotated: c['rotation'] = [-math.degrees(rot.x),-math.degrees(rot.y),math.degrees(rot.z)]
            uv = {}; group = None
            for f,n in zip(obj.data.polygons,names):
                bn = face_bone(obj,f,rig)
                if group is not None and group!=bn: raise ValueError('A cube must belong to one bone')
                group=bn
                shift,entry = uv_rect(obj,f,tw,th)
                if shift: raise ValueError('Do not rotate UV corners on imported cubes')
                uv[n] = {**json.loads(obj.get('uv_extra','{}')).get(n,{}),**entry}
            c['uv'] = uv
            bones[group].setdefault('cubes',[]).append(c)
        else:
            for f in obj.data.polygons:
                if len(f.vertices)!=4: raise ValueError(f'{obj.name}: only rectangular quads are supported')
                bn = face_bone(obj,f,rig)
                shift,uv = uv_rect(obj,f,tw,th)
                relative = r.matrix_world.inverted() @ obj.matrix_world
                pts = [relative @ obj.data.vertices[i].co for i in f.vertices]
                pts = pts[shift:]+pts[:shift]
                a,b,c,d = pts
                x,y = b-a,a-d
                w,h = x.length,y.length
                if min(w,h)<1e-6 or abs(x.dot(y))>1e-5*w*h or (c-(b+d-a)).length>1e-4:
                    raise ValueError(f'{obj.name} face {f.index}: warped/trapezoidal quad cannot be a Bedrock plane')
                x.normalize(); y.normalize(); z=x.cross(y)
                rot = Matrix((x,y,z)).transposed().to_euler('XYZ')
                cube = {'origin':[-d.x-w,d.y,d.z], 'size':[w,h,0],
                        'pivot':[-d.x,d.y,d.z],
                        'rotation':[-math.degrees(rot.x),-math.degrees(rot.y),math.degrees(rot.z)],
                        'uv':{'north':uv}}
                bones[bn].setdefault('cubes',[]).append(cube)
    if not any(b.get('cubes') for b in bones.values()): raise ValueError('Nothing to export')
    out = {'format_version':'1.12.0','minecraft:geometry':[{'description':desc,'bones':list(bones.values())}]}
    Path(path).write_text(json.dumps(out,indent=2,allow_nan=False)+'\n')
    return out


class ImportBedrock(bpy.types.Operator, ImportHelper):
    bl_idname='import_scene.fapcraft_bedrock'; bl_label='Fapcraft Bedrock geometry'
    filename_ext='.json'
    def execute(self,context):
        try: import_geo(self.filepath)
        except Exception as e: self.report({'ERROR'},str(e)); return {'CANCELLED'}
        return {'FINISHED'}

class ExportBedrock(bpy.types.Operator, ExportHelper):
    bl_idname='export_scene.fapcraft_bedrock'; bl_label='Fapcraft Bedrock geometry'
    filename_ext='.geo.json'
    def execute(self,context):
        try: export_geo(self.filepath)
        except Exception as e: self.report({'ERROR'},str(e)); return {'CANCELLED'}
        return {'FINISHED'}

def menu_import(self,context): self.layout.operator(ImportBedrock.bl_idname)
def menu_export(self,context): self.layout.operator(ExportBedrock.bl_idname)
def register():
    for cls in (ImportBedrock,ExportBedrock): bpy.utils.register_class(cls)
    bpy.types.TOPBAR_MT_file_import.append(menu_import)
    bpy.types.TOPBAR_MT_file_export.append(menu_export)

if __name__=='__main__': register()
