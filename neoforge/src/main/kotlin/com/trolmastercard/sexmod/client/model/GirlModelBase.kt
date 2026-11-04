package com.trolmastercard.sexmod.client.model

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.resources.ResourceLocation
import software.bernie.geckolib.animatable.GeoAnimatable
import software.bernie.geckolib.animation.Animation
import software.bernie.geckolib.cache.GeckoLibCache
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import software.bernie.geckolib.loading.`object`.BakedAnimations
import software.bernie.geckolib.model.GeoModel

/**
 * Base class for geckolib girl models.
 *
 * In 1.12 this class swapped in the caching `GirlAnimationProcessor` by
 * reflection and re-registered every top-level bone after each model load so
 * the name-based bone cache stayed correct. GeckoLib 4's
 * `AnimationProcessor` already maps bones by name and re-registers them in
 * `setActiveModel`, so that part is gone (`GirlAnimationProcessor` is DROPPED,
 * PORTING.md §2.8).
 *
 * **Custom models.** The 1.12 mod injected runtime (custom/reskin) geo models and
 * animations straight into `GeckoLibCache`. GeckoLib 4 rebuilds its caches on
 * every resource reload, so the mod keeps its own maps here instead
 * ([CUSTOM_BAKED_MODELS], [CUSTOM_ANIMATIONS]); [getBakedModel] and [getAnimation]
 * consult them before GeckoLib's caches. The custom-model loaders
 * (`ReskinManager`, `ModelArchive`, `ModelResources`) write into these maps.
 *
 * CLIENT-side only. [getBakedModel] throws when the geo file is missing — model
 * files must exist.
 */
abstract class GirlModelBase<T : GeoAnimatable> protected constructor() : GeoModel<T>() {
    /** The custom model last handed to the processor (GeckoLib's own tracking only sees its cache). */
    private var activeCustomModel: BakedGeoModel? = null

    /**
     * Loads the baked geo model: the mod's runtime models first, then GeckoLib's
     * cache. Re-binds the animation processor whenever the active model changes.
     */
    override fun getBakedModel(location: ResourceLocation): BakedGeoModel {
        val custom = CUSTOM_BAKED_MODELS[location]
        if (custom != null) {
            if (custom !== this.activeCustomModel) {
                this.animationProcessor.setActiveModel(custom)
                this.activeCustomModel = custom
            }

            return custom
        }

        val model = super.getBakedModel(location)
        if (this.activeCustomModel != null) {
            // switching back from a runtime model: GeckoLib still thinks its last model is active
            this.animationProcessor.setActiveModel(model)
            this.activeCustomModel = null
        }

        return model
    }

    /** Runtime animations (custom models) first, then GeckoLib's cache. */
    override fun getAnimation(animatable: T, name: String): Animation? {
        val custom = CUSTOM_ANIMATIONS[this.getAnimationResource(animatable)]
        if (custom != null) {
            val animation = custom.getAnimation(name)
            if (animation != null) {
                return animation
            }
        }

        return super.getAnimation(animatable, name)
    }

    companion object {
        /** Runtime geo models keyed by resource location (custom models / reskins). */
        @JvmField
        val CUSTOM_BAKED_MODELS: MutableMap<ResourceLocation, BakedGeoModel> = ConcurrentHashMap()

        /** Runtime animation files keyed by resource location (custom models / reskins). */
        @JvmField
        val CUSTOM_ANIMATIONS: MutableMap<ResourceLocation, BakedAnimations> = ConcurrentHashMap()

        /** Whether a geo model is available for `location` (runtime map or GeckoLib cache). */
        @JvmStatic
        fun hasBakedModel(location: ResourceLocation?): Boolean {
            if (location == null) {
                return false
            }

            return CUSTOM_BAKED_MODELS.containsKey(location) || GeckoLibCache.getBakedModels().containsKey(location)
        }
    }
}
