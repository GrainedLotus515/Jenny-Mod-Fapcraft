"""Independent numeric comparison; fails on missing bones, changed counts, or drift."""
import argparse, json, math
from pathlib import Path
import numpy as np


def rotation(degrees):
    x,y,z=np.radians(degrees)*np.array([-1,-1,1])
    rx=np.array([[1,0,0],[0,math.cos(x),-math.sin(x)],[0,math.sin(x),math.cos(x)]])
    ry=np.array([[math.cos(y),0,math.sin(y)],[0,1,0],[-math.sin(y),0,math.cos(y)]])
    rz=np.array([[math.cos(z),-math.sin(z),0],[math.sin(z),math.cos(z),0],[0,0,1]])
    return rz@ry@rx


def corners(c):
    o=np.array(c['origin'],float); s=np.array(c['size'],float)
    o[0]=-o[0]-s[0]
    inflate=c.get('inflate',0)
    o-=inflate; s+=2*inflate
    p=np.array(c.get('pivot',[0,0,0]),float)*[-1,1,1]
    points=np.array([o+s*np.array([x,y,z]) for x in (0,1) for y in (0,1) for z in (0,1)])
    return (points-p)@rotation(c.get('rotation',[0,0,0])).T+p


def compare(a,b):
    ga=json.loads(Path(a).read_text())['minecraft:geometry'][0]
    gb=json.loads(Path(b).read_text())['minecraft:geometry'][0]
    assert ga['description']==gb['description']
    ba={v['name']:v for v in ga['bones']}; bb={v['name']:v for v in gb['bones']}
    assert ba.keys()==bb.keys()
    report={'bones':len(ba),'cubes':0,'per_bone_cube_counts':{},'max_errors':{}}
    errors={k:0.0 for k in ['origin_pixels','size_pixels','pivot_pixels','rotation_degrees','uv_pixels','cube_corner_pixels','bone_pivot_pixels','bone_rotation_degrees','world_corner_pixels']}
    def delta(key,x,y):
        e=float(np.max(np.abs(np.array(x)-np.array(y))))
        errors[key]=max(errors[key],e)
    def bone_matrix(bones,name):
        b=bones[name]; p=np.array(b.get('pivot',[0,0,0]),float)*[-1,1,1]
        m=np.eye(4); m[:3,:3]=rotation(b.get('rotation',[0,0,0])); m[:3,3]=p-m[:3,:3]@p
        return bone_matrix(bones,b['parent'])@m if 'parent' in b else m
    for name,x in ba.items():
        y=bb[name]; cx=x.get('cubes',[]); cy=y.get('cubes',[])
        assert x.get('parent')==y.get('parent'),name
        delta('bone_pivot_pixels',x.get('pivot',[0,0,0]),y.get('pivot',[0,0,0]))
        delta('bone_rotation_degrees',x.get('rotation',[0,0,0]),y.get('rotation',[0,0,0]))
        assert len(cx)==len(cy),name
        report['per_bone_cube_counts'][name]={'original':len(cx),'exported':len(cy)}
        report['cubes']+=len(cx)
        for u,v in zip(cx,cy):
            for field,key in [('origin','origin_pixels'),('size','size_pixels'),('pivot','pivot_pixels'),('rotation','rotation_degrees')]:
                delta(key,u.get(field,[0,0,0]),v.get(field,[0,0,0]))
            assert u.get('inflate',0)==v.get('inflate',0)
            assert u['uv'].keys()==v['uv'].keys()
            for face in u['uv']:
                for key in ('uv','uv_size'): delta('uv_pixels',u['uv'][face][key],v['uv'][face][key])
            delta('cube_corner_pixels',corners(u),corners(v))
            def world(c,bones):
                return (bone_matrix(bones,name)@np.column_stack((corners(c),np.ones(8))).T).T[:,:3]
            delta('world_corner_pixels',world(u,ba),world(v,bb))
    report['max_errors']=errors
    report['tolerance']=0.0001
    report['pass']=all(v<report['tolerance'] for v in errors.values())
    assert report['pass'],errors
    return report

if __name__=='__main__':
    ap=argparse.ArgumentParser(); ap.add_argument('original'); ap.add_argument('exported'); ap.add_argument('--output')
    args=ap.parse_args(); result=compare(args.original,args.exported)
    s=json.dumps(result,indent=2)+'\n'
    if args.output: Path(args.output).write_text(s)
    print(json.dumps({k:v for k,v in result.items() if k!='per_bone_cube_counts'},indent=2))
