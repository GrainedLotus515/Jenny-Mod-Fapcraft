package com.trolmastercard.sexmod.client.particle

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource

/**
 * Dragon-breath particle for the dragon staff's breath effect: a vanilla
 * dragon-breath particle whose scale is pinned to the static
 * [BREATH_SCALE] (so callers can tune the breath size globally) and
 * whose quad is rendered with the standard billboard math.
 *
 * CLIENT-side render thread only (vanilla particle system).
 *
 * Port notes: 1.12 copied the whole billboard `renderParticle` only to apply
 * `particleScale = BREATH_SCALE` with the half-extent `0.1 * particleScale`;
 * 1.21's `SingleQuadParticle.render` already does the billboard (including
 * the roll rotation), so only [getQuadSize] is overridden. The vanilla
 * dragon-breath sprites (`generic_5..7`) are looked up from the particle atlas
 * because the vanilla `SpriteSet` is private to the particle engine. Added
 * directly through `mc.particleEngine.add(...)` (no particle type is registered).
 */
open class DragonBreathParticle(world: ClientLevel, x: Double, y: Double, z: Double) :
    net.minecraft.client.particle.DragonBreathParticle(world, x, y, z, 0.0, 0.0, 0.0, BREATH_SPRITES) {

    override fun getQuadSize(scaleFactor: Float): Float = 0.1f * BREATH_SCALE

    /** The vanilla `minecraft:dragon_breath` sprite list, resolved lazily from the particle atlas. */
    private object BreathSprites : SpriteSet {
        private val names = arrayOf("generic_5", "generic_6", "generic_7")

        private fun sprites(): List<TextureAtlasSprite> {
            // the particle atlas belongs to the particle engine, not the model manager
            // (Minecraft.getTextureAtlas(LOCATION_PARTICLES) throws)
            val atlas = Minecraft.getInstance().textureManager.getTexture(TextureAtlas.LOCATION_PARTICLES) as TextureAtlas
            return names.map { atlas.getSprite(ResourceLocation.withDefaultNamespace(it)) }
        }

        override fun get(age: Int, lifetime: Int): TextureAtlasSprite {
            val sprites = this.sprites()
            return sprites[age * (sprites.size - 1) / lifetime]
        }

        override fun get(random: RandomSource): TextureAtlasSprite {
            val sprites = this.sprites()
            return sprites[random.nextInt(sprites.size)]
        }
    }

    companion object {
        @JvmField
        var BREATH_SCALE: Float = 0.2f

        private val BREATH_SPRITES: SpriteSet = BreathSprites
    }
}
