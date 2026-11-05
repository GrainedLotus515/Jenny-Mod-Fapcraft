package com.trolmastercard.sexmod.client.renderer

import com.mojang.blaze3d.platform.NativeImage
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.SkinFetcher
import java.awt.Color
import java.util.LinkedHashMap
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor

/**
 * Bounded LRU of tinted player skins shared by every girl renderer (see
 * [GirlRenderer.SKIN_TEXTURE_CACHE]).
 *
 * **Eviction.** Access-ordered map of `SKIN_CACHE_CAPACITY` entries;
 * evicted and invalidated entries release their GPU texture through the
 * texture manager, except the shared fallback texture which outlives the cache.
 *
 * **Threading.** [invalidate] / [invalidateAll] run on
 * the client render thread; calls from another thread are rescheduled onto it.
 * Texture creation and eviction both touch GL state and must only happen there.
 *
 * CLIENT-side render thread only.
 *
 * Port note: `BufferedImage`/`Graphics.fillRect` became [NativeImage.fillRect] (ABGR colours);
 * `getDynamicTextureLocation` is `TextureManager.register(name, DynamicTexture)`.
 */
object SkinTextureCache {
    const val CAPACITY: Int = 64

    /**
     * Bounded LRU of tinted player skins (access order).
     */
    @JvmField
    val TEXTURES: LinkedHashMap<UUID, ResourceLocation> = object : LinkedHashMap<UUID, ResourceLocation>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, ResourceLocation>): Boolean {
            if (this.size <= CAPACITY) {
                return false
            }

            if (eldest.value != fallbackSkinLocation) {
                Minecraft.getInstance().textureManager.release(eldest.value)
            }

            return true
        }
    }

    /**
     * The bundled steve skin as a dynamic texture, registered once and shared by
     * every fallback lookup.
     */
    private var fallbackSkinLocation: ResourceLocation? = null

    private fun abgr(color: Color): Int = FastColor.ABGR32.color(color.alpha, color.blue, color.green, color.red)

    /**
     * Fetches the player skin for `uuid` ([SkinFetcher]), repaints
     * the head-top/face pixels with the mod's skin-tone colors, registers it as
     * a dynamic texture and caches it. On any fetch error the shared steve
     * fallback texture is cached for the uuid instead.
     */
    @JvmStatic
    fun tint(uuid: UUID, baseSkinColor: Color, blushColor: Color): ResourceLocation {
        val skinImage: NativeImage
        try {
            skinImage = SkinFetcher.fetchSkin(uuid)!! // java-npe: a null image is handled by the catch below
            skinImage.fillRect(0, 0, 4, 3, abgr(baseSkinColor))
            skinImage.fillRect(4, 0, 3, 3, abgr(blushColor))
        } catch (e: Exception) {
            val fallbackTexture = getFallbackSkinTexture()
            TEXTURES[uuid] = fallbackTexture
            return fallbackTexture
        }

        val tintedTexture = Minecraft.getInstance().textureManager.register("player$uuid", DynamicTexture(skinImage))
        TEXTURES[uuid] = tintedTexture
        return tintedTexture
    }

    private fun getFallbackSkinTexture(): ResourceLocation {
        var location = fallbackSkinLocation
        if (location == null) {
            val fallbackImage: NativeImage = try {
                Minecraft.getInstance().resourceManager.open(Main.rl("textures/player/steve.png")).use { NativeImage.read(it) }
            } catch (e: Exception) {
                NativeImage(64, 64, true)
            }

            location = Minecraft.getInstance().textureManager.register("sexmod_player_fallback", DynamicTexture(fallbackImage))
            fallbackSkinLocation = location
        }

        return location!!
    }

    /**
     * Drops `uuid`'s cached tinted skin and releases its GPU texture; the
     * next render fetches and re-tints. Runs on the client thread — calls from
     * another thread are rescheduled onto it.
     */
    @JvmStatic
    fun invalidate(uuid: UUID?) {
        if (uuid == null) {
            return
        }

        val minecraft = Minecraft.getInstance()
        if (!minecraft.isSameThread) {
            minecraft.execute { invalidate(uuid) }
            return
        }

        val staleTexture = TEXTURES.remove(uuid)
        if (staleTexture != null && staleTexture != fallbackSkinLocation) {
            minecraft.textureManager.release(staleTexture)
        }
    }

    /**
     * Drops every cached tinted skin (session change / skin update). Same thread
     * rules as [invalidate].
     */
    @JvmStatic
    fun invalidateAll() {
        val minecraft = Minecraft.getInstance()
        if (!minecraft.isSameThread) {
            minecraft.execute { invalidateAll() }
            return
        }

        for (staleTexture in ArrayList(TEXTURES.values)) {
            if (staleTexture != fallbackSkinLocation) {
                minecraft.textureManager.release(staleTexture)
            }
        }

        TEXTURES.clear()
    }
}
