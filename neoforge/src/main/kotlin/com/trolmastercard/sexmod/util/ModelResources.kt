package com.trolmastercard.sexmod.util

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import software.bernie.geckolib.loading.json.raw.Model
import software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter
import software.bernie.geckolib.loading.`object`.BakedModelFactory
import software.bernie.geckolib.loading.`object`.GeometryTree
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * **Role.** The GeckoLib resources behind [ServerWhitelistManager]'s
 * custom models: turning a `.png` into a dynamic texture, a
 * `.geo.json` into a GeckoLib raw [Model], and dropping both again when the
 * model cache is cleared on disconnect.
 *
 * **Pitfalls.** Client-only — every method here touches the texture manager or
 * the custom baked-model map. The textures are registered under the *model name*, so two
 * servers exposing the same model name overwrite each other's texture until the
 * cache is cleared.
 *
 * Port notes (PORTING.md §2.8): GeckoLib 4 rebuilds its own caches on every
 * resource reload, so baked custom models are kept in [CUSTOM_GEO_MODELS]
 * instead of `GeckoLibCache`; `GirlModelBase.getBakedModel` / `SexSceneModel`
 * must consult [getCustomBakedModel] first. Dynamic texture names are lowercased
 * and sanitised (1.21 resource locations reject upper case).
 */
object ModelResources {
    /** Baked custom models by their `sexmod:<name>model` id (replaces the GeckoLib 3 cache entry). */
    @JvmField
    val CUSTOM_GEO_MODELS: MutableMap<ResourceLocation, BakedGeoModel> = ConcurrentHashMap()

    @JvmStatic
    @Throws(IOException::class)
    fun loadTexture(name: String, file: File): ResourceLocation {
        FileInputStream(file).use { stream -> return loadTexture(name, stream) }
    }

    /** Stream variant used by the zip-backed models ([ModelArchive]). */
    @JvmStatic
    @Throws(IOException::class)
    fun loadTexture(name: String, stream: InputStream): ResourceLocation {
        val image = NativeImage.read(stream)
        return Minecraft.getInstance().textureManager.register(sanitizePath(name), DynamicTexture(image))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun loadGeoModel(file: File): Model {
        val content = StringBuilder()
        val reader = BufferedReader(FileReader(file))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                content.append(line)
            }
        } finally {
            reader.close()
        }

        val json = content.toString()
        return parseGeoModel(json)
    }

    /** 1.12 `Converter.fromJsonString(json)`. */
    @JvmStatic
    fun parseGeoModel(json: String): Model = KeyFramesAdapter.GEO_GSON.fromJson(json, Model::class.java)

    /** 1.12 `RawGeometryTree.parseHierarchy` + `GeoBuilder.constructGeoModel` + `GeckoLibCache.getGeoModels().put`. */
    @JvmStatic
    fun registerGeoModel(geoResource: ResourceLocation, rawGeoModel: Model) {
        val geometryTree = GeometryTree.fromModel(rawGeoModel)
        val geoModel = BakedModelFactory.getForNamespace(geoResource.namespace).constructGeoModel(geometryTree)
        CUSTOM_GEO_MODELS[geoResource] = geoModel
    }

    /** String variant for common callers ([ModelArchive]) so they never mention GeckoLib types. */
    @JvmStatic
    fun registerGeoModelFromJson(geoResource: ResourceLocation, json: String) {
        registerGeoModel(geoResource, parseGeoModel(json))
    }

    /** Lookup used by the GeckoLib model overrides (`getBakedModel`). */
    @JvmStatic
    fun getCustomBakedModel(geoResource: ResourceLocation?): BakedGeoModel? =
        if (geoResource == null) null else CUSTOM_GEO_MODELS[geoResource]

    @JvmStatic
    fun releaseAll(modelDataMap: Map<String, ServerWhitelistManager.ModelData>) {
        for (entry in modelDataMap.entries) {
            val modelData = entry.value
            val fallbackTex = modelData.getFallbackTexture()
            val textureLoc = modelData.getTextureLocation()
            if (fallbackTex != null) {
                CUSTOM_GEO_MODELS.remove(fallbackTex)
            }

            if (textureLoc != null) {
                Minecraft.getInstance().textureManager.release(textureLoc)
            }
        }
    }

    /** 1.21 resource paths only allow `[a-z0-9/._-]`. */
    @JvmStatic
    fun sanitizePath(name: String): String = ServerWhitelistManager.sanitizeResourcePath(name)
}
