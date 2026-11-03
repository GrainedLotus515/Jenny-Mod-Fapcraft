package com.trolmastercard.sexmod.util

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation

/**
 * 1.12 `EnumParticleTypes` compatibility (§2.12). Particle names travel in
 * packets (`SpawnParticlePacket`) as strings; [name] produces the 1.21 registry
 * ID and [byName] resolves either that ID or a 1.12 particle name.
 *
 * 1.12 -> 1.21: CLOUD->CLOUD, CRIT_MAGIC->ENCHANTED_HIT, DRAGON_BREATH->DRAGON_BREATH,
 * EXPLOSION_NORMAL->POOF, HEART->HEART, PORTAL->PORTAL, SLIME->ITEM_SLIME,
 * SMOKE_NORMAL->SMOKE, SPELL->EFFECT, SPELL_WITCH->WITCH,
 * VILLAGER_ANGRY->ANGRY_VILLAGER, VILLAGER_HAPPY->HAPPY_VILLAGER,
 * WATER_BUBBLE->BUBBLE, WATER_SPLASH->SPLASH, WATER_WAKE->FISHING.
 */
object ParticleCompat {
    /** 1.12 `getParticleName()` values and enum constant names -> 1.21 particle. */
    private val LEGACY: Map<String, SimpleParticleType> by lazy {
        val map = HashMap<String, SimpleParticleType>()
        fun put(enumName: String, particleName: String, type: SimpleParticleType) {
            map[enumName] = type
            map[particleName] = type
        }
        put("CLOUD", "cloud", ParticleTypes.CLOUD)
        put("CRIT_MAGIC", "magicCrit", ParticleTypes.ENCHANTED_HIT)
        put("DRAGON_BREATH", "dragonbreath", ParticleTypes.DRAGON_BREATH)
        put("EXPLOSION_NORMAL", "explode", ParticleTypes.POOF)
        put("HEART", "heart", ParticleTypes.HEART)
        put("PORTAL", "portal", ParticleTypes.PORTAL)
        put("SLIME", "slime", ParticleTypes.ITEM_SLIME)
        put("SMOKE_NORMAL", "smoke", ParticleTypes.SMOKE)
        put("SPELL", "spell", ParticleTypes.EFFECT)
        put("SPELL_WITCH", "witchMagic", ParticleTypes.WITCH)
        put("VILLAGER_ANGRY", "angryVillager", ParticleTypes.ANGRY_VILLAGER)
        put("VILLAGER_HAPPY", "happyVillager", ParticleTypes.HAPPY_VILLAGER)
        put("WATER_BUBBLE", "bubble", ParticleTypes.BUBBLE)
        put("WATER_SPLASH", "splash", ParticleTypes.SPLASH)
        put("WATER_WAKE", "wake", ParticleTypes.FISHING)
        map
    }

    /** Registry ID of the particle type (e.g. `minecraft:heart`). */
    @JvmStatic
    fun name(type: SimpleParticleType): String = BuiltInRegistries.PARTICLE_TYPE.getKey(type).toString()

    /** Resolves a registry ID or a 1.12 particle name; `null` if unknown or not a simple particle. */
    @JvmStatic
    fun byName(name: String): SimpleParticleType? {
        LEGACY[name]?.let { return it }
        val id = ResourceLocation.tryParse(name) ?: return null
        return BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null) as? SimpleParticleType
    }
}
