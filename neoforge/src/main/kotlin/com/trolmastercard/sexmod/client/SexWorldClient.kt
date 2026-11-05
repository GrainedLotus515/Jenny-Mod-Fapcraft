package com.trolmastercard.sexmod.client

import net.minecraft.world.level.Level

/**
 * In 1.12 this was a client-only throwaway "preload" world (a flat plains
 * `WorldClient`) created exclusively in `ClientProxy.initRegistries` so GeckoLib
 * models/animation data could be loaded once at startup. Nothing was ever
 * spawned or rendered in it.
 *
 * The 1.21 port drops the preload loop (GeckoLib 4 loads all geo/animation
 * resources on resource reload), so the world class is gone. What remains is
 * the type check entity code used to skip animation/preview logic:
 * every `world instanceof SexWorldClient` becomes [isPreloadWorld], which is
 * always `false`. COMMON object (§2.3).
 */
object SexWorldClient {
    @JvmStatic
    fun isPreloadWorld(level: Level?): Boolean = false
}
