// Function from Blockbench v4.12.6 js/io/formats/bedrock.js (trailing whitespace normalized)
// Copyright JannisX11 and contributors. GPL-3.0; see blockbench-LICENSE.
	function parseCube(s, group) {
		var base_cube = new Cube({
			name: s.name || group.name,
			autouv: 0,
			color: group.color,
			rotation: s.rotation,
			origin: s.pivot
		})
		base_cube.rotation.forEach(function(br, axis) {
			if (axis != 2) base_cube.rotation[axis] *= -1
		})
		base_cube.origin[0] *= -1;
		if (s.origin) {
			base_cube.from.V3_set(s.origin)
			base_cube.from[0] = -(base_cube.from[0] + s.size[0])
			if (s.size) {
				base_cube.to[0] = s.size[0] + base_cube.from[0]
				base_cube.to[1] = s.size[1] + base_cube.from[1]
				base_cube.to[2] = s.size[2] + base_cube.from[2]
			}
		}
		if (s.uv instanceof Array) {
			base_cube.uv_offset[0] = s.uv[0]
			base_cube.uv_offset[1] = s.uv[1]
			base_cube.box_uv = true;
		} else if (s.uv) {
			base_cube.box_uv = false;
			for (var key in base_cube.faces) {
				var face = base_cube.faces[key]
				if (s.uv[key]) {
					face.extend({
						material_name: s.uv[key].material_instance,
						uv: [
							s.uv[key].uv[0],
							s.uv[key].uv[1]
						],
						rotation: s.uv[key].uv_rotation
					})
					if (s.uv[key].uv_size) {
						face.uv_size = [
							s.uv[key].uv_size[0],
							s.uv[key].uv_size[1]
						]
					} else {
						base_cube.autouv = 1;
						base_cube.mapAutoUV();
					}
					if (key == 'up' || key == 'down') {
						face.uv = [face.uv[2], face.uv[3], face.uv[0], face.uv[1]]
					}
				} else {
					face.texture = null;
					face.uv = [0, 0, 0, 0],
					face.rotation = 0;
				}
			}

		}
		if (s.inflate && typeof s.inflate === 'number') {
			base_cube.inflate = s.inflate;
		}
		if (s.mirror === undefined) {
			base_cube.mirror_uv = group.mirror_uv;
		} else {
			base_cube.mirror_uv = s.mirror === true;
		}
		base_cube.addTo(group).init();
		return base_cube;
	}
module.exports = parseCube;
