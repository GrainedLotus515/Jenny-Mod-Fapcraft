// Executes Blockbench's actual cube importer with a small in-memory editor-data stub.
// This tests importer semantics; it does not launch Blockbench's GUI or WebGL renderer.
const fs = require('node:fs');
const assert = require('node:assert/strict');
Array.prototype.V3_set = function(a) { for(let i=0;i<3;i++) this[i]=a[i]; };
global.Cube = class {
  constructor(s) {
    this.rotation=(s.rotation||[0,0,0]).slice(); this.origin=(s.origin||[0,0,0]).slice();
    this.from=[0,0,0]; this.to=[0,0,0]; this.uv_offset=[0,0];
    this.faces={};
    for (const name of ['north','east','south','west','up','down']) {
      const f={uv:[0,0,0,0],texture:'default'};
      f.extend = function(data) { this.uv=[data.uv[0],data.uv[1],data.uv[0],data.uv[1]]; this.rotation=data.rotation; };
      Object.defineProperty(f,'uv_size',{set(v){this.uv[2]=this.uv[0]+v[0];this.uv[3]=this.uv[1]+v[1];}});
      this.faces[name]=f;
    }
  }
  addTo(group) { group.cubes.push(this); return this; }
  init() { return this; }
};
const parseCube=require('./vendor/blockbench-parseCube.js');
const reports=[];
for(const path of process.argv.slice(2)) {
  const model=JSON.parse(fs.readFileSync(path)); assert.equal(model.format_version,'1.12.0');
  const geo=model['minecraft:geometry'][0]; const names=new Set(geo.bones.map(b=>b.name));
  assert.equal(names.size,geo.bones.length);
  let cubes=0,faces=0;
  for(const bone of geo.bones) {
    if(bone.parent) assert(names.has(bone.parent));
    const group={cubes:[],mirror_uv:bone.mirror||false};
    for(const c of bone.cubes||[]) {
      const parsed=parseCube(c,group); cubes++;
      const expectedFrom=[-c.origin[0]-c.size[0],c.origin[1],c.origin[2]];
      assert(parsed.from.every((v,i)=>Math.abs(v-expectedFrom[i])<1e-10));
      for(let i=0;i<3;i++) assert(Math.abs(parsed.to[i]-parsed.from[i]-c.size[i])<1e-10);
      for(const [name,face] of Object.entries(parsed.faces)) {
        if(!c.uv[name]) { assert.equal(face.texture,null); continue; }
        faces++; const uv=c.uv[name];
        const expected=[uv.uv[0],uv.uv[1],uv.uv[0]+uv.uv_size[0],uv.uv[1]+uv.uv_size[1]];
        if(name==='up'||name==='down') expected.push(...expected.splice(0,2));
        assert.deepEqual(face.uv,expected);
      }
    }
  }
  reports.push({path,bones:geo.bones.length,cubes,visible_faces:faces,result:'PASS',
    scope:'Blockbench v4.12.6 actual parseCube function; editor Cube data stub, no GUI'});
}
console.log(JSON.stringify(reports,null,2));
