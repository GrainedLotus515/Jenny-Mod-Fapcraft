"""Independent orthographic software rasterizer: JSON + PNG in, PNG out.
No Blender APIs, mesh export helpers, or Blender-evaluated vertices are used.
Implements GeckoLib's X reflection, Z/Y/X rotation, bone hierarchy and north-face UV order.
"""
import json, math
from pathlib import Path
import numpy as np
from PIL import Image

HERE=Path(__file__).resolve().parent
E=HERE/'evidence'


def rotation(deg):
    x,y,z=np.radians(deg)*[-1,-1,1]
    cx,sx=math.cos(x),math.sin(x); cy,sy=math.cos(y),math.sin(y); cz,sz=math.cos(z),math.sin(z)
    return np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]])@np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]])@np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]])


def transform(item):
    p=np.array(item.get('pivot',[0,0,0]),float)*[-1,1,1]
    m=np.eye(4); m[:3,:3]=rotation(item.get('rotation',[0,0,0])); m[:3,3]=p-m[:3,:3]@p
    return m


def render(geo_path,texture_path,out_path):
    geo=json.loads(Path(geo_path).read_text())['minecraft:geometry'][0]
    camera=json.loads((E/'camera.json').read_text())
    w,h=camera['width'],camera['height']; scale=camera['ortho_scale']
    depth=np.full((h,w),np.inf); image=np.zeros((h,w,4),dtype=np.uint8)
    texture=np.array(Image.open(texture_path).convert('RGBA'))
    texw,texh=geo['description']['texture_width'],geo['description']['texture_height']
    bones={b['name']:b for b in geo['bones']}; cache={}
    def bone_matrix(name):
        if name not in cache:
            b=bones[name]; m=transform(b)
            cache[name]=bone_matrix(b['parent'])@m if 'parent' in b else m
        return cache[name]
    root=np.array([[1/16,0,0,0],[0,0,-1/16,0],[0,1/16,0,0],[0,0,0,1]])
    view=np.array(camera['world_to_camera'])
    triangles=0
    for b in geo['bones']:
        for c in b.get('cubes',[]):
            # Deliberately narrow renderer; fail instead of pretending to support other cases.
            assert set(c['uv'])=={'north'} and c['size'][2]==0 and not c.get('inflate',0)
            o=np.array(c['origin'],float); s=np.array(c['size'],float)
            x=-o[0]-s[0]; y=o[1]; z=o[2]
            pts=np.array([[x,y+s[1],z,1],[x+s[0],y+s[1],z,1],
                          [x+s[0],y,z,1],[x,y,z,1]])
            camera_pts=(view@root@bone_matrix(b['name'])@transform(c)@pts.T).T
            screen=np.column_stack(((camera_pts[:,0]/scale+.5)*w,(.5-camera_pts[:,1]/scale)*h,-camera_pts[:,2]))
            u,v=c['uv']['north']['uv']; a,d=c['uv']['north']['uv_size']
            uv=np.array([[u+a,v],[u,v],[u,v+d],[u+a,v+d]])/[texw,texh]
            for tri in ((0,1,2),(0,2,3)):
                triangles+=1
                p=screen[list(tri)]; t=uv[list(tri)]
                lo=np.maximum(np.floor(p[:,:2].min(axis=0)).astype(int),0)
                hi=np.minimum(np.ceil(p[:,:2].max(axis=0)).astype(int),[w-1,h-1])
                if np.any(hi<lo): continue
                xx,yy=np.meshgrid(np.arange(lo[0],hi[0]+1)+.5,np.arange(lo[1],hi[1]+1)+.5)
                den=(p[1,1]-p[2,1])*(p[0,0]-p[2,0])+(p[2,0]-p[1,0])*(p[0,1]-p[2,1])
                if abs(den)<1e-12: continue
                a0=((p[1,1]-p[2,1])*(xx-p[2,0])+(p[2,0]-p[1,0])*(yy-p[2,1]))/den
                a1=((p[2,1]-p[0,1])*(xx-p[2,0])+(p[0,0]-p[2,0])*(yy-p[2,1]))/den
                a2=1-a0-a1
                zbuf=a0*p[0,2]+a1*p[1,2]+a2*p[2,2]
                sl=np.s_[lo[1]:hi[1]+1,lo[0]:hi[0]+1]
                mask=(a0>=-1e-9)&(a1>=-1e-9)&(a2>=-1e-9)&(zbuf<depth[sl])
                tu=a0*t[0,0]+a1*t[1,0]+a2*t[2,0]; tv=a0*t[0,1]+a1*t[1,1]+a2*t[2,1]
                tx=np.clip((tu*texture.shape[1]).astype(int),0,texture.shape[1]-1)
                ty=np.clip((tv*texture.shape[0]).astype(int),0,texture.shape[0]-1)
                image[sl][mask]=texture[ty,tx][mask]; depth[sl][mask]=zbuf[mask]
    Image.fromarray(image).save(out_path)
    source=np.array(Image.open(E/'source-blender.png').convert('RGBA'))
    ma=source[:,:,3]>127; mb=image[:,:,3]>127
    overlap=ma&mb
    # Ignore silhouette and checker boundaries for a fair antialias-vs-point-sampling comparison.
    interior=overlap.copy()
    for dy,dx in ((0,1),(0,-1),(1,0),(-1,0)):
        interior &= np.roll(overlap,(dy,dx),(0,1))
        interior &= (np.max(np.abs(image[:,:,:3].astype(int)-np.roll(image[:,:,:3],(dy,dx),(0,1)).astype(int)),axis=2)<12)
    diff=np.abs(source[:,:,:3].astype(float)-image[:,:,:3].astype(float))
    metrics={'triangles':triangles,'silhouette_iou':float(np.sum(overlap)/np.sum(ma|mb)),
             'interior_pixels':int(interior.sum()),'interior_mean_rgb_error_255':float(diff[interior].mean()),
             'interior_p99_rgb_error_255':float(np.percentile(diff[interior],99))}
    (E/'render-comparison.json').write_text(json.dumps(metrics,indent=2)+'\n')
    print(json.dumps(metrics,indent=2))
    assert metrics['silhouette_iou']>.99, 'Silhouette mismatch'
    assert metrics['interior_mean_rgb_error_255']<3, 'Texture/transform mismatch'

if __name__=='__main__': render(E/'curved_shell.geo.json',E/'curved_shell.png',E/'exported-independent.png')
