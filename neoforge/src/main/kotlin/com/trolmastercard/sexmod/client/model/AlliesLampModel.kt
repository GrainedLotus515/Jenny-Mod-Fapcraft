package com.trolmastercard.sexmod.client.model

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.SkinFetcher
import com.trolmastercard.sexmod.item.AlliesLampItem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import software.bernie.geckolib.model.GeoModel

/**
 * Geckolib model for the Allies Lamp item: the lamp geo with a skin-based
 * dynamic texture — the local player's fetched skin repainted with the lamp's
 * cyan/white/black face pixels. Falls back to the plain lamp texture on skin
 * fetch failure. Texture cached after first load.
 */
class AlliesLampModel : GeoModel<AlliesLampItem>() {
    @JvmField
    var lampTexture: ResourceLocation? = null

    override fun getModelResource(item: AlliesLampItem?): ResourceLocation = Main.rl("geo/allie/lamp.geo.json")

    override fun getAnimationResource(item: AlliesLampItem?): ResourceLocation = Main.rl("animations/allie/lamp.animation.json")

    /**
     * Lazily builds and caches the skin-blended lamp texture (see class
     * doc). Synchronous [SkinFetcher] network call on first use.
     */
    override fun getTextureResource(item: AlliesLampItem?): ResourceLocation {
        val cached = this.lampTexture
        if (cached != null) {
            return cached
        }

        var texture: ResourceLocation
        try {
            val mc = Minecraft.getInstance()
            val skin = SkinFetcher.fetchSkin(mc.player!!.uuid)!! // java-npe: caught below, like 1.12
            skin.fillRect(0, 0, 2, 2, FastColor.ABGR32.color(255, 255, 254, 185))
            skin.fillRect(2, 0, 1, 2, FastColor.ABGR32.color(255, 255, 255, 255))
            skin.fillRect(3, 0, 1, 2, FastColor.ABGR32.color(255, 0, 0, 0))
            texture = mc.textureManager.register("allieslamp", DynamicTexture(skin))
        } catch (e: Exception) {
            Main.LOGGER.error("could not build the allies lamp texture", e)
            texture = Main.rl("textures/entity/allie/lamp.png")
        }

        this.lampTexture = texture
        return texture
    }

    fun getLampTexture(item: AlliesLampItem?): ResourceLocation = Main.rl("animations/allie/lamp.animation.json")
}
