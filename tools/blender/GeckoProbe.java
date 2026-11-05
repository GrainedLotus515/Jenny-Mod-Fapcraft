import java.nio.file.*;
import software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter;
import software.bernie.geckolib.loading.json.raw.Model;
import software.bernie.geckolib.loading.object.*;
import software.bernie.geckolib.cache.object.*;

/** Uses the installed mod dependency's REAL Gson deserializers and model baker. */
class GeckoProbe {
    static int bones, cubes, quads;
    static void count(GeoBone bone) {
        bones++;
        for (GeoCube cube : bone.getCubes()) {
            cubes++;
            for (GeoQuad quad : cube.quads()) if (quad != null) quads++;
        }
        for (GeoBone child : bone.getChildBones()) count(child);
    }
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            bones = cubes = quads = 0;
            Model raw = KeyFramesAdapter.GEO_GSON.fromJson(Files.readString(Path.of(path)), Model.class);
            BakedGeoModel baked = BakedModelFactory.DEFAULT_FACTORY.constructGeoModel(GeometryTree.fromModel(raw));
            for (GeoBone bone : baked.topLevelBones()) count(bone);
            System.out.printf("PASS %s: version=%s bones=%d cubes=%d visible_quads=%d%n",path,raw.formatVersion(),bones,cubes,quads);
            if (bones == 0 || cubes == 0 || quads == 0) throw new AssertionError("Empty model");
        }
    }
}
